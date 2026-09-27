package com.ugallery.feature.pdfstudio

import com.ugallery.core.designsystem.GalleryFoldInfo
import com.ugallery.core.designsystem.GalleryFoldOrientation
import androidx.compose.ui.unit.dp
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

    @Test
    fun defaultModeIsExpandedThreePaneWithoutAFold() {
        assertEquals(PdfStudioLayoutMode.Compact, PdfStudioLayoutPolicy.forSize(360f, 640f, 1f).mode)
        assertEquals(
            PdfStudioLayoutMode.ExpandedThreePane,
            PdfStudioLayoutPolicy.forSize(1200f, 800f, 1f).mode,
        )
    }

    @Test
    fun verticalSeparatingFoldWithRoomOnBothSidesIsHingeSplit() {
        // A foldable-like inner screen: ~1600dp wide, hinge roughly centered, both sides well above
        // the 280dp*scale minimum pane width.
        val fold = GalleryFoldInfo(
            orientation = GalleryFoldOrientation.Vertical,
            isSeparating = true,
            left = 790.dp,
            top = 0.dp,
            right = 810.dp,
            bottom = 900.dp,
        )
        val policy = PdfStudioLayoutPolicy.forSize(1600f, 900f, 1f, fold)
        assertEquals(PdfStudioLayoutMode.HingeSplit, policy.mode)
        assertTrue(policy.expanded)
        assertEquals(fold, policy.foldInfo)
    }

    @Test
    fun verticalSeparatingFoldWithATooNarrowSideFallsBackToCompact() {
        // The hinge sits close to one edge, so one side would be too cramped for its own pane.
        val fold = GalleryFoldInfo(
            orientation = GalleryFoldOrientation.Vertical,
            isSeparating = true,
            left = 100.dp,
            top = 0.dp,
            right = 120.dp,
            bottom = 900.dp,
        )
        val policy = PdfStudioLayoutPolicy.forSize(1600f, 900f, 1f, fold)
        assertEquals(PdfStudioLayoutMode.Compact, policy.mode)
    }

    @Test
    fun nonSeparatingVerticalFoldDoesNotForceHingeSplit() {
        val fold = GalleryFoldInfo(
            orientation = GalleryFoldOrientation.Vertical,
            isSeparating = false,
            left = 790.dp,
            top = 0.dp,
            right = 810.dp,
            bottom = 900.dp,
        )
        val policy = PdfStudioLayoutPolicy.forSize(1600f, 900f, 1f, fold)
        assertEquals(PdfStudioLayoutMode.ExpandedThreePane, policy.mode)
    }

    @Test
    fun horizontalSeparatingFoldIsTabletopRegardlessOfWidth() {
        val fold = GalleryFoldInfo(
            orientation = GalleryFoldOrientation.Horizontal,
            isSeparating = true,
            left = 0.dp,
            top = 390.dp,
            right = 840.dp,
            bottom = 410.dp,
        )
        // Even a narrow (compact-width) tabletop posture must resolve to Tabletop, not Compact.
        assertEquals(PdfStudioLayoutMode.Tabletop, PdfStudioLayoutPolicy.forSize(600f, 800f, 1f, fold).mode)
        assertEquals(PdfStudioLayoutMode.Tabletop, PdfStudioLayoutPolicy.forSize(1200f, 800f, 1f, fold).mode)
    }
}
