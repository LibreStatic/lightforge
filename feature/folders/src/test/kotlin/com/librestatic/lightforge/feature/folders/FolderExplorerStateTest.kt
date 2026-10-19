package com.librestatic.lightforge.feature.folders

import com.librestatic.lightforge.core.model.folders.mergeAdvancedOverlay
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderExplorerStateTest {
    private data class Row(val volume: String, val path: String, val bucket: Long, val count: Long, val bytes: Long = count * 10)

    private fun tree(vararg rows: Row): FolderTree {
        val forest = buildFolderForest(
            items = rows.toList(),
            volumeOf = Row::volume,
            relativePathOf = Row::path,
            fallbackKeyOf = { it.bucket.toString() },
            fallbackNameOf = { it.bucket.toString() },
            nameOrder = NaturalNameOrder,
        ).map { node -> node.mapRows() }
        return FolderTree.from(forest)
    }

    private fun com.librestatic.lightforge.core.model.folders.FolderNode<Row>.mapRows():
        com.librestatic.lightforge.core.model.folders.FolderNode<FolderStats> =
        com.librestatic.lightforge.core.model.folders.FolderNode(
            volumeName, relativePath, name,
            direct?.let { FolderStats(it.bucket, it.count, it.count, 0, it.bytes, 1, 100 + it.bucket, 1000 + it.bucket, true) },
            children.map { it.mapRows() },
        )

    private val single = tree(
        Row("external_primary", "DCIM/Camera/", 1, 5),
        Row("external_primary", "DCIM/Camera/Burst/", 2, 3),
        Row("external_primary", "Pictures/Screenshots/", 3, 7),
        Row("external_primary", "Android/media/com.whatsapp/WhatsApp Images/", 4, 9),
    )
    private val two = tree(
        Row("external_primary", "DCIM/", 1, 5),
        Row("1234-ABCD", "DCIM/", 2, 4),
    )

    @Test
    fun emptyTreeIsEmptyState() {
        assertEquals(FolderExplorerUiState.Empty, buildFolderExplorerUiState(FolderTree.Empty, FolderLocation.Start))
    }

    @Test
    fun twoVolumesStartAtVolumeList() {
        val state = buildFolderExplorerUiState(two, FolderLocation.Start, mapOf("1234-ABCD" to VolumeSpace("SD card", 5, 10)))
        state as FolderExplorerUiState.Volumes
        assertEquals(listOf("external_primary", "1234-ABCD"), state.volumes.map { it.volumeName })
        assertEquals("SD card", state.volumes[1].label)
        assertEquals(4L, state.volumes[1].itemCount)
        assertEquals(40L, state.volumes[1].sizeBytes)
        assertNotNull(state.volumes[0].cover)
    }

    @Test
    fun singleVolumeStartsInsideIt() {
        val state = buildFolderExplorerUiState(single, FolderLocation.Start) as FolderExplorerUiState.Folder
        assertEquals(listOf("Android/media/com.whatsapp/WhatsApp Images", "DCIM/Camera", "Pictures/Screenshots"), state.folders.map { it.name })
        assertNull(state.files)
        assertEquals(1, state.crumbs.size)
        assertTrue(state.crumbs.single().isCurrent)
    }

    @Test
    fun foldersAreCompactedAndCountSubtree() {
        val state = buildFolderExplorerUiState(single, FolderLocation.Start) as FolderExplorerUiState.Folder
        val byName = state.folders.associateBy { it.name }
        val camera = byName.getValue("DCIM/Camera")
        assertEquals(8L, camera.itemCount)
        assertEquals(80L, camera.sizeBytes)
        assertEquals(1, camera.subfolderCount)
        assertEquals(FolderAction.Enter(FolderLocation("external_primary", "DCIM/Camera/")), camera.action)
        // The cover is the newest item of the subtree: the Burst sub-folder.
        assertEquals(1002L, camera.cover?.mediaStoreId)
    }

    @Test
    fun leafOpensItsAlbumAndNonLeafEnters() {
        val state = buildFolderExplorerUiState(single, FolderLocation.Start) as FolderExplorerUiState.Folder
        val screenshots = state.folders.first { it.name == "Pictures/Screenshots" }
        val action = screenshots.action as FolderAction.OpenFiles
        assertEquals(AlbumKey.Physical("external_primary", 3), action.album.key)
        assertEquals("Screenshots", action.album.name)
        assertEquals(7L, action.album.itemCount)
        val whatsapp = state.folders.first { it.name.startsWith("Android") }
        // Compacted chain navigates to (and, being a leaf, opens) its deepest folder.
        assertEquals(AlbumKey.Physical("external_primary", 4), (whatsapp.action as FolderAction.OpenFiles).album.key)
    }

    @Test
    fun folderWithDirectItemsShowsFilesCard() {
        val state = buildFolderExplorerUiState(single, FolderLocation("external_primary", "DCIM/Camera/")) as FolderExplorerUiState.Folder
        val files = state.files!!
        assertEquals(5L, files.itemCount)
        assertEquals(AlbumKey.Physical("external_primary", 1), (files.action as FolderAction.OpenFiles).album.key)
        assertEquals(listOf("Burst"), state.folders.map { it.name })
        assertEquals(listOf("external_primary" to null, "DCIM/Camera" to "DCIM/Camera/"), state.crumbs.map { (it.title ?: it.volumeName) to it.location.relativePath })
    }

    @Test
    fun breadcrumbMergesCompactedSegmentsAndMarksCurrent() {
        val state = buildFolderExplorerUiState(
            single,
            FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp Images/"),
        ) as FolderExplorerUiState.Folder
        assertEquals(listOf(null, "Android/media/com.whatsapp/WhatsApp Images"), state.crumbs.map { it.title })
        assertEquals(listOf(false, true), state.crumbs.map { it.isCurrent })
    }

    @Test
    fun parentSkipsMergedSegmentsAndStopsAtStart() {
        val deep = FolderLocation("external_primary", "DCIM/Camera/Burst/")
        assertEquals(FolderLocation("external_primary", "DCIM/Camera/"), parentLocation(single, deep))
        // Android/media/com.whatsapp/ is merged into its child, so Back from it lands on the volume root.
        assertEquals(
            FolderLocation("external_primary", null),
            parentLocation(single, FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp Images/")),
        )
        assertNull(parentLocation(single, FolderLocation("external_primary", null)))
        assertNull(parentLocation(single, FolderLocation.Start))
    }

    @Test
    fun parentWithTwoVolumesGoesToVolumeList() {
        assertEquals(FolderLocation.Start, parentLocation(two, FolderLocation("1234-ABCD", null)))
        assertNull(parentLocation(two, FolderLocation.Start))
        assertEquals(FolderLocation("1234-ABCD", null), parentLocation(two, FolderLocation("1234-ABCD", "DCIM/")))
    }

    @Test
    fun missingLocationFallsBackToNearestAncestor() {
        assertEquals(
            FolderLocation("external_primary", "DCIM/Camera/"),
            resolveLocation(single, FolderLocation("external_primary", "DCIM/Camera/Gone/Deeper/")),
        )
        assertEquals(FolderLocation.Start, resolveLocation(two, FolderLocation("missing", "DCIM/")))
        assertEquals(FolderLocation("external_primary", null), resolveLocation(single, FolderLocation("missing", null)))
    }

    // Advanced mode

    private val advancedTree = com.librestatic.lightforge.core.model.folders.mergeAdvancedOverlay(
        single,
        hiddenGroups = listOf(
            com.librestatic.lightforge.core.model.folders.HiddenFolderGroup(
                "external_primary", "Pictures/Private/", 4, 400, 50, 60, 9,
                com.librestatic.lightforge.core.model.folders.HiddenMediaKind.Image,
            ),
        ),
        emptyDirs = listOf(com.librestatic.lightforge.core.model.folders.EmptyDirectory("external_primary", "Empty/")),
    )

    private fun cardsAtRoot(tree: FolderTree, recorded: (FolderLocation) -> Boolean = { false }) =
        (buildFolderExplorerUiState(tree, FolderLocation.Start, isRecordedHide = recorded) as FolderExplorerUiState.Folder).folders

    @Test
    fun modeOffCardsCarryNoAdvancedFlags() {
        val cards = cardsAtRoot(single)
        assertTrue(cards.none { it.isHidden || it.isEmptyDirectory || it.isHiddenByApp })
    }

    @Test
    fun hiddenAndEmptyFoldersAreFlagged() {
        val cards = cardsAtRoot(advancedTree) { it.relativePath == "Pictures/Private/" }
        val pictures = cards.single { it.name.startsWith("Pictures") }
        assertEquals(1, cards.count { it.isEmptyDirectory })
        val empty = cards.single { it.isEmptyDirectory }
        assertEquals(0L, empty.itemCount)
        assertNull(empty.minSortMillis)
        assertTrue(pictures.itemCount > 0)
        val hidden = (buildFolderExplorerUiState(
            advancedTree, FolderLocation("external_primary", "Pictures/"),
            isRecordedHide = { it.relativePath == "Pictures/Private/" },
        ) as FolderExplorerUiState.Folder).folders.single { it.isHidden }
        assertTrue(hidden.isHiddenByApp)
        assertEquals(4L, hidden.directCount)
        assertEquals(50L, hidden.minSortMillis)
        assertEquals(60L, hidden.maxSortMillis)
        assertTrue(hidden.action is FolderAction.Enter)
    }

    @Test
    fun recordedHideIsFlaggedWhenItsMediaSitsOnlyInSubfolders() {
        val tree = mergeAdvancedOverlay(
            single,
            hiddenGroups = listOf(
                com.librestatic.lightforge.core.model.folders.HiddenFolderGroup(
                    "external_primary", "DCIM/Trip/Day1/", 3, 300, 5, 6, 8,
                    com.librestatic.lightforge.core.model.folders.HiddenMediaKind.Image,
                ),
                com.librestatic.lightforge.core.model.folders.HiddenFolderGroup(
                    "external_primary", "DCIM/Trip/Day2/", 2, 200, 7, 9, 10,
                    com.librestatic.lightforge.core.model.folders.HiddenMediaKind.Image,
                ),
            ),
            emptyDirs = listOf(com.librestatic.lightforge.core.model.folders.EmptyDirectory("external_primary", "Quiet/")),
        )
        val cards = (buildFolderExplorerUiState(
            tree, FolderLocation("external_primary", "DCIM/"),
            isRecordedHide = { it.relativePath == "DCIM/Trip/" || it.relativePath == "Quiet/" },
        ) as FolderExplorerUiState.Folder).folders
        val trip = cards.single { it.name.startsWith("Trip") }
        assertTrue(trip.isHidden)
        assertTrue(trip.isHiddenByApp)
        val root = cardsAtRoot(tree) { it.relativePath == "Quiet/" }
        val quiet = root.single { it.name == "Quiet" }
        assertTrue(quiet.isHidden && quiet.isHiddenByApp)
    }

    @Test
    fun hiddenItemsShowOnlyInTheirOwnFolder() {
        val item = HiddenItemUi("external_primary", "Pictures/Private", 9, "a.jpg", "image/jpeg", false, 1, 1, "content://x/9")
        val inside = buildFolderExplorerUiState(
            advancedTree, FolderLocation("external_primary", "Pictures/Private/"), hiddenItems = listOf(item),
        ) as FolderExplorerUiState.Folder
        assertEquals(listOf(item), inside.hiddenItems)
        val elsewhere = buildFolderExplorerUiState(
            advancedTree, FolderLocation("external_primary", "Pictures/"), hiddenItems = listOf(item),
        ) as FolderExplorerUiState.Folder
        assertTrue(elsewhere.hiddenItems.isEmpty())
    }

    @Test
    fun emptyDirectoryOpensAsAnEmptyFolder() {
        val state = buildFolderExplorerUiState(advancedTree, FolderLocation("external_primary", "Empty/")) as FolderExplorerUiState.Folder
        assertTrue(state.isEmpty)
    }

    @Test
    fun folderWithFilesGetsAnInlineMediaScope() {
        val state = buildFolderExplorerUiState(single, FolderLocation("external_primary", "DCIM/Camera/")) as FolderExplorerUiState.Folder
        assertEquals(FolderMediaScope("external_primary", "DCIM/Camera/", false), state.media)
        val flattened = buildFolderExplorerUiState(
            single, FolderLocation("external_primary", "DCIM/"), includeSubfolders = true,
        ) as FolderExplorerUiState.Folder
        assertEquals(FolderMediaScope("external_primary", "DCIM/", true), flattened.media)
        val root = buildFolderExplorerUiState(single, FolderLocation("external_primary", null)) as FolderExplorerUiState.Folder
        assertNull(root.media)
    }

    @Test
    fun chainCompactionCanBeSwitchedOff() {
        val root = FolderLocation("external_primary", null)
        fun names(compact: Boolean) =
            (buildFolderExplorerUiState(single, root, compactChains = compact) as FolderExplorerUiState.Folder).folders.map { it.name }
        assertTrue(names(true).contains("Android/media/com.whatsapp/WhatsApp Images"))
        assertTrue(names(false).contains("Android"))
        assertTrue(names(false).none { it.contains('/') })

        val deep = FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp Images/")
        val compactCrumbs = (buildFolderExplorerUiState(single, deep, compactChains = true) as FolderExplorerUiState.Folder).crumbs
        val plainCrumbs = (buildFolderExplorerUiState(single, deep, compactChains = false) as FolderExplorerUiState.Folder).crumbs
        assertEquals(2, compactCrumbs.size)
        assertEquals(5, plainCrumbs.size)
        assertEquals(FolderLocation("external_primary", "Android/media/com.whatsapp/"), parentLocation(single, deep, compactChains = false))
        assertEquals(FolderLocation("external_primary", null), parentLocation(single, deep, compactChains = true))
    }

    @Test
    fun singleVolumeRootReportsItsSpace() {
        val space = VolumeSpace(null, 5, 10)
        val root = buildFolderExplorerUiState(single, FolderLocation.Start, mapOf("external_primary" to space)) as FolderExplorerUiState.Folder
        assertEquals(space, root.rootSpace)
        val nested = buildFolderExplorerUiState(
            single, FolderLocation("external_primary", "DCIM/Camera/"), mapOf("external_primary" to space),
        ) as FolderExplorerUiState.Folder
        assertNull(nested.rootSpace)
    }

    @Test
    fun folderNameValidation() {
        assertNull(validateFolderName("Trips", listOf("Other")))
        assertEquals(FolderNameProblem.Blank, validateFolderName("  ", emptyList()))
        assertEquals(FolderNameProblem.Reserved, validateFolderName("..", emptyList()))
        assertEquals(FolderNameProblem.InvalidCharacters, validateFolderName("a/b", emptyList()))
        assertEquals(FolderNameProblem.TooLong, validateFolderName("x".repeat(256), emptyList()))
        assertEquals(FolderNameProblem.AlreadyExists, validateFolderName("trips", listOf("Trips")))
    }

    @Test fun pickerNeverChoosesTheVolumeList() {
        for (purpose in PickPurpose.values()) {
            assertTrue(!ExplorerMode.PickDestination(purpose, FolderLocation("v", "A/")).canChoose(null))
            assertTrue(!ExplorerMode.PickDestination(purpose, FolderLocation("v", "A/")).canChoose(FolderLocation.Start))
        }
    }

    @Test fun itemPickersChooseAnyFolderOfAnyVolume() {
        val move = ExplorerMode.PickDestination(PickPurpose.Move)
        val copy = ExplorerMode.PickDestination(PickPurpose.Copy)
        assertTrue(move.canChoose(FolderLocation("external_primary", null)))
        assertTrue(move.canChoose(FolderLocation("1234-ABCD", "DCIM/Camera/")))
        assertTrue(copy.canChoose(FolderLocation("external_primary", "Pictures/")))
    }

    @Test fun folderMoveRefusesTheSourceItsDescendantsItsParentAndOtherVolumes() {
        val mode = ExplorerMode.PickDestination(PickPurpose.MoveFolder, FolderLocation("external_primary", "DCIM/Exports/"))
        assertTrue(!mode.canChoose(FolderLocation("external_primary", "DCIM/Exports/")))
        assertTrue(!mode.canChoose(FolderLocation("external_primary", "DCIM/Exports/2026/Trips")))
        assertTrue(!mode.canChoose(FolderLocation("external_primary", "DCIM/")))
        assertTrue(!mode.canChoose(FolderLocation("1234-ABCD", "DCIM/")))
        assertTrue(mode.canChoose(FolderLocation("external_primary", "Pictures/")))
        assertTrue(mode.canChoose(FolderLocation("external_primary", null)))
        // A sibling whose name merely starts with the source name is not a descendant.
        assertTrue(mode.canChoose(FolderLocation("external_primary", "DCIM/Exports2/")))
    }

    @Test fun movingARootLevelFolderToTheVolumeRootIsRefusedAsItsParent() {
        val mode = ExplorerMode.PickDestination(PickPurpose.MoveFolder, FolderLocation("external_primary", "Pictures/"))
        assertTrue(!mode.canChoose(FolderLocation("external_primary", null)))
        assertTrue(mode.canChoose(FolderLocation("external_primary", "DCIM/")))
    }

    @Test fun noPickerEverChoosesTheAndroidTree() {
        for (purpose in PickPurpose.values()) {
            val mode = ExplorerMode.PickDestination(purpose, FolderLocation("external_primary", "DCIM/Exports/"))
            assertTrue(!mode.canChoose(FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp/")))
            assertTrue(!mode.canChoose(FolderLocation("external_primary", "android/media/")))
        }
    }

    @Test fun standardTopLevelFoldersAreProtected() {
        for (p in listOf(null, "", "DCIM/", "pictures/", "Download", "Podcasts/", "Screenshots/", "Audiobooks/")) {
            assertTrue(p.toString(), isStandardFolder(p))
        }
        for (p in listOf("DCIM/Camera/", "Pictures/Trips/", "Trips/", "Podcasts2/")) {
            assertTrue(p, !isStandardFolder(p))
        }
    }

    @Test fun theAndroidTreeIsRecognisedInAnyCase() {
        for (p in listOf("Android/", "Android/media/", "android/media/com.whatsapp/", "ANDROID/data/x")) {
            org.junit.Assert.assertTrue(p, isAndroidAppTree(p))
        }
        for (p in listOf("Pictures/Android/", "AndroidApps/", "DCIM/")) {
            org.junit.Assert.assertFalse(p, isAndroidAppTree(p))
        }
    }

    @Test
    fun chosenCoverReplacesTheNewestItemAndIsMarked() {
        val chosen = com.librestatic.lightforge.core.model.MediaKey("external_primary", 77)
        val state = buildFolderExplorerUiState(
            single,
            FolderLocation.Start,
            covers = mapOf(pinKeyOf(FolderLocation("external_primary", "DCIM/Camera/"))!! to chosen),
        ) as FolderExplorerUiState.Folder
        val byName = state.folders.associateBy { it.name }
        assertEquals(chosen, byName.getValue("DCIM/Camera").cover)
        assertTrue(byName.getValue("DCIM/Camera").hasCustomCover)
        assertTrue(!byName.getValue("Pictures/Screenshots").hasCustomCover)
    }

    @Test
    fun aPendingNewFolderIsChosenInsideTheFolderShownAndFollowsThePickerRules() {
        val parent = FolderLocation("external_primary", "Pictures/")
        val child = pendingFolderLocation(parent, " Trip ")
        assertEquals(FolderLocation("external_primary", "Pictures/Trip/"), child)
        val mode = ExplorerMode.PickDestination(PickPurpose.Move, emptyList(), withFiles = true)
        assertTrue(mode.canChoose(child))
        assertFalse(mode.canChoose(pendingFolderLocation(FolderLocation("external_primary", "Android/media/com.x/"), "Trip")))
        val folderMove = ExplorerMode.PickDestination(PickPurpose.MoveFolder, listOf(FolderLocation("external_primary", "Pictures/Trip/")))
        assertFalse(folderMove.canChoose(pendingFolderLocation(FolderLocation("external_primary", "Pictures/Trip/"), "Sub")))
    }
}
