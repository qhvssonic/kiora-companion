package dev.kiora.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.document.paddle.PaddleDocumentOcr
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.sherpa.SenseVoiceRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Runs both ONNX clients in one process against the single packaged runtime. */
@RunWith(AndroidJUnit4::class)
class OnnxRuntimeSharedTest {
    @Test fun ocrAndSpeechWorkInTheSameProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = File(context.filesDir, "onnx-test")
        val model = File(samples, "model.int8.onnx")
        val tokens = File(samples, "tokens.txt")
        val wav = File(samples, "zh.wav")
        assumeTrue("Push the official SenseVoice sample files to ${samples.absolutePath}",
            model.isFile && tokens.isFile && wav.isFile)

        val image = Bitmap.createBitmap(1600, 440, Bitmap.Config.ARGB_8888)
        val imageBytes = try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
            canvas.drawText("设备编号 A102", 60f, 170f, paint)
            canvas.drawText("检查日期 2026-10-20", 60f, 330f, paint)
            ByteArrayOutputStream().use { output ->
                image.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            image.recycle()
        }
        val ocr = PaddleDocumentOcr.create(context)
        try {
            assertTrue(ocr.recognize(imageBytes).text.contains("A102"))
            val audio = readPcm16Wav(wav)
            SenseVoiceRecognizer(model, tokens).use { recognizer ->
                assertTrue(recognizer.transcribe(audio).text.isNotBlank())
            }
            assertTrue(ocr.recognize(imageBytes).text.contains("A102"))
        } finally {
            ocr.release()
        }
    }

    private fun readPcm16Wav(file: File): Pcm16Audio {
        val bytes = file.readBytes()
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        val data = bytes.indices.firstOrNull { offset ->
            offset + 8 <= bytes.size && String(bytes, offset, 4, Charsets.US_ASCII) == "data"
        } ?: error("WAV data chunk not found")
        val length = minOf(le32(bytes, data + 4), bytes.size - data - 8)
        val pcm = ShortArray(length / 2) { index ->
            val offset = data + 8 + index * 2
            ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()
        }
        return Pcm16Audio(pcm, le32(bytes, 24))
    }

    private fun le32(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xff) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            ((bytes[index + 2].toInt() and 0xff) shl 16) or
            ((bytes[index + 3].toInt() and 0xff) shl 24)
}
