package dev.kiora.companion

import android.animation.ObjectAnimator
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import dev.kiora.document.OcrPage
import dev.kiora.document.paddle.PaddleDocumentOcr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Small offline OCR workbench. The source and corrections survive app restarts. */
class DocumentScanActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { DocumentDraftStore(this) }
    private var currentId: String? = null
    private var currentPageId: String? = null
    private var pendingCameraId: String? = null
    private var pendingCameraPageId: String? = null
    private var pageDisplayed = false
    private var recognizing = false
    private lateinit var preview: ImageView
    private lateinit var emptyPreview: TextView
    private lateinit var scanLine: View
    private var scanAnimator: ObjectAnimator? = null
    private lateinit var pageScroll: HorizontalScrollView
    private lateinit var pageActions: LinearLayout
    private lateinit var pageStrip: LinearLayout
    private lateinit var pageCaption: TextView
    private lateinit var captureButton: Button
    private lateinit var newDocumentButton: Button
    private lateinit var moveEarlierButton: Button
    private lateinit var moveLaterButton: Button
    private lateinit var deletePageButton: Button
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var drafts: LinearLayout
    private lateinit var draftsHeader: TextView
    private var draftsExpanded = false
    private lateinit var recognizeButton: Button
    private lateinit var saveButton: Button
    private lateinit var useButton: Button
    private lateinit var deleteButton: Button
    private data class Recognized(val page: OcrPage?, val result: DocumentDraftStore.EngineResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentId = savedInstanceState?.getString("current_id")
        currentPageId = savedInstanceState?.getString("current_page_id")
        pendingCameraId = savedInstanceState?.getString("pending_camera_id")
        pendingCameraPageId = savedInstanceState?.getString("pending_camera_page_id")
        window.statusBarColor = 0xFFFAFAF8.toInt()
        window.navigationBarColor = 0xFFFAFAF8.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
        currentId?.let { openDraft(it, currentPageId) }
        showDrafts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentId?.let { outState.putString("current_id", it) }
        currentPageId?.let { outState.putString("current_page_id", it) }
        pendingCameraId?.let { outState.putString("pending_camera_id", it) }
        pendingCameraPageId?.let { outState.putString("pending_camera_page_id", it) }
        saveCurrent()
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        saveCurrent()
        super.onPause()
    }

    override fun onDestroy() {
        if (::editor.isInitialized && currentId != null) saveCurrent()
        scanAnimator?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(62)))
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 35f
            gravity = Gravity.CENTER
            setTextColor(INK)
            contentDescription = "返回聊天"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), -1))
        header.addView(TextView(this).apply {
            text = "文档扫描"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        newDocumentButton = styledButton("新建文档", false) {
            if (recognizing) return@styledButton
            saveCurrent()
            currentId = null
            currentPageId = null
            pageDisplayed = false
            editor.text.clear()
            preview.setImageDrawable(null)
            preview.visibility = View.GONE
            emptyPreview.visibility = View.VISIBLE
            status.text = "已准备新文档，拍照或导入第一页"
            refreshPages()
            updateActions()
        }
        newDocumentButton.textSize = 12f
        header.addView(newDocumentButton, LinearLayout.LayoutParams(dp(94), dp(38)))
        root.addView(TextView(this).apply {
            text = "CAPTURE  /  OCR  /  REVIEW"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(ACCENT)
            setPadding(dp(20), dp(4), dp(20), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "逐页采集 · 本机识别 · 校对后交给 Kiora"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(dp(20), 0, dp(20), dp(14))
        })
        val captureActions = LinearLayout(this).apply { setPadding(dp(15), 0, dp(15), 0) }
        root.addView(captureActions, LinearLayout.LayoutParams(-1, dp(52)))
        captureButton = styledButton("＋ 拍摄页面", true) { takePhoto() }
        captureActions.addView(captureButton,
            LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(5) })
        captureActions.addView(styledButton("导入图片", false) { pickImage() },
            LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
        status = TextView(this).apply {
            text = "原图与校对内容只保存在本机应用目录"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(16))
        }
        scroll.addView(content)
        pageCaption = TextView(this).apply {
            text = "新文档 · 等待第一页"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(pageCaption)
        pageScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        pageStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        pageScroll.addView(pageStrip)
        content.addView(pageScroll, LinearLayout.LayoutParams(-1, dp(48)))
        pageActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        content.addView(pageActions, LinearLayout.LayoutParams(-1, dp(44)).apply {
            bottomMargin = dp(8)
        })
        moveEarlierButton = pageAction("← 前移") { movePage(-1) }
        moveLaterButton = pageAction("后移 →") { movePage(1) }
        deletePageButton = pageAction("删除本页", destructive = true) { confirmDeletePage() }
        pageActions.addView(moveEarlierButton, LinearLayout.LayoutParams(0, -1, 1f))
        pageActions.addView(moveLaterButton, LinearLayout.LayoutParams(0, -1, 1f))
        pageActions.addView(deletePageButton, LinearLayout.LayoutParams(0, -1, 1f))
        val previewFrame = FrameLayout(this).apply {
            background = rounded(DARK, 20)
            clipToOutline = true
            contentDescription = "查看扫描原图，支持双指缩放"
            setOnClickListener { showCurrentPagePreview() }
        }
        content.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(232)).apply {
            bottomMargin = dp(10)
        })
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "扫描原图"
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        emptyPreview = TextView(this).apply {
            text = "＋\n拍摄或导入页面"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFB7CDD0.toInt())
        }
        previewFrame.addView(emptyPreview, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addView(object : View(this) {
            private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF59C6C8.toInt()
                strokeWidth = dp(2).toFloat()
                style = Paint.Style.STROKE
            }
            init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            override fun onDraw(canvas: Canvas) {
                val m = dp(17).toFloat()
                val arm = dp(22).toFloat()
                val right = width - m
                val bottom = height - m
                canvas.drawLine(m, m + arm, m, m, marker)
                canvas.drawLine(m, m, m + arm, m, marker)
                canvas.drawLine(right - arm, m, right, m, marker)
                canvas.drawLine(right, m, right, m + arm, marker)
                canvas.drawLine(m, bottom - arm, m, bottom, marker)
                canvas.drawLine(m, bottom, m + arm, bottom, marker)
                canvas.drawLine(right - arm, bottom, right, bottom, marker)
                canvas.drawLine(right, bottom - arm, right, bottom, marker)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        scanLine = View(this).apply {
            setBackgroundColor(0xFF59C6C8.toInt())
            alpha = 0.75f
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewFrame.addView(scanLine, FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
        })
        previewFrame.addView(TextView(this).apply {
            text = "SOURCE  /  LOCAL"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(0xFFB7CDD0.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xBB142D33.toInt(), 10)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            setMargins(dp(10), dp(10), 0, 0)
        })
        previewFrame.addView(TextView(this).apply {
            text = "PP-OCRv6  ·  ON DEVICE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(0xFFB7CDD0.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xBB142D33.toInt(), 10)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply {
            setMargins(0, 0, dp(10), dp(10))
        })
        previewFrame.addView(TextView(this).apply {
            text = "点击放大"
            textSize = 11f
            setTextColor(0xFFE0F6F6.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xBB142D33.toInt(), 10)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
            setMargins(dp(10), 0, 0, dp(10))
        })
        content.addView(TextView(this).apply {
            text = "02  /  逐页校对"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(12), 0, dp(9))
        })
        editor = EditText(this).apply {
            hint = "识别文字将显示在这里，可逐页修改"
            textSize = 15f
            gravity = Gravity.TOP
            minLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = rounded(Color.WHITE, 18, BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        content.addView(editor, LinearLayout.LayoutParams(-1, -2))
        draftsHeader = TextView(this).apply {
            text = "本机草稿  ▾"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(19), 0, dp(12))
            setOnClickListener {
                draftsExpanded = !draftsExpanded
                text = if (draftsExpanded) "本机草稿  ▴" else "本机草稿  ▾"
                drafts.visibility = if (draftsExpanded) View.VISIBLE else View.GONE
            }
        }
        content.addView(draftsHeader)
        drafts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(drafts)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(8), dp(15), dp(12))
            setBackgroundColor(BG)
        }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        val firstActionRow = LinearLayout(this)
        val secondActionRow = LinearLayout(this)
        actions.addView(firstActionRow, LinearLayout.LayoutParams(-1, -2))
        actions.addView(secondActionRow, LinearLayout.LayoutParams(-1, -2))
        recognizeButton = styledButton("重新识别", false) { confirmRecognize() }
        addActionButton(firstActionRow, recognizeButton)
        saveButton = styledButton("保存草稿", false) {
            saveCurrent()
            status.text = "草稿已保存在本机"
            refreshPages()
            showDrafts()
        }
        addActionButton(firstActionRow, saveButton)
        useButton = styledButton("用于聊天", true) { useInChat() }
        addActionButton(secondActionRow, useButton)
        deleteButton = styledButton("删除草稿", false, destructive = true) { confirmDelete() }
        addActionButton(secondActionRow, deleteButton)
        refreshPages()
    }

    private fun addActionButton(row: LinearLayout, button: Button) {
        button.gravity = Gravity.CENTER
        button.isSingleLine = true
        button.textSize = 15f
        row.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(5), dp(3), dp(5), dp(3))
        })
    }

    private fun styledButton(title: String, primary: Boolean, destructive: Boolean = false,
                             action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else if (destructive) RED else INK)
        backgroundTintList = null
        background = rounded(if (primary) DARK else Color.WHITE, 15,
            if (primary) null else BORDER)
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        setOnClickListener { action() }
    }

    private fun pageAction(title: String, destructive: Boolean = false, action: () -> Unit) =
        Button(this).apply {
            text = title
            textSize = 12f
            isAllCaps = false
            setTextColor(if (destructive) RED else ACCENT)
            backgroundTintList = null
            background = rounded(Color.WHITE, 10, BORDER)
            stateListAnimator = null
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun takePhoto() {
        if (recognizing) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            return
        }
        saveCurrent()
        val existingId = currentId
        val page = try {
            if (existingId == null) store.newCameraDraft().pages.first()
            else store.newCameraPage(existingId)
        } catch (error: Exception) {
            status.text = "无法创建页面：${error.message ?: "请重试"}"
            return
        }
        val id = existingId ?: page.source.parentFile!!.name
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", page.source)
            pendingCameraId = id
            pendingCameraPageId = page.id
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newUri(contentResolver, "扫描原图", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, CAPTURE)
        } catch (error: Exception) {
            pendingCameraId = null
            pendingCameraPageId = null
            if (existingId == null) store.discardEmptyCameraDraft(id)
            else store.discardPendingCameraPage(id, page.id)
            status.text = "无法打开相机：${error.message ?: "请检查相机应用"}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto()
            else status.text = "未获得相机权限，无法拍照扫描"
        }
    }

    private fun pickImage() {
        if (recognizing) return
        saveCurrent()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, PICK)
    }

    @Deprecated("Uses platform result API without an Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE) {
            val id = pendingCameraId
            val pageId = pendingCameraPageId
            pendingCameraId = null
            pendingCameraPageId = null
            val pendingSource = if (id != null && pageId != null) {
                if (pageId == "legacy") store.load(id)?.source
                else java.io.File(filesDir, "document-drafts/$id/pages/$pageId/source-$pageId.jpg")
            } else null
            if (resultCode == RESULT_OK && id != null && pageId != null &&
                (pendingSource?.length() ?: 0L) > 0L) {
                try {
                    if (pageId != "legacy") store.commitCameraPage(id, pageId)
                    openDraft(id, pageId)
                    recognizeCurrent()
                } catch (error: Exception) {
                    status.text = "保存拍摄页面失败：${error.message ?: "请重试"}"
                }
            } else {
                if (id != null && pageId != null) {
                    if (pageId == "legacy") store.discardEmptyCameraDraft(id)
                    else store.discardPendingCameraPage(id, pageId)
                }
                if (resultCode == RESULT_OK) status.text = "拍照未生成图片，请重试"
            }
            return
        }
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uris = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
        }.distinct()
        if (uris.isEmpty()) return
        scope.launch {
            try {
                val previousId = currentId
                val imported = withContext(Dispatchers.IO) {
                    var id = previousId
                    val added = mutableListOf<String>()
                    var failed = 0
                    uris.forEach { uri ->
                        runCatching {
                            if (id == null) {
                                val draft = store.importImage(contentResolver, uri)
                                id = draft.id
                                added += draft.pages.first().id
                            } else {
                                added += store.appendImage(requireNotNull(id), contentResolver, uri).id
                            }
                        }.onFailure { failed++ }
                    }
                    Triple(id, added, failed)
                }
                val id = imported.first
                if (id == null || imported.second.isEmpty()) {
                    status.text = "未能导入图片，请检查格式和文件大小"
                    return@launch
                }
                openDraft(id, imported.second.last())
                recognizePages(id, imported.second)
                if (imported.third > 0) {
                    status.text = "已导入 ${imported.second.size} 页，${imported.third} 页未能导入"
                }
            } catch (error: Exception) {
                status.text = "无法导入图片：${error.message ?: "请重试"}"
                currentId?.let { openDraft(it, currentPageId) }
            }
        }
    }

    private fun openDraft(id: String, pageId: String? = null) {
        if (recognizing) return
        if (pageDisplayed) saveCurrent()
        val draft = store.load(id) ?: return
        currentId = id
        val page = draft.pages.firstOrNull { it.id == pageId } ?: draft.pages.first()
        currentPageId = page.id
        editor.setText(page.text)
        pageDisplayed = true
        val lastResult = store.loadPaddleResult(id, page.id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(page.source.absolutePath, bounds)
        val sample = generateSequence(1) { it * 2 }.first { divisor ->
            bounds.outWidth / divisor <= 1200 && bounds.outHeight / divisor <= 1200
        }
        preview.setImageBitmap(BitmapFactory.decodeFile(page.source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        preview.visibility = View.VISIBLE
        emptyPreview.visibility = View.GONE
        status.text = when {
            page.text.isNotBlank() -> "第 ${draft.pages.indexOf(page) + 1} 页已恢复，可继续校对"
            lastResult?.error != null -> "上次识别失败：${lastResult.error.take(100)}"
            else -> "原图已保存，准备识别…"
        }
        refreshPages()
        updateActions()
        showDrafts()
    }

    private fun showCurrentPagePreview() {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        val source = store.load(id)?.pages?.firstOrNull { it.id == pageId }?.source ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", source)
            startActivity(Intent(this, ImagePreviewActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { status.text = "无法打开原图：${it.message ?: "请重试"}" }
    }

    private fun confirmRecognize() {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        if (store.loadPaddleResult(id, pageId) == null &&
            store.load(id)?.pages?.firstOrNull { it.id == pageId }?.recognizedText.isNullOrBlank()) recognizeCurrent()
        else SettingsSheet.showConfirmation(this, "重新识别？",
            "PP-OCRv6 将重新识别当前页，并替换这一页修改过的文字。其他页面不受影响。",
            "重新识别", "扫描文档") { recognizeCurrent() }
    }

    private fun recognizeCurrent() {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        recognizePages(id, listOf(pageId))
    }

    private fun recognizePages(id: String, pageIds: List<String>) {
        if (recognizing || pageIds.isEmpty()) return
        recognizing = true
        scanLine.visibility = View.VISIBLE
        scanAnimator = ObjectAnimator.ofFloat(scanLine, View.TRANSLATION_Y, 0f, dp(228).toFloat())
            .apply {
                duration = 1600L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        updateActions()
        status.text = "正在本机识别 1 / ${pageIds.size} 页…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val engine = PaddleDocumentOcr.create(applicationContext)
                    try {
                        pageIds.forEachIndexed { index, pageId ->
                            val source = store.load(id)?.pages?.firstOrNull { it.id == pageId }?.source
                                ?: return@forEachIndexed
                            withContext(Dispatchers.Main) {
                                status.text = "正在本机识别 ${index + 1} / ${pageIds.size} 页…"
                            }
                            val started = SystemClock.elapsedRealtime()
                            val recognized = try {
                                require(source.length() <= MAX_SOURCE_BYTES) { "图片超过 20 MiB" }
                                val page = engine.recognize(source.readBytes())
                                Recognized(page, DocumentDraftStore.EngineResult(
                                    page.text, page.lines.size,
                                    SystemClock.elapsedRealtime() - started, engine.coldLoadTimeMs))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Log.e("KioraDocumentScan", "PP-OCRv6 failed on page ${index + 1}", error)
                                Recognized(null, DocumentDraftStore.EngineResult(
                                    elapsedMs = SystemClock.elapsedRealtime() - started,
                                    error = paddleErrorMessage(error)))
                            }
                            store.savePaddleResult(id, pageId, recognized.result)
                            recognized.page?.takeIf { it.text.isNotBlank() }?.let {
                                store.saveRecognition(id, pageId, it)
                            }
                        }
                    } finally {
                        engine.release()
                    }
                }
                val active = store.load(id)?.pages?.firstOrNull { it.id == currentPageId }
                active?.let { editor.setText(it.text) }
                val result = currentPageId?.let { store.loadPaddleResult(id, it) }
                status.text = when {
                    result?.error != null -> "当前页识别失败：${result.error.take(100)}"
                    active?.text.isNullOrBlank() -> "未识别到文字，请逐页检查或手动输入"
                    else -> "已识别 ${pageIds.size} 页 · 当前页 ${result?.lineCount ?: 0} 行，请逐页校对"
                }
                refreshPages()
                showDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "识别失败：${error.message ?: "请重试"}"
            } catch (error: LinkageError) {
                Log.e("KioraDocumentScan", "PP-OCRv6 native loading failed", error)
                status.text = "识别组件加载失败：${paddleErrorMessage(error)}"
            } finally {
                recognizing = false
                scanAnimator?.cancel()
                scanAnimator = null
                scanLine.translationY = 0f
                scanLine.visibility = View.GONE
                updateActions()
            }
        }
    }

    private fun paddleErrorMessage(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val detail = root.message?.take(240).orEmpty()
        return if (root === error || detail.isBlank()) error.message ?: error.javaClass.simpleName
            else "${error.message ?: error.javaClass.simpleName}: $detail"
    }

    private fun saveCurrent() {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        if (!::editor.isInitialized || !pageDisplayed || recognizing) return
        runCatching { store.saveEdited(id, pageId, editor.text.toString()) }
            .onFailure { if (::status.isInitialized) status.text = "保存失败：${it.message ?: "请重试"}" }
    }

    private fun useInChat() {
        val id = currentId ?: return
        if (recognizing) return
        saveCurrent()
        val pages = store.load(id)?.pages.orEmpty()
        val blank = pages.indexOfFirst { it.text.isBlank() }
        if (blank >= 0) {
            openDraft(id, pages[blank].id)
            status.text = "第 ${blank + 1} 页没有校对文字，请先完成这一页"
            return
        }
        val text = if (pages.size == 1) pages.first().text.trim()
            else pages.mapIndexed { index, page ->
                "## 第 ${index + 1} 页\n\n${page.text.trim()}"
            }.joinToString("\n\n")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DRAFT_ID, id).putExtra(EXTRA_TEXT, text))
        finish()
    }

    private fun movePage(offset: Int) {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        if (recognizing) return
        saveCurrent()
        runCatching { store.movePage(id, pageId, offset) }
            .onSuccess { openDraft(id, pageId); status.text = "页面顺序已调整" }
            .onFailure { status.text = it.message ?: "无法移动页面" }
    }

    private fun confirmDeletePage() {
        val id = currentId ?: return
        val pageId = currentPageId ?: return
        if (recognizing) return
        val pages = store.load(id)?.pages.orEmpty()
        val index = pages.indexOfFirst { it.id == pageId }
        if (index < 0) return
        SettingsSheet.showConfirmation(this, "删除第 ${index + 1} 页？",
            "这将删除该页原图和校对文字，其他页面保留。", "删除本页", "扫描文档") {
            try {
                store.deletePage(id, pageId)
                if (pages.size == 1) clearCurrent("文档已删除")
                else {
                    val next = if (index + 1 < pages.size) pages[index + 1] else pages[index - 1]
                    pageDisplayed = false
                    openDraft(id, next.id)
                }
                showDrafts()
            } catch (error: Exception) {
                status.text = "删除页面失败：${error.message ?: "请重试"}"
            }
        }
    }

    private fun refreshPages() {
        if (!::pageStrip.isInitialized) return
        pageStrip.removeAllViews()
        val pages = currentId?.let(store::load)?.pages.orEmpty()
        val currentIndex = pages.indexOfFirst { it.id == currentPageId }
        pageScroll.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
        pageActions.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE
        pageCaption.text = if (pages.isEmpty()) "新文档 · 等待第一页"
            else "01  /  页面 ${currentIndex + 1} / ${pages.size}"
        pages.forEachIndexed { index, page ->
            val selected = page.id == currentPageId
            pageStrip.addView(TextView(this).apply {
                text = "P${index + 1}  ${if (page.text.isBlank()) "待校对" else "✓"}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (selected) Color.WHITE else INK)
                background = rounded(if (selected) ACCENT else Color.WHITE, 12,
                    if (selected) null else BORDER)
                setOnClickListener {
                    if (!recognizing) currentId?.let { id -> openDraft(id, page.id) }
                }
            }, LinearLayout.LayoutParams(dp(91), dp(38)).apply { rightMargin = dp(7) })
        }
        updateActions()
    }

    private fun clearCurrent(message: String) {
        currentId = null
        currentPageId = null
        pageDisplayed = false
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        emptyPreview.visibility = View.VISIBLE
        editor.text.clear()
        status.text = message
        refreshPages()
        updateActions()
    }

    private fun confirmDelete() {
        val id = currentId ?: return
        if (recognizing) return
        SettingsSheet.showConfirmation(this, "删除扫描草稿？",
            "这将删除本机保存的原图、识别结果和修改文字。", "删除", "扫描文档") {
            try {
                store.delete(id)
                clearCurrent("草稿已删除")
                showDrafts()
            } catch (error: Exception) {
                status.text = "删除失败：${error.message ?: "请重试"}"
            }
        }
    }

    private fun showDrafts() {
        if (!::drafts.isInitialized) return
        drafts.removeAllViews()
        store.list().take(12).forEach { draft ->
            val label = draft.pages.firstOrNull { it.text.isNotBlank() }?.text
                ?.lineSequence()?.firstOrNull()?.take(28).orEmpty().ifBlank { "待识别文档" }
            drafts.addView(TextView(this).apply {
                text = "${draft.pages.size} 页  ·  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.updatedAt))}\n$label"
                textSize = 13f
                setTextColor(INK)
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(Color.WHITE, 15, BORDER)
                setOnClickListener { openDraft(draft.id) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun updateActions() {
        if (!::recognizeButton.isInitialized) return
        val pages = currentId?.let(store::load)?.pages.orEmpty()
        val index = pages.indexOfFirst { it.id == currentPageId }
        val enabled = pages.isNotEmpty() && !recognizing
        recognizeButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        useButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
        moveEarlierButton.isEnabled = enabled && index > 0
        moveLaterButton.isEnabled = enabled && index >= 0 && index < pages.lastIndex
        deletePageButton.isEnabled = enabled
        captureButton.isEnabled = !recognizing && pages.size < DocumentDraftStore.MAX_PAGES
        newDocumentButton.isEnabled = !recognizing
        captureButton.text = if (pages.isEmpty()) "＋ 拍摄第一页" else "＋ 拍摄下一页"
        listOf(recognizeButton, saveButton, useButton, deleteButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
        listOf(moveEarlierButton, moveLaterButton, deletePageButton, captureButton, newDocumentButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val BG = 0xFFFAFAF8.toInt()
        private const val INK = 0xFF20201F.toInt()
        private const val DARK = 0xFF142D33.toInt()
        private const val ACCENT = 0xFF087C83.toInt()
        private const val MUTED = 0xFF777773.toInt()
        private const val BORDER = 0xFFE5E7E3.toInt()
        private const val RED = 0xFFB4473D.toInt()
        const val EXTRA_DRAFT_ID = "document_draft_id"
        const val EXTRA_TEXT = "document_text"
        private const val CAPTURE = 1
        private const val PICK = 2
        private const val CAMERA_PERMISSION = 3
        private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
    }
}
