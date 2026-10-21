package dev.kiora.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID

/** Persistent, bounded thumbnails for sent images; originals remain in their own storage. */
internal class MessagePreviews(private val context: Context) {
    private val root = File(context.filesDir, "message-previews")

    fun isImage(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("image/") == true ||
        uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase() in
            setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

    fun save(uri: Uri): String? = runCatching {
        val bitmap = decode(uri, 2048) ?: error("无法读取图片")
        root.mkdirs()
        val name = "${UUID.randomUUID()}.jpg"
        File(root, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        name
    }.getOrNull()

    fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片" }
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    fun file(name: String): File? = name.takeIf { it.matches(Regex("[0-9a-f-]{36}\\.jpg")) }
        ?.let { File(root, it) }?.takeIf(File::isFile)
}
