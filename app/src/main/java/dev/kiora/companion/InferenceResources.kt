package dev.kiora.companion

import android.os.Debug
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dev.kiora.core.*

/** Process-wide across Activities; logs contain operation types and metrics, never user data. */
internal object InferenceResources {
    private var memory: ActivityManager? = null
    fun initialize(context: Context) {
        memory = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val coordinator = InferenceCoordinator { event ->
        Log.i("KioraInference", "operation=${event.operation} phase=${event.phase} " +
            "waitMs=${event.waitMillis} runMs=${event.executionMillis} " +
            "nativeHeapKiB=${Debug.getNativeHeapAllocatedSize() / 1024}")
    }

    suspend fun <T> withInference(operation: String,
        priority: InferencePriority = InferencePriority.INTERACTIVE,
        block: suspend () -> T): T = coordinator.withInference(operation, priority) {
        val info = ActivityManager.MemoryInfo()
        memory?.getMemoryInfo(info)
        check(!info.lowMemory) { "设备当前可用内存不足，请结束其他任务后重试" }
        block()
    }
}

/** Acquire per request, allowing tools to use the same coordinator between model turns. */
internal class CoordinatedModelPort(private val delegate: ModelPort) : UsageReportingModelPort {
    override suspend fun respond(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
        respondWithUsage(messages, tools).assistant
    override suspend fun respondStreaming(messages: List<AgentMessage>, tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit) = respondStreamingWithUsage(messages, tools, onTextDelta).assistant

    override suspend fun respondWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>) =
        InferenceResources.withInference("chat") {
            if (delegate is UsageReportingModelPort) delegate.respondWithUsage(messages, tools)
            else ModelResponse(delegate.respond(messages, tools))
        }

    override suspend fun respondStreamingWithUsage(messages: List<AgentMessage>, tools: List<ToolDefinition>,
        onTextDelta: suspend (String) -> Unit) = InferenceResources.withInference("chat") {
        if (delegate is UsageReportingModelPort) delegate.respondStreamingWithUsage(messages, tools, onTextDelta)
        else ModelResponse(delegate.respondStreaming(messages, tools, onTextDelta))
    }
}
