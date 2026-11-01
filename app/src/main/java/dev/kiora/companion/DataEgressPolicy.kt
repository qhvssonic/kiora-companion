package dev.kiora.companion

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import dev.kiora.core.ModelRequestRejectedException

internal enum class LocalDataMode(val label: String) {
    ASK("外发前询问"), LOCAL_ONLY("仅在本机处理");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: ASK
    }
}

data class DataEgressRequest(
    val sessionId: String,
    val destination: String,
    val sources: Set<String>,
    /** Optional stable recipient identity, e.g. two accounts at the same A2A endpoint. */
    val destinationId: String? = null,
)

enum class DataEgressChoice { ONCE, SESSION, DENY }

/** Separate from tool execution permission. Grants live only in this agent instance. */
internal class DataEgressPolicy(
    private val mode: () -> LocalDataMode,
    private val record: (DataEgressRequest, String) -> Unit = { _, _ -> },
    private val confirm: suspend (DataEgressRequest) -> DataEgressChoice,
) {
    private val grants = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    suspend fun check(request: DataEgressRequest) {
        if (request.sources.isEmpty()) return
        fun checkMode() {
            if (mode() == LocalDataMode.LOCAL_ONLY) {
                record(request, "仅本地 · 已阻止")
                throw ModelRequestRejectedException("当前设置仅在本机处理资料，请使用本机模型；外部发送已停止")
            }
        }
        checkMode()
        val key = request.sessionId to (request.destinationId ?: request.destination)
        if (key in grants) { record(request, "会话授权 · 已允许"); return }
        val choice = confirm(request)
        checkMode()
        when (choice) {
            DataEgressChoice.ONCE -> record(request, "单次授权 · 已允许")
            DataEgressChoice.SESSION -> {
                record(request, "会话授权 · 已允许")
                grants.add(key)
            }
            DataEgressChoice.DENY -> {
                record(request, "用户拒绝 · 已阻止")
                throw ModelRequestRejectedException("未获准向 ${request.destination} 发送本地资料")
            }
        }
    }

    fun clear() = grants.clear()
}

/** Never show URL user-info, query credentials or fragments in an approval surface. */
internal fun egressEndpoint(url: String): String {
    val uri = URI(url)
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "无效的外部服务地址" }
    return URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
}
