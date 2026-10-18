package dev.kiora.companion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.core.SessionTree
import dev.kiora.core.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomStorageTest {
    @Test fun conversationRepositoryStoresAndSelectsChats() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        try {
            val repository = ConversationRepository(context, db)
            val first = repository.currentId()
            repository.append(first, ChatLine("user", "第一段提问"))
            repository.append(first, ChatLine("assistant", "第一段回答"))
            val second = repository.newConversation()
            repository.append(second, ChatLine("user", "第二段提问"))

            assertEquals(second, repository.currentId())
            assertEquals(listOf(ChatLine("user", "第一段提问"), ChatLine("assistant", "第一段回答")),
                repository.lines(first))
            assertEquals(setOf("第一段提问", "第二段提问"), repository.sessions().map { it.title }.toSet())
            assertEquals("第一段回答", repository.sessions().first { it.id == first }.preview)
            repository.select(first)
            assertEquals(first, repository.currentId())
        } finally {
            db.close()
        }
    }

    @Test fun agentSessionStoresBranchToolMessagesAndRemoteTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        try {
            val store = RoomSessionStore(db)
            store.create("session")
            val tree = SessionTree.create("session")
            val first = tree.append(AgentMessage.User("你好"), "one", 1L)
            store.append("session", first)
            val assistant = AgentMessage.Assistant("调用工具", listOf(ToolCall("call", "demo",
                JsonObject(mapOf("value" to JsonPrimitive(1))))))
            val second = tree.append(assistant, "two", 2L)
            store.append("session", second)
            val result = tree.append(AgentMessage.ToolResult("call", "demo", "完成"), "three", 3L)
            store.append("session", result)
            assertEquals(assistant, store.load("session")!!.allEntries()[1].message)
            assertEquals(result.message, store.load("session")!!.allEntries()[2].message)
            store.moveLeaf("session", "one")
            assertEquals(listOf("one"), store.load("session")!!.activeEntries().map { it.id })
            db.dao().putRemoteTask(RemoteTaskEntity("session", "task-123"))
            assertEquals("task-123", db.dao().remoteTaskId("session"))
            db.dao().clearRemoteTask("session")
            assertNull(db.dao().remoteTaskId("session"))
        } finally {
            db.close()
        }
    }
}
