package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfPreviewPolicyTest {
    @Test
    fun everySupportedCanvasAndThumbnailFitsItsPixelBudget() {
        for (count in 1..24) for (thumbnail in listOf(false, true)) {
            val side = PdfPreviewPolicy.side(count, thumbnail)
            assertTrue(side in 1..if (thumbnail) 192 else 1024)
            val budget =
                if (thumbnail) PdfPreviewPolicy.THUMBNAIL_BYTES else PdfPreviewPolicy.CANVAS_BYTES
            assertTrue(PdfPreviewPolicy.rotatedBytes(side) * count <= budget)
        }
    }

    @Test
    fun densePagesUseSmallerDecodesWithoutAZeroSize() {
        assertEquals(1024, PdfPreviewPolicy.side(1, false))
        assertTrue(PdfPreviewPolicy.side(24, false) < PdfPreviewPolicy.side(1, false))
        assertTrue(PdfPreviewPolicy.side(24, true) > 0)
    }
}
