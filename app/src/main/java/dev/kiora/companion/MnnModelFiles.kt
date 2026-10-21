package dev.kiora.companion

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** Imports a complete MNN model directory; config.json refers to sibling files by name. */
internal class MnnModelFiles(private val context: Context) {
    private val resolver = context.contentResolver
    private val root = File(context.filesDir, "mnn-models")
    private var copiedFiles = 0
    private var copiedBytes = 0L

    fun importTree(tree: Uri): File {
        check(root.isDirectory || root.mkdirs()) { "无法创建模型目录" }
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val label = queryName(rootUri).ifBlank { "mnn-model" }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            .trim('.', '_').ifBlank { "mnn-model" }
        val token = UUID.randomUUID().toString().take(8)
        val pending = File(root, "$safe-$token.pending")
        val destination = File(root, "$safe-$token")
        check(pending.mkdir()) { "无法创建导入临时目录" }
        copiedFiles = 0
        copiedBytes = 0L
        try {
            copyChildren(tree, treeId, pending, 0)
            check(File(pending, "config.json").isFile) { "所选目录中没有 config.json" }
            check(pending.renameTo(destination)) { "无法保存模型目录" }
            return File(destination, "config.json")
        } finally {
            if (pending.exists()) pending.deleteRecursively()
        }
    }

    private fun copyChildren(tree: Uri, parentId: String, target: File, depth: Int) {
        check(depth < 8) { "模型目录层级过深" }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(projection[0])
            val nameColumn = cursor.getColumnIndexOrThrow(projection[1])
            val typeColumn = cursor.getColumnIndexOrThrow(projection[2])
            val seen = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val name = cursor.getString(nameColumn)
                val type = cursor.getString(typeColumn)
                require(name.isNotBlank() && name != "." && name != ".." &&
                    '/' !in name && '\\' !in name && seen.add(name)) { "模型目录包含无效或重复文件名" }
                val file = File(target, name)
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
                    check(file.mkdir()) { "无法创建模型子目录" }
                    copyChildren(tree, id, file, depth + 1)
                } else {
                    check(++copiedFiles <= 256) { "模型文件数量超过 256" }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    resolver.openInputStream(uri)?.use { input ->
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copiedBytes += count
                                check(copiedBytes <= 20L * 1024 * 1024 * 1024) { "模型目录超过 20 GiB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("无法读取模型文件：$name")
                }
            }
        } ?: error("无法读取所选模型目录")
    }

    private fun queryName(uri: Uri): String = resolver.query(uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0).orEmpty() else ""
    }.orEmpty()
}
