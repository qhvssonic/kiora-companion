package dev.kiora.companion

import dev.kiora.core.AgentTool
import dev.kiora.core.AgentMessage
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolImage
import dev.kiora.core.ToolOutput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneResourceToolsTest {
    private data class Call(val name: String, val arguments: JsonObject)
    private val calls = mutableListOf<Call>()

    private fun fake(name: String): AgentTool = object : AgentTool {
        override val definition = ToolDefinition(name, name, JsonObject(emptyMap()))
        override suspend fun execute(arguments: JsonObject): String {
            calls += Call(name, arguments)
            return name
        }
    }

    private fun tools(): Map<String, AgentTool> {
        val storage = listOf(
            "list_authorized_folders", "list_authorized_files", "search_authorized_files",
            "inspect_authorized_file", "read_authorized_text", "copy_authorized_file",
            "move_authorized_file", "rename_authorized_file", "delete_authorized_file",
        ).map(::fake)
        val galleryRead = object : AgentTool {
            override val definition = ToolDefinition("read_gallery_photo", "", JsonObject(emptyMap()))
            override suspend fun execute(arguments: JsonObject) = "gallery image"
            override suspend fun executeResult(arguments: JsonObject): ToolOutput {
                calls += Call("read_gallery_photo", arguments)
                return ToolOutput("gallery image", listOf(ToolImage("image/jpeg", "YQ==")))
            }
        }
        val gallery = listOf(fake("list_gallery_photos"), galleryRead)
        return PhoneResourceTools(fake("workspace_read"), storage, gallery,
            selectedFile = { id ->
                calls += Call("selected_file", args("index" to id))
                ToolOutput("selected text")
            }).all()
            .associateBy { it.definition.name }
    }

    private fun args(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (key, value) ->
        key to JsonPrimitive(value)
    })

    @Test fun readRoutesWorkspaceAuthorizedTextAndGalleryPhoto() = runBlocking {
        val read = tools().getValue("read")
        read.execute(args("path" to "notes.txt"))
        read.execute(args("path" to "authorized:folder_123/docs/report.txt"))
        val photo = read.executeResult(args("path" to "gallery:gallery_42"))

        assertEquals(listOf("workspace_read", "read_authorized_text", "read_gallery_photo"), calls.map { it.name })
        assertEquals("folder_123", calls[1].arguments["folderId"]?.jsonPrimitive?.content)
        assertEquals("docs/report.txt", calls[1].arguments["path"]?.jsonPrimitive?.content)
        assertEquals("gallery_42", calls[2].arguments["photoId"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", photo.images.single().mimeType)
    }

    @Test fun readUsesTurnScopedSelectedFileId() = runBlocking {
        val result = tools().getValue("read").executeResult(args("path" to "selected:0"))
        assertEquals("selected text", result.text)
        assertEquals("selected_file", calls.single().name)
        assertEquals("0", calls.single().arguments["index"]?.jsonPrimitive?.content)
    }

    @Test fun readAcceptsNumericGalleryPathFromConversation() = runBlocking {
        tools().getValue("read").executeResult(args("path" to "gallery:42"))
        assertEquals("gallery_42", calls.single().arguments["photoId"]?.jsonPrimitive?.content)
    }

    @Test fun browseAndManageDispatchToGrantBoundTools() = runBlocking {
        val exposed = tools()
        assertEquals(setOf("read", PhoneResourceTools.BROWSE, PhoneResourceTools.MANAGE), exposed.keys)
        exposed.getValue(PhoneResourceTools.BROWSE).execute(args("action" to "files", "folderId" to "folder_123"))
        exposed.getValue(PhoneResourceTools.BROWSE).execute(args("action" to "gallery", "query" to "cat"))
        exposed.getValue(PhoneResourceTools.MANAGE).execute(args(
            "action" to "move", "folderId" to "folder_123", "path" to "a.txt",
            "destinationFolderId" to "folder_456",
        ))
        exposed.getValue(PhoneResourceTools.MANAGE).execute(args(
            "action" to "delete", "folderId" to "folder_123", "path" to "old.txt",
        ))

        assertEquals(listOf("list_authorized_files", "list_gallery_photos", "move_authorized_file",
            "delete_authorized_file"), calls.map { it.name })
        assertEquals("folder_123", calls[2].arguments["sourceFolderId"]?.jsonPrimitive?.content)
        assertEquals("a.txt", calls[2].arguments["sourcePath"]?.jsonPrimitive?.content)
        assertEquals("folder_456", calls[2].arguments["destinationFolderId"]?.jsonPrimitive?.content)
        assertTrue(calls[3].arguments["destinationFolderId"] == null)
    }

    @Test fun imageToolResultSurvivesRoomMessageEncoding() {
        val result = AgentMessage.ToolResult("call", "read", "Photo",
            images = listOf(ToolImage("image/jpeg", "YQ==")))
        assertEquals(result, AgentMessageCodec.decode(AgentMessageCodec.encode(result)))
    }

    @Test fun toolFailureKindSurvivesRoomMessageEncoding() {
        val result = AgentMessage.ToolResult("call", "read", "Denied", true,
            failureKind = dev.kiora.core.ToolFailureKind.AUTHORIZATION_DENIED)
        assertEquals(result, AgentMessageCodec.decode(AgentMessageCodec.encode(result)))
    }

    @Test fun selectedImageSurvivesRoomMessageEncoding() {
        val user = AgentMessage.User("describe", listOf(ToolImage("image/jpeg", "YQ==")))
        assertEquals(user, AgentMessageCodec.decode(AgentMessageCodec.encode(user)))
    }
}
