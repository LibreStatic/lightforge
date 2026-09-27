package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfModelsTest {
    private val hash = "a".repeat(64)

    private fun image() = PdfImage(asset = hash, width = 80.0, height = 40.0)

    private fun project() =
        PdfProject(
            name = "Test",
            assets = listOf(PdfAsset(hash, "image/jpeg", 800, 400)),
            pages = listOf(PdfPage(images = listOf(image()))),
        )

    @Test
    fun jsonRoundTrip() {
        val p = project()
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun unitsArePhysical() {
        assertEquals(25.4, PdfUnit.Inch.factor(300), 0.0)
        assertEquals(25.4, PdfUnit.Pixel.factor(300) * 300, .000001)
    }

    @Test
    fun resizeRetainsRatioAndStaysInsideMargins() {
        val page = PdfPage()
        val i = PdfGeometry.resize(image(), page, 800.0, 40.0, true)
        assertEquals(2.0, i.width / i.height, .000001)
        assertTrue(i.x + i.width <= 200.000001)
        assertTrue(i.y >= 10)
    }

    @Test
    fun unlockedBoxChangesWithoutChangingSourceAsset() {
        val i = PdfGeometry.resize(image().copy(locked = false), PdfPage(), 100.0, 40.0, true)
        assertEquals(100.0, i.width, 0.0)
        assertEquals(40.0, i.height, 0.0)
        assertEquals(hash, i.asset)
    }

    @Test
    fun gridFitsEveryImageAndPreservesFit() {
        val p = PdfGeometry.grid(PdfPage(images = List(24) { image() }), 6, 4.0)
        assertEquals(24, p.images.size)
        p.images.forEach {
            assertTrue(it.x >= 10 && it.y >= 10)
            assertTrue(it.x + it.width <= 200.000001)
            assertTrue(it.y + it.height <= 287.000001)
            assertEquals(PdfFit.Contain, it.fit)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidGeometry() {
        project().copy(pages = listOf(PdfPage(width = Double.NaN))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingSource() {
        project().copy(assets = emptyList()).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalHash() {
        project().copy(assets = listOf(PdfAsset("../../private", "image/jpeg"))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownOrientation() {
        project().copy(assets = listOf(PdfAsset(hash, "image/jpeg", 1, 1, 9))).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyProjects() {
        project().copy(pages = emptyList()).validate()
    }

    @Test
    fun exifRoundTrip() {
        val p = project().copy(assets = listOf(PdfAsset(hash, "image/jpeg", 400, 800, 6)))
        assertEquals(p, PdfCodec.decode(PdfCodec.encode(p)))
    }

    @Test
    fun resizeFromBottomRightCornerMatchesLegacySingleHandle() {
        val page = PdfPage()
        val viaCorner =
            PdfGeometry.resizeFromCorner(image(), page, PdfGeometry.Corner.BottomRight, 20.0, 10.0)
        val viaResize = PdfGeometry.resize(image(), page, 100.0, 50.0, true)
        assertEquals(viaResize.width, viaCorner.width, .0001)
        assertEquals(viaResize.height, viaCorner.height, .0001)
        assertEquals(viaResize.x, viaCorner.x, .0001)
        assertEquals(viaResize.y, viaCorner.y, .0001)
    }

    @Test
    fun resizeFromTopLeftCornerKeepsOppositeCornerFixed() {
        val page = PdfPage()
        val i = image().copy(x = 50.0, y = 50.0, width = 80.0, height = 40.0)
        val right = i.x + i.width
        val bottom = i.y + i.height
        val resized = PdfGeometry.resizeFromCorner(i, page, PdfGeometry.Corner.TopLeft, -10.0, -10.0)
        assertEquals(right, resized.x + resized.width, .0001)
        assertEquals(bottom, resized.y + resized.height, .0001)
        assertTrue(resized.width > i.width)
    }

    @Test
    fun resizeFromCornerNeverProducesNonFiniteOrNegativeSize() {
        val page = PdfPage()
        val i = image()
        for (corner in PdfGeometry.Corner.entries) {
            val resized = PdfGeometry.resizeFromCorner(i, page, corner, -1000.0, -1000.0)
            assertTrue(resized.width.isFinite() && resized.width > 0)
            assertTrue(resized.height.isFinite() && resized.height > 0)
        }
    }

    @Test
    fun constrainedExtremeRatioIsFinite() {
        for (n in 1..1000) {
            val i =
                PdfGeometry.constrain(image().copy(width = n * 13.13, height = n * .171), PdfPage())
            assertTrue(i.width.isFinite())
            assertTrue(i.x >= 10)
        }
    }
}
