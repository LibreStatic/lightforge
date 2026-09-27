package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfPanelLogicTest {
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

    @Test fun paperPresetsMatchRegardlessOfOrientation() {
        assertEquals(PdfPaperPresets.A4, PdfPaperPresets.matching(210.0, 297.0))
        assertEquals(PdfPaperPresets.A4, PdfPaperPresets.matching(297.0, 210.0))
        assertEquals(PdfPaperPresets.SQUARE, PdfPaperPresets.matching(148.0, 148.0))
        assertEquals(PdfPaperPresets.CUSTOM, PdfPaperPresets.matching(123.0, 456.0))
    }
}
