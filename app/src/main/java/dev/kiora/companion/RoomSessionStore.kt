package dev.kiora.companion

import androidx.room.withTransaction
import dev.kiora.core.SessionEntry
import dev.kiora.core.SessionStore
import dev.kiora.core.SessionTree

/** Android Room adapter for Kiora's platform-neutral SessionStore interface. */
class RoomSessionStore(private val db: CompanionDatabase) : SessionStore {
    private val dao = db.dao()

    override suspend fun load(sessionId: String): SessionTree? = db.withTransaction {
        val session = dao.agentSession(sessionId) ?: return@withTransaction null
        val entries = dao.agentEntries(sessionId).map { row ->
            SessionEntry(row.id, row.parentId, row.timestamp, AgentMessageCodec.decode(row.messageJson))
        }
        SessionTree.restore(sessionId, entries, session.leafId)
    }

    override suspend fun create(sessionId: String) {
        require(dao.insertAgentSession(AgentSessionEntity(sessionId, null)) != -1L) {
            "Session already exists"
        }
    }

    override suspend fun append(sessionId: String, entry: SessionEntry) = db.withTransaction {
        val session = checkNotNull(dao.agentSession(sessionId)) { "Session not found" }
        require(session.leafId == entry.parentId) { "Stale session branch" }
        require(dao.insertAgentEntry(AgentEntryEntity(
            id = entry.id,
            sessionId = sessionId,
            position = dao.nextAgentPosition(sessionId),
            parentId = entry.parentId,
            timestamp = entry.timestampEpochMillis,
            messageJson = AgentMessageCodec.encode(entry.message),
        )) != -1L) { "Duplicate session entry ID" }
        dao.updateLeaf(sessionId, entry.id)
    }

    override suspend fun moveLeaf(sessionId: String, entryId: String?) = db.withTransaction {
        requireNotNull(dao.agentSession(sessionId)) { "Session not found" }
        require(entryId == null || dao.hasAgentEntry(sessionId, entryId) > 0) { "Unknown branch point" }
        dao.updateLeaf(sessionId, entryId)
    }
}
