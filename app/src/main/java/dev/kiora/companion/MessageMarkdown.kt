package dev.kiora.companion

import android.content.Context
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/** Renders assistant Markdown as native Android text, including GFM tables and task lists. */
class MessageMarkdown(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.17f, 1.08f, 1f, 1f))
                builder.headingBreakHeight(0)
                builder.blockMargin((14 * density).toInt())
                builder.linkColor(0xFF167D73.toInt())
                builder.blockQuoteColor(0xFF167D73.toInt())
                builder.codeBackgroundColor(0xFFF1F3F0.toInt())
                builder.codeBlockBackgroundColor(0xFFF1F3F0.toInt())
            }
        })
        .build()

    fun render(view: TextView, source: String) {
        markwon.setMarkdown(view, MobileMarkdownTables.format(source))
    }

    fun plainText(source: String): String = markwon.toMarkdown(source).toString()
}
