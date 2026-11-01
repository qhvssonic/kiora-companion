package dev.kiora.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Bounded permission decisions, not proof of network delivery. No document contents or secrets. */
internal class DataEgressAudit(context: Context) {
    private val prefs = context.getSharedPreferences("data_egress_audit", Context.MODE_PRIVATE)

    fun record(request: DataEgressRequest, decision: String) = synchronized(lock) {
        val records = JSONArray(prefs.getString("records", "[]"))
        val next = JSONArray().put(JSONObject().put("time", System.currentTimeMillis())
            .put("session", request.sessionId).put("destination", request.destination)
            .put("sources", request.sources.sorted().joinToString("、")).put("decision", decision))
        for (i in 0 until minOf(records.length(), 29)) next.put(records.getJSONObject(i))
        check(prefs.edit().putString("records", next.toString()).commit()) { "无法保存资料外发授权记录" }
    }

    fun recent(): List<Pair<String, String>> = synchronized(lock) {
        val records = JSONArray(prefs.getString("records", "[]"))
        (0 until records.length()).map { i ->
            val item = records.getJSONObject(i)
            item.getString("decision") to (DateFormat.getDateTimeInstance().format(Date(item.getLong("time"))) +
                "\n${item.getString("destination")}\n${item.getString("sources")}")
        }
    }

    private companion object { val lock = Any() }
}
