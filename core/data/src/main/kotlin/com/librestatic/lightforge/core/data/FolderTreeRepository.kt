package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.FolderSummaryRow
import com.librestatic.lightforge.core.database.LibraryDao
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder hierarchy derived live from `media_items`. Nothing is persisted: generation deltas carry no
 * tombstones, so any incrementally maintained tree would drift from the real library.
 */
class FolderTreeRepository(
    private val dao: LibraryDao,
    private val debounceMillis: Long = 300,
) {
    fun observeFolderTree(): Flow<FolderTree> = observeFolderTree(dao.folderSummaries(), debounceMillis)

    /** The keys among [keys] that the library lists, so the explorer does not count an on-disk row twice. */
    suspend fun libraryKeysAmong(keys: Collection<com.librestatic.lightforge.core.model.MediaKey>) =
        dao.libraryKeysAmong(keys)
}

internal fun observeFolderTree(rows: Flow<List<FolderSummaryRow>>, debounceMillis: Long): Flow<FolderTree> = channelFlow {
    var first = true
    var pending: Job? = null
    rows.collect { latest ->
        if (first) {
            // The first state is built inside the collector, so a later upstream emission can never
            // cancel it; the collector waits for it and only then sees the next rows.
            first = false
            send(withContext(Dispatchers.Default) { buildFolderTree(latest) })
        } else {
            // Scans rewrite the table in bursts; only the last state of a burst is worth building.
            pending?.cancel()
            pending = launch {
                delay(debounceMillis)
                send(withContext(Dispatchers.Default) { buildFolderTree(latest) })
            }
        }
    }
}.conflate().distinctUntilChanged()

internal fun buildFolderTree(rows: List<FolderSummaryRow>): FolderTree {
    // A folder whose rows are all inaccessible is hidden or emptied, so it drops out. Only a volume
    // that has no accessible row at all is unmounted: its folders stay, flagged unavailable.
    val volumesWithAccessibleRows = rows.filter { it.accessibleCount > 0 }.mapTo(HashSet()) { it.volumeName }
    val visibleRows = rows.filter { it.accessibleCount > 0 || it.volumeName !in volumesWithAccessibleRows }
    val forest: List<FolderNode<FolderStats>> = buildFolderForest(
        items = visibleRows,
        volumeOf = FolderSummaryRow::volumeName,
        relativePathOf = FolderSummaryRow::relativePath,
        fallbackKeyOf = { it.bucketId.toString() },
        fallbackNameOf = { it.displayName ?: it.bucketId?.toString().orEmpty() },
        nameOrder = NaturalNameOrder,
        combine = FolderSummaryRow::mergedWith,
    ).map { it.mapStats() }
    return FolderTree.from(forest)
}

private fun FolderNode<FolderSummaryRow>.mapStats(): FolderNode<FolderStats> = FolderNode(
    volumeName = volumeName,
    relativePath = relativePath,
    name = name,
    direct = direct?.let {
        FolderStats(
            bucketId = it.bucketId,
            directCount = it.directCount,
            imageCount = it.imageCount,
            videoCount = it.videoCount,
            totalBytes = it.totalBytes,
            minSortMillis = it.minSortMillis,
            maxSortMillis = it.maxSortMillis,
            coverMediaStoreId = it.coverMediaStoreId,
            isAvailable = it.isAvailable,
        )
    },
    children = children.map { it.mapStats() },
)

/** Two raw groups that normalise to one path (for example "A\\B" and "A/B/") are one folder. */
private fun FolderSummaryRow.mergedWith(other: FolderSummaryRow): FolderSummaryRow {
    val otherIsNewer = other.accessibleCount > 0 &&
        (accessibleCount == 0L || other.maxSortMillis > maxSortMillis)
    return copy(
        bucketId = bucketId ?: other.bucketId,
        displayName = displayName ?: other.displayName,
        directCount = directCount + other.directCount,
        imageCount = imageCount + other.imageCount,
        videoCount = videoCount + other.videoCount,
        totalBytes = totalBytes + other.totalBytes,
        minSortMillis = when {
            accessibleCount == 0L -> other.minSortMillis
            other.accessibleCount == 0L -> minSortMillis
            else -> minOf(minSortMillis, other.minSortMillis)
        },
        maxSortMillis = maxOf(maxSortMillis, other.maxSortMillis),
        coverMediaStoreId = if (otherIsNewer || coverMediaStoreId == null) {
            other.coverMediaStoreId ?: coverMediaStoreId
        } else {
            coverMediaStoreId
        },
        isAvailable = isAvailable || other.isAvailable,
        accessibleCount = accessibleCount + other.accessibleCount,
    )
}
