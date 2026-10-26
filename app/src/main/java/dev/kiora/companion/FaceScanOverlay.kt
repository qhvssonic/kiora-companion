package dev.kiora.companion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/** Visual guide only. The moving line never implies authentication or liveness. */
internal class FaceScanOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    fun stop() { animator?.cancel(); animator = null; progress = 0f; invalidate() }
    fun updateProgress(value: Float) { progress = value.coerceIn(0f,1f); invalidate() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = RectF(width*.13f, height*.18f, width*.87f, height*.82f)
        paint.style = Paint.Style.FILL; paint.color = 0x55000000
        canvas.drawRect(0f,0f,width.toFloat(),frame.top,paint)
        canvas.drawRect(0f,frame.bottom,width.toFloat(),height.toFloat(),paint)
        canvas.drawRect(0f,frame.top,frame.left,frame.bottom,paint)
        canvas.drawRect(frame.right,frame.top,width.toFloat(),frame.bottom,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2); paint.color = 0x99FFFFFF.toInt()
        canvas.drawRoundRect(frame,dp(22),dp(22),paint)
        paint.strokeWidth = dp(3); paint.color = Color.WHITE
        val arm = dp(24)
        for ((x,dirX) in listOf(frame.left to 1,frame.right to -1)) for ((y,dirY) in listOf(frame.top to 1,frame.bottom to -1)) {
            canvas.drawLine(x+dirX*dp(18),y,x+dirX*arm*2,y,paint)
            canvas.drawLine(x,y+dirY*dp(18),x,y+dirY*arm*2,paint)
        }
        if (animator != null) {
            val scanY = frame.top+dp(24)+(frame.height()-dp(48))*position
            paint.color = 0xFFACD6B9.toInt(); paint.strokeWidth = dp(2)
            canvas.drawLine(frame.left+dp(10),scanY,frame.right-dp(10),scanY,paint)
        }
        val barY = frame.bottom+dp(18)
        paint.strokeWidth = dp(3); paint.strokeCap = Paint.Cap.ROUND; paint.color = 0x55FFFFFF
        canvas.drawLine(frame.left,barY,frame.right,barY,paint)
        paint.color = 0xFFACD6B9.toInt()
        if (progress > 0f) canvas.drawLine(frame.left,barY,frame.left+frame.width()*progress,barY,paint)
        paint.strokeCap = Paint.Cap.BUTT
    }
    private fun dp(n: Int) = n*resources.displayMetrics.density
}
