package dev.kiora.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.kiora.core.ToolAuthorization
import dev.kiora.core.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings by lazy { getSharedPreferences("companion_settings", MODE_PRIVATE) }
    private val conversations by lazy { ConversationRepository(this) }
    private lateinit var agent: CompanionAgent
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var emptyState: View
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var attachmentStrip: LinearLayout
    private lateinit var attachmentContainer: View
    private lateinit var status: TextView
    private val selectedUris = mutableListOf<Uri>()
    private var streamView: TextView? = null
    private var activeConfig: CompanionConfig? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        agent = CompanionAgent(this, ::confirmTool, ::showDelta) { status.text = it }
        buildUi()
        showConversation()
        if (settings.getBoolean("had_secret", false)) {
            status.text = "打开设置，重新输入本次运行的密钥"
        } else if (settings.getString("model_url", "").orEmpty().isNotBlank() ||
            settings.getString("card_url", "").orEmpty().isNotBlank()) {
            scope.launch { connect(savedConfig()) }
        }
    }

    override fun onDestroy() {
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
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))
        header.addView(iconButton("☰", "历史会话") { showHistory() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply {
            text = "Kiora"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(iconButton("✎", "新会话") { newConversation() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(iconButton("⚙", "设置") { showSettings() }, LinearLayout.LayoutParams(dp(44), dp(44)))

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        transcriptScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content.addView(transcriptScroll, FrameLayout.LayoutParams(-1, -1))
        transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }
        transcriptScroll.addView(transcript)
        emptyState = buildEmptyState()
        content.addView(emptyState, FrameLayout.LayoutParams(-1, -1))

        status = TextView(this).apply {
            text = "设置模型或连接电脑端 Agent"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(30)))

        val selectedScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        attachmentContainer = selectedScroll
        root.addView(selectedScroll)
        attachmentStrip = LinearLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
        }
        selectedScroll.addView(attachmentStrip)

        val composerShell = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.WHITE, 27, STROKE)
        }
        val composerOuter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(3), dp(14), dp(12))
        }
        root.addView(composerOuter)
        composerOuter.addView(composerShell, LinearLayout.LayoutParams(-1, -2))
        composerShell.addView(iconButton("+", "添加照片或文件") { showAttachmentMenu() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        composer = EditText(this).apply {
            hint = "给 Kiora 发消息"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = null
            minLines = 1
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(3), dp(8), dp(4), dp(8))
        }
        composerShell.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = TextView(this).apply {
            text = "↑"
            contentDescription = "发送"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(INK, 24)
            setOnClickListener { sendMessage() }
        }
        composerShell.addView(sendButton, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun buildEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(26), 0, dp(26), dp(65))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.kiora_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        addView(TextView(this@MainActivity).apply {
            text = "今天想处理什么？"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(54)))
        addView(TextView(this@MainActivity).apply {
            text = "选择手机里的照片或文件，让电脑端 Agent 帮你处理。"
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        addView(suggestion("分析一张照片") { pickPhotos() })
        addView(suggestion("把文件交给电脑") { pickFiles() })
        addView(suggestion("询问电脑端 Agent") {
            composer.setText("请帮我处理这个任务：")
            composer.setSelection(composer.length())
            composer.requestFocus()
        })
    }

    private fun suggestion(label: String, action: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(INK)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(17), 0, dp(17), 0)
        background = rounded(Color.WHITE, 18, STROKE)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
    }

    private fun iconButton(glyph: String, description: String, action: () -> Unit): TextView = TextView(this).apply {
        text = glyph
        contentDescription = description
        textSize = 25f
        gravity = Gravity.CENTER
        setTextColor(INK)
        setOnClickListener { action() }
    }

    private fun showConversation() {
        streamView = null
        transcript.removeAllViews()
        conversations.lines(conversations.currentId).forEach(::addLine)
        emptyState.visibility = if (transcript.childCount == 0) View.VISIBLE else View.GONE
        scrollToBottom()
    }

    private fun addLine(line: ChatLine): TextView {
        emptyState.visibility = View.GONE
        val row = LinearLayout(this).apply {
            gravity = if (line.role == "user") Gravity.RIGHT else Gravity.LEFT
            setPadding(0, dp(7), 0, dp(7))
        }
        transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        if (line.role != "user") row.addView(TextView(this).apply {
            text = "✦"
            textSize = 19f
            setTextColor(TEAL)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(dp(31), dp(34)))
        return TextView(this).apply {
            text = line.text
            textSize = 16f
            setTextColor(INK)
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (line.role == "user") {
                background = rounded(USER_BUBBLE, 20)
                setPadding(dp(15), dp(11), dp(15), dp(11))
            } else setPadding(dp(2), dp(4), dp(12), dp(7))
            val maxWidth = resources.displayMetrics.widthPixels - dp(if (line.role == "user") 72 else 76)
            row.addView(this, LinearLayout.LayoutParams(-2, -2).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT })
            this.maxWidth = maxWidth
        }.also { scrollToBottom() }
    }

    private fun showDelta(delta: String) {
        val view = streamView ?: addLine(ChatLine("assistant", "")).also { streamView = it }
        view.append(delta)
        scrollToBottom()
    }

    private fun scrollToBottom() { transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun sendMessage() {
        if (busy) return
        val instruction = composer.text.toString().trim()
        if (instruction.isBlank() && selectedUris.isEmpty()) return
        if (!agent.isConfigured) {
            status.text = "请先完成连接设置"
            showSettings()
            return
        }
        if (selectedUris.isNotEmpty() && !agent.hasDesktop) {
            status.text = "发送附件需要配置电脑端 Agent Card"
            showSettings()
            return
        }
        if (selectedUris.isNotEmpty()) {
            val names = selectedUris.map(::displayName)
            AlertDialog.Builder(this)
                .setTitle("发送到 ${agent.targetName}？")
                .setMessage("附件：${names.joinToString("、")}\n\n要求：${instruction.ifBlank { "请处理这些附件" }}")
                .setNegativeButton("取消", null)
                .setPositiveButton("发送") { _, _ -> performSend(instruction) }
                .show()
        } else performSend(instruction)
    }

    private fun performSend(instruction: String) {
        val files = selectedUris.toList()
        val id = conversations.currentId
        val shown = buildString {
            append(instruction.ifBlank { "请处理这些附件" })
            if (files.isNotEmpty()) append("\n").append(files.joinToString("  ") { "📎 ${displayName(it)}" })
        }
        val user = ChatLine("user", shown)
        conversations.append(id, user)
        addLine(user)
        composer.text.clear()
        busy = true
        sendButton.alpha = 0.4f
        status.text = "正在处理…"
        scope.launch {
            try {
                val reply = agent.send(id, instruction, files)
                if (streamView != null) {
                    streamView!!.text = reply
                    streamView = null
                } else addLine(ChatLine("assistant", reply))
                conversations.append(id, ChatLine("assistant", reply))
                selectedUris.clear()
                showAttachments()
                status.text = "已完成"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                streamView = null
                status.text = "发送失败：${error.message ?: "未知错误"}"
                addLine(ChatLine("assistant", "发送失败：${error.message ?: "请检查连接后重试"}"))
            } finally {
                busy = false
                sendButton.alpha = 1f
            }
        }
    }

    private fun showAttachmentMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("选择照片", "选择文件")) { _, which ->
                if (which == 0) pickPhotos() else pickFiles()
            }.show()
    }

    private fun pickPhotos() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择照片"), PICK_PHOTOS)
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_FILES)
    }

    @Deprecated("Uses the platform picker result API without an additional Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode != PICK_PHOTOS && requestCode != PICK_FILES) return
        data.data?.let { selectedUris.add(it) }
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) selectedUris.add(clip.getItemAt(index).uri)
        }
        val unique = selectedUris.distinct()
        selectedUris.clear()
        selectedUris.addAll(unique)
        showAttachments()
    }

    private fun showAttachments() {
        attachmentStrip.removeAllViews()
        attachmentContainer.visibility = if (selectedUris.isEmpty()) View.GONE else View.VISIBLE
        selectedUris.toList().forEach { uri ->
            attachmentStrip.addView(TextView(this).apply {
                text = "📎 ${displayName(uri)}  ×"
                textSize = 13f
                setTextColor(INK)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(USER_BUBBLE, 15)
                setOnClickListener { selectedUris.remove(uri); showAttachments() }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(8) })
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "附件"
    }.getOrDefault("附件")

    private fun showHistory() {
        val recent = conversations.sessions()
        val labels = listOf("＋ 新会话") + recent.map { it.title }
        AlertDialog.Builder(this).setTitle("历史会话")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == 0) newConversation() else {
                    conversations.currentId = recent[index - 1].id
                    showConversation()
                }
            }.show()
    }

    private fun newConversation() {
        conversations.newConversation()
        selectedUris.clear()
        showAttachments()
        showConversation()
        status.text = "新会话"
    }

    private fun showSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(6))
        }
        val modelUrl = settingField(panel, "模型站点地址", "https://example.com", settings.getString("model_url", "").orEmpty())
        val modelId = settingField(panel, "模型 ID", "模型服务提供的名称", settings.getString("model_id", "").orEmpty())
        val apiKey = settingField(panel, "模型 API Key（仅本次运行）", "可选", activeConfig?.apiKey.orEmpty(), true)
        val cardUrl = settingField(panel, "电脑端 Agent Card", "http://192.168.1.5:9999/.well-known/agent-card.json",
            settings.getString("card_url", "").orEmpty())
        val token = settingField(panel, "A2A Bearer Token（仅本次运行）", "可选", activeConfig?.a2aToken.orEmpty(), true)
        val scroll = ScrollView(this).apply { addView(panel) }
        AlertDialog.Builder(this).setTitle("连接设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并连接") { _, _ ->
                val config = CompanionConfig(
                    modelUrl.text.toString().trim(), modelId.text.toString().trim(), apiKey.text.toString().trim(),
                    cardUrl.text.toString().trim(), token.text.toString().trim(),
                )
                settings.edit().putString("model_url", config.modelUrl).putString("model_id", config.modelId)
                    .putString("card_url", config.agentCardUrl)
                    .putBoolean("had_secret", config.apiKey.isNotBlank() || config.a2aToken.isNotBlank()).apply()
                scope.launch { connect(config) }
            }.show()
    }

    private fun settingField(parent: LinearLayout, label: String, hintText: String, value: String,
        secret: Boolean = false): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(7) })
        return EditText(this).also { field ->
            field.setText(value)
            field.hint = hintText
            field.textSize = 15f
            field.setSingleLine(true)
            field.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            parent.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun savedConfig() = CompanionConfig(
        settings.getString("model_url", "").orEmpty(), settings.getString("model_id", "").orEmpty(), "",
        settings.getString("card_url", "").orEmpty(), "",
    )

    private suspend fun connect(config: CompanionConfig) {
        status.text = "正在连接…"
        try {
            status.text = agent.configure(config)
            activeConfig = config
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { status.text = "连接失败：${error.message ?: "请检查地址"}" }
    }

    private suspend fun confirmTool(call: ToolCall): ToolAuthorization = suspendCancellableCoroutine { continuation ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("允许访问电脑端能力？")
            .setMessage("能力：${call.name}\n内容：${call.arguments.toString().take(600)}")
            .setPositiveButton("允许") { _, _ ->
                if (continuation.isActive) continuation.resume(ToolAuthorization.Allowed)
            }
            .setNegativeButton("拒绝") { _, _ ->
                if (continuation.isActive) continuation.resume(ToolAuthorization.Denied("用户拒绝"))
            }
            .setOnCancelListener {
                if (continuation.isActive) continuation.resume(ToolAuthorization.Denied("用户取消"))
            }.create()
        continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        dialog.show()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_PHOTOS = 11
        const val PICK_FILES = 12
        const val BG = 0xFFFAFAF8.toInt()
        const val INK = 0xFF20201F.toInt()
        const val MUTED = 0xFF777773.toInt()
        const val STROKE = 0xFFE4E4E0.toInt()
        const val USER_BUBBLE = 0xFFF0F0ED.toInt()
        const val TEAL = 0xFF167D73.toInt()
    }
}
