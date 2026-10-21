package dev.kiora.companion

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dev.kiora.document.OcrPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Keeps the source image, OCR output, and human-edited text outside the chat history. */
internal class DocumentDraftStore(private val context: Context) {
    private val root = File(context.filesDir, "document-drafts").apply { mkdirs() }

    data class Draft(val id: String, val source: File, val text: String, val recognizedText: String,
        val updatedAt: Long)

    data class EngineResult(
        val text: String = "",
        val lineCount: Int = 0,
        val elapsedMs: Long = 0,
        val loadMs: Long? = null,
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = error == null
    }

    fun newCameraDraft(): Draft {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        return Draft(id, File(directory, "source.jpg"), "", "", System.currentTimeMillis())
    }

    fun importImage(resolver: ContentResolver, uri: Uri): Draft {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        val extension = when (resolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val source = File(directory, "source.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= MAX_SOURCE_BYTES) { "图片超过 20 MiB" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法打开所选图片")
            require(source.length() > 0L) { "图片为空" }
            return Draft(id, source, "", "", System.currentTimeMillis())
        } catch (error: Exception) {
            source.delete()
            directory.delete()
            throw error
        }
    }

    fun load(id: String): Draft? {
        if (!ID_PATTERN.matches(id)) return null
        val directory = File(root, id)
        val source = directory.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("source.") }
            ?: return null
        return Draft(id, source, File(directory, "edited.txt").takeIf(File::isFile)?.readText().orEmpty(),
            File(directory, "recognized.txt").takeIf(File::isFile)?.readText().orEmpty(),
            directory.lastModified())
    }

    fun list(): List<Draft> = root.listFiles().orEmpty().mapNotNull { load(it.name) }
        .sortedByDescending(Draft::updatedAt)

    fun discardEmptyCameraDraft(id: String) {
        if (!ID_PATTERN.matches(id)) return
        val directory = File(root, id)
        val source = File(directory, "source.jpg")
        if (source.length() == 0L && directory.listFiles().orEmpty().all { it == source }) {
            source.delete()
            directory.delete()
        }
    }

    fun delete(id: String) {
        require(ID_PATTERN.matches(id)) { "无效的草稿 ID" }
        val directory = File(root, id)
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "草稿目录无效" }
        if (directory.exists()) check(directory.deleteRecursively()) { "无法删除草稿" }
    }

    fun saveRecognition(id: String, page: OcrPage) {
        val draft = requireNotNull(load(id)) { "扫描草稿不存在" }
        val directory = draft.source.parentFile!!
        atomicWrite(File(directory, "recognized.txt"), page.text)
        val lines = JSONArray()
        page.lines.forEach { line ->
            lines.put(JSONObject().apply {
                put("text", line.text)
                line.confidence?.let { put("confidence", it.toDouble()) }
                line.bounds?.let { bounds ->
                    put("bounds", JSONArray().put(bounds.left).put(bounds.top)
                        .put(bounds.right).put(bounds.bottom))
                }
            })
        }
        atomicWrite(File(directory, "recognized-lines.json"), lines.toString())
        atomicWrite(File(directory, "edited.txt"), page.text)
        directory.setLastModified(System.currentTimeMillis())
    }

    fun savePaddleResult(id: String, result: EngineResult) {
        val directory = requireNotNull(load(id)) { "扫描草稿不存在" }.source.parentFile!!
        atomicWrite(File(directory, "ocr-paddle.json"), result.toJson().toString())
        directory.setLastModified(System.currentTimeMillis())
    }

    fun loadPaddleResult(id: String): EngineResult? {
        val directory = load(id)?.source?.parentFile ?: return null
        val current = File(directory, "ocr-paddle.json")
        if (current.isFile) return runCatching { JSONObject(current.readText()).toEngineResult() }.getOrNull()
        val legacy = File(directory, "ocr-comparison.json")
        return if (legacy.isFile) runCatching {
            JSONObject(legacy.readText()).getJSONObject("paddle").toEngineResult()
        }.getOrNull() else null
    }

    fun saveEdited(id: String, text: String) {
        val draft = requireNotNull(load(id)) { "扫描草稿不存在" }
        atomicWrite(File(draft.source.parentFile, "edited.txt"), text)
        draft.source.parentFile!!.setLastModified(System.currentTimeMillis())
    }

    private fun atomicWrite(file: File, content: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun EngineResult.toJson() = JSONObject().apply {
        put("text", text)
        put("lineCount", lineCount)
        put("elapsedMs", elapsedMs)
        loadMs?.let { put("loadMs", it) }
        error?.let { put("error", it) }
    }

    private fun JSONObject.toEngineResult() = EngineResult(
        text = optString("text"),
        lineCount = optInt("lineCount"),
        elapsedMs = optLong("elapsedMs"),
        loadMs = if (has("loadMs")) optLong("loadMs") else null,
        error = if (has("error")) optString("error") else null,
    )

    private companion object {
        const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        val ID_PATTERN = Regex("[a-f0-9-]{36}")
    }
}
