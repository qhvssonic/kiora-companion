package dev.kiora.companion

import android.content.Context
import dev.kiora.core.AgentMessage

/** Sticky session-level inheritance also covers derived answers and compacted summaries. */
internal class LocalDataProvenance(context: Context, preferencesName: String = "local_data_sources") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun mark(sessionId: String, source: String) = synchronized(lock) {
        val existing = sources(sessionId)
        if (source in existing) return@synchronized
        val sources = existing + source
        check(prefs.edit().putStringSet(sessionId, sources).commit()) { "无法保存资料来源，已停止处理" }
    }

    fun sources(sessionId: String): Set<String> = prefs.getStringSet(sessionId, emptySet()).orEmpty().toSet()

    fun inheritHistory(sessionId: String, messages: List<AgentMessage>) {
        if (messages.any { it is AgentMessage.ToolResult && it.toolName != "get_local_time" })
            mark(sessionId, "本地工具或远端任务记录")
        if (messages.any { it is AgentMessage.User && it.images.isNotEmpty() }) mark(sessionId, "会话图片")
        if (prefs.getBoolean("known:$sessionId", false)) return
        // Older sessions predate source tracking. Their plain text may contain OCR or derived data.
        if (!prefs.getBoolean("known:$sessionId", false) && sources(sessionId).isEmpty() && messages.isNotEmpty())
            mark(sessionId, "历史会话及其派生内容")
        check(prefs.edit().putBoolean("known:$sessionId", true).commit()) { "无法保存会话资料策略" }
    }
    private companion object { val lock = Any() }
}
