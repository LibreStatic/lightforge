package com.librestatic.lightforge.feature.pdfstudio

/**
 * Parses page-range strings like "1-3, 5" for the export sheet's Custom pages field (Phase G3).
 * Pure Kotlin with no Android/Compose dependency, so it is unit-testable on the JVM, and returns a
 * typed result rather than throwing for user-typed input.
 *
 * Accepted syntax per comma-separated token (1-based, whitespace around commas/dashes is ignored):
 *  - `"N"`   a single page
 *  - `"A-B"` an ascending range (`A <= B`)
 *  - `"N-"`  from `N` to the last page
 *  - `"-N"`  from the first page to `N`
 *
 * The en dash (`–`), em dash (`—`) and minus sign (`−`) are accepted as range
 * separators the same as the plain hyphen, since a phone's suggested punctuation often substitutes
 * one for the other. Duplicate and overlapping tokens are merged; the result is always sorted,
 * distinct 0-based page indices in document order (ascending), regardless of the order the tokens
 * were typed in — e.g. `"5, 1-2"` parses to `[0, 1, 4]`.
 */
object PdfPageRange {
    sealed interface Result {
        /** Sorted, distinct 0-based page indices, in document order. */
        data class Ok(val indices: List<Int>) : Result

        /** [token] is the offending piece of input (empty for [Kind.Empty]). */
        data class Error(val kind: Kind, val token: String) : Result
    }

    enum class Kind {
        /** The input was blank (or only commas/whitespace). */
        Empty,
        /** A token was not a number, a range, or an open-ended range. */
        Invalid,
        /** A page number was 0, unparseably large, or greater than the page count. */
        OutOfRange,
        /** An `"A-B"` range had `A > B`. */
        Reversed,
    }

    private val dashes = charArrayOf('–', '—', '−')
    private val singlePattern = Regex("^\\d+$")
    private val rangePattern = Regex("^(\\d+)-(\\d+)$")
    private val openEndPattern = Regex("^(\\d+)-$")
    private val openStartPattern = Regex("^-(\\d+)$")

    private fun normalizeDashes(token: String): String {
        var result = token
        dashes.forEach { result = result.replace(it, '-') }
        return result
    }

    /** [text] as a valid 1-based page number within `1..pageCount`, or null if it does not parse
     * (including overflow past [Long]) or falls outside that range. */
    private fun pageNumberOrNull(text: String, pageCount: Int): Int? {
        val value = text.toLongOrNull() ?: return null
        if (value < 1 || value > pageCount) return null
        return value.toInt()
    }

    fun parse(input: String, pageCount: Int): Result {
        require(pageCount >= 0)
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Error(Kind.Empty, "")
        val tokens = trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result.Error(Kind.Invalid, trimmed)
        val indices = sortedSetOf<Int>()
        for (raw in tokens) {
            val token = normalizeDashes(raw)
            when {
                singlePattern.matches(token) -> {
                    val n = pageNumberOrNull(token, pageCount) ?: return Result.Error(Kind.OutOfRange, raw)
                    indices += n - 1
                }
                rangePattern.matches(token) -> {
                    val (a, b) = rangePattern.matchEntire(token)!!.destructured
                    val start = pageNumberOrNull(a, pageCount) ?: return Result.Error(Kind.OutOfRange, a)
                    val end = pageNumberOrNull(b, pageCount) ?: return Result.Error(Kind.OutOfRange, b)
                    if (start > end) return Result.Error(Kind.Reversed, raw)
                    for (n in start..end) indices += n - 1
                }
                openEndPattern.matches(token) -> {
                    val a = openEndPattern.matchEntire(token)!!.groupValues[1]
                    val start = pageNumberOrNull(a, pageCount) ?: return Result.Error(Kind.OutOfRange, a)
                    for (n in start..pageCount) indices += n - 1
                }
                openStartPattern.matches(token) -> {
                    val b = openStartPattern.matchEntire(token)!!.groupValues[1]
                    val end = pageNumberOrNull(b, pageCount) ?: return Result.Error(Kind.OutOfRange, b)
                    for (n in 1..end) indices += n - 1
                }
                else -> return Result.Error(Kind.Invalid, raw)
            }
        }
        return Result.Ok(indices.toList())
    }

    /**
     * Compact, human-readable form of [indices] (0-based), e.g. `[0, 1, 4]` -> `"1-2,5"`.
     * Consecutive runs collapse into a range. [dash] and [separator] let callers choose ASCII
     * punctuation (an editable field's prefill text) or typographic punctuation (a read-only
     * summary, e.g. an en dash and ", ").
     */
    fun summarize(indices: List<Int>, dash: String = "-", separator: String = ","): String {
        val sorted = indices.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val n = sorted[i]
            if (n == prev + 1) {
                prev = n
                continue
            }
            parts += if (start == prev) "${start + 1}" else "${start + 1}$dash${prev + 1}"
            start = n
            prev = n
        }
        parts += if (start == prev) "${start + 1}" else "${start + 1}$dash${prev + 1}"
        return parts.joinToString(separator)
    }
}
