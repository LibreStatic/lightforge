package com.librestatic.lightforge.feature.folders

import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTotals
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.breadcrumb
import com.librestatic.lightforge.core.model.folders.compactSingleChildChains

/**
 * Where the explorer is. A null [volumeName] is the start level: the volume list, or, with a single
 * volume, that volume's root. A null [relativePath] is a volume's root.
 */
data class FolderLocation(val volumeName: String? = null, val relativePath: String? = null) {
    companion object {
        val Start = FolderLocation()
    }
}

/** What the platform says about a volume; [label] is null for the primary volume (the UI localises it). */
data class VolumeSpace(val label: String?, val freeBytes: Long?, val totalBytes: Long?)

/** Whether the explorer browses folders or picks a destination folder for a move or copy. */
sealed interface ExplorerMode {
    data object Browse : ExplorerMode

    /**
     * Choose a folder: cards only navigate, files, menus and hidden badges are not shown.
     * [sourceFolder] is the folder being moved for [PickPurpose.MoveFolder].
     */
    data class PickDestination(val purpose: PickPurpose, val sourceFolder: FolderLocation? = null) : ExplorerMode
}

enum class PickPurpose { Move, Copy, MoveFolder }

/**
 * Whether [here] is a valid destination. Nothing is valid at the volume list, and a folder cannot be
 * moved into itself, into one of its descendants, into the parent it already has, or to another volume.
 */
fun ExplorerMode.PickDestination.canChoose(here: FolderLocation?): Boolean {
    val volume = here?.volumeName ?: return false
    if (purpose != PickPurpose.MoveFolder) return true
    val source = sourceFolder ?: return false
    if (source.volumeName != volume) return false // a folder is only moved within its volume
    val sourcePath = normalizedFolderPath(source.relativePath.orEmpty())
    if (sourcePath.isEmpty()) return false
    val herePath = normalizedFolderPath(here.relativePath.orEmpty())
    if (herePath.startsWith(sourcePath)) return false
    val parent = sourcePath.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    return herePath != parent
}

sealed interface FolderAction {
    /** Navigate into a folder that has sub-folders. */
    data class Enter(val location: FolderLocation) : FolderAction

    /** Open the folder's own files in the album screen. */
    data class OpenFiles(val album: AlbumSummary) : FolderAction
}

data class FolderCardUi(
    val key: String,
    val name: String,
    /** Items in the folder and everything below it. */
    val itemCount: Long,
    val sizeBytes: Long,
    val subfolderCount: Int,
    val cover: MediaKey?,
    val action: FolderAction,
    /** The folder this card stands for (the deepest one of a compacted chain). */
    val location: FolderLocation,
    /** Advanced mode only: the folder is excluded from the system gallery by a .nomedia. */
    val isHidden: Boolean = false,
    /** Hidden by this app, so the app can unhide it. */
    val isHiddenByApp: Boolean = false,
    /** Advanced mode only: a directory without any media. */
    val isEmptyDirectory: Boolean = false,
    /** Items stored directly in the folder. */
    val directCount: Long = 0,
    val minSortMillis: Long? = null,
    val maxSortMillis: Long? = null,
)

/** One hidden image or video, for the read-only grid of a hidden folder. */
data class HiddenItemUi(
    val volumeName: String,
    val relativePath: String,
    val mediaStoreId: Long,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateMillis: Long,
    val uri: String,
)

/** What the inline file grid of a folder shows. */
data class FolderMediaScope(val volumeName: String, val relativePath: String, val includeSubfolders: Boolean)

data class VolumeCardUi(
    val volumeName: String,
    val label: String?,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val itemCount: Long,
    val sizeBytes: Long,
    val cover: MediaKey?,
    val location: FolderLocation,
)

/** One breadcrumb segment. [title] is null for the volume segment, whose label the UI resolves. */
data class CrumbUi(
    val title: String?,
    val volumeName: String,
    val volumeLabel: String?,
    val location: FolderLocation,
    val isCurrent: Boolean,
)

sealed interface FolderExplorerUiState {
    data object Loading : FolderExplorerUiState

    data object Empty : FolderExplorerUiState

    /** The folder tree could not be read; the screen offers Retry. */
    data object Error : FolderExplorerUiState

    data class Volumes(val volumes: List<VolumeCardUi>) : FolderExplorerUiState

    /**
     * [files] describes the folder's own items and heads the inline grid ([media]). [hiddenItems] are the
     * hidden items of a hidden folder (advanced mode). [rootSpace] is set at the root of a single volume.
     */
    data class Folder(
        val crumbs: List<CrumbUi>,
        val files: FolderCardUi?,
        val folders: List<FolderCardUi>,
        val media: FolderMediaScope? = null,
        val hiddenItems: List<HiddenItemUi> = emptyList(),
        val rootSpace: VolumeSpace? = null,
    ) : FolderExplorerUiState {
        /** Nothing to show below the breadcrumb: a placeholder takes the whole viewport. */
        val isEmpty: Boolean get() = files == null && media == null && folders.isEmpty() && hiddenItems.isEmpty()
    }
}

/** A synthetic single-child folder is shown merged into its child ("A/B"), so it is never a stop. */
private fun FolderNode<FolderStats>.isPassThrough() = direct == null && children.size == 1

/** Clamps [location] to something that exists: a missing folder falls back to its nearest existing ancestor. */
fun resolveLocation(tree: FolderTree, location: FolderLocation): FolderLocation {
    val volumes = tree.volumes
    if (volumes.isEmpty()) return FolderLocation.Start
    val volume = location.volumeName?.let { name -> volumes.firstOrNull { it.volumeName == name } }
        ?: volumes.singleOrNull()
        ?: return FolderLocation.Start
    val path = location.relativePath ?: return FolderLocation(volume.volumeName, null)
    val existing = breadcrumb(path).lastOrNull { tree.find(volume.volumeName, it.relativePath) != null }
    return FolderLocation(volume.volumeName, existing?.relativePath)
}

/** The location Back leads to, or null when [location] is already the start level. */
fun parentLocation(tree: FolderTree, location: FolderLocation): FolderLocation? {
    val resolved = resolveLocation(tree, location)
    val volume = resolved.volumeName ?: return null
    val path = resolved.relativePath ?: return if (tree.volumes.size > 1) FolderLocation.Start else null
    for (crumb in breadcrumb(path).dropLast(1).asReversed()) {
        val node = tree.find(volume, crumb.relativePath) ?: continue
        if (!node.isPassThrough()) return FolderLocation(volume, crumb.relativePath)
    }
    return FolderLocation(volume, null)
}

/**
 * [isRecordedHide] tells whether this app hid the folder (advanced mode); [hiddenItems] are all hidden
 * rows, of which the ones stored in the current folder are shown; [includeSubfolders] widens the inline
 * file grid to the whole subtree.
 */
fun buildFolderExplorerUiState(
    tree: FolderTree,
    location: FolderLocation,
    spaces: Map<String, VolumeSpace> = emptyMap(),
    includeSubfolders: Boolean = false,
    hiddenItems: List<HiddenItemUi> = emptyList(),
    isRecordedHide: (FolderLocation) -> Boolean = { false },
): FolderExplorerUiState {
    if (tree.volumes.isEmpty()) return FolderExplorerUiState.Empty
    val resolved = resolveLocation(tree, location)
    val volumeName = resolved.volumeName
    if (volumeName == null) {
        return FolderExplorerUiState.Volumes(
            tree.volumes.map { root ->
                val totals = tree.totals(root)
                val space = spaces[root.volumeName]
                VolumeCardUi(
                    volumeName = root.volumeName,
                    label = space?.label,
                    freeBytes = space?.freeBytes,
                    totalBytes = space?.totalBytes,
                    itemCount = totals.totalCount,
                    sizeBytes = totals.totalBytes,
                    cover = totals.cover(root.volumeName),
                    location = FolderLocation(root.volumeName, null),
                )
            },
        )
    }
    val path = resolved.relativePath
    val node = if (path == null) tree.volumes.first { it.volumeName == volumeName } else tree.find(volumeName, path)!!
    val files = node.direct
        ?.takeIf { it.directCount > 0 && it.bucketId != null }
        ?.let { filesCard(node, it) }
    // The volume root has no folder path to query, so its files are never listed inline.
    val media = path?.takeIf { files != null || (includeSubfolders && tree.totals(node).totalCount > 0) }
        ?.let { FolderMediaScope(volumeName, it, includeSubfolders) }
    val here = path?.let(::normalizedFolderPath)
    return FolderExplorerUiState.Folder(
        crumbs = crumbs(tree, volumeName, path, spaces[volumeName]?.label),
        files = files,
        folders = node.children.map { it.compactSingleChildChains() }.map { card(tree, it, isRecordedHide) },
        media = media,
        hiddenItems = if (here == null) emptyList() else hiddenItems.filter {
            it.volumeName == volumeName && normalizedFolderPath(it.relativePath) == here
        },
        rootSpace = if (path == null && tree.volumes.size == 1) spaces[volumeName] else null,
    )
}

private fun FolderTotals.cover(volumeName: String) = coverMediaStoreId?.let { MediaKey(volumeName, it) }

private fun album(node: FolderNode<FolderStats>, stats: FolderStats) = AlbumSummary(
    key = AlbumKey.Physical(node.volumeName, requireNotNull(stats.bucketId)),
    name = node.name.substringAfterLast('/'),
    itemCount = stats.directCount,
    latestSortMillis = stats.maxSortMillis,
    cover = stats.coverMediaStoreId?.let { MediaKey(node.volumeName, it) },
    availability = if (stats.isAvailable) AlbumAvailability.Available else AlbumAvailability.VolumeUnavailable,
)

/** "A/B/" for any spelling of the same folder path, so MediaStore and disk paths compare equal. */
fun normalizedFolderPath(path: String): String =
    path.trim('/').takeIf { it.isNotEmpty() }?.let { "$it/" } ?: ""

private fun filesCard(node: FolderNode<FolderStats>, stats: FolderStats) = FolderCardUi(
    key = "files",
    name = node.name,
    itemCount = stats.directCount,
    sizeBytes = stats.totalBytes,
    subfolderCount = node.children.size,
    cover = stats.coverMediaStoreId?.let { MediaKey(node.volumeName, it) },
    action = FolderAction.OpenFiles(album(node, stats)),
    location = FolderLocation(node.volumeName, node.relativePath),
    directCount = stats.directCount,
    minSortMillis = stats.minSortMillis,
    maxSortMillis = stats.maxSortMillis,
)

private fun card(tree: FolderTree, node: FolderNode<FolderStats>, isRecordedHide: (FolderLocation) -> Boolean): FolderCardUi {
    val totals = tree.totals(node)
    val stats = node.direct
    val location = FolderLocation(node.volumeName, node.relativePath)
    // A folder without sub-folders has nothing to navigate into: it opens its own files.
    val action = if (node.children.isEmpty() && stats?.bucketId != null && !stats.isHidden) {
        FolderAction.OpenFiles(album(node, stats))
    } else {
        FolderAction.Enter(location)
    }
    // A folder this app hid is hidden even when its media sits only in sub-folders or it holds none.
    val recordedHide = isRecordedHide(location)
    val isHidden = stats?.isHidden == true || recordedHide
    return FolderCardUi(
        key = FolderTree.keyOf(node),
        name = node.name,
        itemCount = totals.totalCount,
        sizeBytes = totals.totalBytes,
        subfolderCount = node.children.size,
        cover = totals.cover(node.volumeName),
        action = action,
        location = location,
        isHidden = isHidden,
        isHiddenByApp = recordedHide,
        isEmptyDirectory = stats?.isEmptyDirectory == true && totals.totalCount == 0L,
        directCount = stats?.directCount ?: 0,
        minSortMillis = totals.minSortMillis,
        maxSortMillis = totals.maxSortMillis,
    )
}

/** Why a folder name is refused; the dialog maps each to a message. */
enum class FolderNameProblem { Blank, InvalidCharacters, Reserved, TooLong, AlreadyExists }

/**
 * Checks a new folder name against the rules every file system shares and [siblings], the names that
 * already exist next to it (compared ignoring case, since shared storage is case-insensitive).
 */
fun validateFolderName(name: String, siblings: Collection<String>): FolderNameProblem? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> FolderNameProblem.Blank
        trimmed == "." || trimmed == ".." -> FolderNameProblem.Reserved
        trimmed.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } -> FolderNameProblem.InvalidCharacters
        trimmed.toByteArray(Charsets.UTF_8).size > 255 -> FolderNameProblem.TooLong
        siblings.any { it.equals(trimmed, ignoreCase = true) } -> FolderNameProblem.AlreadyExists
        else -> null
    }
}

/** The folder's own name: the last path segment of [location], or null at a volume root. */
fun FolderLocation.leafName(): String? =
    relativePath?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

private fun crumbs(tree: FolderTree, volumeName: String, path: String?, volumeLabel: String?): List<CrumbUi> {
    val segments = breadcrumb(path)
    val result = mutableListOf(
        CrumbUi(null, volumeName, volumeLabel, FolderLocation(volumeName, null), isCurrent = segments.isEmpty()),
    )
    var pending: String? = null
    segments.forEachIndexed { index, segment ->
        pending = pending?.let { "$it/${segment.name}" } ?: segment.name
        val isLast = index == segments.lastIndex
        val node = tree.find(volumeName, segment.relativePath)
        // Segments that the card view merges ("Android/media") also merge into one crumb.
        if (isLast || node == null || !node.isPassThrough()) {
            result += CrumbUi(pending, volumeName, volumeLabel, FolderLocation(volumeName, segment.relativePath), isCurrent = isLast)
            pending = null
        }
    }
    return result
}
