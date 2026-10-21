package dev.kiora.companion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ModelUsage
import dev.kiora.core.SessionModelInvocation
import dev.kiora.core.SessionTree
import dev.kiora.core.SessionUsage
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
    @Test fun versionOneDatabaseMigratesWithoutLosingSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "kiora-migration-test.db"
        context.deleteDatabase(name)
        val legacy = context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null)
        try {
            legacy.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            legacy.execSQL("CREATE TABLE conversation_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(sessionId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            legacy.execSQL("CREATE INDEX index_conversation_messages_sessionId_id ON conversation_messages(sessionId, id)")
            legacy.execSQL("CREATE TABLE agent_sessions (id TEXT NOT NULL PRIMARY KEY, leafId TEXT)")
            legacy.execSQL("CREATE TABLE agent_entries (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, position INTEGER NOT NULL, parentId TEXT, timestamp INTEGER NOT NULL, messageJson TEXT NOT NULL, FOREIGN KEY(sessionId) REFERENCES agent_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            legacy.execSQL("CREATE INDEX index_agent_entries_sessionId_position ON agent_entries(sessionId, position)")
            legacy.execSQL("CREATE TABLE app_state (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            legacy.execSQL("CREATE TABLE remote_tasks (sessionId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL)")
            legacy.execSQL("INSERT INTO agent_sessions (id, leafId) VALUES ('old', NULL)")
            legacy.execSQL("INSERT INTO remote_tasks (sessionId, taskId) VALUES ('old', 'task-legacy')")
            legacy.version = 1
        } finally {
            legacy.close()
        }
        val db = Room.databaseBuilder(context, CompanionDatabase::class.java, name)
            .addMigrations(CompanionDatabase.MIGRATION_1_2, CompanionDatabase.MIGRATION_2_3).build()
        try {
            assertEquals("task-legacy", db.dao().remoteTaskId("old"))
            assertEquals("old", RoomSessionStore(db).load("old")?.id)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun conversationRepositoryStoresAndSelectsChats() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java).build()
        try {
            val repository = ConversationRepository(context, db)
            val first = repository.currentId()
            repository.append(first, ChatLine("user", "第一段提问", listOf("sample.jpg")))
            repository.append(first, ChatLine("assistant", "第一段回答"))
            val second = repository.newConversation()
            repository.append(second, ChatLine("user", "第二段提问"))

            assertEquals(second, repository.currentId())
            assertEquals(listOf(ChatLine("user", "第一段提问", listOf("sample.jpg")),
                ChatLine("assistant", "第一段回答")),
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
            val usage = SessionUsage("usage-one", "two", 3L, ModelUsage(12, 4, 16))
            val model = SessionModelInvocation("model-one", "two", 3L,
                ModelIdentity("actual-model", "cloud-a"))
            store.appendUsage("session", usage)
            store.appendModelInvocation("session", model)
            val result = tree.append(AgentMessage.ToolResult("call", "demo", "完成"), "three", 3L)
            store.append("session", result)
            assertEquals(assistant, store.load("session")!!.allEntries()[1].message)
            assertEquals(result.message, store.load("session")!!.allEntries()[2].message)
            assertEquals(listOf(usage), store.load("session")!!.activeUsages())
            assertEquals(listOf(model), store.load("session")!!.activeModelInvocations())
            store.moveLeaf("session", "one")
            assertEquals(listOf("one"), store.load("session")!!.activeEntries().map { it.id })
            assertEquals(emptyList<SessionUsage>(), store.load("session")!!.activeUsages())
            assertEquals(listOf(model), store.load("session")!!.allModelInvocations())
            db.dao().putRemoteTask(RemoteTaskEntity("session", "task-123"))
            assertEquals("task-123", db.dao().remoteTaskId("session"))
            db.dao().clearRemoteTask("session")
            assertNull(db.dao().remoteTaskId("session"))
        } finally {
            db.close()
        }
    }
}
