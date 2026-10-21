package dev.kiora.companion

import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A small built-in tool for testing the complete on-device model → tool → model loop. */
class DeviceTimeTool : AgentTool {
    override val definition = ToolDefinition(
        name = "get_local_time",
        description = "Get the current date, time, and time zone from this Android device. Use this for questions about the current time.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(emptyMap()),
        )),
    )
    override suspend fun execute(arguments: JsonObject): String = ZonedDateTime.now().toString()
}
