package com.librestatic.lightforge.feature.folders

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.folders.EmptyDirectory
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.HiddenFolderGroup
import com.librestatic.lightforge.core.model.folders.HiddenMediaKind
import com.librestatic.lightforge.core.model.folders.mergeAdvancedOverlay
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource

// Debug-only, zero-argument entry points for tools/compose-driver. They run the real
// buildFolderExplorerUiState over a fake folder tree and feed it to the real FolderExplorerContent;
// navigation works (tap cards and crumbs). Thumbnails are flat tinted bitmaps.

/** Two volumes: the start level lists them with free space. */
@Composable
fun FolderExplorerVolumesPreview() = Frame(twoVolumes, FolderLocation.Start)

/** A folder with its own files plus sub-folders, one of them a compacted chain. */
@Composable
fun FolderExplorerFolderPreview() = Frame(twoVolumes, FolderLocation("external_primary", "DCIM/"))

/** Single volume: starts inside it, with compacted names. */
@Composable
fun FolderExplorerSingleVolumePreview() = Frame(singleVolume, FolderLocation.Start)

/** A deep path, to check the breadcrumb scrolls to the current folder. */
@Composable
fun FolderExplorerDeepPreview() =
    Frame(singleVolume, FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/"))

/** No folders at all. */
@Composable
fun FolderExplorerEmptyPreview() = Frame(FolderTree.Empty, FolderLocation.Start)

/** Advanced mode off: the plain read-only browse view, with no overflow, badges or hidden rows. */
@Composable
fun FolderExplorerBrowseOffPreview() = Frame(singleVolume, FolderLocation.Start)

/**
 * Advanced mode on: hidden folders carry the Hidden badge, an empty directory is listed, each card has
 * an overflow menu (tap `folder-card-menu-<key>`; long-press works too).
 */
@Composable
fun FolderExplorerAdvancedPreview() = Frame(advancedTree, FolderLocation.Start, actions = previewActions)

/** Advanced mode, inside a hidden folder: its items show in a read-only grid. */
@Composable
fun FolderExplorerAdvancedHiddenFolderPreview() = Frame(
    advancedTree,
    FolderLocation("external_primary", "Pictures/Private/"),
    actions = previewActions,
    hiddenItems = previewHiddenItems,
)

/** Advanced mode was revoked while this app still hides folders: the notice offers to unhide them. */
@Composable
fun FolderExplorerRevokedPreview() = Frame(singleVolume, FolderLocation.Start, revoked = 2)

/** The folder tree could not be read: the error state with Retry. */
@Composable
fun FolderExplorerErrorPreview() = Frame(state = FolderExplorerUiState.Error)

/** A folder without anything in it. */
@Composable
fun FolderExplorerEmptyFolderPreview() = Frame(advancedTree, FolderLocation("external_primary", "Empty/"), actions = previewActions)

/** Destination picker for Move to folder, starting at the single volume's root. */
@Composable
fun FolderPickerMovePreview() = PickerFrame(PickPurpose.Move, advancedTree, FolderLocation.Start)

/** Destination picker for Copy to folder, inside a folder that has sub-folders. */
@Composable
fun FolderPickerCopyPreview() = PickerFrame(PickPurpose.Copy, advancedTree, FolderLocation("external_primary", "DCIM/"))

/** Folder move: inside the folder being moved, so "Move here" is disabled; its card is not listed. */
@Composable
fun FolderPickerMoveFolderInvalidPreview() = PickerFrame(
    PickPurpose.MoveFolder, advancedTree, FolderLocation("external_primary", "DCIM/Exports/"),
    source = FolderLocation("external_primary", "DCIM/Exports/"),
)

/** Folder move into a valid destination: "Move here" is enabled. */
@Composable
fun FolderPickerMoveFolderPreview() = PickerFrame(
    PickPurpose.MoveFolder, advancedTree, FolderLocation("external_primary", "Pictures/"),
    source = FolderLocation("external_primary", "DCIM/Exports/"),
)

/** A leaf folder without sub-folders: the placeholder is centred and the folder can still be chosen. */
@Composable
fun FolderPickerNoSubfoldersPreview() =
    PickerFrame(PickPurpose.Move, advancedTree, FolderLocation("external_primary", "Pictures/Instagram/"))

/** Inside Android/media: Move here and New folder are disabled, the system wipes that tree with its app. */
@Composable
fun FolderPickerAndroidTreePreview() =
    PickerFrame(PickPurpose.Move, advancedTree, FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/"))

/** Two volumes at the volume list: nothing can be chosen yet. */
@Composable
fun FolderPickerVolumesPreview() = PickerFrame(PickPurpose.Copy, twoVolumes, FolderLocation.Start)

@Composable
private fun PickerFrame(
    purpose: PickPurpose,
    tree: FolderTree,
    start: FolderLocation,
    source: FolderLocation? = null,
) {
    LightforgeTheme {
        var location by remember { mutableStateOf(start) }
        val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
        FolderPickerContent(
            purpose = purpose,
            sourceFolder = source,
            state = buildFolderExplorerUiState(tree, location, spaces),
            thumbnails = loader,
            onEnter = { location = it },
            onBack = { parentLocation(tree, location)?.let { location = it } },
            onNewFolder = {},
            onConfirm = {},
        )
    }
}

@Composable
private fun Frame(
    tree: FolderTree = FolderTree.Empty,
    start: FolderLocation = FolderLocation.Start,
    actions: FolderExplorerActions? = null,
    hiddenItems: List<HiddenItemUi> = emptyList(),
    revoked: Int = 0,
    state: FolderExplorerUiState? = null,
) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            var location by remember { mutableStateOf(start) }
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            FolderExplorerContent(
                state = state ?: buildFolderExplorerUiState(
                    tree, location, spaces, hiddenItems = hiddenItems, isRecordedHide = { it.relativePath == "Pictures/Private/" },
                ),
                thumbnails = loader,
                onEnter = { location = it },
                onOpenFiles = {},
                actions = actions,
                revokedWithHidden = revoked,
            )
        }
    }
}

private val previewActions = FolderExplorerActions(
    onHide = {},
    onUnhide = {},
    onRename = { _, _ -> },
    onDelete = {},
    onOpenHiddenItem = {},
)

private val previewHiddenItems = (1L..6L).map {
    HiddenItemUi("external_primary", "Pictures/Private/", it, "IMG_000$it.jpg", "image/jpeg", it % 3 == 0L, 3_200_000L, 1_700_000_000_000L + it, "content://media/$it")
}

private val spaces = mapOf(
    "external_primary" to VolumeSpace(null, 41_000_000_000L, 128_000_000_000L),
    "1234-ABCD" to VolumeSpace("SD card", 20_000_000_000L, 64_000_000_000L),
)

private class Row(val volume: String, val path: String, val bucket: Long, val count: Long, val newest: Long)

private fun tree(vararg rows: Row): FolderTree {
    fun FolderNode<Row>.stats(): FolderNode<FolderStats> = FolderNode(
        volumeName, relativePath, name,
        direct?.let { FolderStats(it.bucket, it.count, it.count, 0, it.count * 3_500_000L, 1, it.newest, 100 + it.bucket, true) },
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

private val primaryRows = arrayOf(
    Row("external_primary", "DCIM/", 1, 12, 900),
    Row("external_primary", "DCIM/Camera/", 2, 1240, 1000),
    Row("external_primary", "DCIM/Screenshots/", 3, 96, 800),
    Row("external_primary", "DCIM/Exports/2026/Trips/", 4, 31, 700),
    Row("external_primary", "Pictures/Instagram/", 5, 54, 600),
    Row("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/", 6, 530, 500),
)

private val singleVolume = tree(*primaryRows)
private val advancedTree = mergeAdvancedOverlay(
    singleVolume,
    hiddenGroups = listOf(
        HiddenFolderGroup("external_primary", "Pictures/Private/", 6, 19_200_000L, 1_700_000_000_000L, 1_700_000_000_006L, 1L, HiddenMediaKind.Image),
    ),
    emptyDirs = listOf(EmptyDirectory("external_primary", "Empty/"), EmptyDirectory("external_primary", "Pictures/New/")),
)
private val twoVolumes = tree(*primaryRows, Row("1234-ABCD", "DCIM/Camera/", 7, 310, 400))

private val FakeThumbnails = ThumbnailSource { request, _ ->
    val hues = intArrayOf(0xFF7A8F6B.toInt(), 0xFF8C6E5A.toInt(), 0xFF5D7A99.toInt(), 0xFFA38B5C.toInt(), 0xFF6F5F8C.toInt())
    Bitmap.createBitmap(request.widthPx.coerceAtMost(256), request.heightPx.coerceAtMost(256), Bitmap.Config.ARGB_8888)
        .apply { eraseColor(hues[(request.mediaKey.mediaStoreId % hues.size).toInt()]) }
}
