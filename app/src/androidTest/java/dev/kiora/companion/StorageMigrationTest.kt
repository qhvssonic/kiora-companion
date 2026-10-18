package dev.kiora.companion

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.core.JsonlSessionStore
import dev.kiora.core.SessionTree
import dev.kiora.core.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageMigrationTest {
    @Test fun importsBothStoresExactlyOnceAndPreservesAgentBranch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val prefsName = "migration_test_$suffix"
        val directory = File(context.cacheDir, prefsName)
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        try {
            val conversationId = "chat_$suffix"
            prefs.edit().putString("sessions", JSONArray().put(JSONObject()
                .put("id", conversationId).put("title", "旧会话")).toString())
                .putString("current_id", conversationId)
                .putString("messages_$conversationId", JSONArray()
                    .put(JSONObject().put("role", "user").put("text", "旧问题"))
                    .put(JSONObject().put("role", "assistant").put("text", "旧回答"))
                    .toString()).commit()

            val agentId = "agent_$suffix"
            val oldStore = JsonlSessionStore(directory)
            oldStore.create(agentId)
            val oldTree = SessionTree.create(agentId)
            val first = oldTree.append(AgentMessage.User("第一条"), "first", 1L)
            oldStore.append(agentId, first)
            val unusedBranch = oldTree.append(AgentMessage.Assistant("旧分支"), "unused", 2L)
            oldStore.append(agentId, unusedBranch)
            oldTree.branchTo("first")
            oldStore.moveLeaf(agentId, "first")
            val active = oldTree.append(AgentMessage.Assistant("当前分支"), "active", 3L)
            oldStore.append(agentId, active)

            val migrator = LegacyStorageMigrator(context, db, prefsName, directory)
            assertEquals(MigrationReport(1, 1, 0), migrator.migrate())
            val repository = ConversationRepository(context, db)
            assertEquals(conversationId, repository.currentId())
            assertEquals(listOf(ChatLine("user", "旧问题"), ChatLine("assistant", "旧回答")),
                repository.lines(conversationId))
            assertEquals("旧会话", repository.sessions().single().title)
            val restored = RoomSessionStore(db).load(agentId)
            assertNotNull(restored)
            assertEquals(listOf("first", "unused", "active"), restored!!.allEntries().map { it.id })
            assertEquals(listOf("first", "active"), restored.activeEntries().map { it.id })
            assertEquals("当前分支", (restored.activeMessages().last() as AgentMessage.Assistant).text)

            assertEquals(MigrationReport(0, 0, 0), migrator.migrate())
            assertEquals(2, repository.lines(conversationId).size)
            assertEquals(3, RoomSessionStore(db).load(agentId)!!.allEntries().size)
            assertTrue(File(directory, "$agentId.jsonl").exists())
            assertNotNull(prefs.getString("messages_$conversationId", null))
        } finally {
            db.close()
            prefs.edit().clear().commit()
            directory.deleteRecursively()
        }
    }

    @Test fun roomSessionStoreRetainsCursorAndRemoteTask() = runBlocking {
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
