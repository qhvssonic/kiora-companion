package dev.kiora.companion

import dev.kiora.core.AgentMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteContextRecoveryTest {
    @Test fun findsTheFirstSuccessfulTaskForTheSameConnection() {
        fun result(tool: String, taskId: String, failed: Boolean = false) = AgentMessage.ToolResult(
            "call-$taskId", tool, "result", failed, emptyList(), null,
            JsonObject(mapOf("taskId" to JsonPrimitive(taskId))), null,
        )
        val messages = listOf(
            result("a2a_dsh", "first"), result("a2a_other", "other"),
            result("a2a_dsh", "failed", true), result("a2a_dsh", "second"),
        )
        assertEquals(listOf("first", "second"), RemoteContextRecovery.tasks(messages, "dsh") {
            if (it == "a2a_dsh") "dsh" else "other"
        }.map { it.taskId })
        val withContext = messages + AgentMessage.ToolResult("call-third", "a2a_dsh", "result",
            false, emptyList(), null,
            JsonObject(mapOf("taskId" to JsonPrimitive("third"),
                "contextId" to JsonPrimitive("ctx-dsh"))), null)
        assertEquals("ctx-dsh", RemoteContextRecovery.tasks(withContext, "dsh") {
            if (it == "a2a_dsh") "dsh" else "other"
        }.last().contextId)
    }
}
