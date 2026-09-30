package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfMediaPlacementTest {
    @Test
    fun hasRoomForOneMoreAllowsUpToTheLimitAndRejectsAtIt() {
        assertTrue(PdfMediaPlacement.hasRoomForOneMore(0))
        assertTrue(PdfMediaPlacement.hasRoomForOneMore(23))
        assertFalse(PdfMediaPlacement.hasRoomForOneMore(24))
        assertFalse(PdfMediaPlacement.hasRoomForOneMore(30))
        assertFalse(PdfMediaPlacement.hasRoomForOneMore(24, limit = 24))
        assertTrue(PdfMediaPlacement.hasRoomForOneMore(4, limit = 5))
    }

    @Test
    fun centerToTopLeftSubtractsHalfTheSize() {
        val (x, y) = PdfMediaPlacement.centerToTopLeft(100.0, 60.0, 40.0, 20.0)
        assertEquals(80.0, x, 1e-9)
        assertEquals(50.0, y, 1e-9)
    }

    @Test
    fun centerToTopLeftRoundTripsWithTheGeometricCenter() {
        val width = 30.0
        val height = 15.0
        val (x, y) = PdfMediaPlacement.centerToTopLeft(200.0, 150.0, width, height)
        assertEquals(200.0, x + width / 2, 1e-9)
        assertEquals(150.0, y + height / 2, 1e-9)
    }

    @Test
    fun autoSlotTopLeftStepsDiagonallyThenCapsTheOffset() {
        val margin = 10.0
        assertEquals(10.0 to 10.0, PdfMediaPlacement.autoSlotTopLeft(0, margin))
        assertEquals(16.0 to 16.0, PdfMediaPlacement.autoSlotTopLeft(1, margin))
        assertEquals(22.0 to 22.0, PdfMediaPlacement.autoSlotTopLeft(2, margin))
        // Caps at +30mm regardless of how many images are already on the page, so later inserts
        // never march the slot off the page.
        assertEquals(40.0 to 40.0, PdfMediaPlacement.autoSlotTopLeft(50, margin))
    }

    @Test
    fun fitSizeKeepsAspectRatioAndNeverExceedsTheMaxOrThePrintableWidth() {
        // Landscape asset (2:1), plenty of page room: capped by maxWidthMm.
        val (w1, h1) = PdfMediaPlacement.fitSize(pageWidth = 210.0, pageMargin = 10.0, assetWidth = 200, assetHeight = 100)
        assertEquals(85.0, w1, 1e-9)
        assertEquals(42.5, h1, 1e-9)

        // Narrow page: capped by the printable width (page width - 2*margin) instead.
        val (w2, h2) = PdfMediaPlacement.fitSize(pageWidth = 60.0, pageMargin = 10.0, assetWidth = 100, assetHeight = 100)
        assertEquals(40.0, w2, 1e-9)
        assertEquals(40.0, h2, 1e-9)

        // Portrait asset (1:2): height follows the aspect ratio past what width alone implies.
        val (w3, h3) = PdfMediaPlacement.fitSize(pageWidth = 210.0, pageMargin = 10.0, assetWidth = 50, assetHeight = 100)
        assertEquals(85.0, w3, 1e-9)
        assertEquals(170.0, h3, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitSizeRejectsAZeroOrNegativeAssetDimension() {
        PdfMediaPlacement.fitSize(pageWidth = 210.0, pageMargin = 10.0, assetWidth = 0, assetHeight = 100)
    }
}
