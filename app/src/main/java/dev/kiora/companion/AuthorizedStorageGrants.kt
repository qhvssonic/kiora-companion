package dev.kiora.companion

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Host-owned SAF grants; the SDK also checks Android's persisted grants at execution time. */
class AuthorizedStorageGrants(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("authorized_storage", Context.MODE_PRIVATE)

    fun folders(): List<Uri> = values("folders")
    fun readableFolders(): List<Uri> = folders().filter { uri ->
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }
    fun photos(): List<Uri> = values("photos")

    fun addFolder(uri: Uri, resultFlags: Int) = add("folders", uri, resultFlags)
    fun addPhoto(uri: Uri, resultFlags: Int) = add("photos", uri, resultFlags)

    fun removeFolder(uri: Uri) = remove("folders", uri)
    fun removePhoto(uri: Uri) = remove("photos", uri)

    private fun values(key: String): List<Uri> = prefs.getStringSet(key, emptySet()).orEmpty().map(Uri::parse)

    private fun add(key: String, uri: Uri, resultFlags: Int) {
        val permission = resultFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(permission and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "系统未授予读取权限" }
        app.contentResolver.takePersistableUriPermission(uri, permission)
        prefs.edit().putStringSet(key, (values(key).map(Uri::toString) + uri.toString()).toSet()).apply()
    }

    private fun remove(key: String, uri: Uri) {
        val remaining = values(key).map(Uri::toString).filterNot { it == uri.toString() }.toSet()
        prefs.edit().putStringSet(key, remaining).apply()
        val other = if (key == "folders") "photos" else "folders"
        if (uri !in values(other)) {
            val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            if (grant != null) {
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                runCatching { app.contentResolver.releasePersistableUriPermission(uri, flags) }
            }
        }
    }
}
