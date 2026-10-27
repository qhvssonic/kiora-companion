package dev.kiora.companion

import android.content.Context
import kotlinx.coroutines.sync.Mutex

internal enum class VoiceBackend(val label: String) {
    SENSE_VOICE("SenseVoice · 录完转写"),
    PARAFORMER("Paraformer · 实时转写");
}

internal class VoicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    var backend: VoiceBackend
        get() = VoiceBackend.entries.firstOrNull { it.name == prefs.getString("backend", null) }
            ?: VoiceBackend.SENSE_VOICE
        set(value) { prefs.edit().putString("backend", value.name).apply() }
}

/** Serializes ASR model creation, decoding, release and model replacement across Activities. */
internal object VoiceRuntime { val mutex = Mutex() }
