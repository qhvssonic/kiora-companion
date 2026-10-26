package dev.kiora.companion

import dev.kiora.speech.SpeechAudioChunk
import dev.kiora.speech.SpeechSynthesisPort
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.SpeechSynthesisResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpeechPlaybackPipelineTest {
    private val request = SpeechSynthesisRequest("离线朗读验证")

    private fun engine(generate: ((SpeechAudioChunk) -> Unit) -> Long) = object : SpeechSynthesisPort {
        override val modelId = "fake"
        override suspend fun synthesize(request: SpeechSynthesisRequest,
            onAudio: (SpeechAudioChunk) -> Unit) = SpeechSynthesisResult(generate(onAudio), 8_000)
        override suspend fun close() = Unit
    }

    @Test fun shortAudioDrainsWithoutFullPrebufferAndPreservesSampleOrder() = runBlocking {
        val first = FloatArray(1_230) { it / 2_000f }
        val second = FloatArray(1_570) { -it / 2_000f }
        val received = mutableListOf<Float>()
        val result = withTimeout(5_000) {
            SpeechPlaybackPipeline.play(engine { emit ->
                emit(SpeechAudioChunk(first, 8_000))
                emit(SpeechAudioChunk(second, 8_000))
                2_800L
            }, request) { frame ->
                assertTrue(frame.sampleCount <= 800)
                received.addAll(frame.samples().toList())
            }
        }
        assertArrayEquals(first + second, received.toFloatArray(), 0f)
        assertEquals(2_800L, result.sampleCount)
    }

    @Test fun synthesisCanAdvanceWhilePlaybackIsBusy() = runBlocking {
        val secondProduced = CompletableDeferred<Unit>()
        var received = 0
        withTimeout(5_000) {
            SpeechPlaybackPipeline.play(engine { emit ->
                emit(SpeechAudioChunk(FloatArray(8_000), 8_000))
                emit(SpeechAudioChunk(FloatArray(8_000), 8_000))
                secondProduced.complete(Unit)
                16_000L
            }, request) { frame ->
                // The old synchronous callback would deadlock here until playback returned.
                secondProduced.await()
                received += frame.sampleCount
            }
        }
        assertEquals(16_000, received)
    }

    @Test fun cancellationUnblocksProducerWithFullQueueAndWaitsForItsExit() = runBlocking {
        val consuming = CompletableDeferred<Unit>()
        val producerExited = CompletableDeferred<Unit>()
        val callbackReturned = CompletableDeferred<Unit>()
        val job = launch {
            SpeechPlaybackPipeline.play(engine { emit ->
                try {
                    // Larger than the eight-second queue; the callback cannot finish while
                    // playback holds the first frame. This also guards against unbounded queues.
                    emit(SpeechAudioChunk(FloatArray(160_000), 8_000))
                    callbackReturned.complete(Unit)
                    160_000L
                } finally { producerExited.complete(Unit) }
            }, request) {
                consuming.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5_000) {
            consuming.await()
            assertFalse(callbackReturned.isCompleted)
            job.cancelAndJoin()
        }
        assertTrue(producerExited.isCompleted)
        assertFalse(callbackReturned.isCompleted)
    }

    @Test fun producerFailureBeforeFirstAudioDoesNotWaitForever() = runBlocking {
        val failure = IllegalStateException("synthesis failed")
        try {
            withTimeout(5_000) {
                SpeechPlaybackPipeline.play(engine { throw failure }, request) { fail("Unexpected audio") }
            }
            fail("Expected synthesis error")
        } catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
    }
}
