package com.librestatic.lightforge.feature.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSelectionRulesTest {
    private fun card(
        path: String,
        items: Long = 3,
        volume: String = "external_primary",
        hidden: Boolean = false,
        hiddenByApp: Boolean = false,
        excluded: Boolean = false,
        pinned: Boolean = false,
        empty: Boolean = false,
        customCover: Boolean = false,
    ): FolderCardUi {
        val location = FolderLocation(volume, path)
        return FolderCardUi(
            key = path,
            name = path.trimEnd('/'),
            itemCount = items,
            sizeBytes = items * 10,
            subfolderCount = 1,
            cover = null,
            action = FolderAction.Enter(location),
            location = location,
            isHidden = hidden,
            isHiddenByApp = hiddenByApp,
            isEmptyDirectory = empty,
            isExcluded = excluded,
            isPinned = pinned,
            hasCustomCover = customCover,
        )
    }

    @Test
    fun nothingSelectedOffersNothing() {
        assertEquals(SelectionCapabilities(), selectionCapabilities(emptyList(), 0, advanced = true))
    }

    @Test
    fun modeOffOnlyOffersRulesAndLibraryActions() {
        val caps = selectionCapabilities(listOf(card("Trips/")), 0, advanced = false, canPinShortcut = true)
        assertTrue(caps.pin && caps.exclude && caps.addToAlbum && caps.cover && caps.shortcut && caps.properties)
        assertFalse(caps.copy || caps.move || caps.rename || caps.delete || caps.hide || caps.unhide)
    }

    @Test
    fun advancedModeOffersDiskActions() {
        val caps = selectionCapabilities(listOf(card("Trips/")), 0, advanced = true)
        assertTrue(caps.copy && caps.move && caps.rename && caps.delete && caps.hide)
    }

    @Test
    fun renameAndCoverNeedExactlyOneFolderAndNoFiles() {
        val one = listOf(card("Trips/"))
        assertTrue(selectionCapabilities(one, 0, true).rename)
        assertFalse(selectionCapabilities(one, 2, true).rename)
        assertFalse(selectionCapabilities(one, 2, true).cover)
        assertFalse(selectionCapabilities(one + card("Work/"), 0, true).rename)
        assertFalse(selectionCapabilities(one + card("Work/"), 0, true).cover)
        assertFalse(selectionCapabilities(one + card("Work/"), 0, true, canPinShortcut = true).shortcut)
    }

    @Test
    fun mixedSelectionKeepsTransferAndDelete() {
        val caps = selectionCapabilities(listOf(card("Trips/")), fileCount = 4, advanced = true)
        assertTrue(caps.copy && caps.move && caps.delete && caps.addToAlbum)
    }

    @Test
    fun standardFoldersCannotBeMovedRenamedOrDeletedButCanBeCopied() {
        val caps = selectionCapabilities(listOf(card("DCIM/")), 0, advanced = true)
        assertTrue(caps.copy)
        assertFalse(caps.move || caps.rename || caps.delete || caps.hide)
    }

    @Test
    fun androidTreeIsNeverChangedOnDisk() {
        val caps = selectionCapabilities(listOf(card("Android/media/app/")), 0, advanced = true)
        assertFalse(caps.copy || caps.move || caps.rename || caps.delete || caps.hide || caps.unhide)
        assertTrue(caps.exclude)
    }

    @Test
    fun excludeAndIncludeFollowTheWholeSelection() {
        val allExcluded = listOf(card("A/", excluded = true), card("B/", excluded = true))
        assertTrue(selectionCapabilities(allExcluded, 0, true).include)
        assertFalse(selectionCapabilities(allExcluded, 0, true).exclude)
        val some = listOf(card("A/", excluded = true), card("B/"))
        assertTrue(selectionCapabilities(some, 0, true).exclude)
        assertFalse(selectionCapabilities(some, 0, true).include)
    }

    @Test
    fun pinTogglesOnlyWhenEveryFolderIsPinned() {
        assertTrue(selectionCapabilities(listOf(card("A/", pinned = true), card("B/", pinned = true)), 0, false).unpin)
        assertTrue(selectionCapabilities(listOf(card("A/", pinned = true), card("B/")), 0, false).pin)
    }

    @Test
    fun unhideOnlyForFoldersThisAppHid() {
        assertTrue(selectionCapabilities(listOf(card("A/", hidden = true, hiddenByApp = true)), 0, true).unhide)
        val foreign = selectionCapabilities(listOf(card("A/", hidden = true)), 0, true)
        assertFalse(foreign.unhide || foreign.hide)
    }

    @Test
    fun emptyDirectoriesCannotBeCopiedOrAddedToAnAlbum() {
        val caps = selectionCapabilities(listOf(card("A/", items = 0, empty = true)), 0, true)
        assertFalse(caps.copy || caps.addToAlbum || caps.cover)
    }

    @Test
    fun resetCoverOnlyWhenOneFolderHasACustomCover() {
        assertTrue(selectionCapabilities(listOf(card("A/", customCover = true)), 0, false).resetCover)
        assertFalse(selectionCapabilities(listOf(card("A/")), 0, false).resetCover)
    }

    @Test
    fun totalsAddUpTheSelection() {
        val totals = selectionTotals(listOf(card("A/", items = 2), card("B/", items = 5)))
        assertEquals(SelectionTotals(folders = 2, items = 7, bytes = 70, subfolders = 2), totals)
    }

    @Test
    fun multiSourceMoveRefusesIntoAnySourceOrAcrossVolumes() {
        val a = FolderLocation("external_primary", "Pictures/A/")
        val b = FolderLocation("external_primary", "Pictures/B/")
        val mode = ExplorerMode.PickDestination(PickPurpose.MoveFolder, listOf(a, b))
        assertFalse(mode.canChoose(a))
        assertFalse(mode.canChoose(FolderLocation("external_primary", "Pictures/B/Deep/")))
        assertFalse(mode.canChoose(FolderLocation("1234-ABCD", "Pictures/")))
        assertTrue(mode.canChoose(FolderLocation("external_primary", "Movies/")))
    }

    @Test
    fun moveToTheSharedParentIsANoOpUnlessFilesTravel() {
        val a = FolderLocation("external_primary", "Pictures/A/")
        val parent = FolderLocation("external_primary", "Pictures/")
        assertFalse(ExplorerMode.PickDestination(PickPurpose.MoveFolder, listOf(a)).canChoose(parent))
        assertTrue(ExplorerMode.PickDestination(PickPurpose.Move, listOf(a), withFiles = true).canChoose(parent))
        assertTrue(ExplorerMode.PickDestination(PickPurpose.CopyFolder, listOf(a)).canChoose(parent))
    }

    @Test
    fun copyMayCrossVolumesAndOnlyFolderPurposesNeedSources() {
        val a = FolderLocation("external_primary", "Pictures/A/")
        assertTrue(ExplorerMode.PickDestination(PickPurpose.CopyFolder, listOf(a)).canChoose(FolderLocation("1234-ABCD", "Pictures/")))
        assertFalse(ExplorerMode.PickDestination(PickPurpose.CopyFolder).canChoose(FolderLocation("external_primary", "Pictures/")))
        assertTrue(ExplorerMode.PickDestination(PickPurpose.Copy).canChoose(FolderLocation("external_primary", "Pictures/")))
        assertTrue(PickPurpose.Copy.isCopy && PickPurpose.CopyFolder.isCopy)
        assertFalse(PickPurpose.Move.isCopy || PickPurpose.MoveFolder.isCopy)
    }
}
