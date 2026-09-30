package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfTemplateTest {
    @Test
    fun `every template's new project validates`() {
        PdfTemplate.entries.forEach { template ->
            val project = template.newProject("Test ${template.name}")
            project.validate()
            assertEquals(template.columns, project.columns)
            assertEquals(1, project.pages.size)
            assertEquals(template.paper, PdfPaperPresets.matching(project.pages[0].width, project.pages[0].height))
        }
    }

    @Test
    fun `photo grid is A4 portrait 2x2 with 10mm margin and 5mm gap`() {
        val t = PdfTemplate.PhotoGrid
        assertEquals(PdfPaperPresets.A4, t.paper)
        assertFalse(t.landscape)
        assertEquals(2, t.columns)
        assertEquals(4, t.photosPerPage)
        assertEquals(10.0, t.margin, 0.0)
        assertEquals(5.0, t.gap, 0.0)
    }

    @Test
    fun `receipts is a single stacked column with generous margins`() {
        val t = PdfTemplate.Receipts
        assertEquals(PdfPaperPresets.A4, t.paper)
        assertFalse(t.landscape)
        assertEquals(1, t.columns)
        assertTrue("Receipts should use a more generous margin than Photo grid", t.margin > PdfTemplate.PhotoGrid.margin)
    }

    @Test
    fun `prints 10x15 is now a 10x15 print size on A4, 2 per page, Fill`() {
        // Feedback item B: redefined from "one 10x15 print filling a 10x15 sheet" to "10x15 cm
        // photos, as many as fit, on A4" — the count is COMPUTED, not hardcoded.
        val t = PdfTemplate.Prints10x15
        assertEquals(PdfPaperPresets.A4, t.paper)
        assertEquals(PdfPrintSize.Print10x15, t.printSize)
        assertEquals(2, t.photosPerPage)
        assertEquals(2, t.columns)
        assertEquals(5.0, t.margin, 0.0)
        assertEquals(PdfFit.Cover, t.fit)
    }

    @Test
    fun `blank matches the pre-existing new-project defaults`() {
        val t = PdfTemplate.Blank
        assertEquals(PdfPaperPresets.A4, t.paper)
        assertFalse(t.landscape)
        assertEquals(2, t.columns)
        assertEquals(10.0, t.margin, 0.0)
        assertEquals(4.0, t.gap, 0.0)
    }

    @Test
    fun `layoutPages with zero photos yields a single blank page`() {
        PdfTemplate.entries.forEach { template ->
            val pages = template.layoutPages(emptyList())
            assertEquals(1, pages.size)
            assertTrue(pages[0].images.isEmpty())
        }
    }

    @Test
    fun `layoutPages with one photo places it on the single page`() {
        PdfTemplate.entries.forEach { template ->
            val pages = template.layoutPages(listOf("asset-0"))
            assertEquals(1, pages.size)
            assertEquals(1, pages[0].images.size)
            assertEquals("asset-0", pages[0].images[0].asset)
            assertEquals(template.fit, pages[0].images[0].fit)
        }
    }

    @Test
    fun `layoutPages fills a page up to photosPerPage before overflowing`() {
        PdfTemplate.entries.forEach { template ->
            val assets = (0 until template.photosPerPage).map { "asset-$it" }
            val pages = template.layoutPages(assets)
            assertEquals(1, pages.size)
            assertEquals(template.photosPerPage, pages[0].images.size)
        }
    }

    @Test
    fun `layoutPages overflows onto a second page beyond photosPerPage`() {
        PdfTemplate.entries.forEach { template ->
            val assets = (0 until template.photosPerPage + 1).map { "asset-$it" }
            val pages = template.layoutPages(assets)
            assertEquals(2, pages.size)
            assertEquals(template.photosPerPage, pages[0].images.size)
            assertEquals(1, pages[1].images.size)
        }
    }

    @Test
    fun `layoutPages with many photos spreads across the expected number of pages`() {
        val template = PdfTemplate.PhotoGrid
        val assets = (0 until 10).map { "asset-$it" }
        val pages = template.layoutPages(assets)
        // 4 per page -> 3 pages (4, 4, 2)
        assertEquals(3, pages.size)
        assertEquals(4, pages[0].images.size)
        assertEquals(4, pages[1].images.size)
        assertEquals(2, pages[2].images.size)
    }

    @Test
    fun `prints 10x15 places two exact 10x15cm slots per A4 page then overflows`() {
        val t = PdfTemplate.Prints10x15
        val pages = t.layoutPages(listOf("a", "b", "c"))
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].images.size)
        assertEquals(1, pages[1].images.size)
        (pages[0].images + pages[1].images).forEach { image ->
            assertEquals(PdfFit.Cover, image.fit)
            // Every slot is exactly 10x15 cm regardless of orientation, so a page can be cut to
            // size — feedback item B's "slots... with exact physical dimensions".
            val dims = setOf(image.width, image.height)
            assertEquals(setOf(100.0, 150.0), dims)
        }
    }

    @Test
    fun `buildProject produces a validating project with matching assets`() {
        val template = PdfTemplate.PhotoGrid
        val assets = listOf(
            PdfAsset(hash = "a".repeat(64), mime = "image/jpeg"),
            PdfAsset(hash = "b".repeat(64), mime = "image/jpeg"),
        )
        val project = template.buildProject("Photos", assets, assets.map { it.hash })
        project.validate()
        assertEquals(2, project.assets.size)
        assertEquals(1, project.pages.size)
        assertEquals(2, project.pages[0].images.size)
    }

    @Test
    fun `every layoutPages page validates on its own project`() {
        PdfTemplate.entries.forEach { template ->
            val n = template.photosPerPage * 2 + 1
            val assets = (0 until n).map { "%064d".format(it) }
            val pages = template.layoutPages(assets)
            val project =
                PdfProject(
                    name = "Overflow",
                    pages = pages,
                    assets = assets.map { PdfAsset(hash = it, mime = "image/jpeg") },
                    columns = template.columns,
                    gap = template.gap,
                )
            project.validate()
        }
    }
}
