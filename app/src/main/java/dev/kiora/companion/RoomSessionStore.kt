package dev.kiora.companion

import androidx.room.withTransaction
import dev.kiora.core.SessionEntry
import dev.kiora.core.ModelIdentity
import dev.kiora.core.ModelUsage
import dev.kiora.core.SessionModelInvocation
import dev.kiora.core.SessionModelInvocationStore
import dev.kiora.core.SessionStore
import dev.kiora.core.SessionTree
import dev.kiora.core.SessionUsage
import dev.kiora.core.SessionUsageStore

/** Android Room adapter for Kiora's platform-neutral SessionStore interface. */
class RoomSessionStore(private val db: CompanionDatabase) : SessionStore, SessionUsageStore,
    SessionModelInvocationStore {
    private val dao = db.dao()

    override suspend fun load(sessionId: String): SessionTree? = db.withTransaction {
        val session = dao.agentSession(sessionId) ?: return@withTransaction null
        val entries = dao.agentEntries(sessionId).map { row ->
            SessionEntry(row.id, row.parentId, row.timestamp, AgentMessageCodec.decode(row.messageJson))
        }
        val usages = dao.agentUsages(sessionId).map { row ->
            SessionUsage(row.id, row.assistantEntryId, row.timestamp,
                ModelUsage(row.inputTokens, row.outputTokens, row.totalTokens, row.cachedInputTokens))
        }
        val invocations = dao.agentModelInvocations(sessionId).map { row ->
            SessionModelInvocation(row.id, row.assistantEntryId, row.timestamp,
                ModelIdentity(row.modelId, row.providerId))
        }
        SessionTree.restore(sessionId, entries, session.leafId, emptyList(), usages, invocations,
            emptyList(), emptySet())
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

    override suspend fun appendUsage(sessionId: String, usage: SessionUsage) = db.withTransaction {
        require(dao.hasAgentEntry(sessionId, usage.assistantEntryId) > 0) { "Unknown assistant entry" }
        require(dao.insertAgentUsage(AgentUsageEntity(
            usage.id, sessionId, usage.assistantEntryId, usage.timestampEpochMillis,
            usage.usage.inputTokens, usage.usage.outputTokens, usage.usage.totalTokens,
            usage.usage.cachedInputTokens,
        )) != -1L) { "Duplicate usage ID" }
    }

    override suspend fun appendModelInvocation(sessionId: String, invocation: SessionModelInvocation) =
        db.withTransaction {
            require(dao.hasAgentEntry(sessionId, invocation.assistantEntryId) > 0) { "Unknown assistant entry" }
            require(dao.insertAgentModelInvocation(AgentModelInvocationEntity(
                invocation.id, sessionId, invocation.assistantEntryId, invocation.timestampEpochMillis,
                invocation.model.modelId, invocation.model.providerId,
            )) != -1L) { "Duplicate model invocation ID" }
        }
}
