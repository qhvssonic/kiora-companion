package dev.kiora.companion

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The selection and confirmation surface used by connection settings. */
internal object SettingsSheet {
    data class Option(val title: String, val description: String = "", val destructive: Boolean = false)

    private const val INK = 0xFF20201F.toInt()
    private const val MUTED = 0xFF777773.toInt()
    private const val SURFACE = 0xFFFAFAF8.toInt()
    private const val DETAIL = 0xFFF1F3F0.toInt()
    private const val STROKE = 0xFFE5E7E3.toInt()
    private const val RED = 0xFFB4473D.toInt()

    fun showOptions(
        activity: Activity,
        title: String,
        subtitle: String,
        options: List<Option>,
        selectedIndex: Int = -1,
        onSelected: (Int) -> Unit,
    ): Dialog {
        val sheet = create(activity, title, subtitle)
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt()
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        sheet.card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(16)
        })

        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = activity.dp(58)
                setPadding(activity.dp(15), activity.dp(12), activity.dp(14), activity.dp(12))
                background = activity.rounded(if (selected) DETAIL else Color.WHITE, 15,
                    if (selected) null else STROKE)
                setOnClickListener {
                    sheet.dialog.dismiss()
                    onSelected(index)
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = activity.dp(8)
            })
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            labels.addView(activity.label(option.title, 15f,
                if (option.destructive) RED else INK, selected))
            if (option.description.isNotBlank()) {
                labels.addView(activity.label(option.description, 12f, MUTED).apply {
                    setPadding(0, activity.dp(4), 0, 0)
                    setLineSpacing(activity.dp(2).toFloat(), 1f)
                })
            }
            row.addView(activity.label(if (selected) "✓" else "›", 18f,
                if (selected) INK else MUTED).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(activity.dp(28), activity.dp(32)))
        }
        addCancel(activity, sheet)
        return present(activity, sheet)
    }

    fun showConfirmation(
        activity: Activity,
        title: String,
        message: String,
        actionLabel: String,
        subtitle: String = "设置",
        onConfirm: () -> Unit,
    ): Dialog {
        val sheet = create(activity, title, subtitle)
        sheet.card.addView(activity.label(message, 14f, INK).apply {
            background = activity.rounded(DETAIL, 15)
            setPadding(activity.dp(15), activity.dp(14), activity.dp(15), activity.dp(14))
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(18)
            bottomMargin = activity.dp(20)
        })
        val actions = LinearLayout(activity)
        sheet.card.addView(actions, LinearLayout.LayoutParams(-1, activity.dp(48)))
        actions.addView(activity.action("取消", false) { sheet.dialog.dismiss() },
            LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = activity.dp(10) })
        actions.addView(activity.action(actionLabel, true) {
            sheet.dialog.dismiss()
            onConfirm()
        }, LinearLayout.LayoutParams(0, -1, 1f))
        return present(activity, sheet)
    }

    fun showForm(activity: Activity, title: String, subtitle: String, form: View,
                 onSave: () -> Boolean): Dialog {
        val sheet = create(activity, title, subtitle)
        sheet.heading.addView(activity.action("保存", true) {
            if (onSave()) sheet.dialog.dismiss()
        }, sheet.heading.childCount - 1, LinearLayout.LayoutParams(activity.dp(66), activity.dp(38)).apply {
            rightMargin = activity.dp(4)
        })
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    Int.MAX_VALUE else View.MeasureSpec.getSize(heightMeasureSpec)
                val maxHeight = minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(),
                    available)
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false }
        scroll.addView(form)
        sheet.card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = activity.dp(16)
        })
        return present(activity, sheet)
    }

    private data class Sheet(val dialog: Dialog, val card: LinearLayout, val heading: LinearLayout)

    private fun create(activity: Activity, title: String, subtitle: String): Sheet {
        val dialog = Dialog(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(13), activity.dp(20), activity.dp(18))
            background = activity.rounded(SURFACE, 24)
        }
        card.addView(View(activity).apply { background = activity.rounded(STROKE, 3) },
            LinearLayout.LayoutParams(activity.dp(38), activity.dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = activity.dp(18)
            })
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(heading, LinearLayout.LayoutParams(-1, -2))
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        names.addView(activity.label(subtitle, 11f, MUTED, true))
        names.addView(activity.label(title, 20f, INK, true).apply {
            setPadding(0, activity.dp(4), 0, 0)
        })
        heading.addView(activity.label("×", 26f, MUTED).apply {
            gravity = Gravity.CENTER
            contentDescription = "关闭"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))
        return Sheet(dialog, card, heading)
    }

    private fun addCancel(activity: Activity, sheet: Sheet) {
        sheet.card.addView(activity.action("取消", false) { sheet.dialog.dismiss() },
            LinearLayout.LayoutParams(-1, activity.dp(46)).apply { topMargin = activity.dp(7) })
    }

    private fun present(activity: Activity, sheet: Sheet): Dialog {
        activity.currentFocus?.let { focus ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focus.windowToken, 0)
        }
        sheet.dialog.setContentView(sheet.card)
        sheet.dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.46f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setGravity(Gravity.BOTTOM)
        }
        sheet.dialog.show()
        sheet.dialog.window?.setLayout(activity.resources.displayMetrics.widthPixels - activity.dp(24), -2)
        sheet.dialog.window?.attributes = sheet.dialog.window!!.attributes.apply { y = activity.dp(12) }
        return sheet.dialog
    }

    private fun Activity.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun Activity.rounded(color: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun Activity.label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun Activity.action(value: String, filled: Boolean, onClick: () -> Unit) =
        label(value, 15f, if (filled) Color.WHITE else INK, true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (filled) INK else SURFACE, 14, if (filled) null else STROKE)
            setOnClickListener { onClick() }
        }
}
