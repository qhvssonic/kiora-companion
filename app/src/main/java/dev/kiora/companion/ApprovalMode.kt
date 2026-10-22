package dev.kiora.companion

/** Host approval policy. Unknown model-requested tools are never silently treated as read-only. */
enum class ApprovalMode(val key: String, val title: String, val description: String) {
    ASK("ask", "请我批准", "每次模型请求调用工具时都询问"),
    ASSIST("assist", "帮我批准", "自动批准已授权范围内的只读工具；修改、命令和上传询问"),
    FULL("full", "完全访问权限", "自动批准已配置的工具调用，不再弹出授权提示");

    companion object {
        fun fromKey(key: String?): ApprovalMode = entries.firstOrNull { it.key == key } ?: ASSIST
    }
}

/** Only host-owned, read-only tools confined to the app workspace or Android grants belong here. */
internal fun isLowRiskTool(name: String): Boolean = name in setOf(
    "get_local_time", "read", PhoneResourceTools.BROWSE, AlbumSearchTool.NAME,
    "list_authorized_folders", "list_authorized_photos", "list_authorized_files",
    "list_gallery_photos", "describe_gallery_photo",
    "search_authorized_files", "inspect_authorized_file", "read_authorized_text",
)

internal fun isHighRiskTool(name: String): Boolean = name in setOf(
    "write", "edit", "shell", PhoneResourceTools.MANAGE,
    "copy_authorized_file", "move_authorized_file", "rename_authorized_file", "delete_authorized_file",
)

internal fun shouldAskForTool(
    mode: ApprovalMode,
    toolName: String,
    grantedForConversation: Boolean,
): Boolean = when (mode) {
    ApprovalMode.ASK -> true
    ApprovalMode.ASSIST -> isHighRiskTool(toolName) ||
        (!isLowRiskTool(toolName) && !grantedForConversation)
    ApprovalMode.FULL -> false
}
