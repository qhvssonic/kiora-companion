package dev.kiora.companion

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Full-screen inspection of an image already selected or retained by this app. */
class ImagePreviewActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var decoded: Bitmap? = null
    private lateinit var image: ZoomableImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ZoomableImageView(this).apply {
            contentDescription = "图片预览；双指缩放，放大后拖动，双击还原"
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))

        val loading = TextView(this).apply {
            text = "正在打开图片…"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val close = TextView(this).apply {
            text = "×"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "关闭图片预览"
            background = rounded(0x99000000.toInt(), 22)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.RIGHT).apply {
            setMargins(0, dp(12), dp(16), 0)
        })

        val hint = TextView(this).apply {
            text = "双指缩放  ·  拖动查看  ·  双击复位"
            textSize = 12f
            setTextColor(0xFFE0E7E8.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0x99000000.toInt(), 16)
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28)
        })
        setContentView(root)

        val uri = intent.data ?: run {
            loading.text = "图片地址不可用"
            return
        }
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { MessagePreviews(this@ImagePreviewActivity).decode(uri, 3072) }
            if (bitmap == null) loading.text = "无法打开图片"
            else {
                decoded = bitmap
                image.setBitmap(bitmap)
                loading.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        image.setImageDrawable(null)
        decoded?.recycle()
        decoded = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
}

/** Fits the image initially and keeps zoomed content within the viewport while dragging. */
internal class ZoomableImageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private val values = FloatArray(9)
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomTo((zoom * detector.scaleFactor).coerceIn(1f, 6f), detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (zoom > 1f) resetToFit() else zoomTo(2.5f, event.x, event.y)
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setBitmap(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { resetToFit() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        post { resetToFit() }
    }

    private fun resetToFit() {
        val content = drawable ?: return
        if (width == 0 || height == 0 || content.intrinsicWidth <= 0 || content.intrinsicHeight <= 0) return
        val fitScale = min(width.toFloat() / content.intrinsicWidth, height.toFloat() / content.intrinsicHeight)
        transform.reset()
        transform.setScale(fitScale, fitScale)
        transform.postTranslate((width - content.intrinsicWidth * fitScale) / 2f,
            (height - content.intrinsicHeight * fitScale) / 2f)
        zoom = 1f
        imageMatrix = transform
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        if (drawable == null || width == 0 || height == 0) return
        val factor = target / zoom
        transform.postScale(factor, factor, focusX, focusY)
        zoom = target
        keepInBounds()
        imageMatrix = transform
    }

    private fun keepInBounds() {
        val content = drawable ?: return
        transform.getValues(values)
        val scaledWidth = content.intrinsicWidth * values[Matrix.MSCALE_X]
        val scaledHeight = content.intrinsicHeight * values[Matrix.MSCALE_Y]
        val left = values[Matrix.MTRANS_X]
        val top = values[Matrix.MTRANS_Y]
        val boundedLeft = if (scaledWidth <= width) (width - scaledWidth) / 2f
            else left.coerceIn(width - scaledWidth, 0f)
        val boundedTop = if (scaledHeight <= height) (height - scaledHeight) / 2f
            else top.coerceIn(height - scaledHeight, 0f)
        transform.postTranslate(boundedLeft - left, boundedTop - top)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress && zoom > 1f) {
                    transform.postTranslate(event.x - lastX, event.y - lastY)
                    keepInBounds()
                    imageMatrix = transform
                }
                if (event.pointerCount == 1) {
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
            }
        }
        return true
    }
}
