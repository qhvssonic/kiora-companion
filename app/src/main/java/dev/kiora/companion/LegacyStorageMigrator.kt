package dev.kiora.companion

import android.content.Context
import androidx.room.withTransaction
import dev.kiora.core.JsonlSessionStore
import dev.kiora.core.SessionTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

data class MigrationReport(val conversations: Int, val agentSessions: Int, val skipped: Int)

/** One-time, transactional import. Original preferences and JSONL files remain untouched. */
class LegacyStorageMigrator(
    private val context: Context,
    private val db: CompanionDatabase,
    private val historyName: String = "companion_history",
    private val agentDirectory: File = context.filesDir.resolve("agent-sessions"),
) {
    private data class OldConversation(val id: String, val title: String, val lines: List<ChatLine>)

    suspend fun migrate(): MigrationReport = withContext(Dispatchers.IO) {
        val dao = db.dao()
        if (dao.state(MIGRATION_KEY) == "done") return@withContext MigrationReport(0, 0, 0)

        val prefs = context.getSharedPreferences(historyName, Context.MODE_PRIVATE)
        var skipped = 0
        val index = runCatching { JSONArray(prefs.getString("sessions", "[]")) }
            .getOrElse { skipped++; JSONArray() }
        val metadata = mutableListOf<Pair<String, String>>()
        for (position in 0 until index.length()) {
            runCatching {
                val item = index.getJSONObject(position)
                metadata += item.getString("id") to item.getString("title")
            }.onFailure { skipped++ }
        }
        val legacyCurrent = prefs.getString("current_id", null)
        if (legacyCurrent != null && metadata.none { it.first == legacyCurrent }) {
            metadata += legacyCurrent to ""
        }
        val oldConversations = metadata.distinctBy { it.first }.map { (id, title) ->
            val lines = runCatching {
                val messages = JSONArray(prefs.getString("messages_$id", "[]"))
                (0 until messages.length()).map { index ->
                    val item = messages.getJSONObject(index)
                    ChatLine(item.getString("role"), item.getString("text"))
                }
            }.getOrElse { skipped++; emptyList() }
            OldConversation(id, title, lines)
        }

        val oldStore = JsonlSessionStore(agentDirectory)
        val oldAgentSessions = mutableListOf<SessionTree>()
        agentDirectory.listFiles { file: File -> file.isFile && file.extension == "jsonl" }
            ?.forEach { file ->
                runCatching { oldStore.load(file.nameWithoutExtension) }
                    .onSuccess { if (it != null) oldAgentSessions += it }
                    .onFailure { skipped++ }
            }

        db.withTransaction {
            if (dao.state(MIGRATION_KEY) == "done") return@withTransaction
            val now = System.currentTimeMillis()
            oldConversations.forEachIndexed { index, old ->
                val title = old.title.ifBlank { old.lines.firstOrNull { it.role == "user" }?.text?.take(28).orEmpty() }
                val timestamp = now - index
                dao.insertConversation(ConversationEntity(old.id, title, timestamp, timestamp))
                old.lines.forEachIndexed { lineIndex, line ->
                    dao.insertMessage(ConversationMessageEntity(
                        sessionId = old.id, role = line.role, content = line.text,
                        createdAt = timestamp + lineIndex,
                    ))
                }
            }
            oldAgentSessions.forEach { session ->
                dao.insertAgentSession(AgentSessionEntity(session.id, null))
                session.allEntries().forEachIndexed { index, entry ->
                    dao.insertAgentEntry(AgentEntryEntity(
                        id = entry.id, sessionId = session.id, position = index + 1L,
                        parentId = entry.parentId, timestamp = entry.timestampEpochMillis,
                        messageJson = AgentMessageCodec.encode(entry.message),
                    ))
                }
                dao.updateLeaf(session.id, session.leafId)
            }
            dao.putState(AppStateEntity("current_conversation_id",
                legacyCurrent ?: oldConversations.firstOrNull()?.id ?: newId()))
            dao.putState(AppStateEntity(MIGRATION_KEY, "done"))
        }
        MigrationReport(oldConversations.size, oldAgentSessions.size, skipped)
    }

    private fun newId() = "chat_${UUID.randomUUID()}"

    private companion object { const val MIGRATION_KEY = "legacy_storage_migration_v1" }
}
