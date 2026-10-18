package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.FolderSummaryRow
import com.librestatic.lightforge.core.database.LibraryDao
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
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
}

internal fun observeFolderTree(rows: Flow<List<FolderSummaryRow>>, debounceMillis: Long): Flow<FolderTree> = flow {
    var first = true
    emitAll(
        rows.transformLatest { latest ->
            // Scans rewrite the table in bursts; only the last state of a burst is worth building.
            if (!first) delay(debounceMillis)
            first = false
            emit(withContext(Dispatchers.Default) { buildFolderTree(latest) })
        },
    )
}.conflate().distinctUntilChanged()

internal fun buildFolderTree(rows: List<FolderSummaryRow>): FolderTree {
    val forest: List<FolderNode<FolderStats>> = buildFolderForest(
        items = rows,
        volumeOf = FolderSummaryRow::volumeName,
        relativePathOf = FolderSummaryRow::relativePath,
        fallbackKeyOf = { it.bucketId.toString() },
        fallbackNameOf = { it.displayName ?: it.bucketId?.toString().orEmpty() },
        nameOrder = NaturalNameOrder,
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
