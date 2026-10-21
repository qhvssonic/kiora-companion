package dev.kiora.companion

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

internal data class A2aConnectQr(
    val agentCardUrl: String,
    val token: String,
    val scopes: List<String>,
    val user: String?,
) {
    companion object {
        fun parse(text: String): A2aConnectQr {
            require(text.length <= 8_192) { "二维码内容过长" }
            val json = try { Json.parseToJsonElement(text).jsonObject }
            catch (_: Exception) { throw IllegalArgumentException("二维码不是有效的连接信息") }
            require(json.string("type") == "a2a-agent-connect") { "这不是电脑端 Agent 连接二维码" }
            val version = json["v"] as? JsonPrimitive
            require(version != null && !version.isString && version.intOrNull == 1) {
                "不支持此二维码版本"
            }
            val url = json.string("agentCardUrl")?.trim().orEmpty()
            val parsed = try { URI(url) } catch (_: Exception) { null }
            require(url.length <= 2_048 && parsed != null &&
                parsed.scheme in listOf("http", "https") && !parsed.host.isNullOrBlank() &&
                parsed.userInfo == null && parsed.fragment == null) {
                "Agent Card 地址无效，需使用 http 或 https 地址"
            }
            val token = json.string("token") ?: throw IllegalArgumentException("二维码缺少 Bearer Token")
            require(token.length <= 4_096 && token.none { it == '\r' || it == '\n' }) {
                "Bearer Token 格式无效"
            }
            val scopes = when (val value = json["scopes"]) {
                null -> emptyList()
                is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
                    ?: throw IllegalArgumentException("scopes 必须是字符串列表") }
                else -> throw IllegalArgumentException("scopes 必须是字符串列表")
            }
            require(scopes.size <= 32 && scopes.all { it.length <= 128 && it.none(::isControl) }) {
                "scopes 格式无效"
            }
            val user = json.string("user")
            require(user == null || (user.length <= 128 && user.none(::isControl))) {
                "user 格式无效"
            }
            return A2aConnectQr(url, token, scopes, user)
        }

        private fun JsonObject.string(name: String): String? = when (val value = this[name]) {
            null -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("$name 必须是文字")
            else -> throw IllegalArgumentException("$name 必须是文字")
        }

        private fun isControl(char: Char): Boolean = char.code < 0x20 || char.code == 0x7F
    }
}
