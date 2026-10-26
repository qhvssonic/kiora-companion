package dev.kiora.companion

import dev.kiora.face.FaceImage

/** Raw camera pixels are never encoded as JPEG or written to disk. */
internal object FacePreviewFrame {
    fun decodeNv21(bytes: ByteArray, width: Int, height: Int, rotation: Int): FaceImage {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(width <= 1920 && height <= 1920)
        require(bytes.size >= width*height*3/2)
        require(rotation in listOf(0,90,180,270))
        val outWidth = if (rotation % 180 == 0) width else height
        val outHeight = if (rotation % 180 == 0) height else width
        val pixels = IntArray(width*height)
        for (y in 0 until height) for (x in 0 until width) {
            val luma = maxOf(0, (bytes[y*width+x].toInt() and 255)-16)
            val uv = width*height + (y/2)*width + (x and -2)
            val v = (bytes[uv].toInt() and 255)-128
            val u = (bytes[uv+1].toInt() and 255)-128
            val r = ((298*luma + 409*v + 128) shr 8).coerceIn(0,255)
            val g = ((298*luma - 100*u - 208*v + 128) shr 8).coerceIn(0,255)
            val b = ((298*luma + 516*u + 128) shr 8).coerceIn(0,255)
            val index = when(rotation) {
                90 -> x*outWidth + (height-1-y)
                180 -> (height-1-y)*outWidth + (width-1-x)
                270 -> (width-1-x)*outWidth + y
                else -> y*outWidth+x
            }
            pixels[index] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return FaceImage(outWidth,outHeight,pixels)
    }
}
