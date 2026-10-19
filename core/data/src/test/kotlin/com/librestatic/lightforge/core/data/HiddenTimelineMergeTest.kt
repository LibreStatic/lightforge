package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenTimelineMergeTest {
    private fun media(id: Long, millis: Long, name: String = "m$id", size: Long = id) = TimelineMedia(
        key = MediaKey("external_primary", id),
        kind = MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = millis,
        width = 1,
        height = 1,
        durationMillis = 0,
        displayName = name,
        sizeBytes = size,
        dateModifiedSeconds = millis / 1000,
        isHiddenOverlay = id >= 100,
    )

    /** Walks the gaps the way the pager does: before and after are neighbours, null at the ends. */
    private fun merged(rows: List<TimelineMedia>, hidden: List<TimelineMedia>, settings: LibrarySettings): List<Long> {
        val order = hiddenOrder(settings)
        val sorted = hidden.sortedWith(order)
        val out = mutableListOf<Long>()
        val padded = listOf<TimelineMedia?>(null) + rows + listOf<TimelineMedia?>(null)
        for (i in 0 until padded.size - 1) {
            hiddenBetween(padded[i], padded[i + 1], sorted, order).forEach { out += it.key.mediaStoreId }
            padded[i + 1]?.let { out += it.key.mediaStoreId }
        }
        return out
    }

    @Test fun `newest first puts hidden items at their date including runs in one gap and both ends`() {
        val rows = listOf(media(1, 5000), media(2, 3000), media(3, 1000))
        val hidden = listOf(media(100, 9000), media(101, 4000), media(102, 3500), media(103, 2000), media(104, 10))
        assertEquals(listOf(100L, 1, 101, 102, 2, 103, 3, 104), merged(rows, hidden, LibrarySettings()))
    }

    @Test fun `oldest first reverses the placement`() {
        val rows = listOf(media(3, 1000), media(2, 3000), media(1, 5000))
        val hidden = listOf(media(100, 9000), media(101, 4000), media(104, 10))
        assertEquals(listOf(104L, 3, 2, 101, 1, 100), merged(rows, hidden, LibrarySettings(ascending = true)))
    }

    @Test fun `name and size sorts use their own field`() {
        val byName = LibrarySettings(sort = LibrarySort.Name, ascending = true)
        val rows = listOf(media(1, 0, "apple"), media(2, 0, "cherry"))
        assertEquals(listOf(1L, 100, 2), merged(rows, listOf(media(100, 0, "Banana")), byName))
        val bySize = LibrarySettings(sort = LibrarySort.Size, ascending = false)
        val bySizeRows = listOf(media(1, 0, size = 900), media(2, 0, size = 100))
        assertEquals(listOf(1L, 100, 2), merged(bySizeRows, listOf(media(100, 0, size = 500)), bySize))
    }

    @Test fun `grouping orders by date group first so a non-date sort never repeats a header`() {
        val day = 86_400_000L
        val bySize = LibrarySettings(sort = LibrarySort.Size, ascending = false, grouping = com.librestatic.lightforge.core.preferences.LibraryGrouping.Day)
        val order = hiddenOrder(bySize, java.time.ZoneOffset.UTC)
        // Library rows as the query returns them: newest day first, largest first inside a day.
        val rows = listOf(media(1, 3 * day, size = 10), media(2, 3 * day + 5, size = 5), media(3, day, size = 99))
        assertEquals(rows, rows.sortedWith(order))
        // A big hidden file from an older day must not jump above the newer day's rows.
        val hidden = media(100, day + 7, size = 1_000)
        val all = (rows + hidden).sortedWith(order)
        assertEquals(listOf(1L, 2, 100, 3), all.map { it.key.mediaStoreId })
    }

    @Test fun `an overlay item never shares a stable key with a library item of the same id`() {
        val library = TimelineEntry.Media(media(7, 1))
        val hidden = TimelineEntry.Media(media(7, 1).copy(isHiddenOverlay = true))
        org.junit.Assert.assertNotEquals(library.stableKey, hidden.stableKey)
    }

    @Test fun `an empty library still lists every hidden item once`() {
        val hidden = listOf(media(100, 1), media(101, 2))
        assertEquals(listOf(101L, 100), merged(emptyList(), hidden, LibrarySettings()))
    }

    @Test fun `a scanned item the library already lists is dropped and repeats collapse`() {
        val inLibrary = setOf(MediaKey("external_primary", 7))
        val scanned = listOf(media(100, 1), media(7, 5), media(100, 1))
        val kept = withoutLibraryKeys(scanned, inLibrary)
        assertEquals(listOf(100L), kept.map { it.key.mediaStoreId })
        val rows = listOf(media(7, 5), media(1, 3))
        val keys = merged(rows, kept, LibrarySettings()).map { it }
        assertEquals(keys.size, keys.toSet().size)
    }
}
