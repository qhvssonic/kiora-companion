package dev.kiora.companion

import java.io.File

/** Only fields consumed when constructing a chat model; no tool/A2A/summary settings.
 * Do not log this value: online credentials participate in identity.
 */
internal data class ChatModelKey(
    val provider: String,
    val location: String,
    val modelId: String = "",
    val credential: String = "",
    val backend: String = "",
    val fileSize: Long = 0,
    val fileModified: Long = 0,
)

internal fun CompanionConfig.chatModelKey(): ChatModelKey = when (providerId) {
    "litert", "mnn" -> {
        val file = File(if (providerId == "litert") localModelPath else mnnConfigPath)
        ChatModelKey(providerId, file.absolutePath,
            backend = if (providerId == "litert") {
                if (localBackend == "gpu") "gpu" else "cpu"
            } else "",
            fileSize = file.length(), fileModified = file.lastModified())
    }
    else -> ChatModelKey(providerId.ifBlank { ProviderCatalog.infer(modelUrl).id },
        modelUrl, modelId, apiKey)
}
