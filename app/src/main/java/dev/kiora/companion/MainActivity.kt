package dev.kiora.companion

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageButton
import androidx.core.content.FileProvider
import dev.kiora.core.AgentEvent
import dev.kiora.core.AgentMessage
import dev.kiora.core.QueuedUserInput
import dev.kiora.core.ToolAuthorization
import dev.kiora.core.ToolAuthorizationRequest
import dev.kiora.speech.Pcm16Audio
import dev.kiora.speech.SpeechSynthesisRequest
import dev.kiora.speech.sherpa.SenseVoiceRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import java.io.File
import java.util.WeakHashMap

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settings by lazy { getSharedPreferences("companion_settings", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }
    private val a2aConnections by lazy { A2aConnectionStore(this) }
    private val conversations by lazy { ConversationRepository(this) }
    private val markdown by lazy { MessageMarkdown(this) }
    private lateinit var agent: CompanionAgent
    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var emptyState: View
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var queuePanel: LinearLayout
    private var queueExpanded = false
    private lateinit var voiceButton: TextView
    private lateinit var attachmentStrip: LinearLayout
    private lateinit var attachmentContainer: View
    private lateinit var status: TextView
    private lateinit var modelIndicator: TextView
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var drawerList: LinearLayout
    private lateinit var drawerSearch: EditText
    private val selectedUris = mutableListOf<Uri>()
    private val albumTransferUris = mutableSetOf<Uri>()
    private var scanDraftId: String? = null
    private val capturedFiles = mutableMapOf<Uri, File>()
    private var pendingCaptureFile: File? = null
    private var streamRow: LinearLayout? = null
    private var streamContent: LinearLayout? = null
    private var streamTail: TextView? = null
    private val streamSource = StringBuilder()
    private var streamCommittedChars = 0
    private var pendingStreamRender: Runnable? = null
    private var pendingBottomScroll = false
    private var activeConfig: CompanionConfig? = null
    private val allowedToolsByConversation = mutableMapOf<String, MutableSet<String>>()
    private val liveTools = mutableMapOf<String, LiveTool>()
    private var currentConversationId: String? = null
    private var historyJob: Job? = null
    private var conversationJob: Job? = null
    private var conversationLoading = true
    private var busy = false
    private var sendJob: Job? = null
    private var initialUserEventPending = false
    private var answerShownInRun = false
    private var queueItems: List<QueuedUserInput> = emptyList()
    private var queueRefreshVersion = 0
    private var queueSubmitting = false
    private var guidingQueued = false
    private val connectMutex = Mutex()
    private var connectionRequest = 0
    private var connecting = false
    private var modelReady = false
    private val voiceFiles by lazy { VoiceModelFiles(this) }
    private val voiceRecorder by lazy { PushToTalkRecorder(::onVoiceRecorded, ::onVoiceLevel) }
    private var voiceProcessing = false
    private var voicePending = false
    private var recordingWaveform: RecordingWaveformDrawable? = null
    private val messageWidthInsets = WeakHashMap<TextView, Int>()
    private lateinit var speechControls: LinearLayout
    private lateinit var speechLabel: TextView
    private lateinit var speechStop: ImageButton
    private var speechJob: Job? = null
    private val speech by lazy { OfflineSpeechPlayer(this) { text, active ->
        if (this::speechControls.isInitialized) {
            speechControls.visibility = if (active || text.isNotBlank()) View.VISIBLE else View.GONE
            speechLabel.text = text
            speechStop.visibility = if (active) View.VISIBLE else View.GONE
        }
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queueExpanded = savedInstanceState?.getBoolean("queue_expanded") ?: false
        pendingCaptureFile = savedInstanceState?.getString("pending_capture_path")?.let(::File)
        savedInstanceState?.getStringArrayList("selected_uris")
            ?.mapTo(selectedUris, Uri::parse)
        savedInstanceState?.getStringArrayList("album_transfer_uris")
            ?.mapTo(albumTransferUris, Uri::parse)
        scanDraftId = savedInstanceState?.getString("scan_draft_id")
        savedInstanceState?.getStringArrayList("captured_paths")?.forEach { path ->
            val file = File(path)
            if (file.isFile) {
                val uri = FileProvider.getUriForFile(this, "$packageName.capture", file)
                capturedFiles[uri] = file
            }
        }
        File(cacheDir, "captures").listFiles()?.forEach { file ->
            if (file != pendingCaptureFile && file !in capturedFiles.values && file.isFile) file.delete()
        }
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        agent = CompanionAgent(this, ::confirmTool, ::showDelta, { status.text = it }, ::showToolEvent)
        buildUi()
        composer.setText(savedInstanceState?.getString("composer_draft").orEmpty())
        showAttachments()
        status.text = "正在加载会话…"
        scope.launch {
            try {
                currentConversationId = conversations.currentId()
                showConversation()
                if (settings.getString("provider_id", "") == "litert" ||
                    (settings.getString("provider_id", "") == "mnn" &&
                        settings.getString("mnn_config_path", "").orEmpty().isNotBlank()) ||
                    (settings.getString("provider_id", "") !in listOf("litert", "mnn") &&
                        settings.getString("model_url", "").orEmpty().isNotBlank() &&
                        settings.getString("model_id", "").orEmpty().isNotBlank())) {
                    startConnection(savedConfig())
                } else status.text = "请先配置模型"
            } catch (error: Exception) {
                status.text = "加载会话失败：${error.message ?: "请检查存储"}"
            }
        }
    }

    override fun onDestroy() {
        speech.stop()
        if (this::voiceButton.isInitialized) {
            recordingWaveform = null
            voiceRecorder.cancel()
        }
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch { runCatching { agent.close() } }
        super.onDestroy()
    }

    override fun onStop() {
        speech.stop()
        speechJob?.cancel()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val followBottom = this::transcriptScroll.isInitialized && isNearBottom()
        super.onConfigurationChanged(newConfig)
        messageWidthInsets.forEach { (view, inset) ->
            view.maxWidth = messageMaxWidth(inset)
        }
        if (followBottom) scrollToBottom()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCaptureFile?.let { outState.putString("pending_capture_path", it.absolutePath) }
        outState.putStringArrayList("selected_uris", ArrayList(selectedUris.map(Uri::toString)))
        outState.putStringArrayList("album_transfer_uris", ArrayList(albumTransferUris.map(Uri::toString)))
        scanDraftId?.let { outState.putString("scan_draft_id", it) }
        outState.putStringArrayList("captured_paths", ArrayList(capturedFiles.values.map(File::getAbsolutePath)))
        outState.putString("composer_draft", composer.text.toString())
        outState.putBoolean("queue_expanded", queueExpanded)
        super.onSaveInstanceState(outState)
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
        header.addView(headerIcon(R.drawable.ic_sidebar_lines, "历史会话") { showHistory() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
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
        header.addView(headerIcon(R.drawable.ic_new_conversation, "新会话") { newConversation() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
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
            setPadding(dp(18), dp(20), dp(8), dp(24))
        }
        transcriptScroll.addView(transcript)
        emptyState = buildEmptyState()
        content.addView(emptyState, FrameLayout.LayoutParams(-1, -1))

        queuePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }
        content.addView(queuePanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(8)
        })

        status = TextView(this).apply {
            text = "请先配置模型"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(42)))

        speechControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, 0, 0)
            visibility = View.GONE
        }
        speechLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(INK)
            setPadding(0, 0, dp(8), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        speechControls.addView(speechLabel, LinearLayout.LayoutParams(0, -2, 1f))
        speechStop = speechIcon(R.drawable.ic_speech_stop).apply {
            contentDescription = "停止当前朗读"
            tooltipText = "停止朗读"
            setOnClickListener { speech.stop(); speechJob?.cancel() }
        }
        speechControls.addView(speechStop, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(speechControls, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(14), 0, dp(14), dp(4))
        })

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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(5))
            background = rounded(Color.WHITE, 27, STROKE)
        }
        val composerOuter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(3), dp(14), dp(12))
        }
        root.addView(composerOuter)
        composerOuter.addView(composerShell, LinearLayout.LayoutParams(-1, -2))
        composer = EditText(this).apply {
            hint = "给 Kiora 发消息"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(MUTED)
            background = null
            minLines = 1
            maxLines = 5
            minHeight = dp(38)
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(9), dp(5), dp(9), dp(4))
        }
        composerShell.addView(composer, LinearLayout.LayoutParams(-1, -2))
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateSendControl() }
        })
        val composerActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        composerShell.addView(composerActions, LinearLayout.LayoutParams(-1, dp(42)))
        composerActions.addView(iconButton("+", "拍照或添加照片和文件") { showAttachmentMenu() },
            LinearLayout.LayoutParams(dp(42), dp(42)))
        composerActions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        voiceButton = TextView(this).apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_microphone_outline, 0, 0, 0)
            contentDescription = "开始语音输入"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(INK)
            setOnClickListener { toggleVoice() }
        }
        composerActions.addView(voiceButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        sendButton = TextView(this).apply {
            text = "↑"
            contentDescription = "发送"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(INK, 24)
            setOnClickListener { sendMessage() }
        }
        composerActions.addView(sendButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        updateSendControl()
        buildDrawer(host)
    }

    private fun updateSendControl() {
        if (!::sendButton.isInitialized || !::composer.isInitialized) return
        val stopping = busy && queueItems.isNotEmpty() && composer.text.isBlank() && selectedUris.isEmpty()
        sendButton.text = if (stopping) "■" else "↑"
        sendButton.textSize = if (stopping) 21f else 25f
        sendButton.contentDescription = when {
            stopping -> "停止当前任务"
            busy || queueItems.isNotEmpty() -> "加入队列"
            else -> "发送"
        }
    }

    private fun renderQueue() {
        if (!::queuePanel.isInitialized) return
        queuePanel.removeAllViews()
        queuePanel.visibility = if (queueItems.isEmpty()) View.GONE else View.VISIBLE
        updateSendControl()
        if (queueItems.isEmpty()) queueExpanded = false
        if (::transcript.isInitialized) transcript.setPadding(dp(18), dp(20), dp(8),
            dp(if (queueItems.isEmpty()) 24 else if (queueExpanded) 240 else 72))
        if (queueItems.isEmpty()) return
        if (!queueExpanded) {
            queuePanel.addView(TextView(this).apply {
                text = if (busy) "排队 · ${queueItems.size}  ⌃" else "待继续 · ${queueItems.size}  ⌃"
                contentDescription = "展开队列，${queueItems.size} 条待处理指令"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(INK)
                setPadding(dp(14), 0, dp(14), 0)
                background = rounded(Color.WHITE, 22, STROKE)
                setOnClickListener {
                    queueExpanded = true
                    composer.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(composer.windowToken, 0)
                    renderQueue()
                }
            }, LinearLayout.LayoutParams(-2, dp(42)))
            return
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(9))
            background = rounded(Color.WHITE, 18, STROKE)
            clipToOutline = true
        }
        queuePanel.addView(card, LinearLayout.LayoutParams(-1, -2))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(header, LinearLayout.LayoutParams(-1, dp(36)))
        header.addView(TextView(this).apply {
            text = if (busy) "排队中 · ${queueItems.size} 条" else "队列已暂停 · ${queueItems.size} 条"
            textSize = 13f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!busy) header.addView(TextView(this).apply {
            text = "▶ 继续"
            contentDescription = "继续处理排队指令"
            textSize = 14f
            setTextColor(INK)
            setPadding(dp(9), dp(4), dp(4), dp(4))
            setOnClickListener {
                queueExpanded = false
                renderQueue()
                continueQueue()
            }
        })
        header.addView(TextView(this).apply {
            text = "⌄"
            contentDescription = "收起队列"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setOnClickListener {
                queueExpanded = false
                renderQueue()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(36)))
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(dp(156), View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        queueItems.forEach { item ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(3), dp(5))
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
            row.addView(TextView(this).apply {
                text = "↳  ${item.text}"
                textSize = 15f
                setTextColor(INK)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "引导"
                contentDescription = "引导此排队指令：${item.text.take(30)}"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(9), dp(7), dp(8), dp(7))
                setOnClickListener {
                    queueExpanded = false
                    renderQueue()
                    guideQueued(item.id)
                }
            })
            row.addView(TextView(this).apply {
                text = "×"
                contentDescription = "删除此排队指令：${item.text.take(30)}"
                textSize = 21f
                setTextColor(MUTED)
                setPadding(dp(7), dp(3), dp(5), dp(3))
                setOnClickListener { discardQueued(item.id) }
            })
        }
    }

    private fun refreshQueue() {
        val id = currentConversationId ?: return
        val revision = ++queueRefreshVersion
        scope.launch {
            val items = runCatching { agent.pendingQueuedInputs(id) }.getOrElse { emptyList() }
            if (currentConversationId == id && revision == queueRefreshVersion) {
                queueItems = items
                renderQueue()
            }
        }
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
            text = "拍照或选择手机里的照片和文件，让 Kiora 帮你处理。"
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(48)))
        addView(suggestion("扫描纸质文档") { scanDocument() })
        addView(suggestion("分析一张照片") { pickPhotos() })
        addView(suggestion("搜索本地相册") { prepareAlbumSearch() })
        addView(suggestion("选择文件") { pickFiles() })
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

    private fun headerIcon(drawable: Int, description: String, action: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = description
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun showConversation() {
        speech.stop()
        speechJob?.cancel()
        val id = currentConversationId ?: return
        val connectionAtLoad = connectionRequest
        val loadingDuringConnection = connecting
        if (!connecting) status.text = "正在加载会话…"
        clearStream()
        liveTools.clear()
        transcript.removeAllViews()
        emptyState.visibility = View.GONE
        conversationLoading = true
        queueRefreshVersion++
        queueItems = emptyList()
        renderQueue()
        conversationJob?.cancel()
        conversationJob = scope.launch {
            try {
                val lines = conversations.lines(id)
                val modelDetail = agent.latestModelDetail(id)
                val queued = agent.pendingQueuedInputs(id)
                if (currentConversationId == id) {
                    lines.forEach { line ->
                        if (line.role == "tool") addToolLine(line.text, restored = true)
                        else addLine(line)
                    }
                    emptyState.visibility = if (transcript.childCount == 0) View.VISIBLE else View.GONE
                    queueItems = queued
                    renderQueue()
                    conversationLoading = false
                    if (!loadingDuringConnection && !connecting && !busy &&
                        connectionAtLoad == connectionRequest) {
                        status.text = when {
                            modelDetail != null -> "会话上次 · $modelDetail"
                            modelReady -> "模型就绪"
                            else -> "请先配置模型"
                        }
                    }
                    scrollToBottom()
                }
            } catch (error: Exception) {
                status.text = "读取会话失败：${error.message ?: "请重试"}"
            }
        }
    }

    private fun addLine(line: ChatLine): TextView {
        emptyState.visibility = View.GONE
        val row = LinearLayout(this).apply {
            gravity = if (line.role == "user") Gravity.RIGHT else Gravity.LEFT
            setPadding(0, dp(7), 0, dp(7))
        }
        transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (line.role == "user") {
                background = rounded(USER_BUBBLE, 20)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
        if (line.role == "user") line.previewImages.forEach { name ->
            MessagePreviews(this).file(name)?.let { file ->
                val thumbnail = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "查看已发送图片"
                    background = rounded(Color.WHITE, 14)
                    clipToOutline = true
                    setOnClickListener {
                        showImagePreview(FileProvider.getUriForFile(this@MainActivity,
                            "$packageName.capture", file))
                    }
                }
                bubble.addView(thumbnail, LinearLayout.LayoutParams(dp(220), dp(150)).apply {
                    bottomMargin = dp(6)
                })
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        MessagePreviews(this@MainActivity).decode(Uri.fromFile(file), 360)
                    }
                    if (thumbnail.parent != null) thumbnail.setImageBitmap(bitmap)
                    else bitmap?.recycle()
                }
            }
        }
        val textView = TextView(this).apply {
            textSize = 16f
            setTextColor(INK)
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            if (line.role == "user") setPadding(dp(2), dp(2), 0, dp(2))
            else setPadding(dp(2), dp(4), dp(12), dp(7))
            bubble.addView(this, LinearLayout.LayoutParams(-2, -2))
            limitMessageWidth(this, if (line.role == "user") 56 else 36)
            if (line.role == "user") text = line.text else markdown.render(this, line.text)
        }
        if (line.role == "assistant") addSpeechAction(bubble, line.text)
        scrollToBottom()
        return textView
    }

    private fun speechIcon(drawable: Int): ImageButton = ImageButton(this).apply {
        setImageResource(drawable)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        scaleType = ImageView.ScaleType.FIT_CENTER
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x14777773), null, rounded(Color.WHITE, 24))
        elevation = 0f
    }

    private fun addSpeechAction(parent: LinearLayout, source: String) {
        if (source.isBlank()) return
        parent.addView(speechIcon(R.drawable.ic_speech_speaker).apply {
            contentDescription = "离线朗读这条回复"
            tooltipText = "朗读"
            setOnClickListener {
                if (voicePending || voiceProcessing) {
                    status.text = "请先结束录音或等待转写完成"
                } else if (MeloTtsModelFiles(this@MainActivity).current() == null) {
                    if (!busy) startActivityForResult(Intent(this@MainActivity, SettingsActivity::class.java)
                        .putExtra("focus_tts", true), SETTINGS)
                    else status.text = "请先完成任务，再到设置中导入离线朗读模型"
                } else {
                    speechJob?.cancel()
                    speech.stop()
                    speechJob = scope.launch {
                        try { speech.speak(SpeechSynthesisRequest(markdown.plainText(source))) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { speechLabel.text = "朗读失败：${error.message}"
                            speechControls.visibility = View.VISIBLE }
                    }
                }
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(2) })
    }

    private fun showImagePreview(uri: Uri) {
        startActivity(Intent(this, ImagePreviewActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private data class LiveTool(val messageId: Long, var trace: ToolTrace, val header: TextView)

    private data class ToolLineViews(
        val details: TextView,
        val gallery: LinearLayout,
        val previewRow: LinearLayout,
        val stack: FrameLayout,
        val expand: TextView,
        val expandedGallery: HorizontalScrollView,
        val photos: LinearLayout,
        val collapse: TextView,
        val selectionBar: LinearLayout,
        val selectionLabel: TextView,
        val sendPhotos: TextView,
        val selectedPhotoIds: MutableSet<Long> = mutableSetOf(),
        val selectionChips: MutableMap<Long, TextView> = mutableMapOf(),
        var candidates: List<ToolTrace.AlbumPhoto> = emptyList(),
        var renderedResult: String? = null,
    )

    private fun addToolLine(stored: String, restored: Boolean = false): TextView {
        emptyState.visibility = View.GONE
        val trace = ToolTrace.fromStoredText(stored)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(12), dp(3))
        }
        transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        val details = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(INK)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(USER_BUBBLE, 12)
            visibility = View.GONE
        }
        val header = TextView(this).apply {
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(7), 0, dp(7))
            setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                contentDescription = if (details.visibility == View.VISIBLE) "折叠工具详情" else "展开工具详情"
            }
        }
        row.addView(header, LinearLayout.LayoutParams(-1, -2))
        row.addView(details, LinearLayout.LayoutParams(-1, -2))
        val gallery = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(3), 0, dp(5))
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val stack = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        previewRow.addView(stack, LinearLayout.LayoutParams(dp(286), dp(214)))
        val expand = TextView(this).apply {
            textSize = 14f
            setTextColor(INK)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(0xF5FFFFFF.toInt(), 18)
            visibility = View.GONE
        }
        gallery.addView(previewRow, LinearLayout.LayoutParams(-1, -2))
        val galleryPhotos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val expandedGallery = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(galleryPhotos, ViewGroup.LayoutParams(-2, -2))
        }
        gallery.addView(expandedGallery, LinearLayout.LayoutParams(-1, -2))
        val collapse = TextView(this).apply {
            text = "收起照片  ⌃"
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), 0, dp(5))
            visibility = View.GONE
        }
        gallery.addView(collapse, LinearLayout.LayoutParams(-1, dp(42)))
        val selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(4))
        }
        val selectionLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
        }
        selectionBar.addView(selectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val sendPhotos = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(15), 0, dp(15), 0)
            background = rounded(INK, 18)
        }
        selectionBar.addView(sendPhotos, LinearLayout.LayoutParams(-2, dp(36)))
        gallery.addView(selectionBar, LinearLayout.LayoutParams(-1, dp(48)))
        row.addView(gallery, LinearLayout.LayoutParams(-1, -2))
        val views = ToolLineViews(details, gallery, previewRow, stack, expand,
            expandedGallery, galleryPhotos, collapse, selectionBar, selectionLabel, sendPhotos)
        expand.setOnClickListener { setAlbumExpanded(views, true) }
        collapse.setOnClickListener { setAlbumExpanded(views, false) }
        sendPhotos.setOnClickListener {
            val chosen = if (views.candidates.size == 1) views.candidates
                else views.candidates.filter { it.id in views.selectedPhotoIds }
            if (chosen.isEmpty()) {
                status.text = "请先勾选要发送的照片"
                return@setOnClickListener
            }
            chooseAlbumTransferTarget(chosen)
        }
        header.tag = views
        if (trace == null) {
            header.text = "⚠ 无法显示工具记录  ⌄"
            details.text = stored.take(8_000)
        } else updateToolLine(header, trace, restored)
        scrollToBottom()
        return header
    }

    private fun updateToolLine(header: TextView, trace: ToolTrace, restored: Boolean = false) {
        val views = header.tag as ToolLineViews
        val details = views.details
        val stateText = when (trace.state) {
            ToolTrace.State.RUNNING -> if (restored) "调用中断" else "正在调用"
            ToolTrace.State.SUCCEEDED -> "已调用"
            ToolTrace.State.FAILED -> "调用失败"
            ToolTrace.State.INPUT_REQUIRED -> "等待补充信息"
            ToolTrace.State.AUTH_REQUIRED -> "等待授权"
        }
        val icon = when (trace.state) {
            ToolTrace.State.FAILED -> "⚠"
            ToolTrace.State.INPUT_REQUIRED, ToolTrace.State.AUTH_REQUIRED -> "◷"
            else -> "⌘"
        }
        header.text = "$icon $stateText ${trace.label}  ⌄"
        header.setTextColor(if (trace.state == ToolTrace.State.FAILED) 0xFFB35B3B.toInt() else MUTED)
        header.contentDescription = "展开工具详情：$stateText ${trace.label}"
        details.text = buildString {
            append("工具：").append(trace.name)
            append("\n参数：\n").append(trace.displayArguments().take(8_000))
            append("\n\n结果：\n")
            append(when {
                restored && trace.state == ToolTrace.State.RUNNING -> "调用中断，未收到结果"
                trace.state == ToolTrace.State.RUNNING -> "等待结果…"
                trace.result.isBlank() -> "（无返回内容）"
                else -> trace.displayResult().take(16_000)
            })
        }
        renderAlbumPhotos(views, trace)
    }

    private fun renderAlbumPhotos(views: ToolLineViews, trace: ToolTrace) {
        if (trace.name != AlbumSearchTool.NAME || trace.state != ToolTrace.State.SUCCEEDED) {
            views.gallery.visibility = View.GONE
            return
        }
        if (views.renderedResult == trace.result) return
        views.renderedResult = trace.result
        views.photos.removeAllViews()
        views.stack.removeAllViews()
        views.selectedPhotoIds.clear()
        views.selectionChips.clear()
        val candidates = trace.albumPhotos()
        views.candidates = candidates
        val multiple = candidates.size > 1
        val stackWidth = minOf(dp(286), resources.displayMetrics.widthPixels - dp(48))
        views.stack.layoutParams = (views.stack.layoutParams as LinearLayout.LayoutParams).apply {
            width = stackWidth
            height = dp(if (multiple) 214 else 190)
        }
        views.expand.text = "展开 ${candidates.size} 张"
        views.expand.visibility = if (multiple) View.VISIBLE else View.GONE
        views.stack.setOnClickListener(if (multiple) View.OnClickListener { setAlbumExpanded(views, true) }
            else candidates.firstOrNull()?.let { photo ->
                val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val uri = ContentUris.withAppendedId(collection, photo.id)
                View.OnClickListener { showImagePreview(uri) }
            })
        views.stack.contentDescription = if (multiple) "展开 ${candidates.size} 张候选照片"
            else "查看候选照片"
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imageTargets = mutableMapOf<Long, MutableList<ImageView>>()
        candidates.forEachIndexed { index, photo ->
            val uri = ContentUris.withAppendedId(collection, photo.id)
            val toggleSelection = {
                if (!views.selectedPhotoIds.add(photo.id)) views.selectedPhotoIds.remove(photo.id)
                updateAlbumSelection(views)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                contentDescription = if (multiple) "选择第 ${index + 1} 张候选照片：${photo.name}"
                    else "查看候选照片：${photo.name}"
                setOnClickListener { if (multiple) toggleSelection() else showImagePreview(uri) }
            }
            views.photos.addView(card, LinearLayout.LayoutParams(dp(110), -2).apply {
                rightMargin = dp(8)
            })
            val tile = FrameLayout(this)
            card.addView(tile, LinearLayout.LayoutParams(-1, dp(110)))
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(USER_BUBBLE, 12)
                clipToOutline = true
                setOnClickListener { if (multiple) toggleSelection() else showImagePreview(uri) }
            }
            tile.addView(image, FrameLayout.LayoutParams(-1, -1))
            if (multiple) {
                val selector = TextView(this).apply {
                    text = "○"
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(INK)
                    background = rounded(0xEEFFFFFF.toInt(), 18)
                    contentDescription = "选择第 ${index + 1} 张照片"
                    setOnClickListener { toggleSelection() }
                }
                tile.addView(selector, FrameLayout.LayoutParams(dp(42), dp(42),
                    Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(5); rightMargin = dp(5) })
                views.selectionChips[photo.id] = selector
            }
            imageTargets.getOrPut(photo.id) { mutableListOf() }.add(image)
            val caption = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            card.addView(caption, LinearLayout.LayoutParams(-1, dp(31)))
            caption.addView(TextView(this).apply {
                text = photo.name.ifBlank { "照片 ${index + 1}" }
                textSize = 12f
                setTextColor(MUTED)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(5), dp(2), 0)
                setOnClickListener { if (multiple) toggleSelection() else showImagePreview(uri) }
            }, LinearLayout.LayoutParams(0, -1, 1f))
            if (multiple) caption.addView(TextView(this).apply {
                text = "预览"
                textSize = 12f
                setTextColor(INK)
                gravity = Gravity.CENTER
                contentDescription = "预览第 ${index + 1} 张照片"
                setOnClickListener { showImagePreview(uri) }
            }, LinearLayout.LayoutParams(dp(38), -1))
        }
        candidates.indices.reversed().forEach { index ->
            val photo = candidates[index]
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(USER_BUBBLE, 14)
                clipToOutline = true
            }
            val layer = FrameLayout(this).apply {
                background = rounded(Color.WHITE, 16, STROKE)
                clipToOutline = true
                setPadding(dp(2), dp(2), dp(2), dp(2))
                addView(image, FrameLayout.LayoutParams(-1, -1))
                rotation = when (index) {
                    1 -> 3f
                    2 -> 6f
                    else -> 0f
                }
            }
            views.stack.addView(layer, FrameLayout.LayoutParams(
                stackWidth - dp(if (multiple) 56 else 0), dp(190)).apply {
                leftMargin = dp(if (multiple) index * 23 else 0)
                topMargin = dp(if (multiple) 12 else 0)
            })
            imageTargets.getOrPut(photo.id) { mutableListOf() }.add(image)
        }
        if (multiple) views.stack.addView(views.expand, FrameLayout.LayoutParams(-2, dp(36),
            Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = dp(68)
            bottomMargin = dp(24)
        })
        candidates.forEach { photo ->
            val uri = ContentUris.withAppendedId(collection, photo.id)
            val targets = imageTargets.getValue(photo.id)
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    MessagePreviews(this@MainActivity).decode(uri, 240)
                }
                if (views.gallery.isAttachedToWindow && views.renderedResult == trace.result)
                    targets.forEach { it.setImageBitmap(bitmap) }
                else bitmap?.recycle()
            }
        }
        setAlbumExpanded(views, false)
        updateAlbumSelection(views)
        views.gallery.visibility = if (candidates.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setAlbumExpanded(views: ToolLineViews, expanded: Boolean) {
        views.previewRow.visibility = if (expanded) View.GONE else View.VISIBLE
        views.expandedGallery.visibility = if (expanded) View.VISIBLE else View.GONE
        views.collapse.visibility = if (expanded) View.VISIBLE else View.GONE
        views.selectionBar.visibility = if (expanded || views.candidates.size == 1 ||
            views.selectedPhotoIds.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateAlbumSelection(views: ToolLineViews) {
        val single = views.candidates.size == 1
        views.selectionLabel.text = if (single) "在手机相册中" else "已选 ${views.selectedPhotoIds.size} 张"
        views.sendPhotos.text = if (single) "发送到电脑" else "发送所选到电脑"
        views.selectionChips.forEach { (id, chip) ->
            val selected = id in views.selectedPhotoIds
            chip.text = if (selected) "✓" else "○"
            chip.setTextColor(if (selected) Color.WHITE else INK)
            chip.background = rounded(if (selected) INK else 0xEEFFFFFF.toInt(), 18)
            chip.contentDescription = if (selected) "取消选择照片" else "选择照片"
        }
        views.selectionBar.visibility = if (views.expandedGallery.visibility == View.VISIBLE || single ||
            views.selectedPhotoIds.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun chooseAlbumTransferTarget(photos: List<ToolTrace.AlbumPhoto>) {
        if (busy || queueItems.isNotEmpty()) {
            status.text = "请等待当前任务和队列结束后发送照片"
            return
        }
        val targets = a2aConnections.list().filter { it.enabled && it.alias.isNotBlank() }
        if (targets.isEmpty()) {
            status.text = "请先在连接设置中启用电脑端 A2A，并设置触发别名"
            return
        }
        if (targets.size == 1) {
            stageAlbumTransfer(photos, targets.single())
            return
        }
        SettingsSheet.showOptions(this, "选择接收电脑", "照片会先加入消息附件，确认后再发送",
            targets.map { SettingsSheet.Option(it.name, "触发别名 ${it.alias}") }) { index ->
            stageAlbumTransfer(photos, targets[index])
        }
    }

    private fun stageAlbumTransfer(photos: List<ToolTrace.AlbumPhoto>, target: A2aConnectionConfig) {
        val draft = composer.text.toString().trim()
        val otherTargets = a2aConnections.list().filter {
            it.id != target.id && it.alias.isNotBlank() &&
                RemoteTaskRouting.matchingConnections(draft, listOf(it)).isNotEmpty()
        }
        if (otherTargets.isNotEmpty()) {
            status.text = "草稿已提到另一个 A2A 连接，请先修改草稿再选择照片"
            return
        }
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uris = photos.map { ContentUris.withAppendedId(collection, it.id) }
        val combined = (selectedUris + uris).distinct()
        if (combined.count { MessagePreviews(this).isImage(it) } > 8) {
            status.text = "一次最多发送 8 张照片，请先移除部分附件"
            return
        }
        val totalBytes = combined.sumOf { uri ->
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }
        if (totalBytes > 10L * 1024 * 1024) {
            status.text = "所选附件超过 A2A 的 10 MB 上限，请减少照片后重试"
            return
        }
        selectedUris.clear()
        selectedUris.addAll(combined)
        albumTransferUris.addAll(uris)
        showAttachments()
        val instruction = "通过 ${target.alias} 把所选照片保存到电脑桌面。"
        if (draft.isBlank()) composer.setText(instruction)
        else if (!draft.contains(instruction))
            composer.append("\n$instruction")
        composer.setSelection(composer.length())
        status.text = "已加入 ${photos.size} 张照片；确认指令后点击发送"
    }

    private suspend fun showToolEvent(event: AgentEvent) {
        val id = currentConversationId ?: return
        when (event) {
            is AgentEvent.MessageAdded -> when (val message = event.entry.message) {
                is AgentMessage.User -> {
                    if (initialUserEventPending) initialUserEventPending = false
                    else {
                        val line = ChatLine("user", message.text)
                        addLine(line)
                        conversations.append(id, line)
                        status.text = "正在处理排队指令…"
                        refreshQueue()
                    }
                }
                is AgentMessage.Assistant -> if (message.toolCalls.isEmpty()) {
                    val line = ChatLine("assistant", message.text)
                    showFinalAnswer(line)
                    conversations.append(id, line)
                    answerShownInRun = true
                }
                else -> Unit
            }
            is AgentEvent.ToolRequested -> {
                streamRow?.let(transcript::removeView)
                clearStream()
                val trace = ToolTrace.requested(event.call.copy(arguments =
                    agent.effectiveSkillArguments(event.call.name, event.call.arguments)))
                val messageId = conversations.append(id, ChatLine("tool", trace.toStoredText()))
                liveTools[event.call.id] = LiveTool(messageId, trace, addToolLine(trace.toStoredText()))
            }
            is AgentEvent.ToolCompleted -> {
                val live = liveTools.remove(event.result.callId) ?: return
                val result = event.result.text.let {
                    if (it.length > 16_000) it.take(16_000) + "\n…结果已截断" else it
                }
                live.trace = live.trace.copy(
                    result = result,
                    state = when (event.result.attention) {
                        dev.kiora.core.ToolAttention.INPUT_REQUIRED -> ToolTrace.State.INPUT_REQUIRED
                        dev.kiora.core.ToolAttention.AUTH_REQUIRED -> ToolTrace.State.AUTH_REQUIRED
                        null -> if (event.result.isError) ToolTrace.State.FAILED else ToolTrace.State.SUCCEEDED
                    },
                )
                conversations.updateTool(live.messageId, live.trace)
                updateToolLine(live.header, live.trace)
            }
            else -> Unit
        }
    }

    private fun showFinalAnswer(line: ChatLine) {
        if (streamTail != null) {
            val followBottom = isNearBottom()
            if (line.text != streamSource.toString()) {
                streamContent?.removeAllViews()
                streamCommittedChars = 0
                streamTail?.let { streamContent?.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            }
            renderStreamMarkdown(line.text)
            streamContent?.let { addSpeechAction(it, line.text) }
            clearStream()
            if (followBottom) scrollToBottom()
        } else addLine(line)
    }

    private fun showDelta(delta: String) {
        val tail = streamTail ?: addStreamingLine().also {
            streamSource.setLength(0)
            streamCommittedChars = 0
        }
        streamSource.append(delta)
        if (pendingStreamRender == null) {
            val render = Runnable {
                pendingStreamRender = null
                if (streamTail === tail) {
                    val followBottom = isNearBottom()
                    renderStreamMarkdown()
                    if (followBottom) scrollToBottom()
                }
            }
            pendingStreamRender = render
            tail.postDelayed(render, 80)
        }
    }

    private fun addStreamingLine(): TextView {
        emptyState.visibility = View.GONE
        val row = LinearLayout(this).apply { setPadding(0, dp(7), 0, dp(7)) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(12), dp(7))
        }
        transcript.addView(row, LinearLayout.LayoutParams(-1, -2))
        row.addView(content, LinearLayout.LayoutParams(-2, -2))
        val tail = streamingTextView()
        content.addView(tail, LinearLayout.LayoutParams(-2, -2))
        streamRow = row
        streamContent = content
        streamTail = tail
        scrollToBottom()
        return tail
    }

    private fun streamingTextView() = TextView(this).apply {
        textSize = 16f
        setTextColor(INK)
        setTextIsSelectable(true)
        setLineSpacing(dp(3).toFloat(), 1f)
        limitMessageWidth(this, 36)
    }

    private fun messageMaxWidth(insetDp: Int): Int =
        (resources.displayMetrics.widthPixels - dp(insetDp)).coerceAtLeast(dp(120))

    private fun limitMessageWidth(view: TextView, insetDp: Int) {
        messageWidthInsets[view] = insetDp
        view.maxWidth = messageMaxWidth(insetDp)
    }

    private fun renderStreamMarkdown(finalText: String? = null) {
        val content = streamContent ?: return
        val tail = streamTail ?: return
        val source = finalText ?: streamSource.toString()
        // The completed prefix never changes. Only the unfinished block is parsed again.
        val complete = StreamMarkdown.completedPrefixEnd(source)
        if (complete > streamCommittedChars) {
            val block = source.substring(streamCommittedChars, complete).trim('\n')
            if (block.isNotEmpty()) {
                val fixed = streamingTextView()
                markdown.render(fixed, block)
                content.addView(fixed, content.indexOfChild(tail), LinearLayout.LayoutParams(-2, -2).apply {
                    bottomMargin = dp(10)
                })
            }
            streamCommittedChars = complete
        }
        val unfinished = source.substring(streamCommittedChars).trimStart('\n')
        if (unfinished.isEmpty()) {
            tail.visibility = View.GONE
        } else {
            tail.visibility = View.VISIBLE
            markdown.render(tail, unfinished)
        }
    }

    private fun clearStream() {
        pendingStreamRender?.let { render -> streamTail?.removeCallbacks(render) }
        pendingStreamRender = null
        streamRow = null
        streamContent = null
        streamTail = null
        streamSource.setLength(0)
        streamCommittedChars = 0
    }

    private fun isNearBottom(): Boolean =
        transcript.height - transcriptScroll.scrollY - transcriptScroll.height <= dp(48)

    private fun scrollToBottom() {
        if (pendingBottomScroll) return
        pendingBottomScroll = true
        transcriptScroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                transcriptScroll.viewTreeObserver.removeOnPreDrawListener(this)
                pendingBottomScroll = false
                val bottom = (transcript.height - transcriptScroll.height).coerceAtLeast(0)
                if (transcriptScroll.scrollY != bottom) transcriptScroll.scrollTo(0, bottom)
                return true
            }
        })
        transcriptScroll.invalidate()
    }

    private fun sendMessage() {
        if (busy && queueItems.isNotEmpty() && composer.text.isBlank() && selectedUris.isEmpty()) {
            status.text = "正在停止；队列会保留"
            sendJob?.cancel()
            return
        }
        if (voicePending || voiceProcessing) {
            status.text = "请先完成语音转写"
            return
        }
        if (busy || queueItems.isNotEmpty()) {
            queueMessage()
            return
        }
        if (connecting) {
            status.text = "正在连接模型，请稍候"
            return
        }
        if (currentConversationId == null || conversationLoading) {
            status.text = "正在加载会话，请稍候"
            return
        }
        val instruction = composer.text.toString().trim()
        if (instruction.isBlank() && selectedUris.isEmpty()) return
        if (!modelReady || !agent.hasModel) {
            status.text = "请先配置模型"
            showSettings()
            return
        }
        performSend(instruction)
    }

    private fun queueMessage() {
        val id = currentConversationId ?: return
        val text = composer.text.toString().trim()
        if (text.isBlank()) return
        if (queueSubmitting) return
        if (selectedUris.isNotEmpty()) {
            status.text = "排队指令暂不支持附件；请在当前任务结束后发送"
            return
        }
        queueSubmitting = true
        scope.launch {
            try {
                agent.queueFollowUp(id, text)
                if (composer.text.toString().trim() == text) composer.text.clear()
                status.text = "已加入队列"
                refreshQueue()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "排队失败：${error.message ?: "请重试"}"
            } finally {
                queueSubmitting = false
            }
        }
    }

    private fun guideQueued(queueId: String) {
        if (guidingQueued) return
        val id = currentConversationId ?: return
        guidingQueued = true
        scope.launch {
            try {
                if (busy) {
                    status.text = "正在引导当前任务…"
                    val running = sendJob
                    running?.cancel()
                    val stopped = withTimeoutOrNull(10_000) { running?.join(); true } ?: false
                    if (!stopped) {
                        status.text = "当前推理尚未停止，队列已保留；稍后可再点引导"
                        return@launch
                    }
                }
                if (currentConversationId != id) return@launch
                val pending = agent.pendingQueuedInputs(id)
                if (pending.none { it.id == queueId }) {
                    status.text = "这条指令已进入会话或被移除"
                    refreshQueue()
                    return@launch
                }
                if (!modelReady || !agent.hasModel) {
                    status.text = "请先连接模型，再继续队列"
                    return@launch
                }
                performSend("", queueId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "引导失败：${error.message ?: "请重试"}"
                refreshQueue()
            } finally {
                guidingQueued = false
            }
        }
    }

    private fun discardQueued(queueId: String) {
        val id = currentConversationId ?: return
        scope.launch {
            try {
                agent.discardQueuedInput(id, queueId)
                status.text = "已从队列移除"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "移除失败：${error.message ?: "指令可能已开始处理"}"
            } finally {
                refreshQueue()
            }
        }
    }

    private fun continueQueue() {
        if (busy || guidingQueued || queueItems.isEmpty()) return
        guideQueued(queueItems.first().id)
    }

    private fun toggleVoice() {
        if (voiceRecorder.isRecording) {
            voicePending = true
            voiceRecorder.stop()
            recordingWaveform = null
            voiceButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            voiceButton.text = "…"
            voiceButton.contentDescription = "正在结束录音"
            status.text = "正在准备语音转写…"
            return
        }
        if (voicePending || voiceProcessing) return
        if (busy) {
            status.text = "请等待当前回复完成"
            return
        }
        if (!voiceFiles.ready) {
            status.text = "请先在设置中导入 SenseVoice 模型和 tokens.txt"
            showSettings(voice = true)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION)
            return
        }
        startVoice()
    }

    private fun startVoice() {
        try {
            speech.stop()
            speechJob?.cancel()
            voiceRecorder.start(scope)
            voicePending = true
            showRecordingWaveform()
            status.text = "正在收音 · 再点一次结束，最长 30 秒"
        } catch (error: Exception) {
            voicePending = false
            status.text = "无法录音：${error.message ?: "请检查麦克风"}"
        }
    }

    private fun onVoiceRecorded(result: Result<Pcm16Audio>) {
        voicePending = false
        showIdleVoiceButton()
        val audio = result.getOrElse {
            status.text = "录音失败：${it.message ?: "请重试"}"
            return
        }
        voiceProcessing = true
        voiceButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        voiceButton.text = "…"
        voiceButton.contentDescription = "正在转写语音"
        status.text = "正在本机转写…"
        scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) {
                    SenseVoiceRecognizer(voiceFiles.model, voiceFiles.tokens).use { it.transcribe(audio) }
                }
                if (transcript.text.isBlank()) {
                    status.text = "未识别到语音，请重试"
                } else {
                    val existing = composer.text.toString().trimEnd()
                    composer.setText(if (existing.isBlank()) transcript.text else "$existing ${transcript.text}")
                    composer.setSelection(composer.length())
                    status.text = "转写完成 · 请核对或修改文字，再点发送"
                    composer.requestFocus()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "转写失败：${error.message ?: "请检查语音模型文件"}"
            } finally {
                voiceProcessing = false
                showIdleVoiceButton()
            }
        }
    }

    private fun showIdleVoiceButton() {
        recordingWaveform = null
        voiceButton.text = ""
        voiceButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_microphone_outline, 0, 0, 0)
        voiceButton.contentDescription = "开始语音输入"
    }

    private fun showRecordingWaveform() {
        val waveform = RecordingWaveformDrawable(this)
        recordingWaveform = waveform
        voiceButton.text = ""
        voiceButton.setCompoundDrawablesWithIntrinsicBounds(waveform, null, null, null)
        voiceButton.contentDescription = "正在录音，点按结束并转写"
    }

    private fun onVoiceLevel(level: Float) {
        recordingWaveform?.pushLevel(level)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            RECORD_AUDIO_PERMISSION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
                else status.text = "未获得麦克风权限，无法语音输入"
            }
            CAMERA_PERMISSION_PHOTO -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto()
                else status.text = "未获得相机权限，无法拍照"
            }
        }
    }

    private fun performSend(instruction: String, queueId: String? = null) {
        val files = if (queueId == null) selectedUris.toList() else emptyList()
        val transferFiles = files.filter { it in albumTransferUris }
        val scanId = scanDraftId.takeIf { queueId == null && instruction.contains("扫描原文：") }
        val scanReferenceFiles = files.filter { uri ->
            scanId != null && uri.authority == "$packageName.capture" &&
                uri.pathSegments.firstOrNull() == "document-drafts" &&
                uri.pathSegments.getOrNull(1) == scanId
        }
        val id = currentConversationId ?: return
        liveTools.clear()
        if (queueId == null) {
            selectedUris.clear()
            albumTransferUris.clear()
            scanDraftId = null
            showAttachments()
            composer.text.clear()
        }
        busy = true
        renderQueue()
        initialUserEventPending = queueId == null
        answerShownInRun = false
        status.text = if (queueId == null) "正在发送…" else "正在处理引导指令…"
        sendJob = scope.launch {
            var completedForQueue = false
            try {
                if (queueId == null) {
                    val previews = withContext(Dispatchers.IO) {
                        val store = MessagePreviews(this@MainActivity)
                        files.filter(store::isImage).mapNotNull(store::save)
                    }
                    val shown = buildString {
                        append(instruction.ifBlank { "请处理这些附件" })
                        val named = files.filterNot { MessagePreviews(this@MainActivity).isImage(it) }
                        if (named.isNotEmpty()) append("\n").append(named.joinToString("  ") { "📎 ${displayName(it)}" })
                    }
                    val user = ChatLine("user", shown, previews)
                    addLine(user)
                    conversations.append(id, user)
                }
                status.text = "正在处理…"
                val reply = if (queueId == null) agent.send(id, instruction, files, transferFiles,
                    scanReferenceFiles, scanId != null)
                    else agent.sendQueued(id, queueId)
                if (reply.attention != null || !answerShownInRun) {
                    val line = ChatLine("assistant", reply.text)
                    showFinalAnswer(line)
                    conversations.append(id, line)
                }
                clearCapturedFiles()
                val state = when (reply.attention) {
                    dev.kiora.core.ToolAttention.INPUT_REQUIRED -> "等待补充信息"
                    dev.kiora.core.ToolAttention.AUTH_REQUIRED -> "等待授权"
                    null -> "已完成"
                }
                status.text = listOfNotNull(state, reply.modelDetail).joinToString(" · ")
                completedForQueue = reply.attention == null
            } catch (cancelled: CancellationException) {
                streamRow?.let(transcript::removeView)
                clearStream()
                status.text = "任务已停止；已执行的外部操作请核对结果"
            } catch (error: Exception) {
                clearStream()
                if (queueId == null) {
                    selectedUris.addAll(files)
                    albumTransferUris.addAll(transferFiles)
                    scanDraftId = scanId
                    showAttachments()
                    if (composer.text.isBlank()) composer.setText(instruction)
                }
                status.text = "发送失败：${error.message ?: "未知错误"}"
                addLine(ChatLine("assistant", "发送失败：${error.message ?: "请检查连接后重试"}"))
            } finally {
                withContext(NonCancellable) {
                    liveTools.values.forEach { live ->
                        live.trace = live.trace.copy(state = ToolTrace.State.FAILED, result = "调用中断，未收到结果")
                        runCatching { conversations.updateTool(live.messageId, live.trace) }
                        updateToolLine(live.header, live.trace)
                    }
                    liveTools.clear()
                    busy = false
                    sendJob = null
                    initialUserEventPending = false
                    val pending = runCatching { agent.pendingQueuedInputCount(id) }.getOrDefault(0)
                    if (pending > 0) status.text = "${status.text} · $pending 条指令待下次处理"
                    refreshQueue()
                    if (completedForQueue && pending > 0 && currentConversationId == id) {
                        val next = agent.pendingQueuedInputs(id).firstOrNull()
                        if (next != null) performSend("", next.id)
                    }
                }
            }
        }
    }

    private fun showAttachmentMenu() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        SettingsSheet.showOptions(this, "添加附件", "发送给 Kiora",
            listOf(
                SettingsSheet.Option("扫描文档", "离线识别原文并校对后使用"),
                SettingsSheet.Option("拍照", "拍摄后输入提示词再发送"),
                SettingsSheet.Option("选择照片", "从手机中选择已有图片"),
                SettingsSheet.Option("选择文件", "从工作区或已授权目录选择"),
            )) { index ->
            when (index) {
                0 -> scanDocument()
                1 -> takePhoto()
                2 -> pickPhotos()
                else -> pickFiles()
            }
        }
    }

    private fun scanDocument() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        startActivityForResult(Intent(this, DocumentScanActivity::class.java), DOCUMENT_SCAN)
    }

    private fun takePhoto() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_PHOTO)
            return
        }
        val photo = try {
            File.createTempFile("kiora_capture_", ".jpg", File(cacheDir, "captures").apply { mkdirs() })
        } catch (error: Exception) {
            status.text = "无法准备照片：${error.message ?: "存储不可用"}"
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", photo)
            pendingCaptureFile = photo
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newUri(contentResolver, "Kiora photo", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, CAPTURE_PHOTO)
        } catch (error: Exception) {
            pendingCaptureFile = null
            photo.delete()
            status.text = "无法打开相机：${error.message ?: "请检查是否安装相机应用"}"
        }
    }

    private fun pickPhotos() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, PICK_PHOTOS)
    }

    private fun pickFiles() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        val grants = AuthorizedStorageGrants(this).readableFolders()
        SettingsSheet.showOptions(this, "选择文件", "工作区与已授权目录",
            listOf(SettingsSheet.Option("工作区", "默认授权 · Kiora 创建的文件")) +
                grants.map { SettingsSheet.Option(authorizedFolderName(it), "已授权目录") } +
                SettingsSheet.Option("管理授权目录", "在连接设置中添加或撤销目录")) { index ->
            when {
                index == 0 -> pickWorkspaceFile()
                index <= grants.size -> browseAuthorizedFolder(grants[index - 1])
                else -> startActivityForResult(Intent(this, SettingsActivity::class.java), SETTINGS)
            }
        }
    }

    private fun authorizedFolderName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }

    private fun browseAuthorizedFolder(tree: Uri, parent: Uri? = null) {
        val browser = AuthorizedAttachmentBrowser(contentResolver)
        val directory = parent ?: browser.root(tree)
        val entries = runCatching { browser.children(tree, directory) }.getOrElse { error ->
            status.text = "无法读取授权目录：${error.message ?: "请重新授权"}"
            return
        }
        if (entries.isEmpty()) { status.text = "此目录暂无文件"; return }
        SettingsSheet.showOptions(this, authorizedFolderName(tree), "仅显示此授权目录中的文件",
            entries.map { entry -> SettingsSheet.Option(
                (if (entry.directory) "📁 " else "") + entry.name,
                if (entry.directory) "打开文件夹" else "选择文件") }) { index ->
            val entry = entries[index]
            if (entry.directory) browseAuthorizedFolder(tree, entry.uri)
            else {
                if (entry.uri !in selectedUris) selectedUris.add(entry.uri)
                showAttachments()
                status.text = "已添加授权目录中的文件，输入提示词后点击发送"
            }
        }
    }

    private fun pickWorkspaceFile() {
        val root = File(filesDir, "agent-workspace").canonicalFile
        val files = root.walkTopDown().maxDepth(4).filter { file ->
            file.isFile && file.canonicalPath.startsWith(root.path + File.separator)
        }.take(100).sortedBy { it.relativeTo(root).path.lowercase() }.toList()
        if (files.isEmpty()) {
            status.text = "工作区暂无文件"
            return
        }
        SettingsSheet.showOptions(this, "工作区文件", "选择要附加的文件", files.map { file ->
            SettingsSheet.Option(file.name, file.relativeTo(root).path)
        }) { index ->
            val uri = runCatching {
                FileProvider.getUriForFile(this, "$packageName.capture", files[index])
            }.getOrElse { error ->
                status.text = "无法选择工作区文件：${error.message ?: "路径不可用"}"
                return@showOptions
            }
            if (uri !in selectedUris) selectedUris.add(uri)
            showAttachments()
            status.text = "已添加工作区文件，输入提示词后点击发送"
        }
    }

    @Deprecated("Uses the platform picker result API without an additional Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_PHOTO) {
            val photo = pendingCaptureFile
            pendingCaptureFile = null
            if (resultCode == RESULT_OK && photo != null && photo.length() > 0) {
                val uri = FileProvider.getUriForFile(this, "$packageName.capture", photo)
                capturedFiles[uri] = photo
                selectedUris.add(uri)
                showAttachments()
                status.text = "照片已添加，输入提示词后点击发送"
                composer.post {
                    composer.requestFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                photo?.delete()
                if (resultCode == RESULT_OK) status.text = "拍照未生成图片，请重试"
            }
            return
        }
        if (requestCode == DOCUMENT_SCAN) {
            val removed = selectedUris.removeAll { uri ->
                uri.authority == "$packageName.capture" &&
                    uri.pathSegments.firstOrNull() == "document-drafts" &&
                    runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { } ?: error("missing") }.isFailure
            }
            if (removed) {
                showAttachments()
                status.text = "已移除删除的扫描草稿附件"
            }
        }
        if (requestCode == SETTINGS) {
            val config = try { savedConfig() } catch (error: Exception) {
                status.text = "无法读取已保存的密钥：${error.message ?: "请在设置中重新输入"}"
                return
            }
            if (resultCode == RESULT_OK) allowedToolsByConversation.clear()
            updateModelIndicator()
            if (config != activeConfig || agent.hasUnavailableA2aConnections(config)) {
                startConnection(config)
            } else if (resultCode == RESULT_OK) {
                status.text = "设置已保存"
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == DOCUMENT_SCAN) {
            val id = data.getStringExtra(DocumentScanActivity.EXTRA_DRAFT_ID) ?: return
            val text = data.getStringExtra(DocumentScanActivity.EXTRA_TEXT)?.trim().orEmpty()
            val draft = DocumentDraftStore(this).load(id) ?: run {
                status.text = "扫描草稿无法读取，请重试"
                return
            }
            scanDraftId = id
            // A scanned document is already represented by its corrected OCR text. Sending every
            // page as vision input would fail on the current LiteRT adapter (one image per turn).
            // Remove stale attachments from this draft after a failed send or a page reorder.
            selectedUris.removeAll { uri ->
                uri.authority == "$packageName.capture" &&
                    uri.pathSegments.firstOrNull() == "document-drafts" &&
                    uri.pathSegments.getOrNull(1) == id
            }
            val attachOriginal = draft.pages.size == 1 &&
                selectedUris.none { MessagePreviews(this).isImage(it) }
            if (attachOriginal) {
                val page = draft.pages.first()
                val extension = page.source.extension.ifBlank { "jpg" }
                selectedUris.add(FileProvider.getUriForFile(this, "$packageName.capture", page.source,
                    "scan-page-1.$extension"))
            }
            showAttachments()
            composer.setText("请根据以下扫描原文整理文档，保留可核对的事实。\n\n扫描原文：\n$text\n\n整理要求：")
            composer.setSelection(composer.length())
            status.text = if (attachOriginal) "扫描原文和校对原图已填入；原图不会代替整理后的文档发送"
                else "${draft.pages.size} 页校对文字已填入；原图保留在扫描草稿中，请补充整理要求"
            return
        }
        if (requestCode != PICK_PHOTOS) return
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
            if (MessagePreviews(this).isImage(uri)) {
                val tile = FrameLayout(this).apply {
                    background = rounded(USER_BUBBLE, 15)
                    clipToOutline = true
                }
                attachmentStrip.addView(tile, LinearLayout.LayoutParams(dp(66), dp(62)).apply {
                    rightMargin = dp(8)
                })
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "预览待发送图片：${displayName(uri)}"
                    setOnClickListener { showImagePreview(uri) }
                }
                tile.addView(image, FrameLayout.LayoutParams(-1, -1))
                tile.addView(TextView(this).apply {
                    text = "×"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(0x99000000.toInt(), 12)
                    contentDescription = "移除 ${displayName(uri)}"
                    setOnClickListener { removeSelectedAttachment(uri) }
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.RIGHT))
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { MessagePreviews(this@MainActivity).decode(uri, 160) }
                    if (uri in selectedUris && tile.parent != null) image.setImageBitmap(bitmap)
                    else bitmap?.recycle()
                }
                return@forEach
            }
            attachmentStrip.addView(TextView(this).apply {
                text = "📎 ${displayName(uri)}  ×"
                textSize = 13f
                setTextColor(INK)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(USER_BUBBLE, 15)
                setOnClickListener { removeSelectedAttachment(uri) }
            }, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(8) })
        }
    }

    private fun removeSelectedAttachment(uri: Uri) {
        if (busy) { status.text = "请等待当前回复完成"; return }
        selectedUris.remove(uri)
        albumTransferUris.remove(uri)
        capturedFiles.remove(uri)?.delete()
        showAttachments()
    }

    private fun clearCapturedFiles() {
        capturedFiles.values.forEach(File::delete)
        capturedFiles.clear()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "附件"
    }.getOrDefault("附件")

    private fun showHistory() {
        if (busy || currentConversationId == null) return
        drawerSearch.text.clear()
        drawerOverlay.visibility = View.VISIBLE
        renderHistory("")
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
            text = "◌   搜索本地相册"
            textSize = 15f
            setTextColor(INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { hideHistory(); prepareAlbumSearch() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
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
        historyJob?.cancel()
        historyJob = scope.launch {
            val recent = conversations.sessions(query)
            if (drawerOverlay.visibility == View.VISIBLE) drawHistory(query, recent)
        }
    }

    private fun drawHistory(query: String, recent: List<ChatSession>) {
        drawerList.removeAllViews()
        if (recent.isEmpty()) {
            drawerList.addView(TextView(this).apply {
                text = if (query.isBlank()) "还没有对话，开始一个新对话吧" else "没有找到相关对话"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        recent.forEach { session ->
            val selected = session.id == currentConversationId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(7), dp(12), dp(7))
                background = rounded(if (selected) USER_BUBBLE else Color.WHITE, 14)
                setOnClickListener {
                    if (busy) { status.text = "请等待当前回复完成"; return@setOnClickListener }
                    hideHistory()
                    scope.launch {
                        conversations.select(session.id)
                        currentConversationId = session.id
                        showConversation()
                    }
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
                text = session.preview.replace('\n', ' ').take(45)
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
        historyJob?.cancel()
    }

    @Deprecated("Back press delegates to the navigation drawer")
    override fun onBackPressed() {
        if (drawerOverlay.visibility == View.VISIBLE) hideHistory() else super.onBackPressed()
    }

    private fun newConversation() {
        if (busy) { status.text = "请等待当前回复完成"; return }
        hideHistory()
        scope.launch {
            currentConversationId = conversations.newConversation()
            clearCapturedFiles()
            selectedUris.clear()
            albumTransferUris.clear()
            scanDraftId = null
            showAttachments()
            showConversation()
            status.text = "新会话"
        }
    }

    private fun showSettings(voice: Boolean = false) {
        if (busy) { status.text = "请等待当前回复完成"; return }
        startActivityForResult(Intent(this, SettingsActivity::class.java).apply {
            putExtra("focus_voice", voice)
        }, SETTINGS)
    }

    private fun prepareAlbumSearch() {
        val prompt = "搜索相册中的图片："
        val draft = composer.text.toString()
        if (draft.isBlank()) composer.setText(prompt)
        else if (!draft.contains(prompt)) composer.append(if (draft.endsWith('\n')) prompt else "\n$prompt")
        composer.setSelection(composer.length())
        composer.requestFocus()
        composer.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
        }
        if (!AlbumSearchService.isReady(this)) {
            status.text = "请先在设置中导入相册模型、授权照片并建立索引"
        }
    }

    private fun chooseHeaderModel() {
        if (busy) return
        val provider = ProviderCatalog.byId(settings.getString("provider_id", null)).let {
            if (settings.contains("provider_id")) it
            else ProviderCatalog.infer(settings.getString("model_url", "").orEmpty())
        }
        if (provider.id == "litert" || provider.models.isEmpty()) {
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
                    startConnection(updated)
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
            else -> "选择模型  ⌄"
        }
    }

    private fun savedConfig(): CompanionConfig {
        val providerId = settings.getString("provider_id", "").orEmpty()
        val modelId = settings.getString("model_id", "").orEmpty()
        return CompanionConfig(
            settings.getString("model_url", "").orEmpty(), modelId, secrets.apiKey(providerId),
            a2aConnections.list(), providerId,
            settings.getString("local_model_path", "").orEmpty(),
            settings.getString("local_backend", "cpu").orEmpty(),
            settings.getInt("context_window_tokens", 0).takeIf { it > 0 }
                ?: ModelContextDefaults.WINDOW_TOKENS,
            settings.getString("mnn_config_path", "").orEmpty(),
        )
    }

    private fun startConnection(config: CompanionConfig) {
        val request = ++connectionRequest
        connecting = true
        modelReady = false
        status.text = "正在连接…"
        scope.launch {
            connectMutex.withLock {
                if (request != connectionRequest) return@withLock
                try {
                    val ready = agent.configure(config)
                    if (request != connectionRequest) return@withLock
                    if (activeConfig != config) allowedToolsByConversation.clear()
                    activeConfig = config
                    modelReady = true
                    status.text = ready
                    updateModelIndicator()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: LinkageError) {
                    if (request == connectionRequest) {
                        status.text = "本机模型运行库不兼容：${error.message ?: "请更新应用"}"
                    }
                } catch (error: Exception) {
                    if (request == connectionRequest) {
                        status.text = "连接失败：${error.message ?: "请检查地址"}"
                    }
                } finally {
                    if (request == connectionRequest) connecting = false
                }
            }
        }
    }

    private suspend fun confirmTool(request: ToolAuthorizationRequest): ToolAuthorization {
        val call = request.call
        val mode = approvalMode()
        val effectiveArguments = agent.effectiveSkillArguments(call.name, call.arguments)
        val fileIds = runCatching {
            effectiveArguments["files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        }.getOrDefault(emptyList())
        val sendsFiles = call.name.startsWith("a2a_") && fileIds.isNotEmpty()
        val fileNames = if (sendsFiles) try {
            agent.describeSkillFiles(fileIds)
        } catch (error: Exception) {
            return ToolAuthorization.Denied("无效的文件参数：${error.message ?: "无法解析附件"}")
        } else emptyList()
        val conversationId = request.sessionId
        val granted = call.name in allowedToolsByConversation[conversationId].orEmpty()
        if (!(sendsFiles && mode != ApprovalMode.FULL) && !shouldAskForTool(mode, call.name, granted)) {
            return ToolAuthorization.Allowed
        }
        val title = when {
            sendsFiles -> "发送文件给 ${agent.skillName(call.name) ?: agent.targetName}？"
            else -> ToolTrace(call.id, call.name, call.arguments.toString()).label
        }
        val details = if (sendsFiles) {
            val names = fileNames.joinToString("、")
            val task = runCatching { call.arguments["instruction"]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
            val form = call.arguments["form"]?.toString()?.take(600)
            "文件：$names\n\n电脑端任务：${task.take(600)}" +
                (if (form == null) "" else "\n\n表单：$form")
        } else {
            val arguments = ToolTrace(call.id, call.name, call.arguments.toString()).displayArguments()
            if (call.arguments.isEmpty()) "无需额外参数" else arguments.take(600)
        }
        val note = when {
            sendsFiles -> "仅发送列出的工作区、已选或已授权文件。"
            mode == ApprovalMode.ASK -> "当前设置为每次调用都询问。"
            isHighRiskTool(call.name) -> "此操作需要你逐次确认。"
            else -> "本会话授权仅在本次打开应用期间有效。"
        }
        val allowSession = mode == ApprovalMode.ASSIST && !isHighRiskTool(call.name) && !sendsFiles
        return suspendCancellableCoroutine { continuation ->
            composer.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(composer.windowToken, 0)
            val dialog = ToolApprovalSheet.show(this, title, call.name, details, note, allowSession) { choice ->
                if (continuation.isActive) {
                    when (choice) {
                        ApprovalChoice.ONCE -> continuation.resume(ToolAuthorization.Allowed)
                        ApprovalChoice.SESSION -> {
                            allowedToolsByConversation.getOrPut(conversationId) { mutableSetOf() }.add(call.name)
                            continuation.resume(ToolAuthorization.Allowed)
                        }
                        ApprovalChoice.DENY -> continuation.resume(ToolAuthorization.Denied("用户拒绝"))
                    }
                }
            }
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        }
    }

    private fun approvalMode(): ApprovalMode = ApprovalMode.fromKey(settings.getString("approval_mode", null))

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_PHOTOS = 11
        const val SETTINGS = 13
        const val CAPTURE_PHOTO = 14
        const val RECORD_AUDIO_PERMISSION = 15
        const val DOCUMENT_SCAN = 16
        const val CAMERA_PERMISSION_PHOTO = 17
        const val BG = 0xFFFAFAF8.toInt()
        const val INK = 0xFF20201F.toInt()
        const val MUTED = 0xFF777773.toInt()
        const val STROKE = 0xFFE4E4E0.toInt()
        const val USER_BUBBLE = 0xFFF0F0ED.toInt()
    }
}
