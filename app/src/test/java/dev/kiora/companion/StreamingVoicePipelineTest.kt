package dev.kiora.companion

import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.SpeechRecognitionUpdate
import dev.kiora.speech.StreamingSpeechSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class StreamingVoicePipelineTest {
    private class Session : StreamingSpeechSession {
        val samples = mutableListOf<Short>()
        var closes = 0
        var finishes = 0
        override suspend fun accept(audio: Pcm16Audio): List<SpeechRecognitionUpdate> {
            samples.addAll(audio.samples.toList())
            return listOf(SpeechRecognitionUpdate(0, samples.size.toLong(), "临时${samples.size}", false))
        }
        override suspend fun finish(): List<SpeechRecognitionUpdate> {
            finishes++
            return listOf(SpeechRecognitionUpdate(0, samples.size + 1L, "最终文本", true))
        }
        override fun close() { closes++ }
    }

    @Test fun captureStartsBeforeModelReadyAndDrainsRecordedPrefixInOrder() = runBlocking {
        val recordedAndStopped = CompletableDeferred<Unit>()
        val engine = Session()
        val texts = mutableListOf<String>()
        val result = withTimeout(2_000) {
            StreamingVoicePipeline.transcribe(
                openSession = { recordedAndStopped.await(); engine },
                capture = { consume ->
                    // Simulate speaking and pressing stop while the model is still loading.
                    val queue = Channel<Pcm16Audio>(3)
                    for (sample in 1..3) queue.send(Pcm16Audio(shortArrayOf(sample.toShort()), 16_000))
                    queue.close()
                    recordedAndStopped.complete(Unit)
                    for (chunk in queue) consume(chunk)
                },
                onText = { texts += it }, onFinalizing = {},
            )
        }
        assertEquals(listOf<Short>(1, 2, 3), engine.samples)
        assertEquals(listOf("临时1", "临时2", "临时3"), texts)
        assertEquals("最终文本", result)
        assertEquals(1, engine.finishes)
        assertEquals(1, engine.closes)
    }

    @Test fun cancellationWaitsForLoadingAndClosesLateSessionWithoutFinishing() = runBlocking {
        val loading = CompletableDeferred<Unit>()
        val capturing = CompletableDeferred<Unit>()
        val capturedExit = CompletableDeferred<Unit>()
        val engine = Session()
        val job = launch {
            StreamingVoicePipeline.transcribe(
                openSession = {
                    loading.complete(Unit)
                    // Simulates a native constructor that only returns after cancellation.
                    withContext(NonCancellable) { capturing.await(); delay(100); engine }
                },
                capture = { _ ->
                    capturing.complete(Unit)
                    try { awaitCancellation() } finally { capturedExit.complete(Unit) }
                }, onText = { fail("Cancelled session must not publish text") }, onFinalizing = {},
            )
        }
        withTimeout(2_000) { loading.await(); capturing.await(); job.cancelAndJoin() }
        assertTrue(capturedExit.isCompleted)
        assertEquals(1, engine.closes)
        assertEquals(0, engine.finishes)
    }

    @Test fun modelFailureCancelsCaptureInsteadOfWaitingForever() = runBlocking {
        val capturing = CompletableDeferred<Unit>()
        var captureClosed = false
        try {
            withTimeout(2_000) {
                StreamingVoicePipeline.transcribe(
                    openSession = { capturing.await(); error("load failed") },
                    capture = { _ ->
                        capturing.complete(Unit)
                        try { awaitCancellation() } finally { captureClosed = true }
                    }, onText = {}, onFinalizing = {},
                )
            }
            fail("Expected model failure")
        } catch (error: IllegalStateException) { assertEquals("load failed", error.message) }
        assertTrue(captureClosed)
    }

    @Test fun captureOverflowClosesLoadedModelAndDoesNotFinalizePartialAudio() = runBlocking {
        val loaded = CompletableDeferred<Unit>()
        val engine = Session()
        try {
            withTimeout(2_000) {
                StreamingVoicePipeline.transcribe(
                    openSession = { engine.also { loaded.complete(Unit) } },
                    capture = { _ -> loaded.await(); error("bounded queue full") },
                    onText = {}, onFinalizing = { fail("Incomplete capture must not finalize") },
                )
            }
            fail("Expected capture failure")
        } catch (error: IllegalStateException) { assertEquals("bounded queue full", error.message) }
        assertEquals(1, engine.closes)
        assertEquals(0, engine.finishes)
    }
}
