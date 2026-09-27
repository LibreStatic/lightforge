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
        assertEquals(PdfPaperPresets.A4, PdfNewProjectTemplate.PhotoGrid.paper)
        assertEquals(2, PdfNewProjectTemplate.PhotoGrid.columns)
        assertEquals(PdfPaperPresets.A4, PdfNewProjectTemplate.Receipts.paper)
        assertEquals(1, PdfNewProjectTemplate.Receipts.columns)
        assertTrue(PdfNewProjectTemplate.Receipts.margin < PdfNewProjectTemplate.PhotoGrid.margin)
        assertEquals(PdfPaperPresets.PRINT_10X15, PdfNewProjectTemplate.Prints10x15.paper)
        assertEquals(1, PdfNewProjectTemplate.Prints10x15.columns)
    }
}
