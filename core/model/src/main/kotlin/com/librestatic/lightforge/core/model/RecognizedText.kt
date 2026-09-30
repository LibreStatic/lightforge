package com.librestatic.lightforge.core.model

/**
 * On-demand text recognition result for one image, used by interactive text selection.
 *
 * Boxes are normalized to 0..1 of the recognized image so callers can map them onto any
 * rendering of the same image. Words are listed in reading order (block, line, element).
 */
data class RecognizedText(val lines: List<RecognizedLine>) {
    val words: List<RecognizedWord> = lines.flatMap { it.words }
    fun isEmpty(): Boolean = words.isEmpty()
}

data class RecognizedLine(val words: List<RecognizedWord>)

data class RecognizedWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Index of the line this word belongs to in [RecognizedText.lines]. */
    val lineIndex: Int,
)
