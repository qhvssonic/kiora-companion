package dev.kiora.companion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.kiora.speech.sherpa.StreamingParaformerRecognizer
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Imports the pinned three-file int8 bundle atomically; original audio is never stored here. */
internal class StreamingVoiceModelFiles(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("streaming_voice_models", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "streaming-voice-models")

    fun current(): File? {
        val name = prefs.getString("directory", null)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return null
        return File(root, name).takeIf { folder ->
            FILES.all { (name, spec) -> File(folder, name).let { it.isFile && it.length() == spec.first } }
        }
    }

    suspend fun importTree(tree: Uri): File = VoiceRuntime.mutex.withLock {
        check(root.isDirectory || root.mkdirs())
        val sources = mutableMapOf<String, Uri>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name in FILES) {
                    require(name !in sources) { "重复文件：$name" }
                    sources[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        } ?: error("无法读取模型目录")
        FILES.keys.forEach { require(it in sources) { "缺少 $it，请选择包含三个模型文件的目录" } }
        val id = UUID.randomUUID().toString()
        val pending = File(root, "$id.pending").apply { check(mkdir()) }
        val target = File(root, id)
        try {
            for ((name, spec) in FILES) {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                app.contentResolver.openInputStream(sources.getValue(name))?.use { input ->
                    File(pending, name).outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= spec.first) { "$name 大小不匹配" }
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法读取 $name")
                require(total == spec.first && digest.digest().joinToString("") { "%02x".format(it) } == spec.second) {
                    "$name 与支持的 Paraformer 中英双语 int8 版本不匹配"
                }
            }
            val session = StreamingParaformerRecognizer(pending).openSession()
            withContext(NonCancellable) { session.close() }
            currentCoroutineContext().ensureActive()
            check(pending.renameTo(target))
            if (!prefs.edit().putString("directory", id).commit()) {
                target.deleteRecursively(); error("无法保存模型设置")
            }
            // The ASR lease ensures no live recognizer holds an old model path.
            root.listFiles()?.filter { it.isDirectory && it.name != id &&
                it.name.matches(Regex("[a-f0-9-]{36}(\\.pending)?")) }?.forEach { it.deleteRecursively() }
            target
        } finally { pending.deleteRecursively() }
    }

    companion object {
        const val REVISION = "8e40c43232a1c5c66c82111efc5820d3accca11b"
        val FILES = mapOf(
            "encoder.int8.onnx" to (165462184L to "81a70226a8934e6ed92aa1d4fc486b428b5398e2f2619ed4897b7294cab90e9a"),
            "decoder.int8.onnx" to (71664561L to "f3cca9f77bb9d93c8fcbfb63ae617b6b1ee96818df3aa3b151c40658fe38594f"),
            "tokens.txt" to (75756L to "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6"),
        )
    }
}
