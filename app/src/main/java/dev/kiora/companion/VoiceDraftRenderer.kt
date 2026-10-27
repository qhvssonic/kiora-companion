package dev.kiora.companion

import android.animation.ValueAnimator
import android.icu.text.BreakIterator
import android.view.animation.LinearInterpolator
import android.widget.EditText
import java.util.Locale

/** Main-thread presentation only. ASR snapshots remain authoritative; nothing is generated here. */
internal class VoiceDraftRenderer(private val composer: EditText, private val original: String) {
    private val cursorWasVisible = composer.isCursorVisible
    private var animator: ValueAnimator? = null
    private var target = original
    private var closed = false

    init { composer.isCursorVisible = false }

    fun submit(transcript: String) {
        if (closed) return
        val next = fullText(transcript)
        if (next == target) return
        stopAnimation()
        target = next
        val visible = composer.text.toString()
        // Revisions/removals are applied atomically. Never visibly erase and retype a correction.
        if (!next.startsWith(visible)) {
            replaceTail(next)
            return
        }
        val suffix = next.substring(visible.length)
        if (suffix.isEmpty()) return
        // ICU character boundaries keep emoji sequences, surrogate pairs and combining marks intact.
        val boundaries = mutableListOf<Int>()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(next) }
        var boundary = iterator.following(visible.length)
        while (boundary != BreakIterator.DONE) {
            boundaries += boundary
            boundary = iterator.next()
        }
        if (boundaries.isEmpty()) { replaceTail(next); return }
        replaceTail(next.substring(0, boundaries.first()))
        if (boundaries.size == 1) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = minOf(120L, (boundaries.size - 1) * 20L)
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val count = 1 + ((boundaries.size - 1) * (animation.animatedValue as Float)).toInt()
                replaceTail(next.substring(0, boundaries[count - 1]))
            }
            start()
        }
    }

    fun finish(transcript: String) {
        if (closed) return
        stopAnimation()
        target = fullText(transcript)
        replaceTail(target)
        close()
    }

    /** On error retain all received text, without waiting for the remaining reveal frames. */
    fun flush() {
        if (closed) return
        stopAnimation()
        replaceTail(target)
    }

    /** Invalidate pending frames before the composer becomes editable or changes conversations. */
    fun close() {
        if (closed) return
        closed = true
        stopAnimation()
        composer.isCursorVisible = cursorWasVisible
    }

    private fun fullText(text: String): String = when {
        text.isBlank() -> original
        original.isBlank() -> text
        else -> "${original.trimEnd()} $text"
    }

    private fun stopAnimation() {
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    private fun replaceTail(next: String) {
        val editable = composer.text
        if (editable.toString() == next) return
        var prefix = 0
        while (prefix < editable.length && prefix < next.length && editable[prefix] == next[prefix]) prefix++
        // A changed supplementary character can share only its high surrogate with the old text.
        if (prefix > 0 && prefix < editable.length && Character.isLowSurrogate(editable[prefix])) prefix--
        editable.replace(prefix, editable.length, next, prefix, next.length)
        if (composer.selectionStart != editable.length || composer.selectionEnd != editable.length)
            composer.setSelection(editable.length)
    }
}
