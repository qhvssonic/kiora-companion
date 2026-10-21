package dev.kiora.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android's one-time media permission, separate from Kiora's per-tool approval. */
internal object GalleryAccess {
    fun hasFullAccess(context: Context): Boolean = context.checkSelfPermission(fullPermission()) ==
        PackageManager.PERMISSION_GRANTED

    fun hasPartialAccess(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED

    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun fullPermission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
}
