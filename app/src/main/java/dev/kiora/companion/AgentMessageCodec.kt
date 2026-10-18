package dev.kiora.companion

import dev.kiora.core.AgentMessage
import dev.kiora.core.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stable JSON payload for agent entries stored in Room. */
internal object AgentMessageCodec {
    fun encode(message: AgentMessage): String = when (message) {
        is AgentMessage.User -> JsonObject(mapOf(
            "role" to JsonPrimitive("user"), "text" to JsonPrimitive(message.text),
        ))
        is AgentMessage.Assistant -> JsonObject(mapOf(
            "role" to JsonPrimitive("assistant"), "text" to JsonPrimitive(message.text),
            "toolCalls" to JsonArray(message.toolCalls.map { call ->
                JsonObject(mapOf("id" to JsonPrimitive(call.id), "name" to JsonPrimitive(call.name),
                    "arguments" to call.arguments))
            }),
        ))
        is AgentMessage.ToolResult -> JsonObject(mapOf(
            "role" to JsonPrimitive("tool"), "callId" to JsonPrimitive(message.callId),
            "toolName" to JsonPrimitive(message.toolName), "text" to JsonPrimitive(message.text),
            "isError" to JsonPrimitive(message.isError),
        ))
    }.toString()

    fun decode(source: String): AgentMessage {
        val value = Json.parseToJsonElement(source).jsonObject
        fun string(key: String) = value.getValue(key).jsonPrimitive.content
        return when (string("role")) {
            "user" -> AgentMessage.User(string("text"))
            "assistant" -> AgentMessage.Assistant(string("text"),
                value.getValue("toolCalls").jsonArray.map { item ->
                    val call = item.jsonObject
                    ToolCall(call.getValue("id").jsonPrimitive.content,
                        call.getValue("name").jsonPrimitive.content,
                        call.getValue("arguments").jsonObject)
                })
            "tool" -> AgentMessage.ToolResult(string("callId"), string("toolName"), string("text"),
                value.getValue("isError").jsonPrimitive.boolean)
            else -> error("Unknown agent message role")
        }
    }
}
