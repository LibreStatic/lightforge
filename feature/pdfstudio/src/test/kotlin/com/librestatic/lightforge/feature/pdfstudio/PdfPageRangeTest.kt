package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfPageRangeTest {
    private fun ok(input: String, pageCount: Int): List<Int> {
        val result = PdfPageRange.parse(input, pageCount)
        return (result as? PdfPageRange.Result.Ok)?.indices
            ?: throw AssertionError("Expected Ok for \"$input\" (pageCount=$pageCount) but got $result")
    }

    private fun error(input: String, pageCount: Int): PdfPageRange.Result.Error {
        val result = PdfPageRange.parse(input, pageCount)
        return result as? PdfPageRange.Result.Error
            ?: throw AssertionError("Expected Error for \"$input\" (pageCount=$pageCount) but got $result")
    }

    @Test
    fun singlePage() {
        assertEquals(listOf(0), ok("1", 5))
    }

    @Test
    fun singlePageInMiddle() {
        assertEquals(listOf(2), ok("3", 5))
    }

    @Test
    fun multipleSinglePagesCommaSeparated() {
        assertEquals(listOf(0, 2, 4), ok("1,3,5", 5))
    }

    @Test
    fun asciiHyphenRange() {
        assertEquals(listOf(0, 1, 2), ok("1-3", 5))
    }

    @Test
    fun enDashRange() {
        assertEquals(listOf(0, 1, 2), ok("1–3", 5))
    }

    @Test
    fun emDashRange() {
        assertEquals(listOf(0, 1, 2), ok("1—3", 5))
    }

    @Test
    fun minusSignRange() {
        assertEquals(listOf(0, 1, 2), ok("1−3", 5))
    }

    @Test
    fun openEndRangeToLastPage() {
        assertEquals(listOf(6, 7, 8, 9), ok("7-", 10))
    }

    @Test
    fun openStartRangeFromFirstPage() {
        assertEquals(listOf(0, 1, 2), ok("-3", 10))
    }

    @Test
    fun whitespaceToleranceAroundTokensAndDashes() {
        assertEquals(listOf(1, 3), ok("  2 ,4-4 ", 5))
    }

    @Test
    fun mergesDuplicateTokens() {
        assertEquals(listOf(0, 1), ok("1,1,2,2", 5))
    }

    @Test
    fun mergesOverlappingRanges() {
        assertEquals(listOf(0, 1, 2, 3), ok("1-3,2-4", 5))
    }

    @Test
    fun sortsIntoDocumentOrderRegardlessOfInputOrder() {
        assertEquals(listOf(0, 1, 4), ok("5, 1-2", 5))
    }

    @Test
    fun reverseInputOrderStillSortsAscending() {
        assertEquals(listOf(0, 1, 2), ok("3,1,2", 5))
    }

    @Test
    fun trailingCommaIsTolerated() {
        assertEquals(listOf(0, 1), ok("1,2,", 5))
    }

    @Test
    fun leadingCommaIsTolerated() {
        assertEquals(listOf(0, 1), ok(",1,2", 5))
    }

    @Test
    fun singlePageProjectWholeRange() {
        assertEquals(listOf(0), ok("1", 1))
    }

    @Test
    fun singlePageProjectSelfRange() {
        assertEquals(listOf(0), ok("1-1", 1))
    }

    @Test
    fun wholeDocumentRange() {
        assertEquals(listOf(0, 1, 2, 3, 4), ok("1-5", 5))
    }

    @Test
    fun emptyStringIsAnError() {
        val e = error("", 5)
        assertEquals(PdfPageRange.Kind.Empty, e.kind)
    }

    @Test
    fun blankStringIsAnError() {
        val e = error("   ", 5)
        assertEquals(PdfPageRange.Kind.Empty, e.kind)
    }

    @Test
    fun onlyCommasIsAnError() {
        val e = error(" , , ", 5)
        assertEquals(PdfPageRange.Kind.Invalid, e.kind)
    }

    @Test
    fun garbageTokenIsInvalid() {
        val e = error("abc", 5)
        assertEquals(PdfPageRange.Kind.Invalid, e.kind)
        assertEquals("abc", e.token)
    }

    @Test
    fun decimalTokenIsInvalid() {
        val e = error("1.5", 5)
        assertEquals(PdfPageRange.Kind.Invalid, e.kind)
    }

    @Test
    fun malformedDoubleRangeIsInvalid() {
        val e = error("1-2-3", 5)
        assertEquals(PdfPageRange.Kind.Invalid, e.kind)
    }

    @Test
    fun lonelyDashIsInvalid() {
        val e = error("-", 5)
        assertEquals(PdfPageRange.Kind.Invalid, e.kind)
    }

    @Test
    fun zeroIsOutOfRange() {
        val e = error("0", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
        assertEquals("0", e.token)
    }

    @Test
    fun pageBeyondCountIsOutOfRange() {
        val e = error("6", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
        assertEquals("6", e.token)
    }

    @Test
    fun rangeEndBeyondCountIsOutOfRange() {
        val e = error("1-6", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
        assertEquals("6", e.token)
    }

    @Test
    fun openStartBeyondCountIsOutOfRange() {
        val e = error("-3", 1)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
        assertEquals("3", e.token)
    }

    @Test
    fun hugeNumberOverflowIsOutOfRange() {
        val e = error("99999999999999999999999", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
    }

    @Test
    fun largeButFinitelyParseableNumberBeyondCountIsOutOfRange() {
        val e = error("99999999999", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
        assertEquals("99999999999", e.token)
    }

    @Test
    fun reversedRangeIsRejected() {
        val e = error("5-3", 5)
        assertEquals(PdfPageRange.Kind.Reversed, e.kind)
        assertEquals("5-3", e.token)
    }

    @Test
    fun reversedRangeWithUnicodeDashIsRejected() {
        val e = error("5–3", 5)
        assertEquals(PdfPageRange.Kind.Reversed, e.kind)
    }

    @Test
    fun firstErrorWinsWhenMultipleTokensAreBad() {
        // "6" (out of range) is encountered before "abc" (invalid).
        val e = error("6,abc", 5)
        assertEquals(PdfPageRange.Kind.OutOfRange, e.kind)
    }

    @Test
    fun summarizeCollapsesConsecutiveRunsIntoRanges() {
        assertEquals("1-3,5", PdfPageRange.summarize(listOf(0, 1, 2, 4)))
    }

    @Test
    fun summarizeSingleIndex() {
        assertEquals("1", PdfPageRange.summarize(listOf(0)))
    }

    @Test
    fun summarizeUnsortedInputIsSortedFirst() {
        assertEquals("1-2,5", PdfPageRange.summarize(listOf(4, 1, 0)))
    }

    @Test
    fun summarizeDedupesInput() {
        assertEquals("1-2", PdfPageRange.summarize(listOf(0, 0, 1, 1)))
    }

    @Test
    fun summarizeEmptyIsEmptyString() {
        assertEquals("", PdfPageRange.summarize(emptyList()))
    }

    @Test
    fun summarizeCustomDashAndSeparator() {
        assertEquals("1–3, 5", PdfPageRange.summarize(listOf(0, 1, 2, 4), dash = "–", separator = ", "))
    }

    @Test
    fun roundTripParseThenSummarizeMatchesCanonicalForm() {
        val indices = ok("5, 1-2", 5)
        assertEquals("1-2,5", PdfPageRange.summarize(indices))
    }
}
