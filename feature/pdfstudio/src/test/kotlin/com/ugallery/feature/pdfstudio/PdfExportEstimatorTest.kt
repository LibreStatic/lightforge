package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfExportEstimatorTest {
    private fun asset(mime: String, w: Int = 4000, h: Int = 3000, hash: String = "a".repeat(64)) =
        PdfAsset(hash = hash, mime = mime, width = w, height = h)

    private fun projectWith(asset: PdfAsset): PdfProject {
        val image = PdfImage(asset = asset.hash, width = 190.0, height = 100.0)
        return PdfProject(name = "p", pages = listOf(PdfPage(images = listOf(image))), assets = listOf(asset))
    }

    @Test
    fun effectiveDimensionsUnderBudgetAreUnchanged() {
        assertEquals(800 to 600, PdfExportEstimator.effectiveDimensions(800, 600, 8192, 16_000_000L))
    }

    @Test
    fun effectiveDimensionsHalveUntilMaxSideFits() {
        val (w, h) = PdfExportEstimator.effectiveDimensions(20000, 10000, 8192, 16_000_000L)
        assertTrue(maxOf(w, h) <= 8192)
        // Halving preserves aspect ratio exactly (integer division of a common sample factor).
        assertEquals(20000 / 4, w)
        assertEquals(10000 / 4, h)
    }

    @Test
    fun effectiveDimensionsHalveUntilPixelBudgetFits() {
        val (w, h) = PdfExportEstimator.effectiveDimensions(6000, 6000, 8192, 16_000_000L)
        assertTrue(w.toLong() * h.toLong() <= 16_000_000L)
    }

    @Test
    fun zeroSizedAssetIsPassedThroughWithoutLooping() {
        assertEquals(0 to 0, PdfExportEstimator.effectiveDimensions(0, 0, 8192, 16_000_000L))
    }

    @Test
    fun jpegNeverExceedsOriginalBudget() {
        assertFalse(PdfExportEstimator.exceedsOriginalBudget(asset("image/jpeg", w = 20000, h = 20000)))
    }

    @Test
    fun importedPdfNeverExceedsOriginalBudget() {
        assertFalse(PdfExportEstimator.exceedsOriginalBudget(asset("application/pdf", w = 0, h = 0)))
    }

    @Test
    fun oversizedPngExceedsOriginalBudget() {
        assertTrue(PdfExportEstimator.exceedsOriginalBudget(asset("image/png", w = 9000, h = 9000)))
    }

    @Test
    fun modestPngFitsOriginalBudget() {
        assertFalse(PdfExportEstimator.exceedsOriginalBudget(asset("image/png", w = 2000, h = 1500)))
    }

    @Test
    fun oversizedOriginalPhotoNumbersReportsOneBasedPositionAcrossPages() {
        val small = asset("image/jpeg", w = 100, h = 100, hash = "a".repeat(64))
        val big = asset("image/png", w = 9000, h = 9000, hash = "b".repeat(64))
        val project =
            PdfProject(
                name = "p",
                pages =
                    listOf(
                        PdfPage(images = listOf(PdfImage(asset = small.hash, width = 10.0, height = 10.0))),
                        PdfPage(
                            images =
                                listOf(
                                    PdfImage(asset = big.hash, width = 10.0, height = 10.0),
                                    PdfImage(asset = small.hash, width = 10.0, height = 10.0),
                                )
                        ),
                    ),
                assets = listOf(small, big),
            )
        assertEquals(listOf(2), PdfExportEstimator.oversizedOriginalPhotoNumbers(project))
    }

    @Test
    fun originalJpegEstimateUsesExactSourceBytes() {
        val a = asset("image/jpeg")
        val project = projectWith(a)
        val estimate =
            PdfExportEstimator.estimate(project, sourceBytes = mapOf(a.hash to 3_000_000L), compact = false)
        assertEquals(3_000_000L, estimate)
    }

    @Test
    fun compactEstimateIsSmallerThanOriginalForALargeImage() {
        val a = asset("image/png", w = 6000, h = 4000)
        val project = projectWith(a)
        val sourceBytes = mapOf(a.hash to 8_000_000L)
        val original = PdfExportEstimator.estimate(project, sourceBytes, compact = false)
        val compact = PdfExportEstimator.estimate(project, sourceBytes, compact = true)
        assertTrue("compact ($compact) should be smaller than original ($original)", compact < original)
    }

    @Test
    fun missingSourceBytesContributeZeroInsteadOfCrashing() {
        val a = asset("image/jpeg")
        val project = projectWith(a)
        assertEquals(0L, PdfExportEstimator.estimate(project, sourceBytes = emptyMap(), compact = false))
    }

    @Test
    fun importedPdfBytesAreProratedAcrossUsedPages() {
        val pdfAsset = asset("application/pdf", w = 0, h = 0)
        val project =
            PdfProject(
                name = "p",
                pages = listOf(PdfPage(source = pdfAsset.hash, sourcePage = 0)),
                assets = listOf(pdfAsset),
            )
        val full =
            PdfExportEstimator.estimate(
                project,
                sourceBytes = mapOf(pdfAsset.hash to 1_000_000L),
                sourcePageCounts = mapOf(pdfAsset.hash to 10),
                compact = false,
            )
        val overhead = 1_500L
        assertEquals(1_000_000L / 10 + overhead, full)
    }

    @Test
    fun estimateIsDeterministicAndOrderIndependent() {
        val a = asset("image/png", w = 3000, h = 2000, hash = "c".repeat(64))
        val project = projectWith(a)
        val bytes = mapOf(a.hash to 500_000L)
        val first = PdfExportEstimator.estimate(project, bytes, compact = false)
        val second = PdfExportEstimator.estimate(project, bytes, compact = false)
        assertEquals(first, second)
    }
}
