package dev.kiora.companion

/** Accepts a model's numeric shorthand only for a file selected in the current turn. */
internal object A2aFileIds {
    fun normalize(id: String, selectedCount: Int): String {
        val index = id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        return if (index != null && index < selectedCount) "selected:$index" else id
    }
}
