package com.librestatic.lightforge.core.data

import org.junit.Assert.*
import org.junit.Test

class GalleryPhotoStackRepositoryTest {
    @Test
    fun normalizesUserTitles() {
        assertEquals("Summer trip", GalleryPhotoStackRepository.normalizeTitle("  Summer\n trip  "))
    }

    @Test
    fun blankRestoresAutomaticTitle() {
        assertNull(GalleryPhotoStackRepository.normalizeTitle(" \t "))
        assertNull(GalleryPhotoStackRepository.normalizeTitle(null))
    }

    @Test
    fun rejectsOversizedTitles() {
        assertEquals(80, GalleryPhotoStackRepository.normalizeTitle("a".repeat(80))!!.length)
        assertTrue(
            runCatching { GalleryPhotoStackRepository.normalizeTitle("a".repeat(81)) }.isFailure
        )
    }
}
