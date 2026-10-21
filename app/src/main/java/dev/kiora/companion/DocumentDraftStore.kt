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

/** Keeps source images, OCR output, and edits in the app's private storage. */
internal class DocumentDraftStore(private val context: Context) {
    private val root = File(context.filesDir, "document-drafts").apply { mkdirs() }

    data class Page(val id: String, val source: File, val text: String, val recognizedText: String)

    data class Draft(
        val id: String,
        val source: File,
        val text: String,
        val recognizedText: String,
        val updatedAt: Long,
        val pages: List<Page>,
    )

    data class EngineResult(
        val text: String = "",
        val lineCount: Int = 0,
        val elapsedMs: Long = 0,
        val loadMs: Long? = null,
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = error == null
    }

    /** The first page retains the original layout so existing drafts remain readable. */
    fun newCameraDraft(): Draft {
        val id = UUID.randomUUID().toString()
        val directory = draftDirectory(id).apply { mkdirs() }
        val page = Page(LEGACY_PAGE, File(directory, "source.jpg"), "", "")
        return Draft(id, page.source, "", "", System.currentTimeMillis(), listOf(page))
    }

    /** Returns a pending page; call commitCameraPage only after the camera writes the image. */
    fun newCameraPage(id: String): Page {
        val draft = requireNotNull(load(id)) { "扫描草稿不存在" }
        require(draft.pages.size < MAX_PAGES) { "一份文档最多 $MAX_PAGES 页" }
        val pageId = UUID.randomUUID().toString()
        val directory = pageDirectory(id, pageId).apply { mkdirs() }
        return Page(pageId, File(directory, "source-$pageId.jpg"), "", "")
    }

    fun commitCameraPage(id: String, pageId: String) {
        val source = File(pageDirectory(id, pageId), "source-$pageId.jpg")
        require(source.isFile && source.length() > 0L) { "拍照未生成图片" }
        appendPageId(id, pageId)
    }

    fun discardPendingCameraPage(id: String, pageId: String) {
        if (!ID_PATTERN.matches(id) || !ID_PATTERN.matches(pageId)) return
        val directory = pageDirectory(id, pageId)
        if (directory.isDirectory && pageId !in pageIds(id)) directory.deleteRecursively()
    }

    fun importImage(resolver: ContentResolver, uri: Uri): Draft {
        val id = UUID.randomUUID().toString()
        val directory = draftDirectory(id).apply { mkdirs() }
        try {
            val source = copyImage(resolver, uri, directory)
            val page = Page(LEGACY_PAGE, source, "", "")
            return Draft(id, source, "", "", System.currentTimeMillis(), listOf(page))
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun appendImage(id: String, resolver: ContentResolver, uri: Uri): Page {
        val draft = requireNotNull(load(id)) { "扫描草稿不存在" }
        require(draft.pages.size < MAX_PAGES) { "一份文档最多 $MAX_PAGES 页" }
        val pageId = UUID.randomUUID().toString()
        val directory = pageDirectory(id, pageId).apply { mkdirs() }
        try {
            val source = copyImage(resolver, uri, directory, "source-$pageId")
            appendPageId(id, pageId)
            return Page(pageId, source, "", "")
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun load(id: String): Draft? {
        if (!ID_PATTERN.matches(id)) return null
        val directory = draftDirectory(id)
        val pages = pageIds(id).mapNotNull { pageId ->
            val pageDir = pageDirectory(id, pageId)
            val source = pageDir.listFiles()?.firstOrNull {
                it.isFile && (it.name.startsWith("source.") || it.name.startsWith("source-"))
            } ?: return@mapNotNull null
            Page(
                pageId,
                source,
                File(pageDir, "edited.txt").takeIf(File::isFile)?.readText().orEmpty(),
                File(pageDir, "recognized.txt").takeIf(File::isFile)?.readText().orEmpty(),
            )
        }
        if (pages.isEmpty()) return null
        return Draft(
            id,
            pages.first().source,
            pages.joinToString("\n\n") { it.text },
            pages.joinToString("\n\n") { it.recognizedText },
            directory.lastModified(),
            pages,
        )
    }

    fun list(): List<Draft> = root.listFiles().orEmpty().mapNotNull { load(it.name) }
        .sortedByDescending(Draft::updatedAt)

    fun discardEmptyCameraDraft(id: String) {
        if (!ID_PATTERN.matches(id)) return
        val directory = draftDirectory(id)
        val source = File(directory, "source.jpg")
        if (source.length() == 0L && directory.listFiles().orEmpty().all { it == source }) {
            source.delete()
            directory.delete()
        }
    }

    fun delete(id: String) {
        require(ID_PATTERN.matches(id)) { "无效的草稿 ID" }
        val directory = draftDirectory(id)
        require(directory.canonicalFile.parentFile == root.canonicalFile) { "草稿目录无效" }
        if (directory.exists()) check(directory.deleteRecursively()) { "无法删除草稿" }
    }

    fun deletePage(id: String, pageId: String) {
        val pages = requireNotNull(load(id)) { "扫描草稿不存在" }.pages
        require(pages.any { it.id == pageId }) { "页面不存在" }
        if (pages.size == 1) {
            delete(id)
            return
        }
        writePageIds(id, pages.map(Page::id).filterNot { it == pageId })
        val pageDir = pageDirectory(id, pageId)
        if (pageId == LEGACY_PAGE) {
            pageDir.listFiles().orEmpty().filter {
                it.name.startsWith("source.") || it.name in PAGE_METADATA
            }.forEach(File::delete)
        } else {
            check(pageDir.deleteRecursively()) { "无法删除页面" }
        }
        touch(id)
    }

    fun movePage(id: String, pageId: String, offset: Int) {
        require(offset == -1 || offset == 1)
        val ids = requireNotNull(load(id)) { "扫描草稿不存在" }.pages.map(Page::id).toMutableList()
        val index = ids.indexOf(pageId)
        require(index >= 0 && index + offset in ids.indices) { "无法移动页面" }
        val swap = ids[index + offset]
        ids[index + offset] = pageId
        ids[index] = swap
        writePageIds(id, ids)
        touch(id)
    }

    fun saveRecognition(id: String, page: OcrPage) =
        saveRecognition(id, requireNotNull(load(id)).pages.first().id, page)

    fun saveRecognition(id: String, pageId: String, page: OcrPage) {
        val directory = existingPageDirectory(id, pageId)
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
        touch(id)
    }

    fun savePaddleResult(id: String, result: EngineResult) =
        savePaddleResult(id, requireNotNull(load(id)).pages.first().id, result)

    fun savePaddleResult(id: String, pageId: String, result: EngineResult) {
        val directory = existingPageDirectory(id, pageId)
        atomicWrite(File(directory, "ocr-paddle.json"), result.toJson().toString())
        touch(id)
    }

    fun loadPaddleResult(id: String): EngineResult? =
        load(id)?.pages?.firstOrNull()?.let { loadPaddleResult(id, it.id) }

    fun loadPaddleResult(id: String, pageId: String): EngineResult? {
        val directory = load(id)?.pages?.firstOrNull { it.id == pageId }?.source?.parentFile ?: return null
        val current = File(directory, "ocr-paddle.json")
        if (current.isFile) return runCatching { JSONObject(current.readText()).toEngineResult() }.getOrNull()
        val legacy = File(directory, "ocr-comparison.json")
        return if (legacy.isFile) runCatching {
            JSONObject(legacy.readText()).getJSONObject("paddle").toEngineResult()
        }.getOrNull() else null
    }

    fun saveEdited(id: String, text: String) =
        saveEdited(id, requireNotNull(load(id)).pages.first().id, text)

    fun saveEdited(id: String, pageId: String, text: String) {
        val directory = existingPageDirectory(id, pageId)
        atomicWrite(File(directory, "edited.txt"), text)
        touch(id)
    }

    private fun copyImage(resolver: ContentResolver, uri: Uri, directory: File,
                          stem: String = "source"): File {
        val extension = when (resolver.getType(uri)) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
        val source = File(directory, "$stem.$extension")
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
        return source
    }

    private fun draftDirectory(id: String): File {
        require(ID_PATTERN.matches(id)) { "无效的草稿 ID" }
        return File(root, id)
    }

    private fun pageDirectory(id: String, pageId: String): File {
        require(pageId == LEGACY_PAGE || ID_PATTERN.matches(pageId)) { "无效的页面 ID" }
        return if (pageId == LEGACY_PAGE) draftDirectory(id)
            else File(File(draftDirectory(id), "pages"), pageId)
    }

    private fun existingPageDirectory(id: String, pageId: String): File {
        require(load(id)?.pages?.any { it.id == pageId } == true) { "页面不存在" }
        return pageDirectory(id, pageId)
    }

    private fun pageIds(id: String): List<String> {
        val directory = draftDirectory(id)
        val order = File(directory, "pages.json")
        if (order.isFile || File(directory, "pages.json.bak").isFile) return runCatching {
            val saved = AtomicFile(order).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONArray(saved)
            (0 until json.length()).map { json.getString(it) }
                .filter { it == LEGACY_PAGE || ID_PATTERN.matches(it) }
                .distinct()
        }.getOrDefault(emptyList())
        return if (directory.listFiles().orEmpty().any { it.isFile && it.name.startsWith("source.") })
            listOf(LEGACY_PAGE) else emptyList()
    }

    private fun appendPageId(id: String, pageId: String) {
        val ids = pageIds(id)
        require(pageId !in ids) { "页面已存在" }
        require(ids.size < MAX_PAGES) { "一份文档最多 $MAX_PAGES 页" }
        writePageIds(id, ids + pageId)
        touch(id)
    }

    private fun writePageIds(id: String, ids: List<String>) {
        atomicWrite(File(draftDirectory(id), "pages.json"), JSONArray(ids).toString())
    }

    private fun touch(id: String) {
        draftDirectory(id).setLastModified(System.currentTimeMillis())
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

    companion object {
        const val MAX_PAGES = 30
        private const val LEGACY_PAGE = "legacy"
        private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        private val ID_PATTERN = Regex("[a-f0-9-]{36}")
        private val PAGE_METADATA = setOf(
            "recognized.txt", "recognized-lines.json", "edited.txt",
            "ocr-paddle.json", "ocr-comparison.json",
        )
    }
}
