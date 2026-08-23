package com.ugallery.core.data

import com.ugallery.core.preferences.FolderSelectionMode
import com.ugallery.core.preferences.LibraryFilter
import com.ugallery.core.preferences.LibraryGrouping
import com.ugallery.core.preferences.LibrarySettings
import com.ugallery.core.preferences.LibrarySort
import com.ugallery.core.preferences.GalleryFolderToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTimelineQueryTest {
    @Test fun `default query keeps production timeline ordering`() {
        val sql = GalleryTimelineQuery.build(LibrarySettings()).sql
        assertTrue(sql.contains("isAccessible=1"))
        assertTrue(sql.contains("isTrashed=0"))
        assertTrue(sql.contains("timelineSortMillis DESC"))
    }

    @Test fun `filter sorting and grouping are encoded as fixed SQL`() {
        val sql = GalleryTimelineQuery.build(
            LibrarySettings(
                sort = LibrarySort.Name,
                ascending = true,
                filter = LibraryFilter.Videos,
                grouping = LibraryGrouping.Month,
            ),
        ).sql
        assertTrue(sql.contains("mediaType=3"))
        assertTrue(sql.contains("strftime('%Y-%m'"))
        assertTrue(sql.contains("LOWER(COALESCE(displayName,'')) ASC"))
    }

    @Test fun `folder tokens are bound rather than interpolated`() {
        val hostileVolume = "external') OR 1=1 --"
        val token = GalleryFolderToken.encode(hostileVolume, 42L)
        assertEquals(hostileVolume to 42L, GalleryFolderToken.decode(token))
        val query = GalleryTimelineQuery.build(
            LibrarySettings(
                folderSelectionMode = FolderSelectionMode.OnlyIncluded,
                includedFolders = setOf(token),
            ),
        )
        assertFalse(query.sql.contains(hostileVolume))
        assertEquals(2, query.argCount)
    }

    @Test fun `empty allow list returns no media and malformed token is rejected`() {
        val query = GalleryTimelineQuery.build(
            LibrarySettings(folderSelectionMode = FolderSelectionMode.OnlyIncluded),
        )
        assertTrue(query.sql.contains("AND 0"))
        assertNull(GalleryFolderToken.decode("invalid"))
    }
}
