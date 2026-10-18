package dev.kiora.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatLine(val role: String, val text: String)
data class ChatSession(val id: String, val title: String)

/** Small private transcript store for the UI; AgentRuntime keeps its own model session tree. */
class ConversationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("companion_history", Context.MODE_PRIVATE)

    var currentId: String
        get() = prefs.getString("current_id", null) ?: newId().also { currentId = it }
        set(value) { prefs.edit().putString("current_id", value).apply() }

    fun newConversation(): String = newId().also { currentId = it }

    fun sessions(): List<ChatSession> = runCatching {
        val array = JSONArray(prefs.getString("sessions", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ChatSession(item.getString("id"), item.getString("title"))
        }
    }.getOrDefault(emptyList())

    fun lines(id: String): List<ChatLine> = runCatching {
        val array = JSONArray(prefs.getString("messages_$id", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ChatLine(item.getString("role"), item.getString("text"))
        }
    }.getOrDefault(emptyList())

    fun append(id: String, line: ChatLine) {
        val messages = JSONArray(prefs.getString("messages_$id", "[]"))
        messages.put(JSONObject().put("role", line.role).put("text", line.text))
        prefs.edit().putString("messages_$id", messages.toString()).apply()
        if (line.role == "user" && sessions().none { it.id == id }) {
            val list = JSONArray()
            list.put(JSONObject().put("id", id).put("title", line.text.take(28)))
            sessions().forEach { list.put(JSONObject().put("id", it.id).put("title", it.title)) }
            prefs.edit().putString("sessions", list.toString()).apply()
        }
    }

    private fun newId(): String = "chat_${java.util.UUID.randomUUID()}"
}
