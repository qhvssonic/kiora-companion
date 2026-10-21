package dev.kiora.companion

import dev.kiora.core.AgentMessage
import dev.kiora.core.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentWorkspaceFilesTest {
    private fun write(id: String, path: String) = AgentMessage.Assistant("", listOf(
        ToolCall(id, "write", JsonObject(mapOf("path" to JsonPrimitive(path),
            "content" to JsonPrimitive("text"))))))

    @Test fun returnsLastSuccessfulWriteFromActiveHistory() {
        val messages = listOf(
            AgentMessage.User("写草稿"), write("old", "draft.md"),
            AgentMessage.ToolResult("old", "write", "Wrote draft.md"),
            AgentMessage.User("写正式稿"), write("new", "report.md"),
            AgentMessage.ToolResult("new", "write", "Wrote report.md"),
            AgentMessage.Assistant("已完成"),
        )
        assertEquals(listOf("report.md"), RecentWorkspaceFiles.fromLatestWriteTurn(messages))
    }

    @Test fun doesNotGuessWhenLastWriteTurnProducedMultipleFiles() {
        val messages = listOf(
            AgentMessage.User("写两个文件"), write("one", "a.md"),
            AgentMessage.ToolResult("one", "write", "Wrote a.md"),
            write("two", "b.md"), AgentMessage.ToolResult("two", "write", "Wrote b.md"),
        )
        assertEquals(listOf("a.md", "b.md"), RecentWorkspaceFiles.fromLatestWriteTurn(messages))
    }

    @Test fun ignoresFailedWrite() {
        val messages = listOf(AgentMessage.User("写文件"), write("bad", "missing.md"),
            AgentMessage.ToolResult("bad", "write", "error", isError = true))
        assertEquals(emptyList<String>(), RecentWorkspaceFiles.fromLatestWriteTurn(messages))
    }

    @Test fun assistantClaimAloneDoesNotIdentifyAnAttachment() {
        val messages = listOf(AgentMessage.User("写文件"),
            AgentMessage.Assistant("已经保存到工作区 work_document.txt"))
        assertEquals(emptyList<String>(), RecentWorkspaceFiles.fromLatestWriteTurn(messages))
        assertEquals(emptyList<String>(), RecentWorkspaceFiles.recentWrittenPaths(messages))
    }

    @Test fun recentWrittenPathsKeepsVerifiedFilesInRecencyOrder() {
        val messages = listOf(
            AgentMessage.User("写第一稿"), write("a", "work_document.txt"),
            AgentMessage.ToolResult("a", "write", "Wrote work_document.txt"),
            AgentMessage.User("写第二稿"), write("b", "notes.md"),
            AgentMessage.ToolResult("b", "write", "Wrote notes.md"),
            AgentMessage.User("修改第一稿"), write("c", "work_document.txt"),
            AgentMessage.ToolResult("c", "write", "Wrote work_document.txt"),
        )
        assertEquals(listOf("work_document.txt", "notes.md"),
            RecentWorkspaceFiles.recentWrittenPaths(messages))
    }
}
