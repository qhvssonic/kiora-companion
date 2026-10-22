package dev.kiora.companion

import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolOutput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A small model-facing surface over the Android grant-bound storage and gallery tools. */
internal class PhoneResourceTools(
    private val workspaceRead: AgentTool,
    storage: List<AgentTool>,
    gallery: List<AgentTool>,
    private val authorizedImage: suspend (String, String) -> ToolOutput? = { _, _ -> null },
    private val workspaceImage: suspend (String) -> ToolOutput? = { null },
    private val selectedFile: suspend (String) -> ToolOutput = { error("No file is selected for this turn") },
    private val normalizeSelectedPath: (String) -> String = { it },
) {
    private val storageByName = storage.associateBy { it.definition.name }
    private val galleryByName = gallery.associateBy { it.definition.name }

    fun all(): List<AgentTool> = listOf(read, browse, manage)

    private val read = object : AgentTool {
        override val definition = ToolDefinition(
            "read",
            "Read a UTF-8 workspace file by relative path, a file selected in this turn as " +
                "selected:<index>, an authorized folder file as authorized:<folderId>/<relativePath>, " +
                "or a gallery photo as gallery:gallery_<numericId>. " +
                "Folder IDs come from browse_phone_files; photo IDs come from browse_phone_files " +
                "or search_album_photos. Search results are already displayed as photos in chat; " +
                "only read a gallery photo when the user asks to analyze its contents. Images are sent as " +
                "image content to the current vision model, not as a prior description. " +
                "An image already attached to the current user message is visible directly; " +
                "do not call read merely to see that image or use an invented path. " +
                "Use workspace:<relativePath> to choose a workspace file when an attachment has the same name.",
            schema(
                "path" to string("Workspace path, workspace:<path>, selected:<index>, authorized:<folderId>/<path>, or gallery:gallery_<numericId>"),
                required = listOf("path"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = executeResult(arguments).text

        override suspend fun executeResult(arguments: JsonObject): ToolOutput {
            val path = normalizeSelectedPath(arguments.value("path"))
            require(path.isNotBlank()) { "path is required" }
            return when {
                path.startsWith("selected:") -> selectedFile(path.removePrefix("selected:"))
                path.startsWith("authorized:") -> {
                    val location = path.removePrefix("authorized:")
                    val folderId = location.substringBefore('/')
                    val relativePath = location.substringAfter('/', "")
                    require(folderId.startsWith("folder_") && relativePath.isNotBlank()) {
                        "Use authorized:<folderId>/<relativePath>"
                    }
                    authorizedImage(folderId, relativePath) ?: storage("read_authorized_text").executeResult(buildJsonObject {
                        put("folderId", folderId)
                        put("path", relativePath)
                    })
                }
                path.startsWith("gallery:") -> {
                    val rawId = path.removePrefix("gallery:")
                    val numericId = rawId.removePrefix("gallery_")
                    require(numericId.isNotEmpty() && numericId.all(Char::isDigit)) {
                        "Use gallery:gallery_<numericId>"
                    }
                    val photoId = "gallery_$numericId"
                    gallery("read_gallery_photo").executeResult(buildJsonObject {
                        put("photoId", photoId)
                    })
                }
                path.startsWith("workspace:") -> {
                    val relative = path.removePrefix("workspace:")
                    workspaceImage(relative) ?: workspaceRead.executeResult(buildJsonObject { put("path", relative) })
                }
                else -> workspaceImage(path) ?: workspaceRead.executeResult(buildJsonObject { put("path", path) })
            }
        }
    }

    private val browse = object : AgentTool {
        override val definition = ToolDefinition(
            BROWSE,
            "Discover user-authorized phone resources. actions: folders lists authorized folders; " +
                "files lists a folderId and optional relative path; search finds names in authorized " +
                "folders; inspect returns metadata for folderId/path; gallery lists recent photos or " +
                "searches photo names after full Android photo access. Use read with the returned IDs " +
                "to examine text or image content. Names alone do not reveal file content.",
            schema(
                "action" to string("folders, files, search, inspect, or gallery", "folders", "files", "search", "inspect", "gallery"),
                "folderId" to string("Authorized folder ID for files or inspect"),
                "path" to string("Relative path inside folderId; omit for folder root"),
                "query" to string("Filename fragment for search or gallery"),
                "limit" to integer("Gallery page size"),
                "offset" to integer("Gallery page offset"),
                required = listOf("action"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = when (arguments.value("action")) {
            "folders" -> storage("list_authorized_folders").execute(JsonObject(emptyMap()))
            "files" -> storage("list_authorized_files").execute(arguments.without("action", "query", "limit", "offset"))
            "search" -> storage("search_authorized_files").execute(arguments.only("query"))
            "inspect" -> storage("inspect_authorized_file").execute(arguments.only("folderId", "path"))
            "gallery" -> gallery("list_gallery_photos").execute(arguments.only("query", "limit", "offset"))
            else -> error("Unknown browse action")
        }
    }

    private val manage = object : AgentTool {
        override val definition = ToolDefinition(
            MANAGE,
            "Change one file inside Android-authorized storage. actions: copy or move a source " +
                "folderId/path (or photoId) to destinationFolderId and optional destinationPath; " +
                "rename with newName; delete exactly one file. These actions require user approval " +
                "in the default authorization mode. Never invent IDs or paths.",
            schema(
                "action" to string("copy, move, rename, or delete", "copy", "move", "rename", "delete"),
                "folderId" to string("Source folder ID; omit when photoId is used"),
                "path" to string("Relative source path inside folderId"),
                "photoId" to string("Authorized selected photo ID instead of folderId/path"),
                "destinationFolderId" to string("Writable destination folder ID for copy or move"),
                "destinationPath" to string("Relative destination directory for copy or move"),
                "newName" to string("New filename for rename, or optional copy/move filename"),
                required = listOf("action"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String {
            val action = arguments.value("action")
            val target = buildJsonObject {
                arguments["photoId"]?.let { put("photoId", it) }
                arguments["folderId"]?.let { put("folderId", it) }
                arguments["path"]?.let { put("path", it) }
                arguments["newName"]?.let { put("newName", it) }
            }
            return when (action) {
                "copy", "move" -> {
                    val transfer = buildJsonObject {
                        target["photoId"]?.let { put("photoId", it) }
                        target["folderId"]?.let { put("sourceFolderId", it) }
                        target["path"]?.let { put("sourcePath", it) }
                        arguments["destinationFolderId"]?.let { put("destinationFolderId", it) }
                        arguments["destinationPath"]?.let { put("destinationPath", it) }
                        target["newName"]?.let { put("newName", it) }
                    }
                    storage(if (action == "copy") "copy_authorized_file" else "move_authorized_file")
                        .execute(transfer)
                }
                "rename" -> storage("rename_authorized_file").execute(target)
                "delete" -> storage("delete_authorized_file").execute(target)
                else -> error("Unknown file action")
            }
        }
    }

    private fun storage(name: String): AgentTool = storageByName.getValue(name)
    private fun gallery(name: String): AgentTool = galleryByName.getValue(name)
    private fun JsonObject.value(name: String): String = this[name]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.only(vararg names: String): JsonObject = JsonObject(filterKeys { it in names })
    private fun JsonObject.without(vararg names: String): JsonObject = JsonObject(filterKeys { it !in names })

    private fun string(description: String, vararg choices: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (choices.isNotEmpty()) put("enum", JsonArray(choices.map(::JsonPrimitive)))
    }

    private fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun schema(vararg fields: Pair<String, JsonObject>, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(fields.toMap()))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    companion object {
        const val BROWSE = "browse_phone_files"
        const val MANAGE = "manage_authorized_file"
    }
}
