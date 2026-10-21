package dev.kiora.companion

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Lists only descendants of a tree URI that the user persisted in AuthorizedStorageGrants. */
internal class AuthorizedAttachmentBrowser(private val resolver: ContentResolver) {
    data class Entry(val uri: Uri, val name: String, val directory: Boolean)

    fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        tree, DocumentsContract.getTreeDocumentId(tree))

    fun children(tree: Uri, parent: Uri): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getDocumentId(parent))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val entries = mutableListOf<Entry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val type = cursor.getString(2)
                entries += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), name,
                    type == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return entries.sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
    }
}
