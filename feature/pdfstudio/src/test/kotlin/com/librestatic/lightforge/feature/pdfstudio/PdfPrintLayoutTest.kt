package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

/**
 * Feedback item B: "the number of photos per page is COMPUTED, not fixed by the template" — the
 * slot-fitting algorithm behind [PdfPrintLayout.fit]. Examples from the spec, worked out with the
 * chosen default margins (5 mm) and gap (0 mm unless a test varies it), asserting the exact
 * counts the algorithm derives.
 */
class PdfPrintLayoutTest {
    private val a4 = 210.0 to 297.0

    @Test
    fun `10x15 on A4 with 5mm margins fits 2 per page across a 0-2mm gap range`() {
        for (gap in listOf(0.0, 1.0, 2.0)) {
            val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, gap, PdfPrintSize.Print10x15)
            assertEquals("gap=$gap", 2, fit.perPage)
        }
    }

    @Test
    fun `10x15 on 10x15 paper with 0 margins fits exactly 1`() {
        val fit = PdfPrintLayout.fit(100.0, 150.0, 0.0, 0.0, PdfPrintSize.Print10x15)
        assertEquals(1, fit.perPage)
        assertEquals(1, fit.columns)
        assertEquals(1, fit.rows)
    }

    @Test
    fun `9x13 on A4 fits 4`() {
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.Print9x13)
        assertEquals(4, fit.perPage)
        assertEquals(2, fit.columns)
        assertEquals(2, fit.rows)
    }

    @Test
    fun `wallet 6x9 on A4 fits at least 9`() {
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.Wallet6x9)
        assertEquals(9, fit.perPage)
        assertEquals(3, fit.columns)
        assertEquals(3, fit.rows)
    }

    @Test
    fun `13x18 on A4 fits 2, rotated to landscape slots`() {
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.Print13x18)
        assertEquals(2, fit.perPage)
        assertTrue(fit.rotated)
        assertEquals(180.0, fit.slotWidthMm, 0.001)
        assertEquals(130.0, fit.slotHeightMm, 0.001)
    }

    @Test
    fun `15x20 on A4 fits 1 per page`() {
        // 150x200 doesn't fit either way in a 200x287 printable A4 area more than once.
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.Print15x20)
        assertEquals(1, fit.perPage)
    }

    @Test
    fun `square paper fits print sizes symmetrically`() {
        // A 148x148 square sheet, 5mm margins -> 138x138 printable. 9x13 in either orientation:
        // cols=floor(138/90)=1, rows=floor(138/130)=1 -> 1 either way (tie -> paper "orientation"
        // is neither landscape nor portrait per (w>h), so paperIsLandscape=false -> picks portrait).
        val fit = PdfPrintLayout.fit(148.0, 148.0, 5.0, 0.0, PdfPrintSize.Print9x13)
        assertEquals(1, fit.perPage)
        assertFalse(fit.rotated)
    }

    @Test
    fun `landscape paper picks the landscape slot orientation on a tie`() {
        // A4 landscape: 297x210, 5mm margins -> 287x200 printable. 10x15 portrait: cols=floor(287/100)=2,
        // rows=floor(200/150)=1 -> 2. Rotated (150x100): cols=floor(287/150)=1, rows=floor(200/100)=2 -> 2.
        // Tie -> paper is landscape, so the rotated (landscape) slot wins.
        val fit = PdfPrintLayout.fit(297.0, 210.0, 5.0, 0.0, PdfPrintSize.Print10x15)
        assertEquals(2, fit.perPage)
        assertTrue(fit.rotated)
    }

    @Test
    fun `custom paper smaller than the print size fits zero, a validation error`() {
        val fit = PdfPrintLayout.fit(80.0, 80.0, 5.0, 0.0, PdfPrintSize.Print10x15)
        assertEquals(0, fit.perPage)
        assertTrue(PdfPrintLayout.slotRects(80.0, 80.0, 5.0, 0.0, fit).isEmpty())
    }

    @Test
    fun `margins consuming the whole page also fit zero`() {
        val fit = PdfPrintLayout.fit(100.0, 150.0, 60.0, 0.0, PdfPrintSize.Print10x15)
        assertEquals(0, fit.perPage)
    }

    @Test
    fun `slotRects produces a centered, gapped, exact-size grid`() {
        val fit = PdfPrintLayout.fit(210.0, 297.0, 5.0, 0.0, PdfPrintSize.Print10x15)
        val rects = PdfPrintLayout.slotRects(210.0, 297.0, 5.0, 0.0, fit)
        assertEquals(2, rects.size)
        rects.forEach {
            assertEquals(100.0, it.width, 0.001)
            assertEquals(150.0, it.height, 0.001)
        }
        // Two slots side by side, exactly filling the 200mm-wide printable area with 0 gap.
        assertEquals(rects[0].x + rects[0].width, rects[1].x, 0.001)
        // Centered vertically: 287mm printable height, 150mm block -> 68.5mm leftover split.
        val expectedY = 5.0 + (287.0 - 150.0) / 2
        assertEquals(expectedY, rects[0].y, 0.001)
    }

    @Test
    fun `paginate chunks and overflows`() {
        assertEquals(listOf(2, 2, 1), PdfPrintLayout.paginate(5, 2))
        assertEquals(listOf(3), PdfPrintLayout.paginate(3, 5))
        assertEquals(emptyList<Int>(), PdfPrintLayout.paginate(0, 2))
    }

    // --- Placement geometry (feedback item A): Fill (cover/crop) vs Fit (contain), incl.
    // panoramic and portrait source photos.

    @Test
    fun `Fit contains a panoramic photo inside a portrait slot, letterboxed vertically`() {
        val r = PdfPrintLayout.contentRect(100.0, 150.0, 300.0, 100.0, PdfFit.Contain)
        // Panoramic 3:1 photo inside a 100x150 (2:3) frame: width-limited, scale = 100/300 = 1/3.
        assertEquals(100.0, r.width, 0.001)
        assertEquals(100.0 / 3, r.height, 0.001)
        assertEquals(0.0, r.x, 0.001)
        // Centered vertically by default focus (.5, .5).
        assertEquals((150.0 - 100.0 / 3) / 2, r.y, 0.001)
    }

    @Test
    fun `Fill crops a panoramic photo to cover a portrait slot`() {
        val r = PdfPrintLayout.contentRect(100.0, 150.0, 300.0, 100.0, PdfFit.Cover)
        // Cover: scale = max(100/300, 150/100) = 1.5 -> content 450x150, only height fits exactly.
        assertEquals(450.0, r.width, 0.001)
        assertEquals(150.0, r.height, 0.001)
        assertEquals(0.0, r.y, 0.001)
        // Centered horizontally by default focus: overflow (450-100)=350 split by focusX=.5.
        assertEquals(-175.0, r.x, 0.001)
    }

    @Test
    fun `Fill respects a non-center focus point`() {
        val r = PdfPrintLayout.contentRect(100.0, 150.0, 300.0, 100.0, PdfFit.Cover, focusX = 0.0, focusY = 0.5)
        assertEquals(0.0, r.x, 0.001)
    }

    @Test
    fun `Fit contains a portrait photo inside a landscape slot`() {
        val r = PdfPrintLayout.contentRect(150.0, 100.0, 100.0, 300.0, PdfFit.Contain)
        // 1:3 portrait photo inside a 150x100 landscape frame: height-limited, scale = 100/300 = 1/3.
        assertEquals(100.0 / 3, r.width, 0.001)
        assertEquals(100.0, r.height, 0.001)
        assertEquals((150.0 - 100.0 / 3) / 2, r.x, 0.001)
        assertEquals(0.0, r.y, 0.001)
    }

    @Test
    fun `Fill exactly fills a slot with matching aspect ratio, no crop`() {
        val r = PdfPrintLayout.contentRect(100.0, 150.0, 200.0, 300.0, PdfFit.Cover)
        assertEquals(100.0, r.width, 0.001)
        assertEquals(150.0, r.height, 0.001)
        assertEquals(0.0, r.x, 0.001)
        assertEquals(0.0, r.y, 0.001)
    }

    @Test
    fun `custom 30x20 mm on A4 keeps the entered landscape orientation, 6 by 14`() {
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.custom(30.0, 20.0))
        assertFalse(fit.rotated)
        assertEquals(30.0, fit.slotWidthMm, 0.001)
        assertEquals(20.0, fit.slotHeightMm, 0.001)
        assertEquals(6, fit.columns)
        assertEquals(14, fit.rows)
        assertEquals(84, fit.perPage)
    }

    @Test
    fun `custom size is never rotated even when only the rotated slot would fit`() {
        // 250 x 150 mm is wider than A4's 200 mm printable width; rotated it would fit, but a
        // custom size is laid out as entered, so the layout reports that nothing fits.
        val fit = PdfPrintLayout.fit(a4.first, a4.second, 5.0, 0.0, PdfPrintSize.custom(250.0, 150.0))
        assertFalse(fit.rotated)
        assertEquals(0, fit.perPage)
    }

    @Test
    fun `custom ids round-trip through fromId`() {
        val size = PdfPrintSize.custom(30.0, 20.0)
        assertEquals("custom:30x20", size.id)
        assertTrue(size.isCustom)
        assertEquals(size, PdfPrintSize.fromId(size.id))
        val fractional = PdfPrintSize.custom(35.0, 45.54)
        assertEquals("custom:35x45.5", fractional.id)
        assertEquals(45.5, fractional.heightMm, 0.0)
        assertEquals(fractional, PdfPrintSize.fromId(fractional.id))
    }

    @Test
    fun `malformed or out-of-bounds custom ids resolve to null`() {
        listOf(
            "custom:", "custom:axb", "custom:30", "custom:30x20x10", "custom:4.9x20", "custom:2000x20",
            "custom:20x4.9", "custom:NaNx20", "custom:Infinityx20", "custom:-30x20", "custom", "nonsense",
        ).forEach { assertNull(it, PdfPrintSize.fromId(it)) }
        assertNull(PdfPrintSize.fromId(null))
        assertNotNull(PdfPrintSize.fromId("custom:10x1000"))
        assertNotNull(PdfPrintSize.fromId("custom:5x20"))
        assertNotNull(PdfPrintSize.fromId("custom:20x5"))
    }

    @Test
    fun `preset ids still resolve to the same presets and are not custom`() {
        assertEquals(5, PdfPrintSize.presets.size)
        PdfPrintSize.presets.forEach {
            assertSame(it, PdfPrintSize.fromId(it.id))
            assertFalse(it.isCustom)
        }
        assertEquals("wallet_6x9", PdfPrintSize.Wallet6x9.id)
        // A custom size is always custom, even with preset dimensions (it keeps its orientation).
        assertTrue(PdfPrintSize.custom(100.0, 150.0).isCustom)
        assertEquals("custom:100x150", PdfPrintSize.custom(100.0, 150.0).id)
        assertTrue(PdfPrintSize.fromId("custom:100x150")!!.isCustom)
    }

    @Test
    fun `usablePerPage caps slots at the per-page element limit`() {
        val fit = PdfPrintLayout.fit(210.0, 297.0, 5.0, 0.0, PdfPrintSize.custom(30.0, 20.0))
        assertEquals(84, fit.perPage)
        assertEquals(PdfMediaPlacement.MaxImagesPerPage, fit.usablePerPage)
        val big = PdfPrintLayout.fit(210.0, 297.0, 5.0, 0.0, PdfPrintSize.Print10x15)
        assertEquals(big.perPage, big.usablePerPage)
    }

    @Test
    fun `chunking by usablePerPage never exceeds the element limit`() {
        val fit = PdfPrintLayout.fit(210.0, 297.0, 5.0, 0.0, PdfPrintSize.custom(30.0, 20.0))
        val chunks = (1..60).toList().chunked(fit.usablePerPage)
        assertEquals(listOf(24, 24, 12), chunks.map { it.size })
        assertTrue(chunks.all { it.size <= PdfMediaPlacement.MaxImagesPerPage })
        assertEquals(fit.perPage, PdfPrintLayout.slotRects(210.0, 297.0, 5.0, 0.0, fit).size)
    }

    @Test
    fun `custom with preset dimensions does not auto-rotate while the preset may`() {
        val custom = PdfPrintSize.custom(130.0, 180.0)
        assertNotEquals(PdfPrintSize.Print13x18, custom)
        assertFalse(PdfPrintLayout.fit(210.0, 297.0, 5.0, 0.0, custom).rotated)
    }
}
