package dev.kiora.companion

import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** A complete snapshot of photos currently visible through the app's gallery permission. */
internal object AlbumPhotoCatalog {
    fun scan(context: Context): List<AlbumIndexStore.Photo> {
        check(hasAccess(context)) { "相册授权已失效，请重新授权" }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(collection, arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
        ), null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC")
            ?: error("无法读取当前授权的相册，索引未更改")
        val photos = cursor.use {
            buildList {
                while (it.moveToNext()) add(AlbumIndexStore.Photo(
                    it.getLong(0), it.getString(1).orEmpty(), it.getLong(2), it.getLong(3)))
            }
        }
        check(hasAccess(context)) { "相册授权已失效，索引未更改" }
        return photos
    }

    private fun hasAccess(context: Context) = GalleryAccess.hasFullAccess(context) ||
        GalleryAccess.hasPartialAccess(context)
}
