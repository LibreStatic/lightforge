package com.librestatic.lightforge.feature.viewer.textselect

import com.librestatic.lightforge.core.model.RecognizedText
import com.librestatic.lightforge.core.model.RecognizedWord
import kotlin.math.max

/** Word range selected on a photo; indices point into [RecognizedText.words] and are inclusive. */
internal data class TextSelection(val anchor: Int, val focus: Int) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)

    fun contains(index: Int): Boolean = index in start..end

    fun text(recognized: RecognizedText): String {
        val words = recognized.words
        if (words.isEmpty()) return ""
        val builder = StringBuilder()
        var previousLine = -1
        for (index in start.coerceAtLeast(0)..end.coerceAtMost(words.lastIndex)) {
            val word = words[index]
            if (previousLine != -1) builder.append(if (word.lineIndex != previousLine) '\n' else ' ')
            builder.append(word.text)
            previousLine = word.lineIndex
        }
        return builder.toString()
    }

    companion object {
        fun all(recognized: RecognizedText) = TextSelection(0, recognized.words.lastIndex)
    }
}

/**
 * Geometry helpers in displayed-image pixels: [width] x [height] is the size the image is drawn at
 * and points are relative to its top-left corner, so distances stay isotropic.
 */
internal class TextSelectionGeometry(
    private val recognized: RecognizedText,
    private val width: Float,
    private val height: Float,
) {
    fun left(word: RecognizedWord) = word.left * width
    fun right(word: RecognizedWord) = word.right * width
    fun top(word: RecognizedWord) = word.top * height
    fun bottom(word: RecognizedWord) = word.bottom * height

    /** Word under [x],[y], or the closest one within [slop] pixels; null when nothing is near. */
    fun hitTest(x: Float, y: Float, slop: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        recognized.words.forEachIndexed { index, word ->
            val dx = max(max(left(word) - x, x - right(word)), 0f)
            val dy = max(max(top(word) - y, y - bottom(word)), 0f)
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best?.takeIf { bestDistance <= slop * slop }
    }

    /**
     * Word a dragged handle snaps to: first the line whose vertical band is closest to [y], then
     * the word in that line horizontally closest to [x]. Always returns a word when any exist.
     */
    fun nearest(x: Float, y: Float): Int? {
        val words = recognized.words
        if (words.isEmpty()) return null
        var bestLine = 0
        var bestLineDistance = Float.MAX_VALUE
        recognized.lines.forEachIndexed { lineIndex, line ->
            val top = line.words.minOf { top(it) }
            val bottom = line.words.maxOf { bottom(it) }
            val distance = when {
                y < top -> top - y
                y > bottom -> y - bottom
                else -> 0f
            }
            if (distance < bestLineDistance) {
                bestLineDistance = distance
                bestLine = lineIndex
            }
        }
        var best = -1
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            if (word.lineIndex != bestLine) return@forEachIndexed
            val distance = when {
                x < left(word) -> left(word) - x
                x > right(word) -> x - right(word)
                else -> 0f
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best.takeIf { it >= 0 }
    }
}
