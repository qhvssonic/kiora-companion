package dev.kiora.companion

import dev.kiora.speech.SpeechAudioChunk
import dev.kiora.speech.SpeechSynthesisPort
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.SpeechSynthesisResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/** Overlap native synthesis with playback, without retaining an entire utterance. */
internal object SpeechPlaybackPipeline {
    // At most eight seconds of queued PCM, regardless of native callback size.
    private const val FRAME_MILLIS = 100
    private const val CAPACITY = 80
    private const val PREBUFFER_MILLIS = 1_000

    suspend fun play(
        engine: SpeechSynthesisPort,
        request: SpeechSynthesisRequest,
        consume: suspend (SpeechAudioChunk) -> Unit,
    ): SpeechSynthesisResult = coroutineScope {
        val frames = Channel<SpeechAudioChunk>(CAPACITY)
        val ready = CompletableDeferred<Unit>()
        val producer = async(Dispatchers.IO) {
            val caller = currentCoroutineContext()
            var rate = 0
            var queuedSamples = 0L
            try {
                engine.synthesize(request) { chunk ->
                    caller.ensureActive()
                    if (rate == 0) rate = chunk.sampleRateHz
                    check(rate == chunk.sampleRateHz) { "朗读过程中采样率发生变化" }
                    val samples = chunk.samples()
                    val frameSize = rate * FRAME_MILLIS / 1_000
                    var offset = 0
                    while (offset < samples.size) {
                        caller.ensureActive()
                        val end = minOf(offset + frameSize, samples.size)
                        val frame = SpeechAudioChunk(samples.copyOfRange(offset, end), rate)
                        // The SDK callback is synchronous. Cancellation of the consumer cancels
                        // the channel below, unblocking this send even when the queue is full.
                        runBlocking { frames.send(frame) }
                        queuedSamples += end - offset
                        if (queuedSamples * 1_000 >= rate.toLong() * PREBUFFER_MILLIS) ready.complete(Unit)
                        offset = end
                    }
                }
            } finally {
                frames.close()
                ready.complete(Unit) // Short utterances must play without waiting for a full second.
            }
        }
        try {
            ready.await()
            for (frame in frames) consume(frame)
            producer.await()
        } finally {
            // Never release the engine here: coroutineScope waits for native synthesis to exit.
            frames.cancel()
            producer.cancel()
        }
    }
}
