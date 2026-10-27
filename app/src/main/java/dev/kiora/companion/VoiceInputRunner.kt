package dev.kiora.companion

import android.content.Context
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.sherpa.SenseVoiceRecognizer
import dev.kiora.speech.sherpa.StreamingParaformerRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Model selection is fixed for one recording. No second-pass model is loaded. */
internal class VoiceInputRunner(private val context: Context) {
    suspend fun transcribe(
        backend: VoiceBackend,
        recorder: PushToTalkRecorder,
        onStarted: () -> Unit,
        onLevel: (Float) -> Unit,
        onPartial: (String) -> Unit,
        onFinalizing: () -> Unit,
    ): String = withContext(Dispatchers.IO) {
        VoiceRuntime.mutex.withLock {
            if (backend == VoiceBackend.PARAFORMER) {
                val directory = checkNotNull(StreamingVoiceModelFiles(context).current()) { "请先导入实时转写模型目录" }
                StreamingVoicePipeline.transcribe(
                    openSession = { StreamingParaformerRecognizer(directory).openSession() },
                    capture = { consume ->
                        recorder.capture(onStarted, onLevel, maxBufferedChunks = 30, onChunk = consume)
                    },
                    onText = { text -> withContext(Dispatchers.Main) { onPartial(text) } },
                    onFinalizing = { withContext(Dispatchers.Main) { onFinalizing() } },
                )
            } else {
                val files = VoiceModelFiles(context)
                check(files.ready) { "请先导入 SenseVoice 模型和 tokens.txt" }
                val audio = ShortArray(PushToTalkRecorder.SAMPLE_RATE * PushToTalkRecorder.MAX_SECONDS)
                var count = 0
                recorder.capture(onStarted, onLevel) { chunk ->
                    chunk.samples.copyInto(audio, count)
                    count += chunk.samples.size
                }
                withContext(Dispatchers.Main) { onFinalizing() }
                currentCoroutineContext().ensureActive()
                SenseVoiceRecognizer(files.model, files.tokens).use {
                    currentCoroutineContext().ensureActive()
                    it.transcribe(Pcm16Audio(audio.copyOf(count), PushToTalkRecorder.SAMPLE_RATE)).text
                }
            }
        }
    }
}
