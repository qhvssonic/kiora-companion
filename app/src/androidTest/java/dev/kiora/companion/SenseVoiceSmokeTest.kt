package dev.kiora.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.sherpa.SenseVoiceRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional device smoke test. Import model.int8.onnx, tokens.txt, and zh.wav first. */
@RunWith(AndroidJUnit4::class)
class SenseVoiceSmokeTest {
    @Test fun transcribesOfficialChineseSampleOffline() = runBlocking {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "sensevoice")
        val model = File(dir, "model.int8.onnx")
        val tokens = File(dir, "tokens.txt")
        val wav = File(dir, "zh.wav")
        assumeTrue(model.isFile && tokens.isFile && wav.isFile)
        val bytes = wav.readBytes()
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        val sampleRate = le32(bytes, 24)
        val data = bytes.indices.firstOrNull { offset ->
            offset + 8 <= bytes.size && String(bytes, offset, 4, Charsets.US_ASCII) == "data"
        } ?: error("WAV data chunk not found")
        val length = minOf(le32(bytes, data + 4), bytes.size - data - 8)
        val samples = ShortArray(length / 2) { index ->
            val offset = data + 8 + index * 2
            ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()
        }
        val result = SenseVoiceRecognizer(model, tokens).use {
            it.transcribe(Pcm16Audio(samples, sampleRate))
        }
        assertTrue("Expected a transcript from the official zh.wav sample", result.text.isNotBlank())
        println("SenseVoice transcript: ${result.text}")
    }

    private fun le32(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xff) or
            ((bytes[index + 1].toInt() and 0xff) shl 8) or
            ((bytes[index + 2].toInt() and 0xff) shl 16) or
            ((bytes[index + 3].toInt() and 0xff) shl 24)
}
