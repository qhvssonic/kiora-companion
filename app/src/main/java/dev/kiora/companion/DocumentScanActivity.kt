package dev.kiora.companion

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private var pendingCameraId: String? = null
    private var recognizing = false
    private lateinit var preview: ImageView
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var drafts: LinearLayout
    private lateinit var recognizeButton: Button
    private lateinit var saveButton: Button
    private lateinit var useButton: Button
    private lateinit var deleteButton: Button
    private data class Recognized(val page: OcrPage?, val result: DocumentDraftStore.EngineResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentId = savedInstanceState?.getString("current_id")
        pendingCameraId = savedInstanceState?.getString("pending_camera_id")
        window.statusBarColor = 0xFFFAFAF8.toInt()
        window.navigationBarColor = 0xFFFAFAF8.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
        currentId?.let(::openDraft)
        showDrafts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentId?.let { outState.putString("current_id", it) }
        pendingCameraId?.let { outState.putString("pending_camera_id", it) }
        saveCurrent()
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        saveCurrent()
        super.onPause()
    }

    override fun onDestroy() {
        if (::editor.isInitialized && currentId != null) saveCurrent()
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
            text = "扫描文档"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(TextView(this).apply {
            text = "拍摄或选择单页印刷文档，离线识别后核对原文。"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(dp(20), dp(5), dp(20), dp(15))
        })
        val captureActions = LinearLayout(this).apply { setPadding(dp(15), 0, dp(15), 0) }
        root.addView(captureActions, LinearLayout.LayoutParams(-1, dp(48)))
        captureActions.addView(styledButton("拍照", false) { takePhoto() },
            LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(5) })
        captureActions.addView(styledButton("选图片", false) { pickImage() },
            LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(5) })
        status = TextView(this).apply {
            text = "图片和文字保存在本机应用私有目录"
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
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "扫描原图"
            background = rounded(Color.WHITE, 18, BORDER)
            clipToOutline = true
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply { bottomMargin = dp(10) })
        content.addView(TextView(this).apply {
            text = "识别原文（可修改）"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(12), 0, dp(9))
        })
        editor = EditText(this).apply {
            hint = "拍照或选择图片后，识别出的原文会显示在这里"
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
        content.addView(TextView(this).apply {
            text = "本机草稿"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(0, dp(19), 0, dp(9))
        })
        drafts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        saveButton = styledButton("保存草稿", false) { saveCurrent(); status.text = "草稿已保存在本机"; showDrafts() }
        addActionButton(firstActionRow, saveButton)
        useButton = styledButton("用于聊天", true) { useInChat() }
        addActionButton(secondActionRow, useButton)
        deleteButton = styledButton("删除草稿", false, destructive = true) { confirmDelete() }
        addActionButton(secondActionRow, deleteButton)
        updateActions()
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
        background = rounded(if (primary) INK else Color.WHITE, 15,
            if (primary) null else BORDER)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun takePhoto() {
        if (recognizing) return
        saveCurrent()
        val draft = store.newCameraDraft()
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", draft.source)
            pendingCameraId = draft.id
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newUri(contentResolver, "扫描原图", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, CAPTURE)
        } catch (error: Exception) {
            pendingCameraId = null
            store.discardEmptyCameraDraft(draft.id)
            status.text = "无法打开相机：${error.message ?: "请检查相机应用"}"
        }
    }

    private fun pickImage() {
        if (recognizing) return
        saveCurrent()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, PICK)
    }

    @Deprecated("Uses platform result API without an Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE) {
            val id = pendingCameraId
            pendingCameraId = null
            if (resultCode == RESULT_OK && id != null && (store.load(id)?.source?.length() ?: 0L) > 0L) {
                openDraft(id)
                recognizeCurrent()
            } else {
                id?.let(store::discardEmptyCameraDraft)
                if (resultCode == RESULT_OK) status.text = "拍照未生成图片，请重试"
            }
            return
        }
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        scope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { store.importImage(contentResolver, uri) }
                openDraft(draft.id)
                recognizeCurrent()
            } catch (error: Exception) {
                status.text = "无法导入图片：${error.message ?: "请重试"}"
            }
        }
    }

    private fun openDraft(id: String) {
        if (recognizing) return
        val draft = store.load(id) ?: return
        if (currentId != id) saveCurrent()
        currentId = id
        editor.setText(draft.text)
        val lastResult = store.loadPaddleResult(id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(draft.source.absolutePath, bounds)
        val sample = generateSequence(1) { it * 2 }.first { divisor ->
            bounds.outWidth / divisor <= 1200 && bounds.outHeight / divisor <= 1200
        }
        preview.setImageBitmap(BitmapFactory.decodeFile(draft.source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }))
        preview.visibility = View.VISIBLE
        status.text = when {
            draft.text.isNotBlank() -> "草稿已从本机恢复，可继续校对"
            lastResult?.error != null -> "上次识别失败：${lastResult.error.take(100)}"
            else -> "原图已保存，准备识别…"
        }
        updateActions()
        showDrafts()
    }

    private fun confirmRecognize() {
        val id = currentId ?: return
        if (store.loadPaddleResult(id) == null && store.load(id)?.recognizedText.isNullOrBlank()) recognizeCurrent()
        else SettingsSheet.showConfirmation(this, "重新识别？",
            "PP-OCRv6 将重新识别同一张原图，并替换当前修改过的文字。原图仍会保留。",
            "重新识别", "扫描文档") { recognizeCurrent() }
    }

    private fun recognizeCurrent() {
        val id = currentId ?: return
        if (recognizing) return
        val source = store.load(id)?.source ?: return
        recognizing = true
        updateActions()
        status.text = "正在本机识别原文…"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    require(source.length() <= MAX_SOURCE_BYTES) { "图片超过 20 MiB" }
                    source.readBytes()
                }
                val paddle = withContext(Dispatchers.IO) { recognizePaddle(bytes) }
                withContext(Dispatchers.IO) {
                    store.savePaddleResult(id, paddle.result)
                    paddle.page?.takeIf { it.text.isNotBlank() }?.let { store.saveRecognition(id, it) }
                }
                paddle.page?.text?.takeIf(String::isNotBlank)?.let(editor::setText)
                status.text = when {
                    paddle.page == null -> "PP-OCRv6 识别失败：${paddle.result.error?.take(100) ?: "请重试或更换图片"}"
                    paddle.page.text.isBlank() -> "未识别到文字，请重拍或手动输入"
                    else -> "已识别 ${paddle.result.lineCount} 行 · ${paddle.result.elapsedMs} ms，请校对原文"
                }
                showDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "识别失败：${error.message ?: "请重试"}"
            } finally {
                recognizing = false
                updateActions()
            }
        }
    }

    private suspend fun recognizePaddle(bytes: ByteArray): Recognized {
        var loadMs: Long? = null
        val started = SystemClock.elapsedRealtime()
        return try {
            val engine = PaddleDocumentOcr.create(applicationContext)
            loadMs = engine.coldLoadTimeMs
            try {
                val recognitionStarted = SystemClock.elapsedRealtime()
                val page = engine.recognize(bytes)
                Recognized(page, DocumentDraftStore.EngineResult(page.text, page.lines.size,
                    SystemClock.elapsedRealtime() - recognitionStarted, loadMs))
            } finally {
                engine.release()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("KioraDocumentScan", "PP-OCRv6 failed", error)
            Recognized(null, DocumentDraftStore.EngineResult(
                elapsedMs = SystemClock.elapsedRealtime() - started, loadMs = loadMs,
                error = paddleErrorMessage(error)))
        } catch (error: LinkageError) {
            Log.e("KioraDocumentScan", "PP-OCRv6 native loading failed", error)
            Recognized(null, DocumentDraftStore.EngineResult(
                elapsedMs = SystemClock.elapsedRealtime() - started, loadMs = loadMs,
                error = paddleErrorMessage(error)))
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
        if (!::editor.isInitialized || recognizing) return
        runCatching { store.saveEdited(id, editor.text.toString()) }
            .onFailure { if (::status.isInitialized) status.text = "保存失败：${it.message ?: "请重试"}" }
    }

    private fun useInChat() {
        val id = currentId ?: return
        if (recognizing) return
        val text = editor.text.toString().trim()
        if (text.isBlank()) { status.text = "请先识别或填写原文"; return }
        saveCurrent()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_DRAFT_ID, id).putExtra(EXTRA_TEXT, text))
        finish()
    }

    private fun confirmDelete() {
        val id = currentId ?: return
        if (recognizing) return
        SettingsSheet.showConfirmation(this, "删除扫描草稿？",
            "这将删除本机保存的原图、识别结果和修改文字。", "删除", "扫描文档") {
            try {
                store.delete(id)
                currentId = null
                preview.setImageDrawable(null)
                preview.visibility = View.GONE
                editor.text.clear()
                status.text = "草稿已删除"
                showDrafts()
                updateActions()
            } catch (error: Exception) {
                status.text = "删除失败：${error.message ?: "请重试"}"
            }
        }
    }

    private fun showDrafts() {
        if (!::drafts.isInitialized) return
        drafts.removeAllViews()
        store.list().take(12).forEach { draft ->
            val label = draft.text.lineSequence().firstOrNull()?.take(35).orEmpty().ifBlank { "待识别文档" }
            drafts.addView(TextView(this).apply {
                text = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.updatedAt))} · $label"
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
        val enabled = currentId != null && !recognizing
        recognizeButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        useButton.isEnabled = enabled
        deleteButton.isEnabled = enabled
        listOf(recognizeButton, saveButton, useButton, deleteButton).forEach {
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val BG = 0xFFFAFAF8.toInt()
        private const val INK = 0xFF20201F.toInt()
        private const val MUTED = 0xFF777773.toInt()
        private const val BORDER = 0xFFE5E7E3.toInt()
        private const val RED = 0xFFB4473D.toInt()
        const val EXTRA_DRAFT_ID = "document_draft_id"
        const val EXTRA_TEXT = "document_text"
        private const val CAPTURE = 1
        private const val PICK = 2
        private const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
    }
}
