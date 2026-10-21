package dev.kiora.companion

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONArray
import java.util.UUID

data class ChatLine(val role: String, val text: String, val previewImages: List<String> = emptyList())
data class ChatSession(val id: String, val title: String, val preview: String)

/** Room-backed UI history; agent context uses RoomSessionStore in the same database. */
class ConversationRepository(context: Context, private val db: CompanionDatabase = CompanionDatabase.get(context)) {
    private val dao = db.dao()

    suspend fun currentId(): String = db.withTransaction {
        dao.state(CURRENT_ID) ?: newId().also { dao.putState(AppStateEntity(CURRENT_ID, it)) }
    }

    suspend fun select(id: String) {
        dao.putState(AppStateEntity(CURRENT_ID, id))
    }

    suspend fun newConversation(): String = newId().also { select(it) }

    suspend fun sessions(query: String = ""): List<ChatSession> = dao.conversations()
        .asSequence()
        .filter { it.title.contains(query, ignoreCase = true) }
        .map { ChatSession(it.id, it.title, it.preview) }
        .toList()

    suspend fun lines(id: String): List<ChatLine> = dao.messages(id).map { message ->
        val images = JSONArray(message.previewImages)
        ChatLine(message.role, message.content, (0 until images.length()).map(images::getString))
    }

    suspend fun append(id: String, line: ChatLine): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = dao.conversation(id)
        val title = if (line.role == "user") line.text.take(28) else ""
        if (existing == null) dao.insertConversation(ConversationEntity(id, title, now, now))
        else if (existing.title.isBlank() && title.isNotBlank()) dao.updateConversation(id, title, now)
        else dao.touchConversation(id, now)
        dao.insertMessage(ConversationMessageEntity(
            sessionId = id, role = line.role, content = line.text, createdAt = now,
            previewImages = JSONArray(line.previewImages).toString(),
        ))
    }

    suspend fun updateTool(messageId: Long, trace: ToolTrace) {
        dao.updateToolMessage(messageId, trace.toStoredText())
    }

    private fun newId(): String = "chat_${UUID.randomUUID()}"

    private companion object { const val CURRENT_ID = "current_conversation_id" }
}
