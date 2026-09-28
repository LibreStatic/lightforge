package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibraryGrouping
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.preferences.GalleryFolderToken
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
                folderRules = mapOf(FolderSelectionTarget.Bucket(hostileVolume, 42L) to true),
            ),
        )
        assertFalse(query.sql.contains(hostileVolume))
        assertEquals(2, query.argCount)
    }

    @Test fun `deep paths and exact buckets use bound precedence`() {
        val hostilePath = "Pictures/%_Family/' OR 1=1/"
        val query = GalleryTimelineQuery.build(
            LibrarySettings(
                folderSelectionMode = FolderSelectionMode.AllExceptExcluded,
                folderRules = mapOf(
                    FolderSelectionTarget.Path("external_primary", "Pictures/") to false,
                    FolderSelectionTarget.Path("external_primary", hostilePath) to true,
                    FolderSelectionTarget.Bucket("external_primary", 42L) to false,
                ),
            ),
        )
        assertFalse(query.sql.contains(hostilePath))
        assertTrue(query.sql.indexOf("bucketId") < query.sql.indexOf("instr"))
        assertEquals(6, query.argCount)
    }

    @Test fun `empty allow list returns no media and malformed token is rejected`() {
        val query = GalleryTimelineQuery.build(
            LibrarySettings(folderSelectionMode = FolderSelectionMode.OnlyIncluded),
        )
        assertTrue(query.sql.contains("AND 0"))
        assertNull(GalleryFolderToken.decode("invalid"))
    }
}

class TimelineScrubberQueryTest {
    private val key = com.librestatic.lightforge.core.database.TimelineKeyset(1_000L, 7L, "external")

    @Test fun `day counts group the displayed rows by local day`() {
        val sql = GalleryTimelineQuery.dayCounts(LibrarySettings(), collapsed = false).sql
        assertTrue(sql.contains("strftime('%Y-%m-%d'"))
        assertTrue(sql.contains("GROUP BY day"))
        assertTrue(sql.contains("isTrashed=0"))
        assertTrue(sql.contains("ORDER BY day DESC"))
    }

    @Test fun `ascending day counts follow display order`() {
        val sql = GalleryTimelineQuery.dayCounts(LibrarySettings(ascending = true), collapsed = false).sql
        assertTrue(sql.contains("ORDER BY day ASC"))
    }

    @Test fun `stacked before reads newer rows in the opposite order`() {
        val sql = GalleryTimelineQuery.stackedBefore(LibrarySettings(), key, 50).sql
        assertTrue(sql.contains("timelineSortMillis ASC"))
        assertFalse(sql.contains("timelineSortMillis DESC"))
    }

    @Test fun `rows before counts rows ahead of a boundary`() {
        val sql = GalleryTimelineQuery.rowsBefore(LibrarySettings(), collapsed = true, boundaryMillis = 5L).sql
        assertTrue(sql.contains("COUNT(*)"))
    }
}
