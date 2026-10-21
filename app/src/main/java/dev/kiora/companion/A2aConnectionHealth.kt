package dev.kiora.companion

import dev.kiora.a2a.A2aPeerConnector
import dev.kiora.a2a.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/** Read-only discovery and TCP probe; no A2A task is created. */
internal object A2aConnectionHealth {
    data class Result(val reachable: Boolean, val label: String)

    suspend fun check(profile: A2aConnectionConfig): Result {
        val connection = try {
            A2aPeerConnector.discoverConnection(
                agentCardUrl = profile.agentCardUrl,
                transport = UrlConnectionTransport(connectTimeoutMillis = 3_000, readTimeoutMillis = 4_000),
                bearerToken = { profile.token.ifBlank { null } },
                rewriteLoopbackHost = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result(false, "Agent Card 不可用：${reason(error)}")
        }
        val endpoint = try {
            URI(connection.endpointUrl)
        } catch (error: Exception) {
            return Result(false, "Agent Card 的任务地址无效")
        }
        val port = when {
            endpoint.port > 0 -> endpoint.port
            endpoint.scheme == "https" -> 443
            endpoint.scheme == "http" -> 80
            else -> return Result(false, "Agent Card 的任务地址无效")
        }
        try {
            withContext(Dispatchers.IO) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, port), 2_500)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result(false, "任务端点 :$port 不可达：${reason(error)}")
        }
        return Result(true, "已连通 · ${connection.skills.size} 项技能")
    }

    private fun reason(error: Exception): String = when (error) {
        is UnknownHostException -> "找不到主机"
        is SocketTimeoutException -> "连接超时"
        is ConnectException -> "连接被拒绝"
        is SecurityException -> "系统阻止连接"
        else -> error.message?.lineSequence()?.firstOrNull()?.take(72).orEmpty().ifBlank { "检测失败" }
    }
}
