package dev.kiora.companion

/** Curated Chat Completions presets. The custom option remains available for other providers. */
data class ProviderPreset(
    val id: String,
    val name: String,
    val endpoint: String,
    val models: List<String>,
    val note: String,
)

object ProviderCatalog {
    val options = listOf(
        ProviderPreset(
            "deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions",
            listOf("deepseek-flash", "deepseek-v4-pro"),
            "适合日常对话与电脑端能力调用",
        ),
        ProviderPreset(
            "openai", "OpenAI", "https://api.openai.com/v1/chat/completions",
            listOf("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini"),
            "使用 OpenAI Chat Completions 接口",
        ),
        ProviderPreset("litert", "LiteRT-LM · 本机", "", listOf("Gemma 4 E2B"),
            "在应用中导入 .litertlm 文件，模型直接在手机运行"),
        ProviderPreset("mnn", "MNN Chat · 本机", "http://127.0.0.1:8080/v1/chat/completions",
            emptyList(), "先在 MNN Chat 中加载模型并开启 API 服务；模型 ID 需与 MNN Chat 一致"),
        ProviderPreset("custom", "其他兼容服务", "", emptyList(), "自行填写接口地址与模型 ID"),
    )

    fun byId(id: String?): ProviderPreset = options.firstOrNull { it.id == id } ?: options.last()

    fun infer(endpoint: String): ProviderPreset = when {
        endpoint.contains("api.deepseek.com", ignoreCase = true) -> options[0]
        endpoint.contains("api.openai.com", ignoreCase = true) -> options[1]
        endpoint.contains("127.0.0.1:8080", ignoreCase = true) -> options.first { it.id == "mnn" }
        else -> options.last()
    }
}
