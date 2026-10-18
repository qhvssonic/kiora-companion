package dev.kiora.companion

import android.content.Context
import android.net.Uri
import dev.kiora.a2a.A2aAgentConnection
import dev.kiora.a2a.A2aInlineFile
import dev.kiora.a2a.A2aPeerConnector
import dev.kiora.a2a.A2aUserMessage
import dev.kiora.a2a.RemoteTaskState
import dev.kiora.android.attachments.AndroidA2aAttachmentReader
import dev.kiora.core.AgentEvent
import dev.kiora.core.AgentMessage
import dev.kiora.core.AgentRuntime
import dev.kiora.core.CapabilitySandbox
import dev.kiora.core.JsonlSessionStore
import dev.kiora.core.RunResult
import dev.kiora.core.SessionTree
import dev.kiora.core.ToolAuthorization
import dev.kiora.core.ToolCall
import dev.kiora.core.ToolCapability
import dev.kiora.model.ChatCompletionModelPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

data class CompanionConfig(
    val modelUrl: String,
    val modelId: String,
    val apiKey: String,
    val agentCardUrl: String,
    val a2aToken: String,
)

/** Host integration: Kiora handles the agent loop, A2A, sessions and bounded attachment reading. */
class CompanionAgent(
    context: Context,
    private val authorize: suspend (ToolCall) -> ToolAuthorization,
    private val onDelta: suspend (String) -> Unit,
    private val onStatus: suspend (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val store = JsonlSessionStore(appContext.filesDir.resolve("agent-sessions"))
    private var connection: A2aAgentConnection? = null
    private var runtime: AgentRuntime? = null
    private val pendingTaskIds = mutableMapOf<String, String>()

    val targetName: String get() = connection?.name?.ifBlank { "电脑端 Agent" } ?: "电脑端 Agent"
    val hasDesktop: Boolean get() = connection != null
    val isConfigured: Boolean get() = connection != null || runtime != null

    suspend fun configure(config: CompanionConfig): String {
        require(config.agentCardUrl.isNotBlank() || (config.modelUrl.isNotBlank() && config.modelId.isNotBlank())) {
            "请配置模型或电脑端 Agent Card"
        }
        val discovered = if (config.agentCardUrl.isBlank()) null else A2aPeerConnector.discoverConnection(
            agentCardUrl = config.agentCardUrl,
            bearerToken = { config.a2aToken.ifBlank { null } },
            rewriteLoopbackHost = true,
        )
        val model = if (config.modelUrl.isBlank() || config.modelId.isBlank()) null else ChatCompletionModelPort(
            endpoint = config.modelUrl,
            model = config.modelId,
            apiKey = { config.apiKey.ifBlank { null } },
        )
        val tools = discovered?.skillTools().orEmpty().ifEmpty {
            discovered?.let { listOf(dev.kiora.a2a.A2aDelegateTool(it.peer)) }.orEmpty()
        }
        val agent = model?.let {
            AgentRuntime(
                model = it,
                tools = tools,
                store = store,
                authorizer = authorize,
                sandbox = CapabilitySandbox(setOf(ToolCapability.NETWORK)),
                events = { event ->
                    when (event) {
                        is AgentEvent.AssistantTextDelta -> withContext(Dispatchers.Main) { onDelta(event.delta) }
                        is AgentEvent.ToolRequested -> withContext(Dispatchers.Main) { onStatus("正在请求 ${event.call.name}…") }
                        else -> Unit
                    }
                },
            )
        }
        connection = discovered
        runtime = agent
        return when {
            discovered != null && agent != null -> "已连接 ${targetName} · 模型就绪"
            discovered != null -> "已连接 ${targetName}"
            else -> "模型就绪"
        }
    }

    suspend fun send(sessionId: String, instruction: String, selected: List<Uri>): String {
        val pending = pendingTaskIds[sessionId]
        val peer = connection?.peer
        if (selected.isNotEmpty() || pending != null || runtime == null) {
            requireNotNull(peer) { "发送附件需要先配置电脑端 Agent Card" }
            val files = buildList {
                var totalBytes = 0L
                selected.forEach { uri ->
                    val file = AndroidA2aAttachmentReader.read(appContext.contentResolver, uri)
                    totalBytes += file.bytes.size
                    require(totalBytes <= A2aInlineFile.MAX_INLINE_BYTES) { "附件总大小不能超过 10 MiB" }
                    add(file)
                }
            }
            val recordedRequest = buildString {
                append(instruction.ifBlank { "请处理这些附件" })
                if (files.isNotEmpty()) append("\n附件：").append(files.joinToString("、") { it.filename })
            }
            onStatus(if (files.isEmpty()) "正在联系电脑端…" else "正在发送 ${files.size} 个附件…")
            val message = A2aUserMessage(instruction, files)
            var task = if (pending == null) peer.send(message) else peer.continueTask(pending, message)
            repeat(120) {
                when (task.state) {
                    RemoteTaskState.COMPLETED -> {
                        pendingTaskIds.remove(sessionId)
                        val answer = task.output.orEmpty().ifBlank { "电脑端已完成处理" }
                        recordExchange(sessionId, recordedRequest, answer)
                        return answer
                    }
                    RemoteTaskState.INPUT_REQUIRED, RemoteTaskState.AUTH_REQUIRED -> {
                        pendingTaskIds[sessionId] = task.id
                        val answer = task.output.orEmpty().ifBlank { "电脑端需要补充信息" }
                        recordExchange(sessionId, recordedRequest, answer)
                        return answer
                    }
                    RemoteTaskState.FAILED, RemoteTaskState.CANCELED, RemoteTaskState.REJECTED -> {
                        pendingTaskIds.remove(sessionId)
                        error(task.output.orEmpty().ifBlank { "电脑端任务${task.state.name.lowercase()}" })
                    }
                    RemoteTaskState.SUBMITTED, RemoteTaskState.WORKING -> {
                        onStatus("电脑端正在处理…")
                        delay(500)
                        task = peer.getTask(task.id)
                    }
                }
            }
            pendingTaskIds[sessionId] = task.id
            val answer = "电脑端仍在处理，任务 ID：${task.id}。请稍后发送消息继续。"
            recordExchange(sessionId, recordedRequest, answer)
            return answer
        }
        return when (val result = requireNotNull(runtime).run(sessionId, instruction)) {
            is RunResult.Completed -> result.text
            is RunResult.Failed -> error(result.reason)
            is RunResult.LimitReached -> error(result.reason)
        }
    }

    private suspend fun recordExchange(sessionId: String, request: String, answer: String) {
        val session = store.load(sessionId) ?: SessionTree.create(sessionId).also { store.create(sessionId) }
        for (message in listOf(AgentMessage.User(request), AgentMessage.Assistant(answer))) {
            val entry = session.append(message, UUID.randomUUID().toString(), System.currentTimeMillis())
            store.append(sessionId, entry)
        }
    }
}
