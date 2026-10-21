package dev.kiora.companion

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.kiora.speech.Pcm16Audio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Host-owned microphone capture. No raw audio is retained after transcription. */
internal class PushToTalkRecorder(
    private val onComplete: (Result<Pcm16Audio>) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    @Volatile var isRecording = false
        private set

    fun start(scope: CoroutineScope) {
        check(!isRecording) { "录音已开始" }
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "设备不支持 16 kHz 单声道录音" }
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, SAMPLE_RATE / 5 * 2))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("麦克风初始化失败")
        }
        try {
            record.startRecording()
        } catch (error: Exception) {
            record.release()
            throw error
        }
        recorder = record
        isRecording = true
        job = scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                val audio = ShortArray(SAMPLE_RATE * MAX_SECONDS)
                val chunk = ShortArray(SAMPLE_RATE / 10)
                var count = 0
                while (isActive && isRecording && count < audio.size) {
                    val read = record.read(chunk, 0, minOf(chunk.size, audio.size - count))
                    when {
                        read > 0 -> {
                            chunk.copyInto(audio, count, 0, read)
                            count += read
                            val energy = (0 until read).sumOf { index ->
                                val sample = chunk[index].toDouble() / 32768.0
                                sample * sample
                            }
                            val rms = sqrt(energy / read)
                            val level = sqrt(((rms - 0.0015) * 8.0).coerceAtLeast(0.0))
                                .coerceAtMost(1.0).toFloat()
                            withContext(Dispatchers.Main) { onLevel(level) }
                        }
                        read < 0 && isRecording -> error("录音读取失败：$read")
                    }
                }
                check(count >= SAMPLE_RATE / 5) { "录音太短，请至少说 0.2 秒" }
                Pcm16Audio(audio.copyOf(count), SAMPLE_RATE)
            }
            isRecording = false
            runCatching { record.stop() }
            record.release()
            recorder = null
            if (outcome.exceptionOrNull() !is CancellationException) {
                withContext(Dispatchers.Main) { onComplete(outcome) }
            }
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false
        runCatching { recorder?.stop() }
    }

    fun cancel() {
        stop()
        job?.cancel()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
    }
}
