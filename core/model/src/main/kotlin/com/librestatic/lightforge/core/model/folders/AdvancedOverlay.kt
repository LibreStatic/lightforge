package com.librestatic.lightforge.core.model.folders

enum class HiddenMediaKind { Image, Video }

/**
 * Media of one kind hidden from the system gallery inside one folder (a `.nomedia` folder). The caller
 * aggregates the raw hidden rows into these groups; the model never sees file paths or rows.
 */
data class HiddenFolderGroup(
    val volumeName: String,
    val relativePath: String,
    val count: Long,
    val bytes: Long,
    val minSortMillis: Long,
    val maxSortMillis: Long,
    val coverMediaStoreId: Long?,
    val kind: HiddenMediaKind,
)

/**
 * A directory on disk the library lists no media for. [isEmpty] is true only when the directory holds nothing
 * (or just a lone `.nomedia`); a directory with other files or subfolders is false and is never shown as empty.
 */
data class EmptyDirectory(val volumeName: String, val relativePath: String, val isEmpty: Boolean = true)

/**
 * Adds hidden folders and empty directories to [tree]. A hidden group lands on the folder it names
 * (creating it and its missing parents), summing into an existing folder's stats and flagging it
 * [FolderStats.isHidden]. An empty directory becomes a zero-stats node flagged
 * [FolderStats.isEmptyDirectory] only when the directory is truly empty and the folder has no media; a disk-only
 * directory that holds other files stays a zero-stats node that is not flagged. Empty overlays return [tree].
 */
fun mergeAdvancedOverlay(
    tree: FolderTree,
    hiddenGroups: List<HiddenFolderGroup>,
    emptyDirs: List<EmptyDirectory>,
): FolderTree {
    if (hiddenGroups.isEmpty() && emptyDirs.isEmpty()) return tree

    val items = buildList {
        fun collect(node: FolderNode<FolderStats>) {
            node.direct?.let { add(OverlayItem(node.volumeName, node.relativePath, node.name, it)) }
            node.children.forEach(::collect)
        }
        tree.volumes.forEach { volume -> volume.children.forEach(::collect) }
        hiddenGroups.forEach { group ->
            add(
                OverlayItem(
                    group.volumeName,
                    group.relativePath,
                    group.relativePath.trim('/').substringAfterLast('/'),
                    FolderStats(
                        bucketId = null,
                        directCount = group.count,
                        imageCount = if (group.kind == HiddenMediaKind.Image) group.count else 0,
                        videoCount = if (group.kind == HiddenMediaKind.Video) group.count else 0,
                        totalBytes = group.bytes,
                        minSortMillis = group.minSortMillis,
                        maxSortMillis = group.maxSortMillis,
                        coverMediaStoreId = group.coverMediaStoreId,
                        isAvailable = true,
                        isHidden = true,
                    ),
                ),
            )
        }
        emptyDirs.forEach { dir ->
            add(
                OverlayItem(
                    dir.volumeName,
                    dir.relativePath,
                    dir.relativePath.trim('/').substringAfterLast('/'),
                    FolderStats(null, 0, 0, 0, 0, 0, 0, null, isAvailable = true, isEmptyDirectory = dir.isEmpty),
                ),
            )
        }
    }
    val forest = buildFolderForest(
        items = items,
        volumeOf = OverlayItem::volumeName,
        relativePathOf = OverlayItem::relativePath,
        fallbackKeyOf = { it.stats.bucketId?.toString() ?: it.name },
        fallbackNameOf = OverlayItem::name,
        nameOrder = NaturalNameOrder,
        combine = { existing, incoming -> existing.copy(stats = existing.stats.mergedWith(incoming.stats)) },
    ).map { node -> node.mapDirect { it.stats } }
    return FolderTree.from(forest)
}

private class OverlayItem(
    val volumeName: String,
    val relativePath: String?,
    val name: String,
    val stats: FolderStats,
) {
    fun copy(stats: FolderStats) = OverlayItem(volumeName, relativePath, name, stats)
}

private fun <T, R> FolderNode<T>.mapDirect(transform: (T) -> R): FolderNode<R> = FolderNode(
    volumeName = volumeName,
    relativePath = relativePath,
    name = name,
    direct = direct?.let(transform),
    children = children.map { it.mapDirect(transform) },
)

private fun FolderStats.mergedWith(other: FolderStats): FolderStats {
    val otherIsNewer = other.directCount > 0 && (directCount == 0L || other.maxSortMillis > maxSortMillis)
    return FolderStats(
        bucketId = bucketId ?: other.bucketId,
        directCount = directCount + other.directCount,
        imageCount = imageCount + other.imageCount,
        videoCount = videoCount + other.videoCount,
        totalBytes = totalBytes + other.totalBytes,
        minSortMillis = when {
            directCount == 0L -> other.minSortMillis
            other.directCount == 0L -> minSortMillis
            else -> minOf(minSortMillis, other.minSortMillis)
        },
        maxSortMillis = maxOf(maxSortMillis, other.maxSortMillis),
        coverMediaStoreId = if (otherIsNewer || coverMediaStoreId == null) {
            other.coverMediaStoreId ?: coverMediaStoreId
        } else {
            coverMediaStoreId
        },
        isAvailable = isAvailable || other.isAvailable,
        isHidden = isHidden || other.isHidden,
        isEmptyDirectory = isEmptyDirectory && other.isEmptyDirectory,
    )
}
