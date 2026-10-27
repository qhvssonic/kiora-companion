package dev.kiora.companion

import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.StreamingSpeechSession
import dev.kiora.speech.StreamingTranscript
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Capture starts independently of model loading. The host capture source must bound its queue.
 * The session remains owned here until both capture and initialization have exited, including cancellation.
 */
internal object StreamingVoicePipeline {
    suspend fun transcribe(
        openSession: suspend () -> StreamingSpeechSession,
        capture: suspend (suspend (Pcm16Audio) -> Unit) -> Unit,
        onText: suspend (String) -> Unit,
        onFinalizing: suspend () -> Unit,
    ): String {
        var owned: StreamingSpeechSession? = null
        try {
            return coroutineScope {
                val ready = CompletableDeferred<StreamingSpeechSession>()
                launch {
                    val session = openSession()
                    owned = session
                    ready.complete(session)
                }
                val transcript = StreamingTranscript()
                capture { audio ->
                    val updates = ready.await().accept(audio)
                    if (updates.isNotEmpty()) {
                        updates.forEach(transcript::apply)
                        onText(transcript.text)
                    }
                }
                onFinalizing()
                ready.await().finish().forEach(transcript::apply)
                transcript.text
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
        }
    }
}
