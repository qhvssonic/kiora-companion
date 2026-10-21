package dev.kiora.companion

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class ApprovalChoice { ONCE, SESSION, DENY }

/** A compact, readable approval surface for model-requested actions. */
internal object ToolApprovalSheet {
    private const val INK = 0xFF20201F.toInt()
    private const val MUTED = 0xFF777773.toInt()
    private const val SURFACE = 0xFFFAFAF8.toInt()
    private const val DETAIL = 0xFFF1F1EE.toInt()
    private const val STROKE = 0xFFE4E4E0.toInt()

    fun show(
        activity: Activity,
        title: String,
        toolName: String,
        details: String,
        note: String,
        allowSession: Boolean,
        onChoice: (ApprovalChoice) -> Unit,
    ): Dialog {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val dialog = Dialog(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            background = rounded(SURFACE, 24)
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))
        heading.addView(text("⌘", 23f, INK, true).apply {
            gravity = Gravity.CENTER
            background = rounded(DETAIL, 14)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val names = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        heading.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        names.addView(text("KIORA · 工具授权", 11f, MUTED, true))
        names.addView(text(title, 19f, INK, true).apply { setPadding(0, dp(3), 0, 0) })

        card.addView(text("Kiora 请求执行以下操作", 14f, MUTED).apply {
            setPadding(0, dp(18), 0, dp(10))
        })
        val detailScroll = ScrollView(activity).apply {
            background = rounded(DETAIL, 15)
            isFillViewport = false
        }
        val detailText = text(details, 14f, INK).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        detailScroll.addView(detailText)
        card.addView(detailScroll, LinearLayout.LayoutParams(-1, -2).apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        })
        detailScroll.post {
            if (detailScroll.height > dp(180)) detailScroll.layoutParams =
                detailScroll.layoutParams.apply { height = dp(180) }
        }
        card.addView(text("工具：$toolName", 11f, MUTED).apply {
            setPadding(dp(2), dp(9), 0, 0)
        })
        card.addView(text(note, 12f, MUTED).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(12), dp(2), dp(16))
        })

        fun button(label: String, filled: Boolean, click: () -> Unit) = text(label, 15f,
            if (filled) Color.WHITE else INK, true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (filled) INK else SURFACE, 14, if (filled) null else STROKE)
            setOnClickListener { dialog.dismiss(); click() }
        }
        if (allowSession) {
            card.addView(button("本会话允许", false) { onChoice(ApprovalChoice.SESSION) },
                LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })
        }
        val actions = LinearLayout(activity)
        card.addView(actions, LinearLayout.LayoutParams(-1, dp(48)))
        actions.addView(button("拒绝", false) { onChoice(ApprovalChoice.DENY) },
            LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) })
        actions.addView(button("允许一次", true) { onChoice(ApprovalChoice.ONCE) },
            LinearLayout.LayoutParams(0, -1, 2f))

        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { onChoice(ApprovalChoice.DENY) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.46f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - dp(24), -2)
        dialog.window?.attributes = dialog.window!!.attributes.apply { y = dp(12) }
        return dialog
    }
}
