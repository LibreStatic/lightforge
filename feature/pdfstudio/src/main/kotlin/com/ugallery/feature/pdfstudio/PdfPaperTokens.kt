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
}
