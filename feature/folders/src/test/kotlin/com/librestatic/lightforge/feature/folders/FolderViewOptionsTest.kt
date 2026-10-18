package com.librestatic.lightforge.feature.folders

import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderViewOptionsTest {
    private fun card(name: String, items: Long = 0, size: Long = 0, date: Long? = null, pinned: Boolean = false) = FolderCardUi(
        key = name, name = name, itemCount = items, sizeBytes = size, subfolderCount = 0, cover = null,
        action = FolderAction.Enter(FolderLocation("v", "$name/")), location = FolderLocation("v", "$name/"),
        maxSortMillis = date, isPinned = pinned,
    )

    @Test fun naturalOrderComparesDigitRunsByValue() {
        assertTrue(naturalCompare("IMG 2", "IMG 10") < 0)
        assertTrue(naturalCompare("img", "IMG") == 0)
        assertTrue(naturalCompare("a01", "a1") == 0)
        assertTrue(naturalCompare("a", "ab") < 0)
    }

    @Test fun sortsByEveryKeyInBothDirections() {
        val cards = listOf(card("b", 5, 50, 3), card("a", 9, 10, 1), card("c", 1, 99, 2))
        fun order(key: FolderSortKey, desc: Boolean) =
            sortFolderCards(cards, FolderViewOptions(key, desc)).map { it.name }
        assertEquals(listOf("a", "b", "c"), order(FolderSortKey.Name, false))
        assertEquals(listOf("c", "b", "a"), order(FolderSortKey.Name, true))
        assertEquals(listOf("c", "b", "a"), order(FolderSortKey.Items, false))
        assertEquals(listOf("a", "b", "c"), order(FolderSortKey.Size, false))
        assertEquals(listOf("b", "c", "a"), order(FolderSortKey.Date, true))
    }

    @Test fun pinnedFoldersComeFirstWhateverTheSort() {
        val cards = listOf(card("a"), card("z", pinned = true), card("m"))
        assertEquals(listOf("z", "a", "m"), sortFolderCards(cards, FolderViewOptions()).map { it.name })
        assertEquals(listOf("z", "m", "a"), sortFolderCards(cards, FolderViewOptions(descending = true)).map { it.name })
    }

    @Test fun optionsRoundTripAndRejectGarbage() {
        val options = FolderViewOptions(FolderSortKey.Size, true, FolderViewMode.List)
        assertEquals(options, FolderViewOptions.decode(options.encode()))
        assertNull(FolderViewOptions.decode("nope"))
        assertNull(FolderViewOptions.decode(null))
        assertEquals("default", FolderViewOptions.keyOf(null))
        assertEquals("v|A/B/", FolderViewOptions.keyOf(FolderLocation("v", "A/B")))
    }

    @Test fun typedPathsDropPlatformPrefixes() {
        assertEquals(FolderLocation("v", "DCIM/Camera/"), parseTypedPath("/storage/emulated/0/DCIM/Camera", "v"))
        assertEquals(FolderLocation("v", "DCIM/"), parseTypedPath("/sdcard/DCIM/", "v"))
        assertEquals(FolderLocation("v", "Pictures/Trips/"), parseTypedPath("Pictures\\Trips", "v"))
        assertEquals(FolderLocation("v", null), parseTypedPath("/storage/emulated/0", "v"))
        assertEquals(FolderLocation("v", "A/B/"), parseTypedPath("/storage/1234-5678/A//B/./", "v"))
        assertNull(parseTypedPath("  ", "v"))
        assertNull(parseTypedPath("DCIM/../Android", "v"))
    }

    @Test fun excludableChainListsTheFolderAndItsAncestors() {
        assertEquals(
            listOf("A/B/C/", "A/B/", "A/"),
            excludableChain(FolderLocation("v", "A/B/C")).map { it.relativePath },
        )
        assertTrue(excludableChain(FolderLocation("v", null)).isEmpty())
    }

    private data class Row(val path: String, val bucket: Long, val count: Long)

    private fun tree(vararg rows: Row): FolderTree {
        val forest = buildFolderForest(
            items = rows.toList(), volumeOf = { "v" }, relativePathOf = Row::path,
            fallbackKeyOf = { it.bucket.toString() }, fallbackNameOf = { it.bucket.toString() }, nameOrder = NaturalNameOrder,
        ).map { it.mapRows() }
        return FolderTree.from(forest)
    }

    private fun com.librestatic.lightforge.core.model.folders.FolderNode<Row>.mapRows():
        com.librestatic.lightforge.core.model.folders.FolderNode<FolderStats> =
        com.librestatic.lightforge.core.model.folders.FolderNode(
            volumeName, relativePath, name,
            direct?.let { FolderStats(it.bucket, it.count, it.count, 0, it.count * 10, 1, 100 + it.bucket, 1000 + it.bucket, true) },
            children.map { it.mapRows() },
        )

    private val sample = tree(
        Row("DCIM/Camera/", 1, 5),
        Row("DCIM/Trips/Paris/", 2, 3),
        Row("Pictures/Paris Walks/", 3, 7),
        Row("Pictures/Memes/", 4, 2),
    )

    @Test fun searchFindsMatchingFoldersAtAnyDepthWithTheirParentPath() {
        val state = buildFolderExplorerUiState(sample, FolderLocation("v", null), query = "paris") as FolderExplorerUiState.Folder
        assertEquals(listOf("Paris", "Paris Walks"), state.folders.map { it.name })
        assertEquals(listOf("DCIM/Trips", "Pictures"), state.folders.map { it.pathLabel })
        assertEquals("paris", state.query)
    }

    @Test fun excludedAndPinnedFlagsReachTheCards() {
        val state = buildFolderExplorerUiState(
            sample, FolderLocation("v", "Pictures/"),
            isExcluded = { _, _, path -> path == "Pictures/Memes/" },
            pinned = setOf("v|Pictures/Memes/"),
        ) as FolderExplorerUiState.Folder
        val memes = state.folders.first { it.name == "Memes" }
        assertTrue(memes.isExcluded)
        assertTrue(memes.isPinned)
        assertEquals("Memes", state.folders.first().name)
        assertFalse(state.folders.first { it.name == "Paris Walks" }.isExcluded)
    }

    @Test fun theChosenSortOrdersTheCards() {
        val state = buildFolderExplorerUiState(
            sample, FolderLocation("v", "Pictures/"), options = FolderViewOptions(FolderSortKey.Items, descending = true),
        ) as FolderExplorerUiState.Folder
        assertEquals(listOf("Paris Walks", "Memes"), state.folders.map { it.name })
    }

    @Test fun copyingAFolderMayGoToItsParentOrAnotherVolumeButNotIntoItself() {
        val mode = ExplorerMode.PickDestination(PickPurpose.CopyFolder, FolderLocation("v", "Pictures/Trips/"))
        assertTrue(mode.canChoose(FolderLocation("v", "Pictures/")))
        assertTrue(mode.canChoose(FolderLocation("w", "DCIM/")))
        assertFalse(mode.canChoose(FolderLocation("v", "Pictures/Trips/")))
        assertFalse(mode.canChoose(FolderLocation("v", "Pictures/Trips/Paris/")))
        assertFalse(mode.canChoose(FolderLocation("v", "Android/media/x/")))
    }
}
