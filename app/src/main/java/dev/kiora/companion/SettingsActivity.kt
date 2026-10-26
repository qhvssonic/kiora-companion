package dev.kiora.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen provider and connection settings; secrets only return to the current process. */
class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("companion_settings", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }
    private val a2aConnections by lazy { A2aConnectionStore(this) }
    private val storageGrants by lazy { AuthorizedStorageGrants(this) }
    private lateinit var provider: ProviderPreset
    private var selectedModel = ""
    private lateinit var providerValue: TextView
    private lateinit var modelValue: TextView
    private lateinit var modelRow: View
    private lateinit var endpointValue: TextView
    private lateinit var customEndpoint: EditText
    private lateinit var customModel: EditText
    private lateinit var apiKey: EditText
    private val draftApiKeys = mutableMapOf<String, String>()
    private lateinit var a2aList: LinearLayout
    private val a2aStatusViews = mutableMapOf<String, TextView>()
    private var a2aMonitorJob: Job? = null
    private var a2aVisible = false
    private lateinit var scanValue: TextView
    private lateinit var scanDetails: TextView
    private lateinit var endpointBlock: LinearLayout
    private lateinit var customModelBlock: LinearLayout
    private lateinit var contextWindowBlock: LinearLayout
    private lateinit var contextWindowValue: EditText
    private lateinit var contextWindowNote: TextView
    private var contextWindowKey = ""
    private lateinit var localBlock: LinearLayout
    private lateinit var localFileValue: TextView
    private lateinit var mnnFolderValue: TextView
    private lateinit var backendValue: TextView
    private lateinit var localNote: TextView
    private lateinit var providerNote: TextView
    private lateinit var keyNote: TextView
    private var localModelPath = ""
    private var mnnConfigPath = ""
    private var localBackend = "cpu"
    private var approvalMode = ApprovalMode.ASSIST
    private var shellEnabled = false
    private lateinit var approvalValue: TextView
    private lateinit var approvalNote: TextView
    private lateinit var shellValue: TextView
    private lateinit var foldersValue: TextView
    private lateinit var galleryValue: TextView
    private lateinit var albumModelValue: TextView
    private lateinit var albumIndexValue: TextView
    private lateinit var voiceModelValue: TextView
    private lateinit var voiceTokensValue: TextView
    private val voiceFiles by lazy { VoiceModelFiles(this) }
    private lateinit var ttsModelValue: TextView
    private var importingTts = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val oldEndpoint = prefs.getString("model_url", "").orEmpty()
        provider = ProviderCatalog.byId(prefs.getString("provider_id", null)).let {
            if (prefs.contains("provider_id")) it else if (oldEndpoint.isBlank()) ProviderCatalog.byId("deepseek")
            else ProviderCatalog.infer(oldEndpoint)
        }
        selectedModel = prefs.getString("model_id", "").orEmpty().ifBlank { provider.models.firstOrNull().orEmpty() }
        localModelPath = prefs.getString("local_model_path", "").orEmpty().ifBlank {
            File(filesDir, "models").listFiles()?.firstOrNull {
                it.isFile && it.extension.equals("litertlm", ignoreCase = true)
            }?.absolutePath.orEmpty()
        }
        localBackend = prefs.getString("local_backend", "cpu").orEmpty()
        mnnConfigPath = prefs.getString("mnn_config_path", "").orEmpty().ifBlank {
            File(filesDir, "mnn-models").listFiles()?.firstNotNullOfOrNull { directory ->
                File(directory, "config.json").takeIf(File::isFile)?.absolutePath
            }.orEmpty()
        }
        approvalMode = ApprovalMode.fromKey(prefs.getString("approval_mode", null))
        shellEnabled = prefs.getBoolean("enable_shell", false)
        buildUi(oldEndpoint)
        refreshProvider()
    }

    private fun buildUi(oldEndpoint: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        setContentView(root)
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, dp(62)))
        top.addView(TextView(this).apply {
            text = "‹"
            textSize = 35f
            setTextColor(INK)
            gravity = Gravity.CENTER
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), -1))
        top.addView(TextView(this).apply {
            text = "连接设置"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        top.addView(TextView(this).apply {
            text = "保存"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(INK, 17)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(dp(72), dp(38)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(36))
        }
        scroll.addView(content)
        content.addView(TextView(this).apply {
            text = "选择 Kiora 的模型，并按需添加电脑端工具"
            textSize = 14f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, dp(34)))

        section(content, "模型服务")
        providerValue = selectionRow(content, "提供商") { chooseProvider() }
        providerNote = note("")
        content.addView(providerNote)
        modelValue = selectionRow(content, "模型") { chooseModel() }
        modelRow = modelValue.parent as View
        endpointBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(endpointBlock, LinearLayout.LayoutParams(-1, -2))
        endpointValue = valueCard(endpointBlock, "接口地址", "")
        customEndpoint = inputCard(endpointBlock, "自定义接口地址", "https://example.com", oldEndpoint, false)
        customModelBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(customModelBlock)
        customModel = inputCard(customModelBlock, "自定义模型 ID", "例如服务商公布的模型名称", selectedModel, false)
        contextWindowBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(contextWindowBlock)
        contextWindowValue = inputCard(contextWindowBlock, "上下文管理上限（tokens）",
            "默认 16000", "", false).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        contextWindowNote = note("")
        contextWindowBlock.addView(contextWindowNote)
        localBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(localBlock)
        localFileValue = selectionRow(localBlock, "本地模型文件") { chooseLocalFile() }
        mnnFolderValue = selectionRow(localBlock, "MNN 模型目录") { chooseMnnFolder() }
        backendValue = selectionRow(localBlock, "推理后端") { chooseBackend() }
        localNote = note("")
        localBlock.addView(localNote)
        apiKey = inputCard(content, "模型 API Key", "粘贴你的 API Key", savedApiKey(provider.id), true)
        keyNote = note("密钥会加密保存在本机，重新打开应用后继续使用。留空并保存可清除。")
        content.addView(keyNote)

        section(content, "连接的 A2A Agent")
        scanValue = selectionRow(content, "扫描连接二维码") { scanA2aQr() }
        scanValue.text = "扫码添加连接  ›"
        scanDetails = note("扫描连接码后核对地址和令牌，再保存为独立连接。")
        content.addView(scanDetails)
        selectionRow(content, "手动添加 Agent") { showA2aEditor() }
            .text = "添加 Agent Card 地址  ›"
        a2aList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(a2aList)
        refreshA2aConnections()
        content.addView(note("连接状态每 15 秒更新；检测 Agent Card 和任务端口，不会执行远端任务。"))
        content.addView(note("每个 Agent 可独立启用、编辑或删除；令牌分别加密保存在本机。"))
        content.addView(note("选中的照片和文件先作为附件信息交给模型；只有智能体调用附件工具时才会上传到电脑端。"))

        section(content, "手机文件与相册")
        foldersValue = selectionRow(content, "授权目录") { chooseFolder() }
        content.addView(note("Android 不允许授权 Download 或手机存储的根目录。要使用下载的文件，请在 Download 中新建子文件夹，把文件移入后授权该子文件夹。"))
        galleryValue = selectionRow(content, "相册访问") { chooseGalleryAccess() }
        content.addView(note("目录由你逐个选择；相册获得 Android 完整照片权限后可直接浏览。聊天中手动选中的照片也可直接识别。"))
        albumModelValue = selectionRow(content, "相册检索模型") { openAlbumSetup() }
        albumIndexValue = selectionRow(content, "本地相册索引") { openAlbumSetup() }
        content.addView(note("导入 EmbeddingGemma 2 后可为已授权照片建立本地索引，供相册搜索和智能体工具使用。"))
        refreshStorageGrants()
        refreshAlbumSettings()

        section(content, "端侧语音识别")
        voiceModelValue = selectionRow(content, "SenseVoice Small int8 模型") { chooseVoiceFile(true) }
        voiceTokensValue = selectionRow(content, "SenseVoice tokens.txt") { chooseVoiceFile(false) }
        content.addView(note("导入 sherpa-onnx 版 model.int8.onnx 和同一模型包的 tokens.txt。文件复制到应用私有存储；录音只在本机转写，结果可修改后发送。"))
        refreshVoiceFiles()

        section(content, "离线朗读")
        ttsModelValue = selectionRow(content, "MeloTTS 中文朗读模型") {
            if (!importingTts) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_TTS_FOLDER)
        }
        content.addView(note("选择解压后的完整 MeloTTS 模型目录。推荐 FP32（model.onnx，约 170 MB），也支持 int8。导入后可朗读助手回复，也可要求智能体朗读文字；音频只在本机生成和播放。"))
        refreshTtsModel()

        section(content, "工具授权")
        approvalValue = selectionRow(content, "授权方式") { chooseApprovalMode() }
        approvalNote = note("")
        content.addView(approvalNote)
        refreshApprovalMode()
        shellValue = selectionRow(content, "手机命令工具") { chooseShellEnabled() }
        content.addView(note("在应用权限下执行命令，工作区目录不是访问边界。开启后仍按上方授权方式批准调用。"))
        refreshShellEnabled()
        if (intent.getBooleanExtra("focus_voice", false)) {
            scroll.post { scroll.smoothScrollTo(0, (voiceModelValue.parent as View).top - dp(24)) }
        }
        if (intent.getBooleanExtra("focus_album", false)) {
            scroll.post { scroll.smoothScrollTo(0, (albumModelValue.parent as View).top - dp(24)) }
        }
        if (intent.getBooleanExtra("focus_tts", false)) {
            scroll.post { scroll.smoothScrollTo(0, (ttsModelValue.parent as View).top - dp(24)) }
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            gravity = Gravity.BOTTOM
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8); bottomMargin = dp(10) })
    }

    private fun selectionRow(parent: LinearLayout, title: String, action: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = rounded(Color.WHITE, 18, BORDER)
            setOnClickListener { action() }
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(10) })
        row.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(MUTED) })
        return TextView(this).apply {
            textSize = 16f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            compoundDrawablePadding = dp(6)
            row.addView(this)
        }
    }

    private fun valueCard(parent: LinearLayout, title: String, value: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(PALE, 16)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        box.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(MUTED) })
        return TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(INK)
            setPadding(0, dp(5), 0, 0)
            box.addView(this)
        }
    }

    private fun inputCard(parent: LinearLayout, title: String, hintText: String, value: String,
        secret: Boolean): EditText {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            background = rounded(Color.WHITE, 18, BORDER)
        }
        parent.addView(box, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(10) })
        box.addView(TextView(this).apply { text = title; textSize = 12f; setTextColor(MUTED) })
        return EditText(this).apply {
            setText(value)
            hint = hintText
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(0xFFAAAAA5.toInt())
            background = null
            setSingleLine(true)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, dp(5), 0, 0)
            box.addView(this, LinearLayout.LayoutParams(-1, dp(40)))
        }
    }

    private fun note(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(MUTED)
        setPadding(dp(3), dp(2), dp(3), dp(5))
    }

    private fun chooseApprovalMode() {
        val modes = ApprovalMode.entries
        SettingsSheet.showOptions(this, "如何批准工具调用？", "工具授权",
            modes.map { SettingsSheet.Option(it.title, it.description) },
            modes.indexOf(approvalMode)) { index ->
            approvalMode = modes[index]
            refreshApprovalMode()
        }
    }

    private fun refreshApprovalMode() {
        approvalValue.text = "${approvalMode.title}  ›"
        approvalNote.text = when (approvalMode) {
            ApprovalMode.ASK -> "每次工具调用都询问；模型请求上传附件时会显示目标、文件名和任务。"
            ApprovalMode.ASSIST -> "默认方式。读取工作区、已授权文件和已开放的相册自动执行；修改、命令和上传每次询问。其他电脑端能力可按会话允许。"
            ApprovalMode.FULL -> "自动批准已配置的工具，包括模型请求的附件上传。仍受 Android 权限和 Kiora 沙箱限制。"
        }
    }

    private fun chooseShellEnabled() {
        SettingsSheet.showConfirmation(this,
            if (shellEnabled) "关闭手机命令工具？" else "启用手机命令工具？",
            if (shellEnabled) "关闭后，Kiora 将不能调用手机上的 shell 命令。"
            else "命令以应用权限运行，可以访问工作区以外的应用资源并发起网络请求。",
            if (shellEnabled) "关闭工具" else "启用工具", "工具设置") {
            shellEnabled = !shellEnabled
            refreshShellEnabled()
        }
    }

    private fun refreshShellEnabled() {
        shellValue.text = if (shellEnabled) "已启用  ›" else "未启用  ›"
    }

    private fun chooseProvider() {
        SettingsSheet.showOptions(this, "选择提供商", "模型服务",
            ProviderCatalog.options.mapIndexed { index, preset ->
                val groupTitle = preset.group.title.takeIf {
                    index == 0 || ProviderCatalog.options[index - 1].group != preset.group
                }
                SettingsSheet.Option(preset.name, preset.note, groupTitle = groupTitle)
            },
            ProviderCatalog.options.indexOf(provider)) { index ->
            val previous = provider
            draftApiKeys[previous.id] = apiKey.text.toString()
            provider = ProviderCatalog.options[index]
            if (provider.id != previous.id) {
                apiKey.setText(draftApiKeys[provider.id] ?: savedApiKey(provider.id))
                selectedModel = provider.models.firstOrNull().orEmpty()
                if (provider.id == "custom") {
                    customEndpoint.setText("")
                    customModel.setText("")
                }
                if (provider.models.isEmpty()) customModel.setText("")
            }
            refreshProvider()
        }
    }

    private fun chooseModel() {
        if (provider.id == "custom") { customModel.requestFocus(); return }
        val choices = provider.models + "其他模型 ID…"
        SettingsSheet.showOptions(this, "选择 ${provider.name} 模型", "模型服务",
            choices.mapIndexed { index, choice ->
                SettingsSheet.Option(choice,
                    if (index == provider.models.size) "手动填写服务商提供的模型 ID" else "")
            }, choices.indexOf(selectedModel)) { index ->
            selectedModel = if (index == provider.models.size) "" else provider.models[index]
            if (selectedModel.isBlank()) customModel.setText("")
            refreshProvider()
            if (selectedModel.isBlank()) customModel.requestFocus()
        }
    }

    private fun refreshProvider() {
        providerValue.text = "${provider.name}  ›"
        providerNote.text = provider.note
        providerNote.visibility = if (provider.note.isBlank()) View.GONE else View.VISIBLE
        val manual = provider.id == "custom" || (provider.id != "mnn" && selectedModel !in provider.models)
        modelValue.text = if (manual) "自定义模型  ›" else "$selectedModel  ›"
        endpointValue.text = provider.endpoint
        modelRow.visibility = if (provider.id == "mnn") View.GONE else View.VISIBLE
        val local = provider.id == "litert" || provider.id == "mnn"
        endpointBlock.visibility = if (local) View.GONE else View.VISIBLE
        localBlock.visibility = if (local) View.VISIBLE else View.GONE
        (localFileValue.parent as View).visibility = if (provider.id == "litert") View.VISIBLE else View.GONE
        (mnnFolderValue.parent as View).visibility = if (provider.id == "mnn") View.VISIBLE else View.GONE
        (backendValue.parent as View).visibility = if (provider.id == "litert") View.VISIBLE else View.GONE
        localFileValue.text = if (localModelPath.isBlank()) "选择 .litertlm 文件  ›"
            else "${File(localModelPath).name}  ›"
        mnnFolderValue.text = if (mnnConfigPath.isBlank()) "选择包含 config.json 的目录  ›"
            else "${File(mnnConfigPath).parentFile?.name ?: "MNN 模型"}  ›"
        backendValue.text = if (localBackend == "gpu") "GPU  ›" else "CPU  ›"
        localNote.text = if (provider.id == "mnn")
            "会复制整个模型目录到应用私有存储；图片输入需要 llm_config.json 声明视觉能力，并包含 visual.mnn 及其权重。"
            else "选择 .litertlm 模型文件后会复制到应用私有存储。导入 Gemma 4 E2B 约需 2.6 GB 空间。"
        (apiKey.parent as View).visibility = if (local) View.GONE else View.VISIBLE
        keyNote.visibility = (apiKey.parent as View).visibility
        endpointBlock.getChildAt(0).visibility = if (provider.id == "custom") View.GONE else View.VISIBLE
        customEndpoint.parent.let { (it as View).visibility = if (provider.id == "custom") View.VISIBLE else View.GONE }
        customModelBlock.visibility = if (manual) View.VISIBLE else View.GONE
        if (manual && selectedModel.isNotBlank()) customModel.setText(selectedModel)
        contextWindowBlock.visibility = View.VISIBLE
        contextWindowValue.hint = "默认 16000，可按需修改"
        contextWindowNote.text = if (provider.id == "mnn")
            "Kiora 会在本机历史变长时自动生成摘要；此值用于管理输入，不改变 MNN 模型本身的上下文能力。"
            else if (provider.id == "litert")
                "Kiora 会在接近此值时自动摘要较早对话；如果模型报告更小的实际窗口，会自动收紧输入。"
            else "填写后，Kiora 会为回复、工具结果和图片预留空间，并按此值管理较早的对话。"
        val contextModel = if (provider.id == "mnn")
            File(mnnConfigPath).parentFile?.name.orEmpty() else selectedModel
        val nextContextKey = "${provider.id}:$contextModel"
        if (nextContextKey != contextWindowKey) {
            contextWindowKey = nextContextKey
            val saved = prefs.getInt("context_window_$nextContextKey", 0).takeIf { it > 0 }
            contextWindowValue.setText((saved ?: ModelContextDefaults.WINDOW_TOKENS).toString())
        }
    }

    private fun save() {
        val endpoint = if (provider.id == "custom") customEndpoint.text.toString().trim() else provider.endpoint
        val model = when {
            provider.id == "mnn" -> File(mnnConfigPath).parentFile?.name.orEmpty()
            provider.id == "custom" || selectedModel !in provider.models -> customModel.text.toString().trim()
            else -> selectedModel
        }
        if (endpoint.isNotBlank() && model.isBlank()) {
            customModel.error = "请选择或填写模型 ID"
            return
        }
        if (provider.id == "custom" && model.isNotBlank() && endpoint.isBlank()) {
            customEndpoint.error = "请填写接口地址"
            return
        }
        if (provider.id == "litert" && (localModelPath.isBlank() || !File(localModelPath).isFile)) {
            localFileValue.error = "请先导入 .litertlm 文件"
            return
        }
        if (provider.id == "mnn" && (mnnConfigPath.isBlank() || !File(mnnConfigPath).isFile)) {
            mnnFolderValue.error = "请先导入 MNN 模型目录"
            return
        }
        val contextWindow = contextWindowValue.text.toString().trim().let { raw ->
            if (raw.isBlank()) null else raw.toIntOrNull()?.takeIf { it in 512..2_000_000 }
        }
        if (contextWindowValue.text.toString().isNotBlank() && contextWindow == null) {
            contextWindowValue.error = "请输入 512 到 2000000 之间的 token 数"
            return
        }
        if (provider.id == "litert" && contextWindow == null) {
            contextWindowValue.error = "请填写本地模型的上下文窗口"
            return
        }
        if (endpoint.isBlank() && provider.id != "litert" && provider.id != "mnn") {
            customEndpoint.error = "请先配置模型接口地址"
            return
        }
        val config = CompanionConfig(endpoint, model,
            if (provider.id == "litert" || provider.id == "mnn") "" else apiKey.text.toString().trim(),
            a2aConnections.list(), provider.id, localModelPath, localBackend, contextWindow,
            mnnConfigPath)
        try {
            secrets.saveApiKey(provider.id, config.apiKey)
        } catch (error: Exception) {
            Toast.makeText(this, "密钥保存失败：${error.message ?: "请重试"}", Toast.LENGTH_LONG).show()
            return
        }
        val saved = prefs.edit().putString("provider_id", provider.id).putString("model_url", endpoint)
            .putString("model_id", model)
            .putString("local_model_path", localModelPath).putString("local_backend", localBackend)
            .putString("mnn_config_path", mnnConfigPath)
            .putInt("context_window_tokens", contextWindow ?: 0)
            .putInt("context_window_$contextWindowKey", contextWindow ?: 0)
            .putString("approval_mode", approvalMode.key)
            .putBoolean("enable_shell", shellEnabled)
            .remove("had_secret").commit()
        if (!saved) {
            Toast.makeText(this, "设置保存失败，请重试", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK, Intent())
        finish()
    }

    private fun savedApiKey(providerId: String): String = try {
        secrets.apiKey(providerId)
    } catch (error: Exception) {
        Toast.makeText(this, error.message ?: "无法读取已保存的密钥", Toast.LENGTH_LONG).show()
        ""
    }

    private fun refreshA2aConnections() {
        a2aMonitorJob?.cancel()
        a2aMonitorJob = null
        a2aList.removeAllViews()
        a2aStatusViews.clear()
        val connections = runCatching { a2aConnections.list() }.getOrElse { error ->
            a2aList.addView(note("读取 A2A 连接失败：${error.message}"))
            return
        }
        if (connections.isEmpty()) {
            a2aList.addView(note("尚未添加 A2A Agent。"))
            return
        }
        connections.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(13), dp(16), dp(13))
                background = rounded(Color.WHITE, 16, BORDER)
                setOnClickListener { showA2aActions(item) }
            }
            a2aList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            card.addView(titleRow)
            titleRow.addView(TextView(this).apply {
                text = item.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(INK)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            titleRow.addView(TextView(this).apply {
                text = if (item.enabled) "已启用  ›" else "已停用  ›"
                textSize = 12f
                setTextColor(if (item.enabled) 0xFF167D73.toInt() else MUTED)
                setPadding(dp(8), 0, 0, 0)
            })
            val connectionStatus = TextView(this).apply {
                text = if (item.enabled) "检测中…" else "已停用"
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(5), 0, 0)
            }
            card.addView(connectionStatus)
            a2aStatusViews[item.id] = connectionStatus
            card.addView(TextView(this).apply {
                text = listOfNotNull(item.alias.takeIf(String::isNotBlank)?.let { "触发别名 $it" },
                    item.agentCardUrl).joinToString(" · ")
                textSize = 12f
                setTextColor(MUTED)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
        }
        if (a2aVisible) startA2aMonitor()
    }

    private fun startA2aMonitor() {
        a2aMonitorJob?.cancel()
        if (!a2aVisible) return
        a2aMonitorJob = scope.launch {
            while (isActive) {
                val profiles = runCatching { a2aConnections.list() }.getOrNull().orEmpty()
                profiles.filter { it.enabled }.map { profile ->
                    launch {
                        val result = A2aConnectionHealth.check(profile)
                        if (isActive) a2aStatusViews[profile.id]?.apply {
                            text = result.label
                            setTextColor(if (result.reachable) 0xFF167D73.toInt() else 0xFFB44B43.toInt())
                        }
                    }
                }.joinAll()
                delay(15_000)
            }
        }
    }

    private fun showA2aEditor(existing: A2aConnectionConfig? = null,
                              url: String = "", bearer: String = "") {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val name = inputCard(form, "连接名称", "例如 DSH 落库 Agent",
            existing?.name.orEmpty(), false).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val alias = inputCard(form, "触发别名（可选）", "例如 dsh；聊天中说“通过 dsh”即可指定连接",
            existing?.alias.orEmpty(), false).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val address = inputCard(form, "Agent Card 地址", "http://电脑局域网IP:端口/.well-known/agent-card.json",
            existing?.agentCardUrl ?: url, false)
        val tokenInput = inputCard(form, "Bearer Token（可选）", "开启入站鉴权时填写",
            existing?.token ?: bearer, true)
        form.addView(note("填写电脑端公布的 Agent Card 地址；只有服务开启入站鉴权时才需要令牌。别名在各连接间不可重复。"))
        SettingsSheet.showForm(this, if (existing == null) "添加 A2A Agent" else "编辑 A2A Agent",
            "连接设置", form) {
            val candidate = A2aConnectionConfig(
                existing?.id ?: A2aConnectionStore.newId(),
                name.text.toString().trim(), address.text.toString().trim(),
                tokenInput.text.toString().trim(), existing?.enabled ?: true,
                alias.text.toString().trim().lowercase(),
            )
            runCatching { a2aConnections.upsert(candidate) }
                .onSuccess { setResult(RESULT_OK, Intent()); refreshA2aConnections() }
                .onFailure { error ->
                    Toast.makeText(this, error.message ?: "连接保存失败", Toast.LENGTH_LONG).show()
                }.isSuccess
        }
    }

    private fun showA2aActions(item: A2aConnectionConfig) {
        SettingsSheet.showOptions(this, item.name, "A2A Agent", listOf(
            SettingsSheet.Option("编辑连接", item.agentCardUrl),
            SettingsSheet.Option("立即检测", "检查 Agent Card 和任务端口"),
            SettingsSheet.Option(if (item.enabled) "停用连接" else "启用连接"),
            SettingsSheet.Option("删除连接", "会清除保存在本机的令牌", true),
        )) { index ->
            when (index) {
                0 -> showA2aEditor(item)
                1 -> {
                    if (!item.enabled) {
                        Toast.makeText(this, "请先启用连接", Toast.LENGTH_SHORT).show()
                    } else {
                        a2aStatusViews[item.id]?.apply { text = "检测中…"; setTextColor(MUTED) }
                        startA2aMonitor()
                    }
                }
                2 -> runCatching { a2aConnections.setEnabled(item.id, !item.enabled) }
                    .onSuccess { setResult(RESULT_OK, Intent()); refreshA2aConnections() }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                3 -> SettingsSheet.showConfirmation(this, "删除 ${item.name}？",
                    "会移除这个连接及其保存在本机的令牌。", "删除", "A2A Agent") {
                    runCatching { a2aConnections.remove(item.id) }
                        .onSuccess { setResult(RESULT_OK, Intent()); refreshA2aConnections() }
                        .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun scanA2aQr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_QR)
            return
        }
        try {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setCaptureActivity(A2aPortraitCaptureActivity::class.java)
                .setPrompt("扫描电脑端 Agent 连接二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .initiateScan()
        } catch (error: Exception) {
            scanValue.text = "无法打开扫码器  ›"
            scanDetails.text = error.message ?: "请检查相机是否可用"
        }
    }

    private fun applyA2aQr(raw: String) {
        val payload = try { A2aConnectQr.parse(raw) } catch (error: Exception) {
            scanValue.text = "二维码无效，请重试  ›"
            scanDetails.text = error.message ?: "无法识别连接信息"
            return
        }
        scanValue.text = "扫码添加连接  ›"
        scanDetails.text = buildString {
            append("请核对 Agent Card 地址和令牌后保存。")
            payload.user?.takeIf(String::isNotBlank)?.let { append(" 账号：").append(it).append("。") }
            if (payload.scopes.isNotEmpty()) append(" 声明范围：").append(payload.scopes.joinToString("、")).append("。")
        }
        showA2aEditor(url = payload.agentCardUrl, bearer = payload.token)
    }

    private fun chooseLocalFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, PICK_MODEL)
    }

    private fun chooseMnnFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_MNN_FOLDER)
    }

    private fun chooseVoiceFile(modelFile: Boolean) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, if (modelFile) PICK_VOICE_MODEL else PICK_VOICE_TOKENS)
    }

    private fun refreshVoiceFiles() {
        voiceModelValue.text = if (voiceFiles.model.isFile) "已导入 · ${voiceFiles.model.length() / 1_000_000} MB  ›"
            else "导入 model.int8.onnx  ›"
        voiceTokensValue.text = if (voiceFiles.tokens.isFile) "已导入 tokens.txt  ›"
            else "导入 tokens.txt  ›"
    }

    private fun refreshTtsModel() {
        val ttsModel = MeloTtsModelFiles(this).current()
        ttsModelValue.text = if (ttsModel != null) "已导入 · ${if (ttsModel.modelFileName == "model.onnx") "FP32" else "int8"} · 中文与词典内英文  ›"
            else "导入解压后的模型目录  ›"
    }

    private fun chooseFolder() {
        val existing = storageGrants.folders()
        SettingsSheet.showOptions(this, "授权目录", "手机文件与相册",
            listOf(SettingsSheet.Option("添加目录", "Download 根目录不可选，可授权其中的子文件夹")) +
                existing.map { SettingsSheet.Option(grantName(it), "点按撤销此目录授权", true) }) { index ->
            if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
            else {
                val uri = existing[index - 1]
                SettingsSheet.showConfirmation(this, "撤销目录授权？",
                    "撤销后，Kiora 将无法读取「${grantName(uri)}」中的文件，直到你重新授权。",
                    "撤销授权", "目录授权") {
                    storageGrants.removeFolder(uri)
                    refreshStorageGrants()
                }
            }
        }
    }

    private fun chooseGalleryAccess() {
        if (GalleryAccess.hasFullAccess(this)) {
            SettingsSheet.showOptions(this, "相册访问", "Android 系统权限",
                listOf(SettingsSheet.Option("管理系统权限", "可在系统设置中更改完整相册访问范围"))) {
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        } else {
            requestPermissions(GalleryAccess.permissionsToRequest(), REQUEST_GALLERY_ACCESS)
        }
    }

    private fun refreshStorageGrants() {
        foldersValue.text = "${storageGrants.folders().size} 个目录  ›"
        galleryValue.text = when {
            GalleryAccess.hasFullAccess(this) -> "可浏览完整相册  ›"
            GalleryAccess.hasPartialAccess(this) -> "仅能访问部分照片  ›"
            else -> "未开启  ›"
        }
    }

    private fun openAlbumSetup() {
        startActivity(Intent(this, AlbumIndexActivity::class.java))
    }

    private fun refreshAlbumSettings() {
        val albumPrefs = getSharedPreferences("album_search", MODE_PRIVATE)
        val model = albumPrefs.getString("path", null)?.let(::File)?.takeIf(File::isFile)
        albumModelValue.text = if (model == null) "未导入 · 点按配置  ›"
            else "已导入 · ${model.length() / 1_000_000} MB  ›"
        val sha = albumPrefs.getString("sha256", null)
        val count = if (model != null && sha != null) {
            val store = AlbumIndexStore(this)
            try { store.count("embeddinggemma2-440m:$sha:photo-v1") }
            finally { store.close() }
        } else 0
        albumIndexValue.text = "$count 张照片已索引  ›"
    }

    override fun onResume() {
        super.onResume()
        if (::galleryValue.isInitialized) refreshStorageGrants()
        if (::albumModelValue.isInitialized) refreshAlbumSettings()
        a2aVisible = true
        if (::a2aList.isInitialized) startA2aMonitor()
    }

    override fun onPause() {
        a2aVisible = false
        a2aMonitorJob?.cancel()
        a2aMonitorJob = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_GALLERY_ACCESS) refreshStorageGrants()
        if (requestCode == REQUEST_CAMERA_QR) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) scanA2aQr()
            else {
                scanValue.text = "扫码需要相机权限  ›"
                scanDetails.text = "未获得相机权限，无法扫描连接二维码。"
            }
        }
    }

    private fun grantName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }

    private fun chooseBackend() {
        SettingsSheet.showOptions(this, "推理后端", "本地模型",
            listOf(SettingsSheet.Option("CPU", "兼容性优先"),
                SettingsSheet.Option("GPU", "需要设备支持")),
            if (localBackend == "gpu") 1 else 0) { index ->
            localBackend = if (index == 0) "cpu" else "gpu"
            refreshProvider()
        }
    }

    @Deprecated("Uses the platform file picker without another Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        IntentIntegrator.parseActivityResult(requestCode, resultCode, data)?.let { result ->
            if (result.contents != null) applyA2aQr(result.contents)
            return
        }
        if (resultCode != RESULT_OK) return
        if (requestCode == PICK_TTS_FOLDER) {
            val uri = data?.data ?: return
            if (importingTts) return
            importingTts = true
            ttsModelValue.text = "正在导入离线朗读模型…"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { MeloTtsModelFiles(this@SettingsActivity).importTree(uri) }
                    refreshTtsModel()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ttsModelValue.text = "导入失败：${error.message ?: "请检查模型文件和可用空间"}"
                } finally { importingTts = false }
            }
            return
        }
        if (requestCode == PICK_FOLDER) {
            data?.data?.let { uri ->
                runCatching { storageGrants.addFolder(uri, data?.flags ?: 0) }
                    .onFailure { foldersValue.text = "授权失败：${it.message}" }
                refreshStorageGrants()
            }
            return
        }
        if (requestCode == PICK_MNN_FOLDER) {
            val uri = data?.data ?: return
            mnnFolderValue.text = "正在导入 MNN 模型目录…"
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) { MnnModelFiles(this@SettingsActivity).importTree(uri) }
                    mnnConfigPath = imported.absolutePath
                    refreshProvider()
                } catch (error: Exception) {
                    mnnFolderValue.text = "导入失败：${error.message ?: "请检查模型文件和可用空间"}"
                }
            }
            return
        }
        if (requestCode == PICK_VOICE_MODEL || requestCode == PICK_VOICE_TOKENS) {
            val uri = data?.data ?: return
            val modelFile = requestCode == PICK_VOICE_MODEL
            val expected = if (modelFile) "model.int8.onnx" else "tokens.txt"
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
            if (name != expected) {
                (if (modelFile) voiceModelValue else voiceTokensValue).error = "请选择 $expected"
                return
            }
            val value = if (modelFile) voiceModelValue else voiceTokensValue
            value.text = "正在导入 $name…"
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { voiceFiles.import(uri, modelFile) }
                    refreshVoiceFiles()
                } catch (error: Exception) {
                    value.text = "导入失败：${error.message ?: "请检查可用空间"}"
                }
            }
            return
        }
        if (requestCode != PICK_MODEL) return
        val uri = data?.data ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        if (!name.endsWith(".litertlm", ignoreCase = true)) {
            localFileValue.error = "请选择 .litertlm 文件"
            return
        }
        localFileValue.text = "正在导入 $name…"
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { importModel(uri, name) }
                localModelPath = imported.absolutePath
                refreshProvider()
            } catch (error: Exception) {
                localFileValue.text = "导入失败：${error.message ?: "请检查可用空间"}"
            }
        }
    }

    private fun importModel(uri: Uri, name: String): File {
        val folder = File(filesDir, "models").apply { mkdirs() }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
        val destination = File(folder, safeName)
        val pending = File(folder, "$safeName.pending")
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                pending.outputStream().buffered().use { target -> source.copyTo(target) }
            } ?: error("无法读取所选文件")
            if (destination.exists()) destination.delete()
            check(pending.renameTo(destination)) { "无法保存模型" }
            return destination
        } finally {
            pending.delete()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_MODEL = 2301
        const val PICK_FOLDER = 2302
        const val REQUEST_GALLERY_ACCESS = 2303
        const val PICK_VOICE_MODEL = 2304
        const val PICK_VOICE_TOKENS = 2305
        const val REQUEST_CAMERA_QR = 2306
        const val PICK_MNN_FOLDER = 2307
        const val PICK_TTS_FOLDER = 2308
        const val BG = 0xFFFAFAF8.toInt()
        const val PALE = 0xFFF1F3F0.toInt()
        const val INK = 0xFF20201F.toInt()
        const val MUTED = 0xFF777773.toInt()
        const val BORDER = 0xFFE5E7E3.toInt()
    }
}
