package dev.kiora.companion

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.kiora.speech.Pcm16Audio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** One foreground recording. Bounded PCM queue; never silently drops audio. */
internal class PushToTalkRecorder {
    private val lock = Any()
    private var recorder: AudioRecord? = null
    @Volatile private var stopping = false
    @Volatile var isRecording = false
        private set

    suspend fun capture(
        onStarted: () -> Unit,
        onLevel: (Float) -> Unit,
        maxBufferedChunks: Int = 10,
        onChunk: suspend (Pcm16Audio) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(maxBufferedChunks in 1..30)
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "设备不支持 16 kHz 单声道录音" }
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBytes * 2, SAMPLE_RATE / 5 * 2))
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                check(!stopping) { "录音已停止" }
                recorder = record
                record.startRecording()
                isRecording = true
            }
            withContext(Dispatchers.Main) { onStarted() }
            coroutineScope {
                // Streaming ASR allows three seconds (96 KB) to overlap cold model loading.
                val chunks = Channel<Pcm16Audio>(capacity = maxBufferedChunks)
                val producer = launch(Dispatchers.IO) {
                    try {
                        val buffer = ShortArray(SAMPLE_RATE / 10)
                        var count = 0
                        while (!stopping && count < SAMPLE_RATE * MAX_SECONDS) {
                            currentCoroutineContext().ensureActive()
                            val read = record.read(buffer, 0, minOf(buffer.size, SAMPLE_RATE * MAX_SECONDS - count))
                            if (read > 0) {
                                count += read
                                check(chunks.trySend(Pcm16Audio(buffer.copyOf(read), SAMPLE_RATE)).isSuccess) {
                                    "设备识别速度跟不上录音，请缩短录音或改用 SenseVoice"
                                }
                                val energy = (0 until read).sumOf { i ->
                                    val sample = buffer[i].toDouble() / 32768.0
                                    sample * sample
                                }
                                val level = sqrt(((sqrt(energy / read) - 0.0015) * 8.0).coerceAtLeast(0.0))
                                    .coerceAtMost(1.0).toFloat()
                                withContext(Dispatchers.Main) { onLevel(level) }
                            } else if (!stopping) error("录音读取失败：$read")
                        }
                        check(count >= SAMPLE_RATE / 5) { "录音太短，请至少说 0.2 秒" }
                    } finally {
                        synchronized(lock) { isRecording = false; runCatching { record.stop() } }
                        chunks.close()
                    }
                }
                try {
                    for (chunk in chunks) onChunk(chunk)
                    producer.join()
                } finally { chunks.cancel() }
            }
        } finally {
            synchronized(lock) {
                isRecording = false
                runCatching { record.stop() }
                record.release()
                recorder = null
            }
        }
    }

    fun stop() = synchronized(lock) {
        stopping = true
        isRecording = false
        runCatching { recorder?.stop() }
        Unit
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 30
    }
}
