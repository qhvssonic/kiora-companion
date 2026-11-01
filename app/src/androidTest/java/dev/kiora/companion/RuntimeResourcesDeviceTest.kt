package dev.kiora.companion

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.document.paddle.PaddleDocumentOcr
import dev.kiora.embedding.mediapipe.EmbeddingGemma2Port
import dev.kiora.face.FaceImage
import dev.kiora.face.onnx.InsightFacePort
import dev.kiora.model.litert.LiteRtBackend
import dev.kiora.model.litert.LiteRtModelPort
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.sherpa.MeloTtsSynthesizer
import dev.kiora.speech.sherpa.StreamingParaformerRecognizer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in stress smoke test. Public/synthetic input only; no conversation writes or network calls. */
class RuntimeResourcesDeviceTest {
    @Test fun residentChatAndSequentialAuxiliaryEnginesRemainUsable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("runtimeResources") == "true")
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("companion_settings", 0)
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val chat = LiteRtModelPort(File(settings.getString("local_model_path", "").orEmpty()),
            backend = if (settings.getString("local_backend", "cpu") == "gpu") LiteRtBackend.GPU else LiteRtBackend.CPU,
            cacheDirectory = File(context.cacheDir, "litert"), enableVision = true)
        val rounds = (arguments.getString("resourceRounds")?.toIntOrNull() ?: 20).also { require(it in 1..20) }
        val voice = checkNotNull(StreamingVoiceModelFiles(context).current()) { "Streaming ASR model required" }
        val tts = checkNotNull(MeloTtsModelFiles(context).current()) { "TTS model required" }
        val face = checkNotNull(FaceModelFiles(context).current()) { "Face model required" }
        val embedding = context.getSharedPreferences("album_search", 0)
        val embeddingFile = File(checkNotNull(embedding.getString("path", null)) { "Embedding model required" })
        val embeddingId = "embeddinggemma2-440m:${embedding.getString("sha256", "")}:photo-v1"
        val audio = wav(File(context.cacheDir, "streaming-asr-validation/0.wav")).take(48_000).toShortArray()
        val bitmap = Bitmap.createBitmap(1200, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
        canvas.drawText("设备编号 A102", 30f, 160f, paint)
        val image = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray()
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val faceImage = FaceImage(bitmap.width, bitmap.height, pixels)
        fun report(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
        var sampledPeak = 0
        var completedRounds = 0
        fun sample(round: Int, stage: String) {
            val pss = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss
            sampledPeak = maxOf(sampledPeak, pss)
            report("resource round=$round stage=$stage pssKb=$pss nativeHeapKb=${Debug.getNativeHeapAllocatedSize()/1024}")
        }
        try {
            sample(0, "baseline")
            InferenceResources.withInference("test-chat-load") { chat.prepare(); sample(0, "chat-resident") }
            repeat(rounds) { index ->
                val round = index + 1
                val start = SystemClock.elapsedRealtime()
                InferenceResources.withInference("test-chat") {
                    assertTrue(chat.respond(listOf(AgentMessage.User("请用一句话说明离线处理的含义。")), emptyList()).text.isNotBlank())
                    sample(round, "chat")
                }
                InferenceResources.withInference("test-asr") {
                    val session = StreamingParaformerRecognizer(voice).openSession()
                    try {
                        for (offset in audio.indices step 1600)
                            session.accept(Pcm16Audio(audio.copyOfRange(offset, minOf(offset + 1600, audio.size)), 16_000))
                        assertTrue(session.finish().any { it.text.isNotBlank() })
                        sample(round, "asr")
                    } finally { withContext(NonCancellable) { session.close() } }
                }
                InferenceResources.withInference("test-tts") {
                    val engine = MeloTtsSynthesizer(tts.directory, tts.id, numThreads = 4, modelFileName = tts.modelFileName)
                    try {
                        assertTrue(engine.synthesize(SpeechSynthesisRequest("设备检查已经完成。")) {}.sampleCount > 0)
                        sample(round, "tts")
                    } finally { withContext(NonCancellable) { engine.close() } }
                }
                InferenceResources.withInference("test-ocr") {
                    val engine = PaddleDocumentOcr.create(context)
                    try { assertTrue(engine.recognize(image).text.contains("A102")); sample(round, "ocr") }
                    finally { engine.release() }
                }
                InferenceResources.withInference("test-embedding") {
                    val engine = EmbeddingGemma2Port(context, embeddingFile, embeddingId)
                    try {
                        assertEquals(768, engine.embedQuery("设备编号").dimension)
                        assertEquals(768, engine.embedBitmap(bitmap).dimension)
                        assertTrue("Embedding must preserve the caller's bitmap", !bitmap.isRecycled)
                        sample(round, "embedding")
                    } finally { withContext(NonCancellable) { engine.close() } }
                }
                InferenceResources.withInference("test-face") {
                    val engine = InsightFacePort(face.directory, face.id)
                    try { engine.analyze(faceImage); sample(round, "face") }
                    finally { withContext(NonCancellable) { engine.close() } }
                }
                sample(round, "auxiliary-closed")
                completedRounds = round
                report("resource round=$round elapsedMs=${SystemClock.elapsedRealtime()-start}")
            }
            report("resource rounds=$rounds sampledPeakPssKb=$sampledPeak")
        } finally {
            withContext(NonCancellable) { chat.close() }
            bitmap.recycle()
            sample(completedRounds, "all-closed")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun wav(file: File): ShortArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(0) == 0x46464952 && buffer.getInt(8) == 0x45564157)
        var offset = 12
        while (offset + 8 <= buffer.limit()) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset.toLong() + 8 + size <= buffer.limit())
            if (buffer.getInt(offset) == 0x61746164) {
                return ShortArray(size / 2) { buffer.getShort(offset + 8 + it * 2) }
            }
            offset += 8 + size + size % 2
        }
        error("WAV data chunk missing")
    }
}
