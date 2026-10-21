package dev.kiora.companion

/** Finds complete top-level blocks that can be rendered once during streaming. */
internal object StreamMarkdown {
    fun completedPrefixEnd(source: String): Int {
        var position = 0
        var boundary = 0
        var pendingBlank = 0
        var listOpen = false
        var fenceChar: Char? = null
        var fenceSize = 0

        while (position < source.length) {
            val newline = source.indexOf('\n', position)
            if (newline < 0) break
            val line = source.substring(position, newline)
            val trimmed = line.trimStart()
            val marker = trimmed.takeWhile { it == '`' || it == '~' }
            val isFence = marker.length >= 3 && marker.all { it == marker.first() }
            val isList = trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
                trimmed.startsWith("+ ") || trimmed.matches(Regex("\\d+[.)]\\s+.*"))

            if (fenceChar != null) {
                if (isFence && marker.first() == fenceChar && marker.length >= fenceSize) {
                    fenceChar = null
                }
            } else if (isFence) {
                fenceChar = marker.first()
                fenceSize = marker.length
            } else if (line.isBlank()) {
                pendingBlank = newline + 1
            } else {
                if (pendingBlank > 0 && (!listOpen || (!isList && !line.startsWith(" ") && !line.startsWith("\t")))) {
                    boundary = pendingBlank
                    listOpen = false
                }
                pendingBlank = 0
                if (isList) listOpen = true
            }
            position = newline + 1
        }
        return boundary
    }
}
