package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerWindowAssemblyTest {
    private fun media(id: Long) = TimelineMedia(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = id,
        width = 1,
        height = 1,
        durationMillis = 0,
    )

    private fun ids(window: ViewerMediaWindow) = window.items.map { it.key.mediaStoreId }

    @Test
    fun normalCaseOrdersPreviousAnchorNext() {
        val window = assembleViewerWindow(
            previous = listOf(media(4), media(3), media(2)),
            anchor = media(5),
            next = listOf(media(6), media(7), media(8)),
            radius = 2,
        )

        assertEquals(listOf(3L, 4L, 5L, 6L, 7L), ids(window))
        assertTrue(window.hasPrevious)
        assertTrue(window.hasNext)
    }

    @Test
    fun shortPagesReportNoMoreItems() {
        val window = assembleViewerWindow(listOf(media(4)), media(5), emptyList(), radius = 2)

        assertEquals(listOf(4L, 5L), ids(window))
        assertFalse(window.hasPrevious)
        assertFalse(window.hasNext)
    }

    @Test
    fun staleAnchorRowInNextAppearsOnce() {
        val window = assembleViewerWindow(
            previous = listOf(media(4)),
            anchor = media(5),
            next = listOf(media(5), media(6), media(7)),
            radius = 2,
        )

        assertEquals(listOf(4L, 5L, 6L, 7L), ids(window))
        assertFalse(window.hasNext)
    }

    @Test
    fun staleAnchorRowInPreviousAppearsOnce() {
        val window = assembleViewerWindow(
            previous = listOf(media(5), media(4), media(3)),
            anchor = media(5),
            next = listOf(media(6)),
            radius = 2,
        )

        assertEquals(listOf(3L, 4L, 5L, 6L), ids(window))
        assertFalse(window.hasPrevious)
        assertFalse(window.hasNext)
    }
}
