package com.librestatic.lightforge.feature.collections

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class MemoryControlsDraftTest {
    @Test
    fun validatesInclusiveCivilDates() {
        assertNotNull(MemoryControlsDraft("2026-06-01", "2026-06-01", "UTC").range())
        assertNull(MemoryControlsDraft("2026-06-02", "2026-06-01", "UTC").range())
        assertNull(MemoryControlsDraft("2026-02-29", "2026-03-01", "UTC").range())
        assertNotNull(MemoryControlsDraft("2024-02-29", "2024-02-29", "UTC").range())
    }

    @Test
    fun rejectsPartialNonIsoOrUnsupportedYear() {
        for (value in
            listOf("", "26-06-01", "0000-01-01", "9999-01-01", "2026-6-1", "2026-06-01 ")) {
            assertNull(MemoryControlsDraft(value, value, "UTC").range())
        }
    }

    @Test
    fun retainsExplicitZoneAcrossDst() {
        val range = MemoryControlsDraft("2026-03-08", "2026-03-08", "America/New_York").range()!!
        assertEquals(23L * 60 * 60 * 1000, range.bounds().second - range.bounds().first)
        assertNull(MemoryControlsDraft("2026-01-01", "2026-01-01", "Invalid/Zone").range())
    }

    @Test
    fun saverRestoresOnlySmallExactCivilInput() {
        val draft = MemoryControlsDraft("2026-10-25", "2026-10-26", "Europe/Paris")
        val scope = SaverScope { true }
        val saved = with(MemoryControlsDraft.Saver) { with(scope) { save(draft) } }!!
        assertEquals(draft, MemoryControlsDraft.Saver.restore(saved))
    }
}
