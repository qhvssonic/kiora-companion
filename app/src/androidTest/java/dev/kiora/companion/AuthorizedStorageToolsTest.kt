package dev.kiora.companion

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.android.storage.AndroidStorageTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Android document provider when a developer grants KioraToolTest. */
@RunWith(AndroidJUnit4::class)
class AuthorizedStorageToolsTest {
    @Test fun grantBoundariesAndFileOperations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val grants = AuthorizedStorageGrants(context)
        val tree = grants.folders().firstOrNull { it.toString().contains("KioraToolTest") }
        assumeTrue("Grant a disposable KioraToolTest folder first", tree != null)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree!!, DocumentsContract.getTreeDocumentId(tree))
        val storage = AndroidStorageTools(context, grants::folders, grants::photos)
        val tools = storage.all().associateBy { it.definition.name }
        val folderId = Json.parseToJsonElement(tools.getValue("list_authorized_folders").execute(JsonObject(emptyMap())))
            .jsonArray.first { it.jsonObject["name"]?.jsonPrimitive?.content == "KioraToolTest" }
            .jsonObject.getValue("id").jsonPrimitive.content
        val created = DocumentsContract.createDocument(context.contentResolver, root, "text/plain", "tool-probe.txt")!!
        val subfolder = DocumentsContract.createDocument(context.contentResolver, root,
            DocumentsContract.Document.MIME_TYPE_DIR, "tool-probe-dir")!!
        try {
            context.contentResolver.openOutputStream(created, "w")!!.use { it.write("probe content".toByteArray()) }
            fun args(vararg fields: Pair<String, String>) = JsonObject(fields.associate { it.first to JsonPrimitive(it.second) })
            val listed = tools.getValue("list_authorized_files").execute(args("folderId" to folderId))
            assertTrue(listed.contains("tool-probe.txt"))
            assertEquals("probe content", tools.getValue("read_authorized_text").execute(
                args("folderId" to folderId, "path" to "tool-probe.txt")))
            val rejected = runCatching { tools.getValue("read_authorized_text").execute(
                args("folderId" to folderId, "path" to "../tool-probe.txt")) }
            assertTrue(rejected.isFailure)
            val copied = tools.getValue("copy_authorized_file").execute(args(
                "sourceFolderId" to folderId, "sourcePath" to "tool-probe.txt",
                "destinationFolderId" to folderId, "newName" to "tool-copy.txt"))
            assertTrue(copied.contains("Copied"))
            assertTrue(tools.getValue("rename_authorized_file").execute(args(
                "folderId" to folderId, "path" to "tool-copy.txt", "newName" to "tool-renamed.txt")).contains("Renamed"))
            assertTrue(tools.getValue("move_authorized_file").execute(args(
                "sourceFolderId" to folderId, "sourcePath" to "tool-renamed.txt",
                "destinationFolderId" to folderId, "destinationPath" to "tool-probe-dir"
            )).contains("Moved"))
            assertTrue(tools.getValue("delete_authorized_file").execute(args(
                "folderId" to folderId, "path" to "tool-probe-dir/tool-renamed.txt")).contains("Deleted"))
        } finally {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, subfolder) }
        }
    }
}
