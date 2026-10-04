package com.librestatic.lightforge.feature.pdfstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDuplicateNameTest {
    private val fmt: (String) -> String = { "$it (copy)" }

    @Test
    fun `plain name gets a single suffix`() =
        assertEquals("Trip (copy)", duplicateProjectName("Trip", emptySet(), fmt))

    @Test
    fun `copy of a copy does not stack suffixes`() =
        assertEquals("Trip (copy) 2", duplicateProjectName("Trip (copy)", setOf("Trip", "Trip (copy)"), fmt))

    @Test
    fun `counter increments past existing names`() =
        assertEquals(
            "Trip (copy) 3",
            duplicateProjectName("Trip (copy) 2", setOf("Trip (copy)", "Trip (copy) 2"), fmt),
        )

    @Test
    fun `long names keep the suffix within 80 characters`() {
        val result = duplicateProjectName("x".repeat(80), emptySet(), fmt)
        assertEquals(80, result.length)
        assertTrue(result.endsWith(" (copy)"))
    }

    @Test
    fun `search folds accents and case`() {
        val rows = listOf(PdfProjectRow("1", "Café Menu", 1, ""), PdfProjectRow("2", "Other", 2, ""))
        assertEquals(listOf("1"), filterAndSortProjects(rows, "CAFE", PdfLibrarySort.Recent).map { it.id })
        assertEquals(listOf("1"), filterAndSortProjects(rows, "café", PdfLibrarySort.Recent).map { it.id })
    }
}
