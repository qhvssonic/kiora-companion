package dev.kiora.companion

import dev.kiora.core.AgentMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Historical task IDs are kept in SDK tool results from older Companion versions. */
internal object RemoteContextRecovery {
    data class PriorTask(val taskId: String, val contextId: String?)

    fun tasks(messages: List<AgentMessage>, connectionId: String,
              connectionForTool: (String) -> String?): List<PriorTask> = messages
        .filterIsInstance<AgentMessage.ToolResult>()
        .filter { !it.isError && connectionForTool(it.toolName) == connectionId }
        .mapNotNull { result ->
            val data = result.structuredContent as? JsonObject ?: return@mapNotNull null
            val taskId = data["taskId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            PriorTask(taskId, data["contextId"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank))
        }
        .distinctBy(PriorTask::taskId)
}
