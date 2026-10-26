package dev.kiora.companion

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.sherpa.MeloTtsSynthesizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in device benchmark; no audio playback, downloads, or changes to imported assets. */
class MeloTtsPerformanceTest {
    @Test fun compareThreadCountsWithoutPlaybackBackpressure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("ttsBenchmark") == "true")
        val context = instrumentation.targetContext
        val model = checkNotNull(MeloTtsModelFiles(context).current()) {
            "Import the pinned MeloTTS model before running this benchmark"
        }
        val text = "有一天，一个程序员去面试。面试官问他，你有什么爱好？程序员回答，我喜欢看电影，特别是那些关于太空探索的。"
        // Some OEMs suspend a UI-less instrumented app. Launch through the shell identity,
        // as an external debugger would, instead of a blocked background activity start.
        instrumentation.uiAutomation.executeShellCommand("am start -W -n dev.kiora.companion/.MainActivity")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val useFp32Cache = InstrumentationRegistry.getArguments().getString("ttsFp32") == "true"
        val modelFileName = if (useFp32Cache) "model.onnx" else model.modelFileName
        val fp32 = modelFileName == "model.onnx"
        val directory = if (useFp32Cache) File(context.cacheDir, "tts-fp32-benchmark").apply {
            check(File(this, "model.onnx").isFile) { "Push FP32 weights into the benchmark cache first" }
            // A separate temporary bundle; never replace the user's imported voice assets.
            for (name in MeloTtsSynthesizer.REQUIRED_FILES.filter { it != "model.int8.onnx" }) {
                File(model.directory, name).copyTo(File(this, name), overwrite = true)
            }
        } else model.directory
        for (threads in listOf(2, 4, 6)) {
            val engine = MeloTtsSynthesizer(directory, if (useFp32Cache) "fp32-benchmark" else model.id,
                numThreads = threads, modelFileName = modelFileName)
            try {
                repeat(2) { round ->
                    Log.i("KioraTtsBenchmark", "begin threads=$threads round=$round")
                    val start = SystemClock.elapsedRealtime()
                    var firstAudioMs = -1L
                    var samples = 0L
                    val result = engine.synthesize(SpeechSynthesisRequest(text)) { chunk ->
                        if (firstAudioMs < 0) firstAudioMs = SystemClock.elapsedRealtime() - start
                        samples += chunk.sampleCount
                    }
                    val elapsedMs = SystemClock.elapsedRealtime() - start
                    assertEquals(samples, result.sampleCount)
                    assertTrue(samples > 0)
                    val report = "fp32=$fp32 threads=$threads round=$round includesLoad=${round == 0} firstAudioMs=$firstAudioMs synthesisMs=$elapsedMs audioMs=${result.durationMillis} rtf=${elapsedMs.toDouble() / result.durationMillis}"
                    Log.i("KioraTtsBenchmark", report)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
                }
            } finally { engine.close() }
        }
    }
}
