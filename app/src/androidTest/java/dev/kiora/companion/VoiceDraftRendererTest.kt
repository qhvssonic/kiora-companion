package dev.kiora.companion

import android.animation.ValueAnimator
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Android Editable/ICU/animation regression; no model, microphone or user draft is involved. */
class VoiceDraftRendererTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun appendsKeepEditableAndRevisionsAndFinalTextAreImmediate() = runBlocking {
        lateinit var composer: EditText
        lateinit var renderer: VoiceDraftRenderer
        val original = "原有草稿 "
        val partial = "这是正在显示的实时转写内容，后续文字会继续增加。"
        ui {
            composer = EditText(instrumentation.targetContext).apply { setText(original) }
            val editable = composer.text
            renderer = VoiceDraftRenderer(composer, original)
            renderer.submit(partial)
            assertSame("Must not recreate the Editable with setText", editable, composer.text)
            assertTrue(composer.text.startsWith(original))
            assertTrue(composer.length() > original.length)
            if (ValueAnimator.areAnimatorsEnabled()) assertTrue(composer.length() < (original + partial).length)
            assertFalse(composer.isCursorVisible)
            // Duplicate snapshots do not restart the animation or move displayed text backwards.
            val firstFrame = composer.text.toString()
            renderer.submit(partial)
            assertEquals(firstFrame, composer.text.toString())
        }
        delay(400)
        ui {
            assertEquals(original + partial, composer.text.toString())
            renderer.submit("这里是修订后的文字")
            assertEquals(original + "这里是修订后的文字", composer.text.toString())
            renderer.submit("这里是修订后的文字以及更多新内容，可能还在逐步显示")
            renderer.finish("最终确认的完整文字。")
            assertEquals(original + "最终确认的完整文字。", composer.text.toString())
            assertTrue(composer.isCursorVisible)
            composer.append("人工修改")
        }
        delay(200)
        ui { assertEquals(original + "最终确认的完整文字。人工修改", composer.text.toString()) }
    }

    @Test fun emojiClustersStayWholeAndClosePreventsStaleFrames() = runBlocking {
        lateinit var composer: EditText
        lateinit var renderer: VoiceDraftRenderer
        ui {
            composer = EditText(instrumentation.targetContext)
            renderer = VoiceDraftRenderer(composer, "")
            renderer.submit("👩‍💻你好，这是完整的表情符号")
            assertTrue(composer.text.startsWith("👩‍💻"))
            renderer.submit("👨‍💻修订内容")
            assertEquals("👨‍💻修订内容", composer.text.toString())
            renderer.submit("👨‍💻修订内容还有一段尚未显示完的文字")
            renderer.close()
            composer.setText("已经切到新的输入")
            renderer.submit("过时的识别回调")
        }
        delay(200)
        ui { assertEquals("已经切到新的输入", composer.text.toString()) }
    }
}
