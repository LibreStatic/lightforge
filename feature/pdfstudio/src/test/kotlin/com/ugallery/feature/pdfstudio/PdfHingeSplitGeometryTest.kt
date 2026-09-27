package com.ugallery.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfHingeSplitGeometryTest {
    @Test
    fun neitherPaneEverReachesTheHingeBand_840dpVerticalHingeAtHalf() {
        // The device-fail geometry: an 840dp window, a 16dp-wide vertical hinge centered at
        // 50% (hinge = [412, 428]). The review found the canvas's right edge landing at 530px
        // against a hinge left of 529.7px at this exact case - an overlap caused by using the
        // hinge's raw bounds with no margin for downstream dp->px rounding.
        val hingeLeft = 412f
        val hingeRight = 428f
        val panes = PdfHingeSplitGeometry.compute(840f, hingeLeft, hingeRight)
        assertTrue("left pane must stay clear of the hinge", panes.leftWidthDp <= hingeLeft)
        assertTrue(
            "right pane must stay clear of the hinge",
            (840f - panes.rightWidthDp) >= hingeRight,
        )
        // Explicit clearance on both sides, not just "touching".
        assertTrue(hingeLeft - panes.leftWidthDp >= PdfHingeSplitGeometry.HingeGapDp)
        assertTrue((840f - panes.rightWidthDp) - hingeRight >= PdfHingeSplitGeometry.HingeGapDp)
        // Symmetric hinge position: ties go to the left pane per compute()'s documented rule.
        assertEquals(404f, panes.leftWidthDp, 0.01f)
        assertTrue(panes.canvasOnLeft)
    }

    @Test
    fun widerSideIsChosenForTheCanvas() {
        // Hinge nearer the left edge: the right side is the larger pane.
        val panes = PdfHingeSplitGeometry.compute(1000f, 200f, 216f)
        assertTrue(!panes.canvasOnLeft)
        assertTrue(panes.rightWidthDp > panes.leftWidthDp)
        assertTrue(panes.leftWidthDp <= 200f)
        assertTrue((1000f - panes.rightWidthDp) >= 216f)
    }

    @Test
    fun neverProducesANegativeWidthEvenWhenTheHingeLeavesNoRoom() {
        // A hinge sitting flush against the edge (leaves less than HingeGapDp of room on that
        // side) must floor at 0, never go negative.
        val panes = PdfHingeSplitGeometry.compute(100f, 2f, 10f)
        assertEquals(0f, panes.leftWidthDp, 0f)
        assertTrue(panes.rightWidthDp in 0f..100f)
    }

    @Test
    fun everyWidthIsAWholeNumber_neverRoundsUpAcrossTheHinge() {
        // Fractional hinge bounds (as a real device fold report might have) must floor, not
        // round, so a pane's edge can never land a fraction of a pixel past where it was
        // computed to stop.
        val panes = PdfHingeSplitGeometry.compute(840f, 412.9f, 427.4f)
        assertEquals(404f, panes.leftWidthDp, 0f) // floor(412.9 - 8) = floor(404.9) = 404
        assertEquals(kotlin.math.floor(840f - 427.4f - 8f), panes.rightWidthDp, 0f)
    }
}
