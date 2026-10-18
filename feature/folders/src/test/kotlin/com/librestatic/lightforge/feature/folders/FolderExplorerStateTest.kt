package com.librestatic.lightforge.feature.folders

import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import org.junit.Assert.assertEquals
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
}
