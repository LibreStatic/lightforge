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

sealed interface FolderAction {
    /** Navigate into a folder that has sub-folders. */
    data class Enter(val location: FolderLocation) : FolderAction

    /** Open the folder's own files in the album screen. */
    data class OpenFiles(val album: AlbumSummary) : FolderAction
}

data class FolderCardUi(
    val key: String,
    val name: String,
    val itemCount: Long,
    val sizeBytes: Long,
    val subfolderCount: Int,
    val cover: MediaKey?,
    val action: FolderAction,
)

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

    data class Volumes(val volumes: List<VolumeCardUi>) : FolderExplorerUiState

    /** [files] is the "Files in this folder" card, present when the folder holds items itself. */
    data class Folder(
        val crumbs: List<CrumbUi>,
        val files: FolderCardUi?,
        val folders: List<FolderCardUi>,
    ) : FolderExplorerUiState
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

fun buildFolderExplorerUiState(
    tree: FolderTree,
    location: FolderLocation,
    spaces: Map<String, VolumeSpace> = emptyMap(),
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
    return FolderExplorerUiState.Folder(
        crumbs = crumbs(tree, volumeName, path, spaces[volumeName]?.label),
        files = files,
        folders = node.children.map { it.compactSingleChildChains() }.map { card(tree, it) },
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

private fun filesCard(node: FolderNode<FolderStats>, stats: FolderStats) = FolderCardUi(
    key = "files",
    name = node.name,
    itemCount = stats.directCount,
    sizeBytes = stats.totalBytes,
    subfolderCount = node.children.size,
    cover = stats.coverMediaStoreId?.let { MediaKey(node.volumeName, it) },
    action = FolderAction.OpenFiles(album(node, stats)),
)

private fun card(tree: FolderTree, node: FolderNode<FolderStats>): FolderCardUi {
    val totals = tree.totals(node)
    val stats = node.direct
    // A folder without sub-folders has nothing to navigate into: it opens its own files.
    val action = if (node.children.isEmpty() && stats?.bucketId != null) {
        FolderAction.OpenFiles(album(node, stats))
    } else {
        FolderAction.Enter(FolderLocation(node.volumeName, node.relativePath))
    }
    return FolderCardUi(
        key = FolderTree.keyOf(node),
        name = node.name,
        itemCount = totals.totalCount,
        sizeBytes = totals.totalBytes,
        subfolderCount = node.children.size,
        cover = totals.cover(node.volumeName),
        action = action,
    )
}

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
