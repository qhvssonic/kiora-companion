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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
    private val markdown by lazy { MessageMarkdown(this) }
    private lateinit var agent: CompanionAgent
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var emptyState: View
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var attachmentStrip: LinearLayout
    private lateinit var attachmentContainer: View
    private lateinit var status: TextView
    private lateinit var modelIndicator: TextView
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var drawerSearch: EditText
    private val selectedUris = mutableListOf<Uri>()
    private var streamView: TextView? = null
    private val streamSource = StringBuilder()
    private var pendingStreamRender: Runnable? = null
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
        val host = FrameLayout(this)
        setContentView(host)
        host.addView(root, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))
        header.addView(iconButton("☰", "历史会话") { showHistory() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { chooseHeaderModel() }
        }
        header.addView(identity, LinearLayout.LayoutParams(0, -1, 1f))
        identity.addView(TextView(this).apply {
            text = "Kiora"
            textSize = 18f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        modelIndicator = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        identity.addView(modelIndicator, LinearLayout.LayoutParams(-1, dp(18)))
        updateModelIndicator()
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
        buildDrawer(host)
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
        clearStream()
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
            if (line.role == "user") text = line.text else markdown.render(this, line.text)
        }.also { scrollToBottom() }
    }

    private fun showDelta(delta: String) {
        val view = streamView ?: addLine(ChatLine("assistant", "")).also {
            streamView = it
            streamSource.setLength(0)
        }
        streamSource.append(delta)
        if (pendingStreamRender == null) {
            val render = Runnable {
                pendingStreamRender = null
                if (streamView === view) {
                    markdown.render(view, streamSource.toString())
                    scrollToBottom()
                }
            }
            pendingStreamRender = render
            view.postDelayed(render, 80)
        }
    }

    private fun clearStream() {
        pendingStreamRender?.let { render -> streamView?.removeCallbacks(render) }
        pendingStreamRender = null
        streamView = null
        streamSource.setLength(0)
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
                    val view = streamView!!
                    clearStream()
                    markdown.render(view, reply)
                } else addLine(ChatLine("assistant", reply))
                conversations.append(id, ChatLine("assistant", reply))
                selectedUris.clear()
                showAttachments()
                status.text = "已完成"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                clearStream()
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
        if (requestCode == SETTINGS) {
            val config = CompanionConfig(
                data.getStringExtra("model_url").orEmpty(), data.getStringExtra("model_id").orEmpty(),
                data.getStringExtra("api_key").orEmpty(), data.getStringExtra("card_url").orEmpty(),
                data.getStringExtra("a2a_token").orEmpty(),
            )
            updateModelIndicator()
            scope.launch { connect(config) }
            return
        }
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
        drawerSearch.text.clear()
        renderHistory("")
        drawerOverlay.visibility = View.VISIBLE
    }

    private fun buildDrawer(host: FrameLayout) {
        drawerOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x66000000)
            setOnClickListener { hideHistory() }
        }
        host.addView(drawerOverlay, FrameLayout.LayoutParams(-1, -1))
        val width = (resources.displayMetrics.widthPixels * 0.84f).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnClickListener { }
            setPadding(dp(16), dp(20), dp(16), dp(16))
            elevation = dp(12).toFloat()
        }
        drawerOverlay.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.LEFT))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        panel.addView(brand, LinearLayout.LayoutParams(-1, dp(54)))
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.kiora_logo)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        brand.addView(TextView(this).apply {
            text = "Kiora"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(iconButton("×", "关闭侧边栏") { hideHistory() }, LinearLayout.LayoutParams(dp(40), dp(40)))

        panel.addView(TextView(this).apply {
            text = "＋   新对话"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            background = rounded(USER_BUBBLE, 15)
            setOnClickListener { newConversation() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12); bottomMargin = dp(14) })

        drawerSearch = EditText(this).apply {
            hint = "搜索对话"
            textSize = 14f
            setTextColor(INK)
            setHintTextColor(MUTED)
            setSingleLine(true)
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(BG, 14, STROKE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderHistory(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        panel.addView(drawerSearch, LinearLayout.LayoutParams(-1, dp(44)))
        panel.addView(TextView(this).apply {
            text = "最近对话"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(8), dp(23), 0, dp(11))
        })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(drawerList)

        panel.addView(View(this).apply { setBackgroundColor(STROKE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) })
        panel.addView(TextView(this).apply {
            text = "⚙   模型与连接设置"
            textSize = 15f
            setTextColor(INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { hideHistory(); showSettings() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
    }

    private fun renderHistory(query: String) {
        drawerList.removeAllViews()
        val recent = conversations.sessions().filter { it.title.contains(query, ignoreCase = true) }
        if (recent.isEmpty()) {
            drawerList.addView(TextView(this).apply {
                text = if (query.isBlank()) "还没有对话，开始一个新对话吧" else "没有找到相关对话"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        recent.forEach { session ->
            val selected = session.id == conversations.currentId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(7), dp(12), dp(7))
                background = rounded(if (selected) USER_BUBBLE else Color.WHITE, 14)
                setOnClickListener {
                    conversations.currentId = session.id
                    showConversation()
                    hideHistory()
                }
            }
            drawerList.addView(row, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(4) })
            row.addView(TextView(this).apply {
                text = session.title.lineSequence().firstOrNull().orEmpty()
                textSize = 15f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(INK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = conversations.lines(session.id).lastOrNull()?.text?.replace('\n', ' ')?.take(45).orEmpty()
                textSize = 12f
                setTextColor(MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun hideHistory() {
        drawerOverlay.visibility = View.GONE
    }

    @Deprecated("Back press delegates to the navigation drawer")
    override fun onBackPressed() {
        if (drawerOverlay.visibility == View.VISIBLE) hideHistory() else super.onBackPressed()
    }

    private fun newConversation() {
        hideHistory()
        conversations.newConversation()
        selectedUris.clear()
        showAttachments()
        showConversation()
        status.text = "新会话"
    }

    private fun showSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java).apply {
            putExtra("api_key", activeConfig?.apiKey.orEmpty())
            putExtra("a2a_token", activeConfig?.a2aToken.orEmpty())
        }, SETTINGS)
    }

    private fun chooseHeaderModel() {
        if (busy) return
        if (settings.getBoolean("had_secret", false) && activeConfig == null) {
            showSettings()
            return
        }
        val provider = ProviderCatalog.byId(settings.getString("provider_id", null)).let {
            if (settings.contains("provider_id")) it
            else ProviderCatalog.infer(settings.getString("model_url", "").orEmpty())
        }
        if (provider.models.isEmpty()) {
            showSettings()
            return
        }
        val choices = provider.models + "更多模型与提供商…"
        AlertDialog.Builder(this).setTitle("选择模型")
            .setSingleChoiceItems(choices.toTypedArray(), provider.models.indexOf(settings.getString("model_id", "")))
            { dialog, index ->
                dialog.dismiss()
                if (index == provider.models.size) showSettings()
                else {
                    val current = activeConfig ?: savedConfig()
                    val updated = current.copy(modelUrl = provider.endpoint, modelId = provider.models[index])
                    settings.edit().putString("model_url", updated.modelUrl)
                        .putString("model_id", updated.modelId).putString("provider_id", provider.id).apply()
                    updateModelIndicator()
                    scope.launch { connect(updated) }
                }
            }.show()
    }

    private fun updateModelIndicator() {
        val model = settings.getString("model_id", "").orEmpty()
        val endpoint = settings.getString("model_url", "").orEmpty()
        val provider = if (settings.contains("provider_id")) ProviderCatalog.byId(settings.getString("provider_id", null))
            else ProviderCatalog.infer(endpoint)
        modelIndicator.text = when {
            model.isNotBlank() -> "${provider.name} · $model  ⌄"
            provider.id == "none" -> "电脑端 Agent  ⌄"
            else -> "选择模型  ⌄"
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
            updateModelIndicator()
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
        const val SETTINGS = 13
        const val BG = 0xFFFAFAF8.toInt()
        const val INK = 0xFF20201F.toInt()
        const val MUTED = 0xFF777773.toInt()
        const val STROKE = 0xFFE4E4E0.toInt()
        const val USER_BUBBLE = 0xFFF0F0ED.toInt()
        const val TEAL = 0xFF167D73.toInt()
    }
}
