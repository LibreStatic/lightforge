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
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import kotlinx.coroutines.flow.flowOf
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

/**
 * Large window (start it with `--qualifiers w1000dp-h700dp`): the folder tree on the left, the content on
 * the right. Tap a tree row to open it, its chevron to open or close it; folders and files are drag sources
 * and the tree rows, breadcrumb segments and cards are drop targets (a drop only logs in the preview).
 */
@Composable
fun FolderExplorerLargeScreenPreview() = Frame(
    advancedTree, FolderLocation("external_primary", "DCIM/"),
    actions = previewActions, browse = previewDragBrowse, withTree = true, advanced = true,
)

/** The same window with advanced mode off: the tree pane is there, folders cannot be dragged. */
@Composable
fun FolderExplorerLargeScreenBrowseOffPreview() = Frame(
    singleVolume, FolderLocation("external_primary", "DCIM/"), browse = previewDragBrowse, withTree = true,
)

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

/** Mode off: a named new folder is pending, the bar says where it will be created and offers the system picker. */
@Composable
fun FolderPickerPendingFolderPreview() =
    PickerFrame(PickPurpose.Move, advancedTree, FolderLocation("external_primary", "DCIM/"), pendingFolder = "Holidays", otherLocation = true)

/** Two volumes at the volume list: nothing can be chosen yet. */
@Composable
fun FolderPickerVolumesPreview() = PickerFrame(PickPurpose.Copy, twoVolumes, FolderLocation.Start)

/**
 * Advanced mode, two folders and three files ticked together: the one selection bar offers copy, move and
 * delete inline and the rest (pin, exclude, hide, album, properties, select all) in its overflow.
 */
@Composable
fun FolderExplorerMixedSelectionPreview() = SelectionFrame(advanced = true, fileCount = 3)

/** Advanced mode off, one folder ticked: only the rules and library actions are offered. */
@Composable
fun FolderExplorerSelectionModeOffPreview() = SelectionFrame(advanced = false, fileCount = 0, ticked = setOf("DCIM/Camera"))

/** One folder is excluded by the folder rules: its card is dimmed and the badge includes it again. */
@Composable
fun FolderExplorerExcludedPreview() = Frame(
    singleVolume,
    FolderLocation.Start,
    browse = previewBrowse,
    excludedPaths = setOf("Pictures/Instagram/"),
)

/** The cover chooser of a folder, with the current choice reset in the top bar. */
@Composable
fun FolderCoverDialogPreview() {
    LightforgeTheme {
        val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
        val items = remember { flowOf(PagingData.from((1..18).map(::previewMedia))) }.collectAsLazyPagingItems()
        FolderCoverDialog(
            folderName = "Camera",
            media = items,
            thumbnails = loader,
            onPick = {},
            onReset = {},
            onDismiss = {},
        )
    }
}

/** The folder view options: folder sort and layout, then the file sort, filter and list layout. */
@Composable
fun FolderViewOptionsDialogPreview() {
    LightforgeTheme {
        FolderViewOptionsDialog(
            options = FolderViewOptions(
                fileSort = FolderFileSort.NameAscending,
                fileFilter = FolderFileFilter.Images,
                fileView = FolderViewMode.List,
            ),
            onChange = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun SelectionFrame(advanced: Boolean, fileCount: Long, ticked: Set<String> = setOf("DCIM/Camera", "DCIM/Screenshots")) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            var location by remember { mutableStateOf(FolderLocation("external_primary", "DCIM/")) }
            var folders by remember { mutableStateOf(ticked) }
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            FolderExplorerContent(
                state = buildFolderExplorerUiState(advancedTree, location, spaces),
                thumbnails = loader,
                onEnter = { location = it },
                onOpenFiles = {},
                actions = previewActions.takeIf { advanced },
                browse = previewBrowse,
                selection = ExplorerSelection(
                    folders = folders,
                    mediaSelecting = fileCount > 0,
                    fileCount = fileCount,
                    advanced = advanced,
                    onToggleFolder = { card -> folders = if (card.key in folders) folders - card.key else folders + card.key },
                    onClear = { folders = emptySet() },
                    canPinShortcut = true,
                ),
            )
        }
    }
}

private val previewBrowse = FolderBrowseActions(
    onTogglePin = {},
    onSetExcluded = { _, _ -> },
    onQueryChange = {},
    onCloseSearch = {},
    onCreateShortcut = {},
)

private fun previewMedia(index: Int) = TimelineMedia(
    key = MediaKey("external_primary", 300L + index),
    kind = MediaKind.Image,
    generationModified = 1,
    timelineSortMillis = 1_780_000_000_000L - index * 3_600_000L,
    width = 4000,
    height = 3000,
    durationMillis = 0L,
    isFavorite = false,
    displayName = "IMG_$index.jpg",
)

@Composable
private fun PickerFrame(
    purpose: PickPurpose,
    tree: FolderTree,
    start: FolderLocation,
    source: FolderLocation? = null,
    pendingFolder: String? = null,
    otherLocation: Boolean = false,
) {
    LightforgeTheme {
        var location by remember { mutableStateOf(start) }
        val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
        FolderPickerContent(
            purpose = purpose,
            sourceFolders = listOfNotNull(source),
            state = buildFolderExplorerUiState(tree, location, spaces),
            thumbnails = loader,
            onEnter = { location = it },
            onBack = { parentLocation(tree, location)?.let { location = it } },
            onNewFolder = {},
            onConfirm = {},
            pendingFolder = pendingFolder,
            onOtherLocation = if (otherLocation) ({}) else null,
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
    browse: FolderBrowseActions? = null,
    excludedPaths: Set<String> = emptySet(),
    withTree: Boolean = false,
    advanced: Boolean = false,
) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            var location by remember { mutableStateOf(start) }
            var expanded by remember { mutableStateOf(treeAncestorKeys(start)) }
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            FolderExplorerContent(
                state = state ?: buildFolderExplorerUiState(
                    tree, location, spaces, hiddenItems = hiddenItems, isRecordedHide = { it.relativePath == "Pictures/Private/" },
                    isExcluded = { _, _, path -> path in excludedPaths },
                ),
                thumbnails = loader,
                onEnter = {
                    location = it
                    expanded = expanded + treeAncestorKeys(it)
                },
                onOpenFiles = {},
                actions = actions,
                revokedWithHidden = revoked,
                browse = browse,
                selection = ExplorerSelection(advanced = advanced),
                tree = if (withTree) {
                    FolderTreeUi(
                        rows = buildFolderTreeRows(tree, location, expanded, spaces),
                        onToggle = { row -> expanded = if (row.expanded) expanded - row.key else expanded + row.key },
                    )
                } else {
                    null
                },
            )
        }
    }
}

private val previewDragBrowse = FolderBrowseActions(
    onTogglePin = {},
    onSetExcluded = { _, _ -> },
    onQueryChange = {},
    onCloseSearch = {},
    onDrop = { _, _ -> },
)

private val previewActions = FolderExplorerActions(
    onHide = {},
    onUnhide = {},
    onRename = { _, _ -> },
    onDelete = { _, _ -> },
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
