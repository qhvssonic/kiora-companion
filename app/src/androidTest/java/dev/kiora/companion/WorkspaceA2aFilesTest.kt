package dev.kiora.companion

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceA2aFilesTest {
    @Test fun readsMarkdownFromWorkspaceOnly() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val parent = File(cache, "workspace-a2a-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val root = File(parent, "workspace").apply { mkdir() }
            val expected = "# 扫描结果\n".toByteArray(Charsets.UTF_8)
            File(root, "result.md").writeBytes(expected)
            File(parent, "outside.md").writeText("outside")

            val file = WorkspaceA2aFiles.read(root, "result.md")
            assertEquals("result.md", file.filename)
            assertEquals("text/markdown", file.mediaType)
            assertArrayEquals(expected, file.bytes)
            assertEquals("result.md", WorkspaceA2aFiles.relativePathForId("result.md"))
            assertEquals("result.md", WorkspaceA2aFiles.relativePathForId("workspace:result.md"))
            assertEquals(null, WorkspaceA2aFiles.relativePathForId("selected:0"))
            assertEquals(null, WorkspaceA2aFiles.relativePathForId("authorized:folder_1/result.md"))
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceA2aFiles.read(root, "../outside.md")
            }
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceA2aFiles.read(root, WorkspaceA2aFiles.relativePathForId("../outside.md")!!)
            }
        } finally {
            parent.deleteRecursively()
        }
    }
}
