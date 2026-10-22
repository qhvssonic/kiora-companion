package dev.kiora.companion

/** Keeps a scan's source image local and binds only a Markdown file written in this turn. */
internal class ScanMarkdownHandoff(private val referenceIds: Set<String>) {
    var markdownPath: String? = null
        private set

    fun recordSuccessfulWrite(path: String) {
        if (path.endsWith(".md", ignoreCase = true)) markdownPath = path
    }

    fun boundFileIds(selectedIds: List<String>): List<String> =
        selectedIds.filterNot { it in referenceIds } +
            listOfNotNull(markdownPath?.let { "workspace:$it" })

    fun validationError(): String? = if (markdownPath == null)
        "请先用本机 write 工具将校对后的扫描原文整理为 .md 文件，再将该文件交给 A2A；不要把扫描原图交给远端代写。"
    else null
}
