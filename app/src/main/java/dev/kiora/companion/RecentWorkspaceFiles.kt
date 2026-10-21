package dev.kiora.companion

import dev.kiora.core.AgentMessage
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Finds files actually written on the active conversation branch, not by filesystem timestamp. */
internal object RecentWorkspaceFiles {
    fun fromLatestWriteTurn(messages: List<AgentMessage>): List<String> =
        writesByTurn(messages).values.lastOrNull()?.toList().orEmpty()

    fun recentWrittenPaths(messages: List<AgentMessage>, limit: Int = 5): List<String> {
        require(limit > 0)
        return writesByTurn(messages).values.toList().asReversed()
            .flatMap { it.toList().asReversed() }.distinct().take(limit)
    }

    private fun writesByTurn(messages: List<AgentMessage>): Map<Int, MutableSet<String>> {
        val pathsByCall = mutableMapOf<String, String>()
        val pathsByTurn = linkedMapOf<Int, MutableSet<String>>()
        var turn = -1
        messages.forEach { message ->
            when (message) {
                is AgentMessage.User -> turn++
                is AgentMessage.Assistant -> message.toolCalls.forEach { call ->
                    if (call.name == "write" || call.name == "edit") {
                        val path = runCatching { call.arguments["path"]?.jsonPrimitive?.contentOrNull }
                            .getOrNull()?.trim()
                        if (!path.isNullOrEmpty()) pathsByCall[call.id] = path
                    }
                }
                is AgentMessage.ToolResult -> if (!message.isError &&
                    (message.toolName == "write" || message.toolName == "edit")) {
                    pathsByCall[message.callId]?.let { path ->
                        pathsByTurn.getOrPut(turn) { linkedSetOf() } += path
                    }
                }
                else -> Unit
            }
        }
        return pathsByTurn
    }
}
