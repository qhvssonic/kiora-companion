package dev.kiora.companion

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.StreamingTranscript
import dev.kiora.speech.sherpa.StreamingParaformerRecognizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in public fixtures and a separate brief microphone lifecycle check. No uploads or conversation writes. */
class StreamingVoiceTest {
    @Test fun publicFixturesProducePartialsBeforeStopAndDrainLastSegment() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("streamingVoice") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        foregroundReady()
        val directory = checkNotNull(StreamingVoiceModelFiles(context).current()) { "Import the pinned bundle first" }
        VoiceRuntime.mutex.withLock {
            val beforeKb = pssKb()
            val loadStart = SystemClock.elapsedRealtime()
            val session = StreamingParaformerRecognizer(directory).openSession()
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            var sampledPeakKb = pssKb()
            try {
                val transcript = StreamingTranscript()
                var partials = 0
                var finals = 0
                var firstPartialMs = -1L
                var firstPartialAudioMs = -1L
                var decodeMs = 0L
                var audioMs = 0L
                val start = SystemClock.elapsedRealtime()
                // Feed the Chinese sample at microphone cadence, then silence and a second sample.
                // This exercises endpoint/reset and prevents a single utterance-only smoke test.
                for (name in listOf("0.wav", "1.wav")) {
                    val pcm = readWav(File(context.cacheDir, "streaming-asr-validation/$name"))
                    for (offset in pcm.indices step 1600) {
                        val chunk = pcm.copyOfRange(offset, minOf(offset + 1600, pcm.size))
                        audioMs += chunk.size * 1000L / 16_000
                        delay((start + audioMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        val tick = SystemClock.elapsedRealtime()
                        val updates = session.accept(Pcm16Audio(chunk, 16_000))
                        decodeMs += SystemClock.elapsedRealtime() - tick
                        updates.forEach { update ->
                            transcript.apply(update)
                            if (update.isFinal) finals++ else if (update.text.isNotBlank()) {
                                partials++
                                if (firstPartialMs < 0) {
                                    firstPartialMs = SystemClock.elapsedRealtime() - start
                                    firstPartialAudioMs = audioMs
                                }
                            }
                        }
                        if (offset % 16_000 == 0) sampledPeakKb = maxOf(sampledPeakKb, pssKb())
                    }
                    // Explicit silence creates an endpoint between clips without finishing the stream.
                    repeat(20) {
                        session.accept(Pcm16Audio(ShortArray(1600), 16_000)).forEach {
                            transcript.apply(it)
                            if (it.isFinal) finals++
                        }
                    }
                }
                assertTrue("Must display changing text before finish", partials >= 2)
                assertTrue("Must seal at least one endpoint before stop", finals >= 1)
                val finishStart = SystemClock.elapsedRealtime()
                val last = session.finish()
                last.forEach(transcript::apply)
                assertTrue(last.last().isFinal)
                assertTrue(transcript.text.isNotBlank())
                assertTrue(session.finish().isEmpty())
                try {
                    session.accept(Pcm16Audio(ShortArray(1600), 16_000))
                    fail("Audio after finish must be rejected")
                } catch (_: IllegalStateException) { }
                val report = "loadMs=$loadMs firstPartialMs=$firstPartialMs firstPartialAudioMs=$firstPartialAudioMs " +
                    "finishMs=${SystemClock.elapsedRealtime()-finishStart} decodeMs=$decodeMs audioMs=$audioMs " +
                    "partials=$partials finals=$finals baselinePssKb=$beforeKb sampledPeakPssKb=$sampledPeakKb " +
                    "deltaPssKb=${sampledPeakKb-beforeKb} transcript=${transcript.text}"
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
            } finally { session.close(); session.close() }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "afterClosePssKb=${pssKb()}\n") })
        }
    }

    @Test fun silenceAndEarlyStopRemainUsableAfterReopening() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("streamingVoice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        VoiceRuntime.mutex.withLock {
            val engine = StreamingParaformerRecognizer(checkNotNull(StreamingVoiceModelFiles(context).current()))
            engine.openSession().use { session ->
                repeat(5) { session.accept(Pcm16Audio(ShortArray(1600), 16_000)) }
                assertTrue(session.finish().all { it.text.isBlank() })
            }
            engine.openSession().use { session ->
                val pcm = readWav(File(context.cacheDir, "streaming-asr-validation/0.wav"))
                val transcript = StreamingTranscript()
                for (offset in 0 until minOf(48_000, pcm.size) step 1600) {
                    session.accept(Pcm16Audio(pcm.copyOfRange(offset, offset + 1600), 16_000))
                        .forEach(transcript::apply)
                }
                session.finish().forEach(transcript::apply)
                assertTrue("Stopping mid-sample must drain available speech", transcript.text.isNotBlank())
            }
        }
    }

    @Test fun microphoneStopBackgroundAndRapidCancelReleaseSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("streamingVoiceUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = foregroundReady()
        val oldBackend = VoicePreferences(activity).backend
        var draft = ""
        val composer = field(activity, "composer") as EditText
        instrumentation.runOnMainSync {
            draft = composer.text.toString()
            VoicePreferences(activity).backend = VoiceBackend.PARAFORMER
        }
        fun toggle() = instrumentation.runOnMainSync {
            MainActivity::class.java.getDeclaredMethod("toggleVoice").apply { isAccessible = true }.invoke(activity)
        }
        suspend fun awaitUi(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (true) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                check(SystemClock.elapsedRealtime() < deadline) { "Voice UI did not reach expected state" }
                delay(50)
            }
        }
        try {
            val clickStart = SystemClock.elapsedRealtime()
            toggle()
            awaitUi { (field(activity, "voiceRecorder") as? PushToTalkRecorder)?.isRecording == true }
            val clickToRecordingMs = SystemClock.elapsedRealtime() - clickStart
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "clickToRecordingMs=$clickToRecordingMs\n")
            })
            assertTrue("Microphone must not wait for a cold ASR load", clickToRecordingMs < 1000)
            instrumentation.runOnMainSync { assertFalse(composer.isEnabled) }
            delay(500)
            toggle()
            awaitUi { field(activity, "voiceJob") == null && composer.isEnabled }
            assertFalse(VoiceRuntime.mutex.isLocked)

            toggle()
            awaitUi { (field(activity, "voiceRecorder") as? PushToTalkRecorder)?.isRecording == true }
            var job: Job? = null
            var recorder: PushToTalkRecorder? = null
            instrumentation.runOnMainSync {
                job = field(activity, "voiceJob") as Job
                recorder = field(activity, "voiceRecorder") as PushToTalkRecorder
            }
            shell("input keyevent 3")
            awaitUi { job!!.isCompleted }
            assertFalse(recorder!!.isRecording)
            assertFalse(VoiceRuntime.mutex.isLocked)
            foregroundReady()
            instrumentation.runOnMainSync { assertTrue(composer.isEnabled) }

            // Two taps in one UI turn cancel preparation before a microphone can start.
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("toggleVoice").apply { isAccessible = true }.let {
                    it.invoke(activity); it.invoke(activity)
                }
            }
            awaitUi { field(activity, "voiceJob") == null && composer.isEnabled }
            delay(250)
            assertFalse(VoiceRuntime.mutex.isLocked)
        } finally {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("cancelVoice").apply { isAccessible = true }.invoke(activity)
                composer.setText(draft)
                VoicePreferences(activity).backend = oldBackend
            }
        }
    }

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private suspend fun foregroundReady(): MainActivity {
        // Instrumentation restarts the app. OEM background throttling otherwise distorts model load time.
        shell("am start -W -n dev.kiora.companion/.MainActivity")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (true) {
            var ready: MainActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                if (activity != null && field(activity, "modelReady") == true) ready = activity
            }
            if (ready != null) return ready!!
            check(SystemClock.elapsedRealtime() < deadline) { "Keep the device unlocked; configure a working chat model" }
            delay(100)
        }
    }

    private fun pssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        var offset = 12
        var valid = false
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset + 8L + length <= bytes.size)
            when (String(bytes, offset, 4)) {
                "fmt " -> {
                    require(length >= 16 && buffer.getShort(offset + 8).toInt() == 1)
                    require(buffer.getShort(offset + 10).toInt() == 1 && buffer.getInt(offset + 12) == 16_000)
                    require(buffer.getShort(offset + 22).toInt() == 16)
                    valid = true
                }
                "data" -> {
                    require(valid)
                    return ShortArray(length / 2) { buffer.getShort(offset + 8 + it * 2) }
                }
            }
            offset += 8 + length + length % 2
        }
        error("Missing PCM data")
    }
}
