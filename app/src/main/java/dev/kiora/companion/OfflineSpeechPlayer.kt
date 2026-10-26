package dev.kiora.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.kiora.speech.SpeechAudioChunk
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.SpeechSynthesisResult
import dev.kiora.speech.sherpa.MeloTtsSynthesizer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** One local playback at a time. Stopping sound and completing native cancellation are separate. */
internal class OfflineSpeechPlayer(
    context: Context,
    private val onState: (String, Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val serial = AtomicLong()
    private val activeSpeech = AtomicBoolean(false)
    private val operationLock = Mutex()
    private val audioLock = Any()
    private var operation: Job? = null
    private var track: AudioTrack? = null

    fun stop() {
        val ticket = serial.incrementAndGet()
        val wasActive = activeSpeech.getAndSet(false)
        synchronized(audioLock) {
            operation?.cancel()
            track?.let { runCatching { it.pause(); it.flush() } }
        }
        if (wasActive) report(ticket, "朗读已停止", false)
    }

    suspend fun speak(request: SpeechSynthesisRequest): SpeechSynthesisResult {
        stop()
        val ticket = serial.incrementAndGet()
        activeSpeech.set(true)
        report(ticket, "正在准备离线朗读…", true)
        try {
            return operationLock.withLock {
                checkCurrent(ticket)
                coroutineScope {
                    val worker = async(Dispatchers.Default, start = CoroutineStart.LAZY) { play(ticket, request) }
                    synchronized(audioLock) {
                        checkCurrent(ticket)
                        operation = worker
                    }
                    try {
                        worker.start()
                        worker.await()
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        // A playback stop should be a tool failure, not cancellation of AgentRuntime.
                        throw IllegalStateException("用户已停止朗读，请勿自动重试", cancelled)
                    } finally {
                        synchronized(audioLock) { if (operation === worker) operation = null }
                    }
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException) report(ticket, "朗读失败：${error.message}", false)
            throw error
        } finally {
            if (serial.get() == ticket) activeSpeech.set(false)
        }
    }

    private suspend fun play(ticket: Long, request: SpeechSynthesisRequest): SpeechSynthesisResult {
        val model = withContext(Dispatchers.IO) { MeloTtsModelFiles(app).current() }
            ?: error("请先在设置中导入 MeloTTS 离线朗读模型")
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && serial.get() == ticket) stop()
            }, main).build()
        var synthesizer: MeloTtsSynthesizer? = null
        var localTrack: AudioTrack? = null
        var written = 0L
        var started = false
        val beganAt = SystemClock.elapsedRealtime()
        val caller = currentCoroutineContext()
        fun startPlayback(output: AudioTrack) {
            synchronized(audioLock) {
                caller.ensureActive()
                checkCurrent(ticket)
                if (!started) {
                    output.play()
                    started = true
                    Log.i("KioraSpeech", "playback_start request=$ticket prepareMs=${SystemClock.elapsedRealtime() - beganAt}")
                    report(ticket, "正在离线朗读…", true)
                }
            }
        }
        try {
            caller.ensureActive()
            checkCurrent(ticket)
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "暂时无法获得音频播放权限，请稍后重试"
            }
            val engine = MeloTtsSynthesizer(model.directory, model.id,
                numThreads = 4, modelFileName = model.modelFileName).also { synthesizer = it }
            return SpeechPlaybackPipeline.play(engine, request) { chunk ->
                caller.ensureActive()
                checkCurrent(ticket)
                val output = localTrack ?: createTrack(chunk, attributes).also { candidate ->
                    synchronized(audioLock) {
                        try {
                            caller.ensureActive()
                            checkCurrent(ticket)
                            track = candidate
                            localTrack = candidate
                        } catch (error: Throwable) { candidate.release(); throw error }
                    }
                }
                val samples = chunk.samples()
                var offset = 0
                while (offset < samples.size) {
                    caller.ensureActive()
                    checkCurrent(ticket)
                    val count = output.write(samples, offset,
                        minOf(samples.size - offset, chunk.sampleRateHz / 20), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "音频播放失败：$count" }
                    offset += count
                    written += count
                    // Fill the device buffer before starting; starting an empty track creates
                    // an avoidable initial underrun even when the producer has PCM ready.
                    if (!started && (written >= output.bufferSizeInFrames || count == 0)) startPlayback(output)
                    if (count == 0) delay(5)
                }
            }.also {
                localTrack?.let { output ->
                    if (!started) startPlayback(output)
                    // Read before the final drain so intentional end-of-stream is not counted.
                    Log.i("KioraSpeech", "playback_queued request=$ticket samples=$written rate=${it.sampleRateHz} underruns=${output.underrunCount}")
                }
                // Drain the bounded AudioTrack buffer before reporting playback completion.
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (localTrack != null && (localTrack.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                    caller.ensureActive()
                    checkCurrent(ticket)
                    check(SystemClock.elapsedRealtime() < deadline) { "音频播放未能完成" }
                    delay(10)
                }
                report(ticket, "", false)
                Log.i("KioraSpeech", "playback_complete request=$ticket elapsedMs=${SystemClock.elapsedRealtime() - beganAt}")
            }
        } finally {
            synchronized(audioLock) {
                if (track === localTrack) track = null
                localTrack?.let { output -> runCatching { output.stop() }; output.release() }
            }
            manager.abandonAudioFocusRequest(focus)
            withContext(NonCancellable) { synthesizer?.close() }
        }
    }

    private fun createTrack(chunk: SpeechAudioChunk, attributes: AudioAttributes): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(chunk.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT)
        check(minimum > 0) { "设备不支持此朗读音频格式" }
        val output = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(chunk.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, chunk.sampleRateHz * 4 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            error("无法初始化朗读播放器")
        }
        return output
    }

    private fun checkCurrent(ticket: Long) {
        if (serial.get() != ticket) throw CancellationException("Speech request was stopped or replaced")
    }

    private fun report(ticket: Long, text: String, active: Boolean) {
        main.post { if (serial.get() == ticket) onState(text, active) }
    }
}
