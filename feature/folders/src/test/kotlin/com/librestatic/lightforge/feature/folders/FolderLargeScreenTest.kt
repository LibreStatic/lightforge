package com.librestatic.lightforge.feature.folders

import androidx.compose.ui.input.key.Key
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLargeScreenTest {
    private data class Row(val volume: String, val path: String, val bucket: Long, val count: Long)

    private fun tree(vararg rows: Row): FolderTree {
        fun FolderNode<Row>.stats(): FolderNode<FolderStats> = FolderNode(
            volumeName, relativePath, name,
            direct?.let { FolderStats(it.bucket, it.count, it.count, 0, it.count * 10, 1, 100 + it.bucket, 1000 + it.bucket, true) },
            children.map { it.stats() },
        )
        return FolderTree.from(
            buildFolderForest(
                items = rows.toList(),
                volumeOf = Row::volume,
                relativePathOf = Row::path,
                fallbackKeyOf = { it.bucket.toString() },
                fallbackNameOf = { it.bucket.toString() },
                nameOrder = NaturalNameOrder,
            ).map { it.stats() },
        )
    }

    private val primary = "external_primary"
    private val library = tree(
        Row(primary, "DCIM/Camera/", 1, 5),
        Row(primary, "DCIM/Camera/Burst/", 2, 3),
        Row(primary, "DCIM/Screenshots/", 3, 2),
        Row(primary, "Pictures/Trips/Paris/", 4, 7),
        Row("1234-ABCD", "DCIM/", 5, 4),
    )

    private fun loc(path: String?, volume: String = primary) = FolderLocation(volume, path)

    @Test
    fun collapsedTreeListsOnlyVolumeRoots() {
        val rows = buildFolderTreeRows(library, FolderLocation.Start, emptySet())
        assertEquals(listOf(primary, "1234-ABCD"), rows.map { it.volumeName })
        assertTrue(rows.all { it.name == null && it.depth == 0 && !it.expanded && it.hasChildren })
    }

    @Test
    fun expandedRowShowsItsFoldersOneLevelDeeper() {
        val rows = buildFolderTreeRows(library, FolderLocation.Start, setOf(treeKeyOf(loc(null))))
        val below = rows.filter { it.volumeName == primary && it.depth == 1 }
        assertEquals(listOf("DCIM", "Pictures/Trips/Paris"), below.map { it.name })
        assertTrue(rows.first { it.volumeName == primary && it.depth == 0 }.expanded)
        assertEquals(loc("Pictures/Trips/Paris/"), below.last().location)
    }

    @Test
    fun chainsMergeLikeTheCardsAndCanBeSwitchedOff() {
        val open = setOf(treeKeyOf(loc(null)))
        val merged = buildFolderTreeRows(library, FolderLocation.Start, open, compactChains = true)
        assertTrue(merged.any { it.name == "Pictures/Trips/Paris" })
        val plain = buildFolderTreeRows(library, FolderLocation.Start, open, compactChains = false)
        assertTrue(plain.any { it.name == "Pictures" } && plain.none { it.name == "Pictures/Trips/Paris" })
    }

    @Test
    fun currentFolderIsMarkedAndAncestorsRevealIt() {
        val at = loc("DCIM/Camera/")
        val rows = buildFolderTreeRows(library, at, treeAncestorKeys(at))
        assertEquals(listOf(loc("DCIM/Camera/")), rows.filter { it.isCurrent }.map { it.location })
        assertEquals(setOf(treeKeyOf(loc(null)), treeKeyOf(loc("DCIM/"))), treeAncestorKeys(at))
    }

    @Test
    fun ancestorsOfAVolumeRootOrTheStartLevel() {
        assertEquals(setOf(treeKeyOf(loc(null))), treeAncestorKeys(loc(null)))
        assertEquals(emptySet<String>(), treeAncestorKeys(FolderLocation.Start))
    }

    private val file = MediaKey(primary, 10)
    private val other = MediaKey(primary, 11)
    private val camera = loc("DCIM/Camera/")

    @Test
    fun filesCanBeDroppedOnAnotherFolderButNotOnTheirOwn() {
        val drag = ExplorerDragPayload(files = listOf(file))
        assertTrue(drag.canDropOn(loc("DCIM/Screenshots/"), filesIn = camera, advanced = false))
        assertFalse(drag.canDropOn(camera, filesIn = camera, advanced = false))
        assertFalse(drag.canDropOn(FolderLocation.Start, filesIn = camera, advanced = true))
    }

    @Test
    fun foldersNeedTheAdvancedModeAndTheMoveRules() {
        val drag = ExplorerDragPayload(folders = listOf(loc("DCIM/Camera/")))
        val target = loc("Pictures/")
        assertFalse(drag.canDropOn(target, filesIn = null, advanced = false))
        assertTrue(drag.canDropOn(target, filesIn = null, advanced = true))
        // Into itself, its own descendants, and the parent it already has.
        assertFalse(drag.canDropOn(loc("DCIM/Camera/"), null, true))
        assertFalse(drag.canDropOn(loc("DCIM/Camera/Burst/"), null, true))
        assertFalse(drag.canDropOn(loc("DCIM/"), null, true))
        // Never across volumes, never into Android/.
        assertFalse(drag.canDropOn(loc("DCIM/", "1234-ABCD"), null, true))
        assertFalse(drag.canDropOn(loc("Android/media/app/"), null, true))
    }

    @Test
    fun standardFoldersCannotBeDragged() {
        assertFalse(ExplorerDragPayload(folders = listOf(loc("DCIM/"))).canDropOn(loc("Pictures/"), null, true))
        assertFalse(ExplorerDragPayload(folders = listOf(loc("Android/media/x/"))).canDropOn(loc("Pictures/"), null, true))
    }

    @Test
    fun foldersDraggedWithFilesMayDropOnTheirParent() {
        val drag = ExplorerDragPayload(folders = listOf(loc("DCIM/Camera/")), withSelectedFiles = true)
        assertTrue(drag.canDropOn(loc("DCIM/"), null, true))
        assertFalse(drag.canDropOn(loc("DCIM/Camera/"), null, true))
    }

    @Test
    fun emptyPayloadDropsNowhere() {
        assertFalse(ExplorerDragPayload().canDropOn(loc("Pictures/"), null, true))
    }

    private fun card(name: String, path: String) = FolderCardUi(
        key = "$primary|$path", name = name, itemCount = 1, sizeBytes = 1, subfolderCount = 0, cover = null,
        action = FolderAction.Enter(loc(path)), location = loc(path),
    )

    @Test
    fun draggingATickedCardCarriesEveryTickedCardAndFile() {
        val a = card("A", "A/")
        val b = card("B", "B/")
        val c = card("C", "C/")
        val selection = ExplorerSelection(folders = setOf(a.key, b.key), mediaSelecting = true, fileCount = 2, isMediaSelected = { it == file })
        assertEquals(
            ExplorerDragPayload(folders = listOf(a.location, b.location), withSelectedFiles = true),
            dragPayloadForFolder(a, listOf(a, b, c), selection),
        )
        // An unticked card is dragged alone.
        assertEquals(ExplorerDragPayload(folders = listOf(c.location)), dragPayloadForFolder(c, listOf(a, b, c), selection))
        // A ticked file takes the ticked cards along; an unticked one goes alone.
        assertEquals(
            ExplorerDragPayload(folders = listOf(a.location, b.location), withSelectedFiles = true),
            dragPayloadForFile(file, listOf(a, b, c), selection),
        )
        assertEquals(ExplorerDragPayload(files = listOf(other)), dragPayloadForFile(other, listOf(a, b, c), selection))
    }

    @Test
    fun keyboardShortcuts() {
        assertEquals(ExplorerShortcut.Up, explorerShortcutFor(Key.Backspace, ctrl = false, alt = false, selecting = false))
        assertEquals(ExplorerShortcut.Up, explorerShortcutFor(Key.DirectionUp, ctrl = false, alt = true, selecting = false))
        assertEquals(ExplorerShortcut.SelectAll, explorerShortcutFor(Key.A, ctrl = true, alt = false, selecting = false))
        assertEquals(ExplorerShortcut.Delete, explorerShortcutFor(Key.Delete, ctrl = false, alt = false, selecting = true))
        assertEquals(ExplorerShortcut.ClearSelection, explorerShortcutFor(Key.Escape, ctrl = false, alt = false, selecting = true))
        // Arrows and Enter belong to the focus system; plain A and Escape without a selection do nothing.
        assertNull(explorerShortcutFor(Key.DirectionDown, ctrl = false, alt = false, selecting = false))
        assertNull(explorerShortcutFor(Key.Enter, ctrl = false, alt = false, selecting = false))
        assertNull(explorerShortcutFor(Key.A, ctrl = false, alt = false, selecting = false))
        assertNull(explorerShortcutFor(Key.Escape, ctrl = false, alt = false, selecting = false))
    }

    @Test
    fun scrollPositionsSurviveTheSavedStateRoundTrip() {
        val positions = linkedMapOf("external_primary|DCIM/" to (12 to 40), "external_primary|null" to (0 to 0))
        assertEquals(positions, decodeScrollPositions(encodeScrollPositions(positions)))
        // Only the most recent 64 are kept, and a damaged line is dropped.
        val many = (1..70).associate { "k$it" to (it to 0) }
        assertEquals(64, encodeScrollPositions(many).size)
        assertEquals(emptyMap<String, Pair<Int, Int>>(), decodeScrollPositions(listOf("broken", "a\tx\t1")))
    }
}
