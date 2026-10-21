package dev.kiora.companion

/** Present Markdown tables as labeled rows so long cells remain readable on narrow screens. */
internal object MobileMarkdownTables {
    private val separator = Regex(":?-{3,}:?")
    private val htmlBreak = Regex("(?i)<br\\s*/?>")

    fun format(source: String): String {
        val lines = source.split('\n')
        val output = mutableListOf<String>()
        var index = 0
        var fence: Char? = null
        var fenceSize = 0

        while (index < lines.size) {
            val line = lines[index]
            val marker = line.trimStart().takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3 && marker.all { it == marker.first() }) {
                if (fence == null) {
                    fence = marker.first()
                    fenceSize = marker.length
                } else if (fence == marker.first() && marker.length >= fenceSize) {
                    fence = null
                }
            }
            if (fence == null && index + 2 < lines.size) {
                val headers = cells(line)
                val dividers = cells(lines[index + 1])
                if (headers != null && dividers?.size == headers.size &&
                    dividers.all { separator.matches(it.trim()) }) {
                    val rows = mutableListOf<List<String>>()
                    var next = index + 2
                    val leadingPipe = line.trimStart().startsWith('|')
                    while (next < lines.size &&
                        (!leadingPipe || lines[next].trimStart().startsWith('|'))) {
                        val row = cells(lines[next]) ?: break
                        if (row.size != headers.size) break
                        rows += row
                        next++
                    }
                    if (rows.isNotEmpty()) {
                        rows.forEachIndexed { rowIndex, row ->
                            if (output.lastOrNull()?.isNotBlank() == true) output += ""
                            output += "**第 ${rowIndex + 1} 行**"
                            row.forEachIndexed { column, value ->
                                val heading = headers[column].ifBlank { "第 ${column + 1} 列" }
                                    .replace("*", "\\*")
                                output += "- **$heading**：${htmlBreak.replace(value, "；").ifBlank { "—" }}"
                            }
                            output += ""
                        }
                        index = next
                        continue
                    }
                }
            }
            output += line
            index++
        }
        return output.joinToString("\n")
    }

    private fun cells(line: String): List<String>? {
        if ('|' !in line) return null
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var position = 0
        while (position < line.length) {
            val character = line[position]
            if (character == '\\' && position + 1 < line.length && line[position + 1] == '|') {
                current.append('|')
                position += 2
                continue
            }
            if (character == '|') {
                values += current.toString().trim()
                current.setLength(0)
            } else current.append(character)
            position++
        }
        values += current.toString().trim()
        if (line.trimStart().startsWith('|')) values.removeAt(0)
        if (line.trimEnd().endsWith('|')) values.removeAt(values.lastIndex)
        return values.takeIf { it.size >= 2 }
    }
}
