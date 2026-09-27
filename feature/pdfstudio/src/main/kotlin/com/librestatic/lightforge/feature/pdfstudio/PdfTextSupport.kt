package com.librestatic.lightforge.feature.pdfstudio

/**
 * Pure glyph-coverage gate for the text layer (Phase G1a). The bundled fonts — Noto Sans and Noto
 * Serif, Regular and Bold — are built from the `notofonts/latin-greek-cyrillic` subset (see the
 * `name` table each TTF embeds), so their `cmap` only covers Latin, Greek and Cyrillic plus the
 * punctuation/symbol blocks every Noto subset carries. [ALLOWED_RANGES] below is the intersection
 * of all four bundled fonts' `cmap` tables — dumped directly from the TTFs (format 4 and 12
 * subtables), not guessed from Unicode block names — so a codepoint accepted here is guaranteed to
 * have a glyph in every font/weight combination the text layer can pick. That guarantee is the
 * whole point: [check] runs before a [PdfText] is ever added, edited or exported, so nothing is
 * ever exported as a tofu/empty box.
 *
 * Scripts that need complex shaping PDFBox does not perform — Arabic, Hebrew, Devanagari and other
 * Indic scripts, Thai, CJK, emoji — are rejected even where an isolated glyph might exist,
 * because PDFBox draws each glyph at its own advance width with no reordering, contextual shaping
 * or mark positioning, which corrupts those scripts even when individual glyphs are present. None
 * of them are in the bundled fonts' cmap anyway, so this is enforced for free by the allow-list.
 */
object PdfTextSupport {
    // Tab/newline/carriage-return are structural — consumed by PdfTextWrap, never painted as
    // glyphs — so they are accepted even though the fonts' cmap has no entry for most of them.
    private val structuralControls = setOf('\t'.code, '\n'.code, '\r'.code)

    /**
     * Intersection of NotoSans-Regular/Bold and NotoSerif-Regular/Bold `cmap` tables, with
     * codepoints that are technically mapped but never meaningful typed text (NUL, bidi isolates,
     * byte-order mark, object replacement) removed. Covers Basic Latin, Latin-1 Supplement, Latin
     * Extended A/B/Additional/Old-Church-Slavonic-adjacent blocks, IPA/phonetic extensions, Greek
     * (incl. Extended), Cyrillic (incl. Supplement and the historic/legacy extension blocks),
     * common punctuation, currency symbols and a handful of letterlike/number-form symbols shared
     * by every Noto subset.
     */
    private val allowedRanges: List<IntRange> =
        listOf(
            0x000D..0x000D,
            0x0020..0x007E,
            0x00A0..0x0377,
            0x037A..0x037F,
            0x0384..0x038A,
            0x038C..0x038C,
            0x038E..0x03A1,
            0x03A3..0x03E1,
            0x03F0..0x052F,
            0x10FB..0x10FB,
            0x1AB0..0x1AC0,
            0x1AC5..0x1AC5,
            0x1AC7..0x1ACE,
            0x1C80..0x1C88,
            0x1D00..0x1DF9,
            0x1DFB..0x1F15,
            0x1F18..0x1F1D,
            0x1F20..0x1F45,
            0x1F48..0x1F4D,
            0x1F50..0x1F57,
            0x1F59..0x1F59,
            0x1F5B..0x1F5B,
            0x1F5D..0x1F5D,
            0x1F5F..0x1F7D,
            0x1F80..0x1FB4,
            0x1FB6..0x1FC4,
            0x1FC6..0x1FD3,
            0x1FD6..0x1FDB,
            0x1FDD..0x1FEF,
            0x1FF2..0x1FF4,
            0x1FF6..0x1FFE,
            0x2000..0x2064,
            // 0x2066-0x206F (bidi isolates and deprecated format chars) deliberately excluded —
            // never meaningful typed text, even though the font happens to map them.
            0x2070..0x2071,
            0x2074..0x208E,
            0x2090..0x209C,
            0x20A0..0x20C0,
            0x20F0..0x20F0,
            0x2100..0x215F,
            0x2183..0x2184,
            0x2189..0x2189,
            0x2212..0x2212,
            0x25CC..0x25CC,
            0x2C60..0x2C7F,
            0x2DE0..0x2E5D,
            0xA640..0xA69F,
            0xA700..0xA7CA,
            0xA7D0..0xA7D1,
            0xA7D3..0xA7D3,
            0xA7D5..0xA7D9,
            0xA7F2..0xA7FF,
            0xA92E..0xA92E,
            0xAB30..0xAB6B,
            0xFB00..0xFB06,
            0xFE20..0xFE2F,
            // 0xFEFF (BOM) and 0xFFFC/0xFFFD (object/character replacement) deliberately excluded.
            0x10780..0x10785,
            0x10787..0x107B0,
            0x107B2..0x107BA,
            0x1DF00..0x1DF1E,
        )

    private fun supported(codePoint: Int): Boolean =
        codePoint in structuralControls || allowedRanges.any { codePoint in it }

    /**
     * [Result.success] when every codepoint in [text] has a glyph in every bundled font/weight;
     * otherwise a [Result.failure] wrapping [PdfOperationFailure]/[PdfFailure.UnsupportedGlyph],
     * matching every other user-facing failure in this module.
     */
    fun check(text: String): Result<Unit> {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!supported(codePoint)) {
                return Result.failure(PdfOperationFailure(PdfFailure.UnsupportedGlyph))
            }
            index += Character.charCount(codePoint)
        }
        return Result.success(Unit)
    }
}
