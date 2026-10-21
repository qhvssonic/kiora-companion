package dev.kiora.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessagePreviewsTest {
    @Test fun sentImageKeepsReadableThumbnailAfterOriginalIsGone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "captures/preview-test.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", source)
        val previews = MessagePreviews(context)
        assertTrue(previews.isImage(uri))
        val name = previews.save(uri)!!
        source.delete()
        assertNotNull(previews.file(name))
        assertNotNull(BitmapFactory.decodeFile(previews.file(name)!!.absolutePath))
        previews.file(name)!!.delete()
    }
}
