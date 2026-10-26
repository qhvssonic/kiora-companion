package dev.kiora.companion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.kiora.face.onnx.InsightFacePort
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed official buffalo_sc files; imported weights never enter the APK or repository. */
internal class FaceModelFiles(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("face_models", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "face-models")
    data class Model(val directory: File, val id: String)
    fun current(): Model? {
        val name = prefs.getString("directory", null)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: return null
        val folder = File(root, name)
        return runCatching { InsightFacePort.validateDirectory(folder); Model(folder, MODEL_ID) }.getOrNull()
    }

    suspend fun importTree(tree: Uri): Model {
        check(root.isDirectory || root.mkdirs())
        val sources = mutableMapOf<String, Uri>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name in HASHES) {
                    require(name !in sources) { "重复文件：$name" }
                    sources[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        } ?: error("无法读取模型目录")
        HASHES.keys.forEach { require(it in sources) { "缺少 $it，请选择解压后的 buffalo_sc 目录" } }
        val id = UUID.randomUUID().toString()
        val pending = File(root, "$id.pending").apply { check(mkdir()) }
        val target = File(root, id)
        try {
            for ((name, expected) in HASHES) {
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
                            require(total <= 64L*1024*1024) { "模型文件过大" }
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法读取 $name")
                require(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                    "$name 与官方 buffalo_sc 固定版本不匹配"
                }
            }
            val engine = InsightFacePort(pending, MODEL_ID)
            try { engine.prepare() } finally { withContext(NonCancellable) { engine.close() } }
            currentCoroutineContext().ensureActive()
            check(pending.renameTo(target))
            if (!prefs.edit().putString("directory", id).commit()) {
                target.deleteRecursively(); error("无法保存模型设置")
            }
            // No live engine uses these paths: imports run exclusively in FaceActivity.
            root.listFiles()?.filter { it.isDirectory && it.name != id &&
                it.name.matches(Regex("[a-f0-9-]{36}(\\.pending)?")) }?.forEach { it.deleteRecursively() }
            return Model(target, MODEL_ID)
        } finally { pending.deleteRecursively() }
    }

    companion object {
        const val MODEL_ID = "buffalo-sc:5e4447f50245:9cc6e4a75f0e:rgb-align112-v1"
        // Official InsightFace v0.7 release buffalo_sc.zip; weights: non-commercial research only.
        val HASHES = mapOf(
            "det_500m.onnx" to "5e4447f50245bbd7966bd6c0fa52938c61474a04ec7def48753668a9d8b4ea3a",
            "w600k_mbf.onnx" to "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f",
        )
    }
}
