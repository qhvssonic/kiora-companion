package dev.kiora.companion

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureProviderTest {
    @Test fun capturedPhotoUriCanBeWrittenAndRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("test_capture_", ".jpg", folder)
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", file)
            context.contentResolver.openOutputStream(uri)?.use { it.write(byteArrayOf(1, 2, 3)) }
            assertEquals("image/jpeg", context.contentResolver.getType(uri))
            assertEquals(listOf<Byte>(1, 2, 3), context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toList() })
        } finally {
            file.delete()
        }
    }

    @Test fun workspaceFileCanBeSelectedAsAnAttachment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "agent-workspace").apply { mkdirs() }
        val file = File.createTempFile("test_selected_", ".txt", folder)
        try {
            file.writeText("workspace attachment")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.capture", file)
            assertEquals("text/plain", context.contentResolver.getType(uri))
            assertEquals("workspace attachment", context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() })
        } finally {
            file.delete()
        }
    }
}
