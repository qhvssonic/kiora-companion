package dev.kiora.companion

import dev.kiora.a2a.A2aInlineFile
import java.io.ByteArrayOutputStream
import java.io.File

/** Reads one app-workspace file for an explicitly authorized A2A upload. */
internal object WorkspaceA2aFiles {
    fun relativePathForId(id: String): String? = when {
        id.startsWith("selected:") || id.startsWith("authorized:") -> null
        else -> id.removePrefix("workspace:")
    }

    fun file(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && '\u0000' !in relativePath) { "无效的工作区文件路径" }
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        require(candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "文件必须位于应用工作区"
        }
        require(candidate.isFile) { "工作区文件不存在：$relativePath" }
        return candidate
    }

    fun read(root: File, relativePath: String): A2aInlineFile {
        val source = file(root, relativePath)
        require(source.length() in 1..A2aInlineFile.MAX_INLINE_BYTES.toLong()) {
            "工作区文件必须在 10 MiB 以内"
        }
        val bytes = source.inputStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= A2aInlineFile.MAX_INLINE_BYTES) { "工作区文件超过 10 MiB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val mime = when (source.extension.lowercase()) {
            "md", "markdown" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
        return A2aInlineFile(source.name, mime, bytes)
    }
}
