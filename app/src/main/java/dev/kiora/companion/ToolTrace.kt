package dev.kiora.companion

import dev.kiora.core.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Display record for a model-requested tool call, stored as a conversation message. */
data class ToolTrace(
    val callId: String,
    val name: String,
    val arguments: String,
    val result: String = "",
    val state: State = State.RUNNING,
) {
    enum class State { RUNNING, SUCCEEDED, FAILED, INPUT_REQUIRED, AUTH_REQUIRED }

    data class AlbumPhoto(val id: Long, val name: String)

    fun albumPhotos(): List<AlbumPhoto> {
        if (name != AlbumSearchTool.NAME || state != State.SUCCEEDED) return emptyList()
        return runCatching {
            Json.parseToJsonElement(result).jsonObject.getValue("items").jsonArray
                .mapNotNull { item ->
                    val photo = item.jsonObject
                    val rawId = photo["photoId"]?.jsonPrimitive?.content.orEmpty()
                    val id = rawId.removePrefix("gallery_").toLongOrNull()?.takeIf { it >= 0 }
                    if (!rawId.startsWith("gallery_") || id == null) null
                    else AlbumPhoto(id, photo["name"]?.jsonPrimitive?.content.orEmpty())
                }.take(3)
        }.getOrDefault(emptyList())
    }

    val label: String get() = when (name) {
        "get_local_time" -> "读取本机时间"
        "read" -> "读取文件或照片"
        "write" -> "写入工作区文件"
        "edit" -> "编辑工作区文件"
        "shell" -> "运行手机命令"
        PhoneResourceTools.BROWSE -> "浏览手机文件与相册"
        AlbumSearchTool.NAME -> "搜索本地相册"
        PhoneResourceTools.MANAGE -> when (runCatching {
            Json.parseToJsonElement(arguments).jsonObject["action"]?.jsonPrimitive?.content
        }.getOrNull()) {
            "copy" -> "复制已授权文件"
            "move" -> "移动已授权文件"
            "rename" -> "重命名已授权文件"
            "delete" -> "删除已授权文件"
            else -> "管理已授权文件"
        }
        "list_authorized_folders" -> "查看已授权目录"
        "list_authorized_photos" -> "查看已授权照片"
        "list_gallery_photos" -> "浏览相册"
        "describe_gallery_photo" -> "识别相册照片"
        "list_authorized_files" -> "浏览已授权目录"
        "search_authorized_files" -> "搜索已授权文件"
        "inspect_authorized_file" -> "查看文件信息"
        "read_authorized_text" -> "读取已授权文本"
        "copy_authorized_file" -> "复制文件"
        "move_authorized_file" -> "移动文件"
        "rename_authorized_file" -> "重命名文件"
        "delete_authorized_file" -> "删除文件"
        else -> name.removePrefix("a2a_").replace('_', ' ').let {
            if (name.startsWith("a2a_")) "电脑端 · $it" else it
        }
    }

    fun displayArguments(): String {
        val listsFiles = name == "list_authorized_files" ||
            (name == PhoneResourceTools.BROWSE && runCatching {
                Json.parseToJsonElement(arguments).jsonObject["action"]?.jsonPrimitive?.content == "files"
            }.getOrDefault(false))
        if (!listsFiles) return arguments
        return runCatching {
            val path = Json.parseToJsonElement(arguments).jsonObject["path"]?.jsonPrimitive?.content.orEmpty()
            if (path.isBlank()) "已授权目录的根目录" else "已授权目录 / $path"
        }.getOrDefault(arguments)
    }

    fun displayResult(): String {
        if (name == AlbumSearchTool.NAME && state == State.SUCCEEDED) {
            val count = albumPhotos().size
            return if (count == 0) "没有可展示的候选照片，请检查相册索引与授权"
                else if (count == 1) "已展示 1 张排序候选照片；点击图片可放大查看"
                else "已展示 $count 张排序候选照片；展开后点击图片可放大查看"
        }
        val listsFiles = name == "list_authorized_files" ||
            (name == PhoneResourceTools.BROWSE && runCatching {
                Json.parseToJsonElement(arguments).jsonObject["action"]?.jsonPrimitive?.content == "files"
            }.getOrDefault(false))
        if (!listsFiles) return result
        return runCatching {
            val data = Json.parseToJsonElement(result).jsonObject
            val items = data.getValue("items").jsonArray
            buildString {
                append("共 ").append(items.size).append(" 项")
                if (data["truncated"]?.jsonPrimitive?.booleanOrNull == true) append("（仅显示前 100 项）")
                items.forEach { item ->
                    val file = item.jsonObject
                    append("\n• ").append(file.getValue("name").jsonPrimitive.content)
                    if (file["mimeType"]?.jsonPrimitive?.content ==
                        "vnd.android.document/directory") append("（文件夹）")
                }
            }
        }.getOrDefault(result)
    }

    fun toStoredText(): String = buildJsonObject {
        put("callId", callId)
        put("name", name)
        put("arguments", arguments)
        put("result", result)
        put("state", state.name)
    }.toString()

    companion object {
        fun requested(call: ToolCall) = ToolTrace(call.id, call.name, call.arguments.toString())

        fun fromStoredText(text: String): ToolTrace? = runCatching {
            val data = Json.parseToJsonElement(text).jsonObject
            ToolTrace(
                callId = data.getValue("callId").jsonPrimitive.content,
                name = data.getValue("name").jsonPrimitive.content,
                arguments = data.getValue("arguments").jsonPrimitive.content,
                result = data["result"]?.jsonPrimitive?.content.orEmpty(),
                state = data["state"]?.jsonPrimitive?.content?.let(State::valueOf) ?: State.RUNNING,
            )
        }.getOrNull()
    }
}
