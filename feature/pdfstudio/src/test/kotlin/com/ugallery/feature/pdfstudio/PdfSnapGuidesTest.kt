package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfSnapGuidesTest {
    private val hash = "a".repeat(64)

    private fun image(x: Double, y: Double, w: Double = 40.0, h: Double = 30.0) =
        PdfImage(asset = hash, x = x, y = y, width = w, height = h)

    @Test
    fun candidatesIncludePageCenterAndMargins() {
        val page = PdfPage(width = 210.0, height = 297.0, margin = 10.0)
        val lines = PdfSnapGuides.candidates(page, emptyList())
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 105.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 10.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 200.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Horizontal && it.position == 148.5 })
    }

    @Test
    fun candidatesIncludeOtherImageEdgesAndCenter() {
        val page = PdfPage()
        val other = image(x = 50.0, y = 60.0, w = 20.0, h = 10.0)
        val lines = PdfSnapGuides.candidates(page, listOf(other))
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 50.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 60.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 70.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Horizontal && it.position == 65.0 })
    }

    @Test
    fun snapsWithinThresholdToPageCenter() {
        val page = PdfPage(width = 210.0, height = 297.0)
        // Image width 40 centered at x=85 would center at 105 (page center); nudge it 1mm off.
        val result = PdfSnapGuides.snap(84.0, 20.0, 40.0, 30.0, PdfSnapGuides.candidates(page, emptyList()))
        assertEquals(85.0, result.x, .0001)
        assertNotNull(result.vertical)
        assertEquals(105.0, result.vertical!!.position, .0001)
    }

    @Test
    fun ignoresGuidesOutsideThreshold() {
        val page = PdfPage(width = 210.0, height = 297.0)
        val result = PdfSnapGuides.snap(70.0, 20.0, 40.0, 30.0, PdfSnapGuides.candidates(page, emptyList()))
        assertEquals(70.0, result.x, .0001)
        assertNull(result.vertical)
    }

    @Test
    fun snapsToOtherImageEdge() {
        val page = PdfPage()
        val other = image(x = 100.0, y = 100.0, w = 30.0, h = 20.0)
        // Dragging near the other image's right edge (130) and top (100).
        val result = PdfSnapGuides.snap(31.0, 99.0, 30.0, 20.0, PdfSnapGuides.candidates(page, listOf(other)))
        // No vertical match here (31 is not within 2mm of any candidate, page has none nearby);
        // horizontal should snap top edge (99) to the other image's top (100).
        assertEquals(100.0, result.y, .0001)
        assertNotNull(result.horizontal)
    }

    @Test
    fun bestMatchIsTheClosestGuideNotTheFirst() {
        val page = PdfPage()
        val a = image(x = 50.0, y = 0.0)
        val b = image(x = 51.0, y = 0.0)
        val candidates = PdfSnapGuides.candidates(page, listOf(a, b))
        val result = PdfSnapGuides.snap(50.6, 0.0, 40.0, 30.0, candidates)
        assertEquals(51.0, result.x, .0001)
    }
}
