package dev.kiora.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.document.paddle.PaddleDocumentOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Verifies Paddle OCR and compatibility with drafts saved by the older comparison UI. */
@RunWith(AndroidJUnit4::class)
class DocumentOcrTest {
    @Test fun diagnoseExistingPhotoWhenRequested() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("draftId")
        assumeTrue(!id.isNullOrBlank())
        val context = instrumentation.targetContext
        val source = DocumentDraftStore(context).load(requireNotNull(id))?.source
        assumeTrue(source?.isFile == true)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(requireNotNull(source).absolutePath, bounds)
        Log.i("KioraOcrDiagnostic", "photo=${bounds.outWidth}x${bounds.outHeight} bytes=${source.length()}")
        val engine = PaddleDocumentOcr.create(context)
        try {
            val page = engine.recognize(source.readBytes())
            Log.i("KioraOcrDiagnostic", "Paddle lines=${page.lines.size}")
        } catch (error: Throwable) {
            Log.e("KioraOcrDiagnostic", "Paddle failure", error)
            throw error
        } finally {
            engine.release()
        }
    } }

    @Test fun recognizesAndRestoresPaddleResult() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1600, 440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 100f }
        canvas.drawText("设备编号 A102", 60f, 170f, paint)
        canvas.drawText("检查日期 2026-10-20", 60f, 330f, paint)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val paddle = PaddleDocumentOcr.create(context)
        val paddlePage = try { paddle.recognize(bytes) } finally { paddle.release() }
        assertTrue(paddlePage.text.contains("A102"))

        val store = DocumentDraftStore(context)
        val draft = store.newCameraDraft()
        try {
            draft.source.writeBytes(bytes)
            File(draft.source.parentFile, "ocr-comparison.json").writeText(JSONObject().apply {
                put("paddle", JSONObject().apply {
                    put("text", "旧版 Paddle 结果")
                    put("lineCount", 1)
                    put("elapsedMs", 200)
                })
            }.toString())
            assertEquals("旧版 Paddle 结果", store.loadPaddleResult(draft.id)?.text)

            store.savePaddleResult(draft.id, DocumentDraftStore.EngineResult(
                paddlePage.text, paddlePage.lines.size, 200, paddle.coldLoadTimeMs))
            val restored = requireNotNull(store.loadPaddleResult(draft.id))
            assertEquals(paddlePage.text, restored.text)
            assertTrue(restored.succeeded)
        } finally {
            store.delete(draft.id)
        }
    }

}
