package com.ugallery.feature.pdfstudio

import androidx.compose.ui.graphics.Color

/**
 * The only file in feature/pdfstudio/src/main allowed to hold color literals.
 *
 * PDF Studio otherwise takes every color from `MaterialTheme.colorScheme` so it always follows
 * the app's Material You theme (light/dark/dynamic). The exceptions here are physical print-space
 * tokens that must render identically no matter the active theme: the page is always printed on
 * white paper, and the editor-only selection/guide overlay needs a fixed-contrast dual stroke that
 * reads over both light and dark photos.
 */
internal object PdfPaperTokens {
    /** Page paper background and print-space text/ink; PDF pages are always white stock. */
    val Paper = Color.White
    val Ink = Color.Black

    /** Selection outline outer stroke (paired with [GuideInner] for a 21:1 contrast band). */
    val GuideOuter = Color.White
    /** Selection outline / snap guide inner stroke. */
    val GuideInner = Color.Black

    /**
     * Text-layer ink palette (Phase G1a) — fixed print-space literals like [Paper]/[Ink] above,
     * never resolved through the Material theme, so a chosen ink prints the same regardless of
     * the app's light/dark/dynamic theme.
     */
    private val TextInk: Map<PdfInk, Color> =
        mapOf(
            PdfInk.Black to Color(0xFF000000),
            PdfInk.DarkGray to Color(0xFF424242),
            PdfInk.Red to Color(0xFFB3261E),
            PdfInk.Blue to Color(0xFF1355C6),
            PdfInk.Green to Color(0xFF1E7B34),
        )

    /** Compose color for [ink] — the editor (Phase G1b) reads this so it stays the exact color
     * the renderer prints. */
    fun compose(ink: PdfInk): Color = TextInk.getValue(ink)

    /**
     * 0f..1f RGB components for [ink], for the isolated PDFBox renderer — which has no Compose
     * dependency, so it cannot hold a `Color(...)` literal itself (see verify_pdf_studio.py's
     * color-literal guardrail); it calls this instead of constructing one.
     */
    fun rgb(ink: PdfInk): Triple<Float, Float, Float> =
        TextInk.getValue(ink).let { Triple(it.red, it.green, it.blue) }
}
