package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfStudioLayoutPolicyTest {
    @Test
    fun narrowParentUsesBottomControls() {
        assertEquals(
            PdfStudioLayoutPolicy(false, false),
            PdfStudioLayoutPolicy.forSize(360f, 640f, 1f),
        )
        assertEquals(
            PdfStudioLayoutPolicy(false, true),
            PdfStudioLayoutPolicy.forSize(320f, 640f, 1f),
        )
    }

    @Test
    fun sidePanelsRequireSpaceForScaledText() {
        assertTrue(PdfStudioLayoutPolicy.forSize(840f, 640f, 1f).expanded)
        assertFalse(PdfStudioLayoutPolicy.forSize(840f, 640f, 2f).expanded)
        assertTrue(PdfStudioLayoutPolicy.forSize(1680f, 640f, 2f).expanded)
    }

    @Test
    fun shortOrLargeTextEditorPreservesCanvas() {
        assertTrue(PdfStudioLayoutPolicy.forSize(840f, 320f, 1f).compactChrome)
        assertTrue(PdfStudioLayoutPolicy.forSize(360f, 640f, 2f).compactChrome)
        assertFalse(PdfStudioLayoutPolicy.forSize(840f, 640f, 1f).compactChrome)
    }
}
