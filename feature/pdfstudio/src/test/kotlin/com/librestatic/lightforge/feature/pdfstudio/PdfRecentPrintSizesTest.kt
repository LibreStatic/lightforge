package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfRecentPrintSizesTest {
    private fun c(w: Double, h: Double) = PdfPrintSize.custom(w, h)

    @Test
    fun `record puts the newest first and dedupes`() {
        var list = emptyList<PdfPrintSize>()
        list = PdfRecentPrintSizes.record(list, c(30.0, 20.0))
        list = PdfRecentPrintSizes.record(list, c(40.0, 25.0))
        list = PdfRecentPrintSizes.record(list, c(30.0, 20.0))
        assertEquals(listOf("custom:30x20", "custom:40x25"), list.map { it.id })
    }

    @Test
    fun `record caps at three and drops the oldest`() {
        var list = emptyList<PdfPrintSize>()
        listOf(10.0, 20.0, 30.0, 40.0).forEach { list = PdfRecentPrintSizes.record(list, c(it, 15.0)) }
        assertEquals(listOf("custom:40x15", "custom:30x15", "custom:20x15"), list.map { it.id })
    }

    @Test
    fun `presets are never recorded`() {
        val list = PdfRecentPrintSizes.record(listOf(c(30.0, 20.0)), PdfPrintSize.Print10x15)
        assertEquals(listOf("custom:30x20"), list.map { it.id })
    }

    @Test
    fun `parse skips garbage presets and duplicates and survives an encode round trip`() {
        val raw = "custom:30x20,garbage,10x15,custom:30x20,custom:1x1,custom:40x25.5,,custom:50x50,custom:60x60"
        assertEquals(listOf("custom:30x20", "custom:40x25.5", "custom:50x50"), PdfRecentPrintSizes.parse(raw).map { it.id })
        val list = PdfRecentPrintSizes.parse(raw)
        assertEquals(list, PdfRecentPrintSizes.parse(PdfRecentPrintSizes.encode(list)))
        assertEquals(emptyList<PdfPrintSize>(), PdfRecentPrintSizes.parse(null))
        assertEquals(emptyList<PdfPrintSize>(), PdfRecentPrintSizes.parse(""))
    }

    @Test
    fun `chip label uses the project unit with trimmed zeros`() {
        assertEquals("30 × 20 mm", formatSizeLabel(30.0, 20.0, PdfUnit.Millimeter, '.'))
        assertEquals("3 × 2 cm", formatSizeLabel(30.0, 20.0, PdfUnit.Centimeter, '.'))
        assertEquals("3,5 × 2 cm", formatSizeLabel(35.0, 20.0, PdfUnit.Centimeter, ','))
        assertEquals("1.18 × 0.79 in", formatSizeLabel(30.0, 20.0, PdfUnit.Inch, '.'))
        assertEquals("25.4 × 50.8 mm", formatSizeLabel(25.4, 50.8, PdfUnit.Millimeter, '.'))
    }

    @Test
    fun `grid label collapses equal sides and uses the project unit`() {
        assertEquals("5 mm", formatGridLabel(5.0, 5.0, PdfUnit.Millimeter, '.'))
        assertEquals("3 × 2 cm", formatGridLabel(30.0, 20.0, PdfUnit.Centimeter, '.'))
        assertEquals("0.5 cm", formatGridLabel(5.0, 5.0, PdfUnit.Centimeter, '.'))
    }
}
