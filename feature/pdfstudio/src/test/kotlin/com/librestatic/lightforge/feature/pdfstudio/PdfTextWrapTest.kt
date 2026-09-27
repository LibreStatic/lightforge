package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfTextWrapTest {
    /** Monospace stand-in measurer: each character is 1 unit wide, so expectations are easy to
     * reason about without a real font. */
    private fun monospace(s: String) = s.length.toFloat()

    @Test
    fun greedyWrapsByWholeWordsWithinTheWidth() {
        val lines = PdfTextWrap.wrap("one two three four", maxWidth = 9f, maxLines = 10, measure = ::monospace)
        // "one two" = 7 chars fits in 9; adding " three" would be 13, too wide.
        assertEquals(listOf("one two", "three", "four"), lines)
    }

    @Test
    fun explicitNewlineAlwaysStartsANewLine() {
        val lines = PdfTextWrap.wrap("ab\ncd", maxWidth = 100f, maxLines = 10, measure = ::monospace)
        assertEquals(listOf("ab", "cd"), lines)
    }

    @Test
    fun aSingleWordWiderThanTheBoxIsHardBrokenAtTheWidestFittingPrefix() {
        val lines = PdfTextWrap.wrap("abcdefghij", maxWidth = 4f, maxLines = 10, measure = ::monospace)
        assertEquals(listOf("abcd", "efgh", "ij"), lines)
    }

    @Test
    fun linesBeyondMaxLinesAreDroppedNotOverflowed() {
        val lines = PdfTextWrap.wrap("one two three four five", maxWidth = 3f, maxLines = 2, measure = ::monospace)
        assertEquals(2, lines.size)
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun zeroMaxLinesProducesNoLines() {
        assertTrue(PdfTextWrap.wrap("anything", maxWidth = 100f, maxLines = 0, measure = ::monospace).isEmpty())
    }

    @Test
    fun emptyTextProducesNoLines() {
        assertTrue(PdfTextWrap.wrap("", maxWidth = 100f, maxLines = 10, measure = ::monospace).isEmpty())
    }

    @Test
    fun aWordExactlyAtTheWidthFitsWithoutHardBreaking() {
        val lines = PdfTextWrap.wrap("abcd", maxWidth = 4f, maxLines = 10, measure = ::monospace)
        assertEquals(listOf("abcd"), lines)
    }

    @Test
    fun hardBreakCanSpanMultipleLinesAndStillRespectMaxLines() {
        val lines = PdfTextWrap.wrap("abcdefgh", maxWidth = 2f, maxLines = 3, measure = ::monospace)
        assertEquals(listOf("ab", "cd", "ef"), lines)
    }

    @Test
    fun multipleParagraphsEachWrapIndependently() {
        val lines =
            PdfTextWrap.wrap("one two\nthree four", maxWidth = 3f, maxLines = 10, measure = ::monospace)
        assertEquals(listOf("one", "two", "thr", "ee", "fou", "r"), lines)
    }
}
