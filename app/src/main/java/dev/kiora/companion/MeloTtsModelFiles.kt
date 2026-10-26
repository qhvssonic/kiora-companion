package dev.kiora.companion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.kiora.speech.sherpa.MeloTtsSynthesizer
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Atomically imports one complete, versioned offline voice; weights stay outside the APK. */
internal class MeloTtsModelFiles(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("offline_tts", Context.MODE_PRIVATE)
    private val root = File(app.filesDir, "melo-tts")
    data class Model(val directory: File, val id: String, val modelFileName: String)

    fun current(): Model? {
        val name = prefs.getString("directory", null) ?: return null
        val hash = prefs.getString("sha256", null)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
        if (!name.matches(Regex("[a-f0-9-]{36}"))) return null
        val directory = File(root, name)
        val modelFileName = prefs.getString("model_file", "model.int8.onnx") ?: return null
        val precision = if (modelFileName == "model.onnx") "fp32" else "int8"
        return runCatching { MeloTtsSynthesizer.validateDirectory(directory, modelFileName)
            Model(directory, "melo-zh-en-$precision:$hash", modelFileName) }.getOrNull()
    }

    suspend fun importTree(tree: Uri): Model {
        check(root.isDirectory || root.mkdirs()) { "无法创建朗读模型目录" }
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val supported = MeloTtsSynthesizer.REQUIRED_FILES + listOf("model.onnx", "LICENSE")
        val sources = mutableMapOf<String, Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name in supported) {
                    require(name !in sources) { "模型目录中存在重复文件：$name" }
                    sources[name] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        } ?: error("无法读取所选模型目录")
        val modelFileName = if ("model.onnx" in sources) "model.onnx" else "model.int8.onnx"
        val precision = if (modelFileName == "model.onnx") "fp32" else "int8"
        val wanted = MeloTtsSynthesizer.requiredFiles(modelFileName) + "LICENSE"
        wanted.forEach { require(it in sources) { "所选目录缺少 $it，请选择解压后的完整 MeloTTS 模型目录" } }
        val id = UUID.randomUUID().toString()
        val pending = File(root, "$id.pending")
        val target = File(root, id)
        check(pending.mkdir())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            for (name in wanted.sorted()) {
                currentCoroutineContext().ensureActive()
                digest.update(name.toByteArray(Charsets.UTF_8))
                val fileDigest = MessageDigest.getInstance("SHA-256")
                val output = File(pending, name)
                resolver.openInputStream(sources.getValue(name))?.use { input ->
                    output.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 256L * 1024 * 1024) { "朗读模型资产超过 256 MiB" }
                            sink.write(buffer, 0, count)
                            fileDigest.update(buffer, 0, count)
                        }
                    }
                } ?: error("无法读取 $name")
                require(output.length() > 0) { "$name 为空" }
                val fileHash = fileDigest.digest()
                require(fileHash.joinToString("") { "%02x".format(it) } == EXPECTED_SHA256.getValue(name)) {
                    "$name 与 MeloTTS 固定版本不匹配，请重新准备完整模型目录"
                }
                digest.update(fileHash)
            }
            MeloTtsSynthesizer.validateDirectory(pending, modelFileName)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            File(pending, "model.sha256").writeText(hash)
            currentCoroutineContext().ensureActive()
            check(pending.renameTo(target)) { "无法保存朗读模型" }
            if (!prefs.edit().putString("directory", id).putString("sha256", hash)
                    .putString("model_file", modelFileName).commit()) {
                target.deleteRecursively()
                error("无法保存朗读模型设置")
            }
            return Model(target, "melo-zh-en-$precision:$hash", modelFileName)
        } finally {
            // Both paths are direct children of the app's private, fixed model root.
            if (pending.exists()) pending.deleteRecursively()
        }
    }

    private companion object {
        // Hugging Face csukuangfj/vits-melo-tts-zh_en revision
        // a0d5c6a264c0ef92d70d8661d8cc502d79627cd6.
        val EXPECTED_SHA256 = mapOf(
            "LICENSE" to "88a50e5a02bbc2a5c2f084dc19da751aa97b1690f5fda76cd8005c8634d1ca70",
            "date.fst" to "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718",
            "lexicon.txt" to "7236884b02435ac5d10cf69b4be40a61b45aa676b5300f0e412f185748fee528",
            "model.int8.onnx" to "f085f5079e05f039b800aeb542f5253c26a303211b0c6465d0d9387977855a63",
            "model.onnx" to "bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d",
            "new_heteronym.fst" to "ca14b2127e27baa571664e4bb791e143e7425f56a6bc29db08d74f97e6aa4e29",
            "number.fst" to "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd",
            "phone.fst" to "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7",
            "tokens.txt" to "d18664a7e12bd7ea1022ddaf951e534e136815016c5a809d6b64156bffb4369d",
        )
    }
}
