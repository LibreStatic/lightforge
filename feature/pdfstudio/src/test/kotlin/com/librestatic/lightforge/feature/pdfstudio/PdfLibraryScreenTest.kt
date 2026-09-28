package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfLibraryScreenTest {
    private fun row(id: String, name: String, updated: Long) =
        PdfProjectRow(id = id, name = name, updated = updated, manifest = "")

    @Test
    fun `search is case-insensitive substring`() {
        val projects =
            listOf(row("1", "Receipts 2024", 10), row("2", "Vacation photos", 20), row("3", "Old receipts", 5))
        val result = filterAndSortProjects(projects, "receipt", PdfLibrarySort.Recent)
        assertEquals(listOf("1", "3"), result.map { it.id })
    }

    @Test
    fun `recent sort orders by updated descending`() {
        val projects = listOf(row("1", "A", 5), row("2", "B", 20), row("3", "C", 10))
        val result = filterAndSortProjects(projects, "", PdfLibrarySort.Recent)
        assertEquals(listOf("2", "3", "1"), result.map { it.id })
    }

    @Test
    fun `name sort is case-insensitive`() {
        val projects = listOf(row("1", "banana", 1), row("2", "Apple", 2), row("3", "cherry", 3))
        val result = filterAndSortProjects(projects, "", PdfLibrarySort.Name)
        assertEquals(listOf("2", "1", "3"), result.map { it.id })
    }

    @Test
    fun `empty query returns every project`() {
        val projects = listOf(row("1", "A", 1), row("2", "B", 2))
        assertEquals(2, filterAndSortProjects(projects, "", PdfLibrarySort.Recent).size)
    }

    @Test
    fun `template presets match the plan's paper and column counts`() {
        // Phase G4 moved these presets into PdfTemplate (single source of truth reused by the
        // library, the New project sheet and the gallery handoff) — see PdfTemplateTest for its
        // own dedicated coverage; this test just keeps confirming the library's expectations.
        assertEquals(PdfPaperPresets.A4, PdfTemplate.PhotoGrid.paper)
        assertEquals(2, PdfTemplate.PhotoGrid.columns)
        assertEquals(PdfPaperPresets.A4, PdfTemplate.Receipts.paper)
        assertEquals(1, PdfTemplate.Receipts.columns)
        assertTrue(PdfTemplate.Receipts.margin > PdfTemplate.PhotoGrid.margin)
        assertEquals(PdfPaperPresets.PRINT_10X15, PdfTemplate.Prints10x15.paper)
        assertEquals(1, PdfTemplate.Prints10x15.columns)
    }
}
