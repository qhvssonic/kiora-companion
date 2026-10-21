package dev.kiora.companion

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.UUID

data class A2aConnectionConfig(
    val id: String,
    val name: String,
    val agentCardUrl: String,
    val token: String = "",
    val enabled: Boolean = true,
    val alias: String = "",
)

/** Connection metadata in preferences; each Bearer token stays encrypted in SecretStore. */
internal class A2aConnectionStore(
    context: Context,
    preferencesName: String = "companion_settings",
    private val secrets: SecretStore = SecretStore(context),
) {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun list(): List<A2aConnectionConfig> {
        if (!prefs.contains(KEY)) migrateLegacy()
        val raw = prefs.getString(KEY, "[]").orEmpty()
        return Json.parseToJsonElement(raw).jsonArray.map { element ->
            val item = element.jsonObject
            val id = item.getValue("id").jsonPrimitive.content
            A2aConnectionConfig(
                id = id,
                name = item.getValue("name").jsonPrimitive.content,
                agentCardUrl = item.getValue("agentCardUrl").jsonPrimitive.content,
                token = secrets.a2aToken(id),
                enabled = item["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                alias = item["alias"]?.jsonPrimitive?.content
                    ?: RemoteTaskRouting.suggestAlias(item.getValue("name").jsonPrimitive.content),
            )
        }
    }

    fun upsert(value: A2aConnectionConfig) {
        validate(value)
        val current = list()
        require(current.none { it.id != value.id && it.agentCardUrl == value.agentCardUrl }) {
            "这个 Agent Card 地址已添加"
        }
        require(value.alias.isBlank() || current.none {
            it.id != value.id && it.alias.equals(value.alias, ignoreCase = true)
        }) { "触发别名已被其他 A2A 连接使用" }
        val next = if (current.any { it.id == value.id }) current.map {
            if (it.id == value.id) value else it
        } else current + value
        require(next.size <= 16) { "最多添加 16 个 A2A 连接" }
        secrets.saveA2aToken(value.id, value.token)
        write(next)
    }

    fun remove(id: String) {
        write(list().filterNot { it.id == id })
        secrets.saveA2aToken(id, "")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        write(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun migrateLegacy() {
        val url = prefs.getString("card_url", "").orEmpty().trim()
        if (url.isBlank()) return
        val legacy = A2aConnectionConfig("legacy", "原电脑端 Agent", url, secrets.bearerToken())
        secrets.saveA2aToken(legacy.id, legacy.token)
        write(listOf(legacy))
    }

    private fun write(items: List<A2aConnectionConfig>) {
        val raw = JsonArray(items.map { item ->
            JsonObject(mapOf(
                "id" to JsonPrimitive(item.id),
                "name" to JsonPrimitive(item.name),
                "agentCardUrl" to JsonPrimitive(item.agentCardUrl),
                "enabled" to JsonPrimitive(item.enabled),
                "alias" to JsonPrimitive(item.alias),
            ))
        }).toString()
        check(prefs.edit().putString(KEY, raw).commit()) { "无法保存 A2A 连接" }
    }

    private fun validate(value: A2aConnectionConfig) {
        require(value.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "无效的连接 ID" }
        require(value.name.isNotBlank() && value.name.length <= 80) { "请填写 1–80 字的连接名称" }
        require(value.alias.isEmpty() || value.alias.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,31}"))) {
            "触发别名须为 2–32 位英文字母、数字、下划线或连字符，并以字母开头"
        }
        require(value.token.length <= 4_096 && value.token.none { it == '\r' || it == '\n' }) {
            "Bearer Token 格式无效"
        }
        val uri = runCatching { URI(value.agentCardUrl) }.getOrNull()
        require(value.agentCardUrl.length <= 2_048 && uri?.scheme in listOf("http", "https") &&
            !uri?.host.isNullOrBlank() && uri?.userInfo == null && uri?.fragment == null) {
            "Agent Card 地址需为完整的 http 或 https URL"
        }
    }

    companion object {
        private const val KEY = "a2a_connections_v1"
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
