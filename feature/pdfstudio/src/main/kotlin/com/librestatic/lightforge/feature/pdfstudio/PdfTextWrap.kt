package com.librestatic.lightforge.feature.pdfstudio

/**
 * Pure word-wrap for the text layer (Phase G1a): greedy wrap by spaces, an explicit `'\n'` always
 * starts a new line, and a single word wider than the box is hard-broken at the widest prefix that
 * still fits. Lines beyond [maxLines] are dropped rather than painted past the box — the box's own
 * height decides how many lines fit, so this never draws outside it.
 *
 * [measure] is caller-supplied (a string's width at the actual font/size in whatever unit the
 * caller's [maxWidth] uses) so the exact same function drives both the isolated PDFBox renderer
 * (glyph advance widths from the embedded font) and, later, Compose's `TextMeasurer` (Phase G1b) —
 * this file has no Android/PDFBox dependency, so it is plain-JVM testable.
 *
 * Consecutive spaces are preserved as literal tokens (an empty word from splitting on `" "`
 * measures as zero-width but still separates its neighbors), matching how a text editor treats
 * repeated spaces rather than collapsing them.
 */
object PdfTextWrap {
    fun wrap(text: String, maxWidth: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        if (maxLines <= 0) return emptyList()
        val lines = mutableListOf<String>()

        // A single word wider than the box: chop off the widest prefix that still fits, then
        // repeat with what's left, until the word is consumed or the line budget runs out.
        fun addHardBroken(word: String) {
            var remaining = word
            while (remaining.isNotEmpty() && lines.size < maxLines) {
                var cut = remaining.length
                while (cut > 1 && measure(remaining.substring(0, cut)) > maxWidth) cut--
                lines += remaining.substring(0, cut)
                remaining = remaining.substring(cut)
            }
        }

        outer@ for (paragraph in text.split("\n")) {
            if (lines.size >= maxLines) break
            var current = ""
            for (word in paragraph.split(" ")) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure(candidate) <= maxWidth) {
                    current = candidate
                    continue
                }
                if (current.isNotEmpty()) {
                    lines += current
                    current = ""
                    if (lines.size >= maxLines) continue@outer
                }
                if (measure(word) <= maxWidth) {
                    current = word
                } else {
                    addHardBroken(word)
                }
                if (lines.size >= maxLines) continue@outer
            }
            if (current.isNotEmpty() && lines.size < maxLines) lines += current
        }
        return lines.take(maxLines)
    }
}
