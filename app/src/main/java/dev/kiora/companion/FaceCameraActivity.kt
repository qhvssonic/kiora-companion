@file:Suppress("DEPRECATION")
package dev.kiora.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import dev.kiora.face.onnx.InsightFacePort

/** Explicit enrollment capture, or bounded local preview scanning for manual queries. */
class FaceCameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var camera: Camera? = null
    private var resumed = false
    private var capturing = false
    private var facing = Camera.CameraInfo.CAMERA_FACING_FRONT
    private lateinit var texture: TextureView
    private lateinit var preview: FrameLayout
    private lateinit var note: TextView
    private lateinit var shutter: TextView
    private var overlay: FaceScanOverlay? = null
    private var scanWork: Job? = null
    private var frames: Channel<ScanFrame>? = null
    private val scanMode by lazy { intent.getStringExtra(SCAN_MODE)?.takeIf { it in listOf("identify", "photos") } }
    private data class ScanFrame(val bytes: ByteArray, val width: Int, val height: Int, val rotation: Int, val timeMs: Long)
    private var savedCapture = false
    private val file by lazy { File(cacheDir, "captures/face-${UUID.randomUUID()}.jpg") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(62)))
        header.addView(control("‹") { finish() }.apply { textSize = 35f; contentDescription = "返回" },
            LinearLayout.LayoutParams(dp(38), -1))
        header.addView(TextView(this).apply {
            text = if (scanMode == null) "采集人脸" else "扫描人脸"
            textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(INK); setPadding(dp(6), 0, 0, 0)
        })
        note = TextView(this).apply {
            text = "请正对镜头，保持光线充足"; textSize = 14f; setTextColor(0xFF777773.toInt())
            gravity = Gravity.CENTER; setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        root.addView(note)
        preview = FrameLayout(this).apply { background = rounded(Color.BLACK, 18); clipToOutline = true }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = dp(20); rightMargin = dp(20) })
        texture = TextureView(this).apply { surfaceTextureListener = this@FaceCameraActivity }
        preview.addView(texture, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        if (scanMode != null) {
            overlay = FaceScanOverlay(this).also { preview.addView(it, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)) }
        }
        val actions = FrameLayout(this)
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(108)))
        shutter = object : TextView(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                paint.color = INK; paint.style = Paint.Style.FILL
                canvas.drawCircle(width/2f, height/2f, dp(32).toFloat(), paint)
                paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
                canvas.drawCircle(width/2f, height/2f, dp(23).toFloat(), paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(width/2f, height/2f, dp(19).toFloat(), paint)
            }
        }.apply { contentDescription = "拍照"; setOnClickListener { capture() } }
        if (scanMode == null) actions.addView(shutter, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
        else {
            actions.addView(TextView(this).apply {
                text = "对准人脸，自动查询"; textSize = 13f; setTextColor(0xFF777773.toInt()); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER))
            actions.addView(control("取消") { finish() },
                FrameLayout.LayoutParams(dp(60), dp(44), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(20) })
        }
        actions.addView(control("切换") {
            if (!capturing) {
                releaseCamera()
                facing = if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) Camera.CameraInfo.CAMERA_FACING_BACK else Camera.CameraInfo.CAMERA_FACING_FRONT
                openCamera()
            }
        }.apply { contentDescription = "切换摄像头"; background = rounded(0xFFF1F3F0.toInt(), 16) },
            FrameLayout.LayoutParams(dp(64), dp(44), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(28) })
        setContentView(root)
    }
    override fun onResume() {
        super.onResume(); resumed = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (texture.isAvailable) openCamera()
    }
    override fun onPause() {
        resumed = false; releaseCamera()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onDestroy() {
        val running = scope.coroutineContext[Job]
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            running?.join()
            if (!savedCapture && file.isFile) file.delete()
        }
        super.onDestroy()
    }
    private fun openCamera() {
        if (!resumed || camera != null || !texture.isAvailable || capturing) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            note.text = "相机权限已关闭，请返回后重新授权"; return
        }
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull {
                Camera.CameraInfo().also { info -> Camera.getCameraInfo(it, info) }.facing == facing
            } ?: error("设备没有此方向的摄像头")
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
            val opened = Camera.open(id)
            camera = opened
            val params = opened.parameters
            val size = params.supportedPreviewSizes.filter {
                it.width <= (if (scanMode == null) 1920 else 1280) && it.height <= (if (scanMode == null) 1080 else 720)
            }
                .maxByOrNull { it.width*it.height } ?: params.supportedPreviewSizes.minBy { it.width*it.height }
            val picture = params.supportedPictureSizes.filter { it.width*it.height <= 5_000_000 }
                .maxByOrNull { it.width*it.height } ?: params.supportedPictureSizes.minBy { it.width*it.height }
            params.setPreviewSize(size.width, size.height); params.setPictureSize(picture.width, picture.height)
            if (scanMode != null) {
                check(ImageFormat.NV21 in params.supportedPreviewFormats) { "当前相机不支持预览扫描，请返回选择照片" }
                params.previewFormat = ImageFormat.NV21
            }
            params.jpegQuality = 95
            params.setRotation(info.orientation)
            if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in params.supportedFocusModes)
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            opened.parameters = params
            val angle = if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360-info.orientation)%360 else info.orientation
            opened.setDisplayOrientation(angle)
            preview.post {
                if (camera !== opened) return@post
                val ratio = if (angle % 180 == 90) size.height.toFloat()/size.width else size.width.toFloat()/size.height
                val w = minOf(preview.width, (preview.height*ratio).toInt())
                texture.layoutParams = FrameLayout.LayoutParams(w, (w/ratio).toInt(), Gravity.CENTER)
                overlay?.layoutParams = FrameLayout.LayoutParams(w, (w/ratio).toInt(), Gravity.CENTER)
            }
            opened.setPreviewTexture(texture.surfaceTexture)
            opened.startPreview()
            if (scanMode != null) startScan(opened, size.width, size.height, info.orientation)
            else note.text = "请正对镜头，保持光线充足"
        } catch (error: Exception) { releaseCamera(); note.text = "无法打开相机：${error.message}" }
    }
    private fun startScan(active: Camera, width: Int, height: Int, rotation: Int) {
        val channel = Channel<ScanFrame>(1)
        frames = channel
        var busy = false
        var accepting = true
        var nextFrameAt = 0L
        // Only one copied frame may be queued or under inference. All other callbacks are returned immediately.
        active.setPreviewCallbackWithBuffer { bytes, source ->
            if (source !== camera || !resumed || capturing) return@setPreviewCallbackWithBuffer
            val now = SystemClock.elapsedRealtime()
            if (!busy && accepting && now >= nextFrameAt) {
                busy = true; nextFrameAt = now+350
                if (!channel.trySend(ScanFrame(bytes.copyOf(), width, height, rotation, now)).isSuccess) busy = false
            }
            if (source === camera) source.addCallbackBuffer(bytes)
        }
        repeat(2) { active.addCallbackBuffer(ByteArray(width*height*3/2)) }
        val previous = scanWork
        scanWork = scope.launch {
            var engine: InsightFacePort? = null
            try {
                // A camera switch waits for the previous inference/session cleanup before loading another engine.
                // Preserve the cleanup chain even when another quick camera switch cancels this waiter.
                withContext(NonCancellable) { previous?.join() }
                currentCoroutineContext().ensureActive()
                val model = FaceModelFiles(this@FaceCameraActivity).current() ?: error("请先导入人脸模型")
                engine = InsightFacePort(model.directory, model.id)
                note.text = "正在准备扫描…"
                engine.prepare()
                overlay?.start()
                val gate = FaceScanGate()
                for (frame in channel) {
                    try {
                        val input = withContext(Dispatchers.Default) {
                            FacePreviewFrame.decodeNv21(frame.bytes, frame.width, frame.height, frame.rotation)
                        }
                        val observations = engine.analyze(input)
                        currentCoroutineContext().ensureActive()
                        if (!resumed || camera !== active) break
                        val state = gate.update(observations, input.width, input.height, frame.timeMs)
                        note.text = state.message; overlay?.updateProgress(state.progress)
                        if (state.ready != null) {
                            capturing = true
                            setResult(RESULT_OK, Intent()
                                .putExtra(SCAN_MODEL, state.ready.embedding.modelId)
                                .putExtra(SCAN_FEATURES, state.ready.embedding.values()))
                            finish()
                            break
                        }
                    } finally { busy = false }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (resumed && camera === active) {
                    note.text = "扫描未完成：${error.message ?: "请返回后重试"}"
                    overlay?.stop()
                }
            } finally {
                accepting = false
                channel.close()
                withContext(NonCancellable) { engine?.close() }
            }
        }
    }
    private fun capture() {
        val active = camera ?: return
        if (capturing) return
        capturing = true; shutter.isEnabled = false
        try {
            active.takePicture(null, null, Camera.PictureCallback { bytes, _ ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                        savedCapture = true
                        setResult(RESULT_OK, Intent().putExtra("capture_name", file.name))
                        finish()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        note.text = "拍照失败：${error.message}"
                        capturing = false; shutter.isEnabled = true
                        releaseCamera(); openCamera()
                    }
                }
            })
        } catch (error: Exception) {
            capturing = false; shutter.isEnabled = true; note.text = "拍照失败：${error.message}"
            releaseCamera(); openCamera()
        }
    }
    private fun releaseCamera() {
        frames?.close(); frames = null; scanWork?.cancel(); overlay?.stop()
        camera?.let {
            runCatching { it.setPreviewCallbackWithBuffer(null) }
            runCatching { it.stopPreview() }; it.release()
        }
        camera = null
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCamera()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { releaseCamera(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    private fun control(title: String, action: () -> Unit) = TextView(this).apply {
        text = title; textSize = 14f; setTextColor(INK); gravity = Gravity.CENTER
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    companion object {
        internal const val SCAN_MODE = "face_scan_mode"
        internal const val SCAN_MODEL = "face_scan_model"
        internal const val SCAN_FEATURES = "face_scan_features"
        private const val BG = 0xFFFAFAF8.toInt()
        private const val INK = 0xFF20201F.toInt()
    }
}
