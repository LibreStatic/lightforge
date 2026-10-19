package com.librestatic.lightforge.feature.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderHiddenFilesTest {
    private fun hidden(id: Long, date: Long, name: String = "h$id.jpg", video: Boolean = false, size: Long = 10) = HiddenItemUi(
        volumeName = "v",
        relativePath = "A/",
        mediaStoreId = id,
        name = name,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        isVideo = video,
        sizeBytes = size,
        dateMillis = date,
        uri = "content://x/$id",
    )

    /** Library files, newest first, as dates. */
    private fun place(
        hidden: List<HiddenItemUi>,
        pagedDates: List<Long>,
        sort: FolderFileSort = FolderFileSort.NewestFirst,
        loaded: Boolean = true,
    ) = placeHiddenItems(hidden, sort, pagedDates.size, loaded) { FileOrderKey(pagedDates[it], "p$it", 0) }
        .map { it.mergedIndex to it.item.mediaStoreId }

    @Test
    fun hiddenFilesSitAmongTheOthersInDateOrder() {
        // Newest first: library 90, 70, 50, 30. A hidden 60 goes between 70 and 50.
        assertEquals(listOf(2 to 1L), place(listOf(hidden(1, 60)), listOf(90, 70, 50, 30)))
    }

    @Test
    fun severalHiddenFilesKeepGridOrderAndCountEachOther() {
        val result = place(listOf(hidden(1, 20), hidden(2, 95), hidden(3, 60)), listOf(90, 70, 50, 30))
        // 95 leads, 60 follows the library's 90 and 70, 20 closes the grid.
        assertEquals(listOf(0 to 2L, 3 to 3L, 6 to 1L), result)
    }

    @Test
    fun aTieKeepsTheLibraryFileFirst() {
        assertEquals(listOf(2 to 1L), place(listOf(hidden(1, 70)), listOf(90, 70, 50)))
    }

    @Test
    fun oldestFirstReversesTheDirection() {
        assertEquals(
            listOf(2 to 1L),
            place(listOf(hidden(1, 60)), listOf(30, 50, 70, 90), FolderFileSort.OldestFirst),
        )
    }

    @Test
    fun nameAndSizeSortsUseTheirOwnKey() {
        val byName = placeHiddenItems(listOf(hidden(1, 0, name = "b.jpg")), FolderFileSort.NameAscending, 3, true) {
            FileOrderKey(0, listOf("a.jpg", "c.jpg", "d.jpg")[it], 0)
        }
        assertEquals(1, byName.single().mergedIndex)
        val bySize = placeHiddenItems(listOf(hidden(1, 0, size = 500)), FolderFileSort.SizeDescending, 3, true) {
            FileOrderKey(0, "x", listOf(900L, 100L, 50L)[it])
        }
        assertEquals(1, bySize.single().mergedIndex)
    }

    @Test
    fun withNoLibraryFilesTheHiddenOnesFillTheGrid() {
        assertEquals(listOf(0 to 2L, 1 to 1L), place(listOf(hidden(1, 10), hidden(2, 20)), emptyList()))
    }

    @Test
    fun aHiddenFileOlderThanTheLoadedPagesWaitsForTheRest() {
        // More pages may come: the 10 would land at the end of what is loaded and then jump, so it is held back.
        assertEquals(emptyList<Pair<Int, Long>>(), place(listOf(hidden(1, 10)), listOf(90, 70), loaded = false))
        // One that sorts inside the loaded range is placed at once.
        assertEquals(listOf(1 to 2L), place(listOf(hidden(2, 80), hidden(1, 10)), listOf(90, 70), loaded = false))
    }

    @Test
    fun theFileFilterAppliesToHiddenFilesToo() {
        val items = listOf(hidden(1, 1), hidden(2, 2, video = true))
        assertEquals(2, filterHiddenItems(items, FolderFileFilter.All).size)
        assertEquals(listOf(1L), filterHiddenItems(items, FolderFileFilter.Images).map { it.mediaStoreId })
        assertEquals(listOf(2L), filterHiddenItems(items, FolderFileFilter.Videos).map { it.mediaStoreId })
        assertTrue(filterHiddenItems(emptyList(), FolderFileFilter.All).isEmpty())
    }
}
