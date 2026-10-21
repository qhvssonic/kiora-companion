package dev.kiora.companion

/** Small model catalog; unknown providers require an explicit window instead of a guessed limit. */
internal object ModelContextDefaults {
    fun window(providerId: String, modelId: String): Int? = when {
        providerId == "litert" -> 4_096 // Current Gemma 4 E2B package reported this limit on-device.
        providerId == "openai" && modelId in setOf("gpt-4.1", "gpt-4.1-mini") -> 1_047_576
        providerId == "openai" && modelId == "gpt-4o-mini" -> 128_000
        else -> null
    }
}
