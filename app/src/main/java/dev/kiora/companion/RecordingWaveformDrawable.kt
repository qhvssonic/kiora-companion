package dev.kiora.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/** Seven recent microphone levels, displayed as a compact recording waveform. */
internal class RecordingWaveformDrawable(context: Context) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val levels = FloatArray(BAR_COUNT)
    private var sampleIndex = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF20201F.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.4f * density
    }
    private var drawableAlpha = 255

    fun pushLevel(level: Float) {
        levels.copyInto(levels, 0, 1)
        val listeningMotion = QUIET_LEVELS[sampleIndex++ % QUIET_LEVELS.size]
        levels[levels.lastIndex] = max(level, listeningMotion).coerceIn(0f, 1f)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        paint.alpha = drawableAlpha
        val left = bounds.exactCenterX() - (BAR_COUNT - 1) * BAR_SPACING_DP * density / 2f
        val centerY = bounds.exactCenterY()
        levels.forEachIndexed { index, level ->
            val height = (3f + 18f * level) * density
            val x = left + index * BAR_SPACING_DP * density
            canvas.drawLine(x, centerY - height / 2f, x, centerY + height / 2f, paint)
        }
    }

    override fun getIntrinsicWidth(): Int = (32f * density).roundToInt()
    override fun getIntrinsicHeight(): Int = (24f * density).roundToInt()

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BAR_COUNT = 7
        const val BAR_SPACING_DP = 4.5f
        val QUIET_LEVELS = floatArrayOf(0.08f, 0.17f, 0.10f, 0.23f, 0.07f, 0.15f, 0.11f)
    }
}
