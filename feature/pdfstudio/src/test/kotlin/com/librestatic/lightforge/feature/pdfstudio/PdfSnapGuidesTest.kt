package com.librestatic.lightforge.feature.pdfstudio

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
    fun resolveDragAppliesGridBeforeGuides() {
        val page = PdfPage(width = 210.0, height = 297.0)
        val candidates = PdfSnapGuides.candidates(page, emptyList())
        // 83 rounds to 85 on a 5 mm grid; 85 is then within 2mm of nothing here, so it should
        // land exactly on the grid value, not the raw candidate.
        val result = PdfSnapGuides.resolveDrag(83.0, 20.0, 10.0, 10.0, candidates, gridMm = 5.0)
        assertEquals(85.0, result.x, .0001)
    }

    @Test
    fun resolveDragWithoutGridStillAppliesGuides() {
        val page = PdfPage(width = 210.0, height = 297.0)
        val candidates = PdfSnapGuides.candidates(page, emptyList())
        // width=10 keeps the center/right edges (109/114) well outside the threshold, so only
        // the left edge (104, 1mm from the page-center guide at 105) can match.
        val result = PdfSnapGuides.resolveDrag(104.0, 20.0, 10.0, 30.0, candidates, gridMm = null)
        assertEquals(105.0, result.x, .0001)
        assertNotNull(result.vertical)
    }

    @Test
    fun resolveDragMatchesManualGridThenGuideComposition() {
        // The exact composition the canvas's onDragEnd used to do manually, to prove
        // resolveDrag(..., gridMm = 5.0) is equivalent (live drag and commit can never disagree
        // since both now call the same function).
        val page = PdfPage(width = 210.0, height = 297.0)
        val candidateX = 83.3
        val candidateY = 19.6
        val manualGridX = kotlin.math.round(candidateX / 5) * 5
        val manualGridY = kotlin.math.round(candidateY / 5) * 5
        val candidates = PdfSnapGuides.candidates(page, emptyList())
        val manual = PdfSnapGuides.snap(manualGridX, manualGridY, 10.0, 10.0, candidates)
        val viaHelper = PdfSnapGuides.resolveDrag(candidateX, candidateY, 10.0, 10.0, candidates, gridMm = 5.0)
        assertEquals(manual.x, viaHelper.x, .0001)
        assertEquals(manual.y, viaHelper.y, .0001)
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

    // --- Phase G1b: guiding against texts too ---

    @Test
    fun candidatesForIncludesOtherTextEdgesAndCenter() {
        val page = PdfPage()
        val text = PdfText(text = "x", x = 50.0, y = 60.0, width = 20.0, height = 10.0)
        val lines = PdfSnapGuides.candidatesFor(page, emptyList(), listOf(text))
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 50.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Vertical && it.position == 70.0 })
        assertTrue(lines.any { it.orientation == PdfSnapGuides.Orientation.Horizontal && it.position == 65.0 })
    }

    @Test
    fun candidatesForCombinesImagesAndTextsInOneList() {
        val page = PdfPage()
        val other = image(x = 100.0, y = 100.0)
        val text = PdfText(text = "x", x = 20.0, y = 20.0, width = 10.0, height = 10.0)
        val combined = PdfSnapGuides.candidatesFor(page, listOf(other), listOf(text))
        // Same as calling candidates(page, images) plus the text-only guides, unioned.
        val fromImagesOnly = PdfSnapGuides.candidates(page, listOf(other))
        val textOnly = PdfSnapGuides.candidatesFor(page, emptyList(), listOf(text))
        assertEquals((fromImagesOnly + textOnly).toSet(), combined.toSet())
    }

    @Test
    fun candidatesDelegatesToCandidatesForWithNoTexts() {
        val page = PdfPage()
        val other = image(x = 12.0, y = 34.0)
        assertEquals(
            PdfSnapGuides.candidatesFor(page, listOf(other), emptyList()),
            PdfSnapGuides.candidates(page, listOf(other)),
        )
    }
}
