package dev.kiora.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import dev.kiora.face.FaceImage

internal object FaceImages {
    /** Full image, bounded decode, preserving EXIF orientation (never a center-cropped thumbnail). */
    fun decode(context: Context, uri: Uri, maxEdge: Int = 1600): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, maxEdge.toFloat()/maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1), (info.size.height*scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight)/sample > maxEdge) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法解码图片")
        val orientation = resolver.openInputStream(uri)?.use { input -> runCatching {
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1) } ?: 1
        val matrix = Matrix().apply { when(orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }
            6 -> setRotate(90f); 7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
        } }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    fun input(bitmap: Bitmap): FaceImage {
        val pixels = IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FaceImage(bitmap.width, bitmap.height, pixels)
    }
}
