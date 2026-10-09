package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfPanelLogicTest {
    @Test
    fun layoutStepIsAboutOneMillimeterInPhysicalUnits() {
        PdfUnit.entries.filter { it != PdfUnit.Pixel }.forEach { unit ->
            val mm = PdfStepperMath.layoutStep(unit) * unit.factor(300)
            assertEquals(1.0, mm, 0.3)
        }
    }

    @Test fun stepperMathClampsAtBothEnds() {
        assertEquals(51.0, PdfStepperMath.stepped(50.0, 1.0, 1, 0.0, 100.0), 0.0)
        assertEquals(49.0, PdfStepperMath.stepped(50.0, 1.0, -1, 0.0, 100.0), 0.0)
        assertEquals(100.0, PdfStepperMath.stepped(100.0, 1.0, 1, 0.0, 100.0), 0.0)
        assertEquals(0.0, PdfStepperMath.stepped(0.0, 1.0, -1, 0.0, 100.0), 0.0)
    }

    @Test fun columnsForObviousTemplatesAreOrientationIndependent() {
        assertEquals(1, PdfLayoutTemplates.columnsFor(1, landscape = true))
        assertEquals(1, PdfLayoutTemplates.columnsFor(1, landscape = false))
        assertEquals(2, PdfLayoutTemplates.columnsFor(4, landscape = true))
        assertEquals(2, PdfLayoutTemplates.columnsFor(4, landscape = false))
        assertEquals(3, PdfLayoutTemplates.columnsFor(9, landscape = true))
        assertEquals(3, PdfLayoutTemplates.columnsFor(9, landscape = false))
    }

    @Test fun columnsForTwoAndSixFollowOrientation() {
        assertEquals(2, PdfLayoutTemplates.columnsFor(2, landscape = true))
        assertEquals(1, PdfLayoutTemplates.columnsFor(2, landscape = false))
        assertEquals(3, PdfLayoutTemplates.columnsFor(6, landscape = true))
        assertEquals(2, PdfLayoutTemplates.columnsFor(6, landscape = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun columnsForRejectsUnsupportedTemplate() {
        PdfLayoutTemplates.columnsFor(3, landscape = true)
    }

    @Test fun rowsForDistinguishesTemplatesSharingAColumnCount() {
        // 4 and 6 share 2 columns in portrait but must differ in rows (Phase F item 0).
        assertEquals(2, PdfLayoutTemplates.columnsFor(4, landscape = false))
        assertEquals(2, PdfLayoutTemplates.columnsFor(6, landscape = false))
        assertEquals(2, PdfLayoutTemplates.rowsFor(4, landscape = false))
        assertEquals(3, PdfLayoutTemplates.rowsFor(6, landscape = false))
        // And 3 and 9 share 3 columns in landscape but differ in rows.
        assertEquals(3, PdfLayoutTemplates.columnsFor(9, landscape = true))
        assertEquals(3, PdfLayoutTemplates.columnsFor(6, landscape = true))
        assertEquals(3, PdfLayoutTemplates.rowsFor(9, landscape = true))
        assertEquals(2, PdfLayoutTemplates.rowsFor(6, landscape = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rowsForRejectsUnsupportedTemplate() {
        PdfLayoutTemplates.rowsFor(3, landscape = true)
    }

    @Test fun matchForFallsBackToPhotosPerPageTile() {
        // Photo grid / Blank: 2 columns, 4 photos per page -> the 2x2 tile.
        assertEquals(4, PdfLayoutTemplates.matchFor(2, landscape = false, photosPerPage = 4))
        // Receipts: 1 column, 3 per page has no such tile -> stays unselected.
        assertNull(PdfLayoutTemplates.matchFor(1, landscape = false, photosPerPage = 3))
        // An unambiguous column count still wins.
        assertEquals(9, PdfLayoutTemplates.matchFor(3, landscape = false, photosPerPage = 4))
    }

    @Test fun unambiguousMatchIsNullWhenSeveralTemplatesShareAColumnCount() {
        // Portrait: columns == 1 matches both template 1 and template 2; columns == 2 matches
        // both template 4 and template 6 — the carry-over bug from the plan's Phase F item 0.
        assertNull(PdfLayoutTemplates.unambiguousMatch(1, landscape = false))
        assertNull(PdfLayoutTemplates.unambiguousMatch(2, landscape = false))
        assertEquals(9, PdfLayoutTemplates.unambiguousMatch(3, landscape = false))
        // Landscape: columns == 2 matches both template 2 and template 4; columns == 3 matches
        // both template 6 and template 9.
        assertNull(PdfLayoutTemplates.unambiguousMatch(2, landscape = true))
        assertNull(PdfLayoutTemplates.unambiguousMatch(3, landscape = true))
        assertEquals(1, PdfLayoutTemplates.unambiguousMatch(1, landscape = true))
    }

    @Test fun customSizeValidatesRangeAndFinite() {
        assertTrue(PdfCustomSize.validate(210.0, 297.0).isValid)
        assertFalse(PdfCustomSize.validate(10.0, 297.0).isValid)
        assertEquals(PdfCustomSize.Problem.TooSmall, PdfCustomSize.validate(10.0, 297.0).width.problem)
        assertFalse(PdfCustomSize.validate(210.0, 3000.0).isValid)
        assertEquals(PdfCustomSize.Problem.TooLarge, PdfCustomSize.validate(210.0, 3000.0).height.problem)
        assertFalse(PdfCustomSize.validate(null, 297.0).isValid)
        assertEquals(PdfCustomSize.Problem.Invalid, PdfCustomSize.validate(null, 297.0).width.problem)
        assertFalse(PdfCustomSize.validate(Double.NaN, 297.0).isValid)
        // Boundaries are inclusive.
        assertTrue(PdfCustomSize.validate(PdfCustomSize.MIN_MM, PdfCustomSize.MAX_MM).isValid)
    }

    @Test fun cropFocusMoveClampsToUnitRange() {
        assertEquals(0.55, PdfCropFocus.move(0.5, 0.05), 1e-9)
        assertEquals(0.0, PdfCropFocus.move(0.02, -0.05), 1e-9)
        assertEquals(1.0, PdfCropFocus.move(0.98, 0.05), 1e-9)
    }

    @Test fun cropFocusPercentRoundsAndClamps() {
        assertEquals(50, PdfCropFocus.percent(0.5))
        assertEquals(0, PdfCropFocus.percent(0.0))
        assertEquals(100, PdfCropFocus.percent(1.0))
        assertEquals(33, PdfCropFocus.percent(0.333))
    }

    @Test fun cropWindowCenteredOnTallImageInWideFrame() {
        // 1080x2400 into a 93x60 frame: the width fits exactly, the height is cropped.
        val w = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, .5, .5)
        assertEquals(1.0, w.width, 1e-9)
        assertEquals(60.0 / (2400.0 * 93.0 / 1080.0), w.height, 1e-9)
        assertEquals(0.0, w.left, 1e-9)
        assertEquals((1 - w.height) / 2, w.top, 1e-9)
    }

    @Test fun cropWindowEdgesFollowFocusZeroAndOne() {
        val a = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, .5, 0.0)
        assertEquals(0.0, a.top, 1e-9)
        val b = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, .5, 1.0)
        assertEquals(1 - b.height, b.top, 1e-9)
        assertEquals(1.0, b.top + b.height, 1e-9)
    }

    @Test fun cropWindowFocusHasNoEffectOnFullyVisibleAxis() {
        val a = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, 0.0, .5)
        val b = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, 1.0, .5)
        assertEquals(0.0, a.left, 1e-9)
        assertEquals(0.0, b.left, 1e-9)
        assertEquals(1.0, b.width, 1e-9)
    }

    @Test fun cropWindowOfRotatedAssetUsesSwappedBitmapSize() {
        // A 2400x1080 asset rotated 90 degrees is displayed as a 1080x2400 bitmap.
        val rotated = PdfCropFocus.window(93.0, 60.0, 1080.0, 2400.0, .5, .5)
        val unrotated = PdfCropFocus.window(93.0, 60.0, 2400.0, 1080.0, .5, .5)
        assertEquals(1.0, unrotated.height, 1e-9)
        assertTrue(unrotated.width < 1.0)
        assertTrue(rotated.height < 1.0)
        assertEquals(1.0, rotated.width, 1e-9)
    }

    @Test fun cropWindowMatchesContentRect() {
        val r = PdfPrintLayout.contentRect(60.0, 93.0, 2400.0, 1080.0, PdfFit.Cover, .3, .7)
        val w = PdfCropFocus.window(60.0, 93.0, 2400.0, 1080.0, .3, .7)
        assertEquals(-r.x / r.width, w.left, 1e-9)
        assertEquals(60.0 / r.width, w.width, 1e-9)
    }

    @Test fun focusForDragMovesWindowOneToOne() {
        // Window shows 25% of a 400px-tall display: 300px of travel; dragging 30px -> +0.1 focus.
        assertEquals(0.6, PdfCropFocus.focusForDrag(0.5, 30.0, 400.0, 0.25), 1e-9)
        assertEquals(1.0, PdfCropFocus.focusForDrag(0.9, 3000.0, 400.0, 0.25), 1e-9)
        // Fully visible axis: no movement.
        assertEquals(0.5, PdfCropFocus.focusForDrag(0.5, 30.0, 400.0, 1.0), 1e-9)
    }

    @Test fun focusCenteredAtClampsAndIgnoresFullyVisibleAxis() {
        assertEquals(0.5, PdfCropFocus.focusCenteredAt(0.0, 0.5, 0.25), 1e-9)
        assertEquals(0.0, PdfCropFocus.focusCenteredAt(0.5, 0.05, 0.25), 1e-9)
        assertEquals(1.0, PdfCropFocus.focusCenteredAt(0.5, 0.99, 0.25), 1e-9)
        assertEquals(0.3, PdfCropFocus.focusCenteredAt(0.3, 0.9, 1.0), 1e-9)
        // Round trip: the window centered at p starts at p - vis/2.
        val f = PdfCropFocus.focusCenteredAt(0.5, 0.4, 0.25)
        assertEquals(0.275, (1 - 0.25) * f, 1e-9)
    }

    @Test fun dragReorderFindsNearestCenterAmongCandidates() {
        val order = listOf("a", "b", "c")
        val centers =
            listOf("a" to (0.0 to 0.0), "b" to (100.0 to 0.0), "c" to (200.0 to 0.0))
        assertEquals(1, PdfDragReorder.nearestIndex(order, centers, 90.0 to 0.0, fallback = 0))
        assertEquals(2, PdfDragReorder.nearestIndex(order, centers, 500.0 to 0.0, fallback = 0))
        assertEquals(0, PdfDragReorder.nearestIndex(order, centers, -500.0 to 0.0, fallback = 0))
    }

    @Test fun dragReorderExcludesKeysSoDroppingNearTheEndLandsOnTheLastRealItem() {
        val order = listOf("a", "b", "c")
        val centers =
            listOf(
                "a" to (0.0 to 0.0),
                "b" to (100.0 to 0.0),
                "c" to (200.0 to 0.0),
                "add-page" to (300.0 to 0.0),
            )
        // Nearest the add-page tile, but it's excluded — must resolve to the last real page,
        // not -1 (order.indexOf("add-page") would be -1 and silently no-op the reorder).
        val target =
            PdfDragReorder.nearestIndex(
                order,
                centers,
                290.0 to 0.0,
                excludeKeys = setOf("add-page"),
                fallback = -1,
            )
        assertEquals(2, target)
    }

    @Test fun dragReorderFallsBackWhenNothingResolvable() {
        assertEquals(3, PdfDragReorder.nearestIndex(emptyList(), emptyList(), 0.0 to 0.0, fallback = 3))
    }

    @Test fun paperPresetsMatchRegardlessOfOrientation() {
        assertEquals(PdfPaperPresets.A4, PdfPaperPresets.matching(210.0, 297.0))
        assertEquals(PdfPaperPresets.A4, PdfPaperPresets.matching(297.0, 210.0))
        assertEquals(PdfPaperPresets.SQUARE, PdfPaperPresets.matching(148.0, 148.0))
        assertEquals(PdfPaperPresets.CUSTOM, PdfPaperPresets.matching(123.0, 456.0))
    }

    @Test fun pageLimitAllowsUpToTheCapAndNoFurther() {
        assertTrue(PdfPageLimit.canAdd(99, 1))
        assertFalse(PdfPageLimit.canAdd(100, 1))
        assertTrue(PdfPageLimit.canAdd(98, 2))
        assertFalse(PdfPageLimit.canAdd(98, 3))
    }
}
