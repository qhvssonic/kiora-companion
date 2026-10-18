package dev.kiora.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Full-screen provider and connection settings; secrets only return to the current process. */
class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("companion_settings", MODE_PRIVATE) }
    private lateinit var provider: ProviderPreset
    private var selectedModel = ""
    private lateinit var providerValue: TextView
    private lateinit var modelValue: TextView
    private lateinit var modelRow: View
    private lateinit var endpointValue: TextView
    private lateinit var customEndpoint: EditText
    private lateinit var customModel: EditText
    private lateinit var apiKey: EditText
    private lateinit var cardUrl: EditText
    private lateinit var token: EditText
    private lateinit var endpointBlock: LinearLayout
    private lateinit var customModelBlock: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val oldEndpoint = prefs.getString("model_url", "").orEmpty()
        provider = ProviderCatalog.byId(prefs.getString("provider_id", null)).let {
            if (prefs.contains("provider_id")) it else if (oldEndpoint.isBlank()) ProviderCatalog.options.first()
            else ProviderCatalog.infer(oldEndpoint)
        }
        selectedModel = prefs.getString("model_id", "").orEmpty().ifBlank { provider.models.firstOrNull().orEmpty() }
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
            text = "让 Kiora 连接你的模型与电脑端 Agent"
            textSize = 14f
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-1, dp(34)))

        section(content, "模型服务")
        providerValue = selectionRow(content, "提供商") { chooseProvider() }
        modelValue = selectionRow(content, "模型") { chooseModel() }
        modelRow = modelValue.parent as View
        endpointBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(endpointBlock, LinearLayout.LayoutParams(-1, -2))
        endpointValue = valueCard(endpointBlock, "接口地址", "")
        customEndpoint = inputCard(endpointBlock, "自定义接口地址", "https://example.com", oldEndpoint, false)
        customModelBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(customModelBlock)
        customModel = inputCard(customModelBlock, "自定义模型 ID", "例如服务商公布的模型名称", selectedModel, false)
        apiKey = inputCard(content, "模型 API Key", "粘贴你的 API Key", intent.getStringExtra("api_key").orEmpty(), true)
        content.addView(note("密钥仅在本次运行中保留，关闭应用后需要重新输入。"))

        section(content, "电脑端 Agent")
        cardUrl = inputCard(content, "Agent Card 地址", "https://电脑地址/.well-known/agent-card.json",
            prefs.getString("card_url", "").orEmpty(), false)
        token = inputCard(content, "Bearer Token（可选）", "电脑端要求认证时填写",
            intent.getStringExtra("a2a_token").orEmpty(), true)
        content.addView(note("照片和文件会在发送前显示目标与文件名，由你确认后才上传。"))
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

    private fun chooseProvider() {
        val names = ProviderCatalog.options.map { it.name }.toTypedArray()
        AlertDialog.Builder(this).setTitle("选择提供商").setItems(names) { _, index ->
            val previous = provider
            provider = ProviderCatalog.options[index]
            if (provider.id != previous.id) {
                selectedModel = provider.models.firstOrNull().orEmpty()
                if (provider.id == "custom") {
                    customEndpoint.setText("")
                    customModel.setText("")
                }
                if (provider.id == "none") selectedModel = ""
            }
            refreshProvider()
        }.show()
    }

    private fun chooseModel() {
        if (provider.id == "custom") { customModel.requestFocus(); return }
        val choices = provider.models + "其他模型 ID…"
        AlertDialog.Builder(this).setTitle("选择 ${provider.name} 模型")
            .setItems(choices.toTypedArray()) { _, index ->
                selectedModel = if (index == provider.models.size) "" else provider.models[index]
                if (selectedModel.isBlank()) customModel.setText("")
                refreshProvider()
                if (selectedModel.isBlank()) customModel.requestFocus()
            }.show()
    }

    private fun refreshProvider() {
        providerValue.text = "${provider.name}  ›"
        val manual = provider.id == "custom" || selectedModel !in provider.models
        modelValue.text = if (manual) "自定义模型  ›" else "$selectedModel  ›"
        endpointValue.text = provider.endpoint
        modelRow.visibility = if (provider.id == "none") View.GONE else View.VISIBLE
        endpointBlock.visibility = if (provider.id == "none") View.GONE else View.VISIBLE
        (apiKey.parent as View).visibility = if (provider.id == "none") View.GONE else View.VISIBLE
        endpointBlock.getChildAt(0).visibility = if (provider.id == "custom") View.GONE else View.VISIBLE
        customEndpoint.parent.let { (it as View).visibility = if (provider.id == "custom") View.VISIBLE else View.GONE }
        customModelBlock.visibility = if (manual && provider.id != "none") View.VISIBLE else View.GONE
        if (manual && selectedModel.isNotBlank()) customModel.setText(selectedModel)
    }

    private fun save() {
        val endpoint = if (provider.id == "custom") customEndpoint.text.toString().trim() else provider.endpoint
        val model = when {
            provider.id == "none" -> ""
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
        if (endpoint.isBlank() && cardUrl.text.toString().isBlank()) {
            cardUrl.error = "请配置模型或电脑端 Agent Card"
            return
        }
        val config = CompanionConfig(endpoint, model,
            if (provider.id == "none") "" else apiKey.text.toString().trim(),
            cardUrl.text.toString().trim(), token.text.toString().trim())
        prefs.edit().putString("provider_id", provider.id).putString("model_url", endpoint)
            .putString("model_id", model).putString("card_url", config.agentCardUrl)
            .putBoolean("had_secret", config.apiKey.isNotBlank() || config.a2aToken.isNotBlank()).apply()
        setResult(RESULT_OK, Intent().apply {
            putExtra("model_url", config.modelUrl)
            putExtra("model_id", config.modelId)
            putExtra("api_key", config.apiKey)
            putExtra("card_url", config.agentCardUrl)
            putExtra("a2a_token", config.a2aToken)
        })
        finish()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BG = 0xFFFAFAF8.toInt()
        const val PALE = 0xFFF1F3F0.toInt()
        const val INK = 0xFF20201F.toInt()
        const val MUTED = 0xFF777773.toInt()
        const val BORDER = 0xFFE5E7E3.toInt()
    }
}
