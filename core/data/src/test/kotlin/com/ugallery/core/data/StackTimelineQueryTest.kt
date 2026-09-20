package com.ugallery.core.data

import com.ugallery.core.database.TimelineKeyset
import com.ugallery.core.preferences.LibrarySettings
import org.junit.Assert.*
import org.junit.Test

class StackTimelineQueryTest {
    @Test
    fun keysetIsOutsideTheGlobalRepresentativeProjection() {
        val sql = GalleryTimelineQuery.stacked(LibrarySettings(), TimelineKeyset(100, 2, "v'"), 20)
        assertTrue(
            sql.sql.lastIndexOf("WHERE timelineSortMillis") >
                sql.sql.indexOf("p.stackId IS NULL")
        )
        assertEquals(7, sql.argCount)
        assertFalse(sql.sql.contains("v'"))
        assertFalse(sql.sql.contains("OFFSET"))
    }

    @Test
    fun selectionBindsStackIdentityAndRevision() {
        val query = GalleryTimelineQuery.stackSelection(LibrarySettings(), "x' OR 1=1", "r'")
        assertEquals(2, query.argCount)
        assertFalse(query.sql.contains("x'"))
        assertTrue(query.sql.contains("LIMIT 501"))
    }

    @Test
    fun selectionModeRetainsTheUncollapsedSourceQuery() {
        assertFalse(GalleryTimelineQuery.build(LibrarySettings()).sql.contains("photo_stacks"))
        assertTrue(GalleryTimelineQuery.stacked(LibrarySettings()).sql.contains("photo_stacks"))
    }
}
