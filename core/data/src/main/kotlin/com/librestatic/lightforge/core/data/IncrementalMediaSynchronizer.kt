package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MediaStoreCheckpointEntity
import com.librestatic.lightforge.core.mediastore.MediaStoreDeltaSource
import com.librestatic.lightforge.core.mediastore.MediaStoreRow
import com.librestatic.lightforge.core.mediastore.VolumeGeneration
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface IncrementalMediaIndexStore : MediaIndexStore {
    suspend fun deleteMedia(volumeName: String, id: Long): Int

    /** Upserts rows without a checkpoint; an active scan token is resolved in the same transaction. */
    suspend fun upsertHintedMedia(items: List<MediaItemEntity>)

    /** Hides one row (isAccessible=0) while keeping its identity and everything saved about it. */
    suspend fun markMediaInaccessible(volumeName: String, id: Long): Int

    /** Hides the accessible rows under [relativePathPrefix] whose id is not in [seenIds]. */
    suspend fun hideFolderRowsNotIn(volumeName: String, relativePathPrefix: String, seenIds: Set<Long>): Int

    /** Where the indexed row says its file lives, or null when the row is unknown. */
    suspend fun mediaLocation(volumeName: String, id: Long): MediaLocation? = null
}

/** The MediaStore folder and file name of an indexed row. */
data class MediaLocation(val relativePath: String?, val displayName: String?)

sealed interface IncrementalSyncResult {
    data object NoChange : IncrementalSyncResult
    data object NeedsInitialScan : IncrementalSyncResult
    data object NeedsFullVolumeReconciliation : IncrementalSyncResult
    data class Complete(val changedItems: Long) : IncrementalSyncResult
    data class PausedPermission(val changedItems: Long) : IncrementalSyncResult
}

sealed interface RowHintResult {
    data object Upserted : RowHintResult
    data object Deleted : RowHintResult

    /** The row still exists but MediaStore stopped treating it as media; it was hidden, not deleted. */
    data object Hidden : RowHintResult
    data object PermissionLost : RowHintResult
}

sealed interface FolderReconcileResult {
    data class Complete(val upserted: Int, val hidden: Int) : FolderReconcileResult
    data object PermissionLost : FolderReconcileResult
}

/** Applies generation-bounded deltas without ever materializing the library or its IDs. */
class IncrementalMediaSynchronizer(
    private val source: MediaStoreDeltaSource,
    private val store: IncrementalMediaIndexStore,
    private val currentAccess: () -> LibraryAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 256,
    /** Shared with the library refresh so a folder reconcile never interleaves with a scan's sweep. */
    private val refreshMutex: Mutex = Mutex(),
    /**
     * Second probe for a row MediaStore no longer shows us: true when the file is still on disk. Without
     * All files access a `.nomedia` made by another app turns a row into one owned by someone else, which
     * reads as absent; deleting it would drop its favourites and albums, so such a row is hidden instead.
     */
    private val fileStillExists: (volumeName: String, relativePath: String?, displayName: String?) -> Boolean =
        { _, _, _ -> false },
) {
    init { require(pageSize in 1..1_000) }

    suspend fun sync(
        volume: VolumeGeneration,
        reconcileUnobservedChanges: Boolean = false,
    ): IncrementalSyncResult = withContext(Dispatchers.IO) {
        var checkpoint = store.checkpoint(volume.volumeName)
            ?: return@withContext IncrementalSyncResult.NeedsInitialScan
        if (checkpoint.scanState != ScanState.Complete.name) {
            return@withContext IncrementalSyncResult.NeedsInitialScan
        }
        if (checkpoint.providerVersion != volume.providerVersion || volume.generation < checkpoint.generation) {
            return@withContext IncrementalSyncResult.NeedsFullVolumeReconciliation
        }
        if (!currentAccess().canReadAny()) return@withContext IncrementalSyncResult.PausedPermission(0)

        // Generation deltas contain changed rows, not tombstones. A newly started observer
        // may have missed removals while this process was absent; unchanged volumes stay cheap.
        if (reconcileUnobservedChanges &&
            (volume.generation != checkpoint.generation || checkpoint.deltaTargetGeneration != null)
        ) return@withContext IncrementalSyncResult.NeedsFullVolumeReconciliation

        val target = checkpoint.deltaTargetGeneration ?: volume.generation
        if (checkpoint.deltaTargetGeneration == null) {
            if (target == checkpoint.generation) return@withContext IncrementalSyncResult.NoChange
            checkpoint = checkpoint.copy(
                deltaTargetGeneration = target,
                deltaGenerationCursor = checkpoint.generation,
                // Exclude rows already represented by the completed generation checkpoint.
                deltaMediaStoreIdCursor = Long.MAX_VALUE,
            )
            store.upsertCheckpoint(checkpoint)
        }

        var changed = 0L
        while (true) {
            coroutineContext.ensureActive()
            if (!currentAccess().canReadAny()) {
                return@withContext IncrementalSyncResult.PausedPermission(changed)
            }
            val page = try {
                source.readGenerationPage(
                    volume.volumeName,
                    checkpoint.deltaGenerationCursor,
                    checkpoint.deltaMediaStoreIdCursor,
                    target,
                    pageSize,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return@withContext IncrementalSyncResult.PausedPermission(changed)
            }
            coroutineContext.ensureActive()
            if (!currentAccess().canReadAny()) {
                return@withContext IncrementalSyncResult.PausedPermission(changed)
            }

            if (page.records.isEmpty()) {
                store.upsertCheckpoint(
                    checkpoint.copy(
                        generation = target,
                        deltaTargetGeneration = null,
                        deltaGenerationCursor = 0,
                        deltaMediaStoreIdCursor = -1,
                        lastSuccessfulSyncMillis = nowMillis(),
                    ),
                )
                return@withContext IncrementalSyncResult.Complete(changed)
            }

            val nextGeneration = requireNotNull(page.nextGeneration)
            val nextId = requireNotNull(page.nextMediaStoreId)
            check(
                nextGeneration > checkpoint.deltaGenerationCursor ||
                    nextGeneration == checkpoint.deltaGenerationCursor &&
                    nextId > checkpoint.deltaMediaStoreIdCursor,
            ) { "MediaStore generation page did not advance" }
            checkpoint = checkpoint.copy(
                deltaGenerationCursor = nextGeneration,
                deltaMediaStoreIdCursor = nextId,
            )
            val scanId = checkpoint.activeScanId ?: 0
            store.commitMediaPage(page.records.map { it.toEntity(scanId) }, checkpoint)
            changed += page.records.size
        }
        @Suppress("UNREACHABLE_CODE")
        error("Sync loop terminated unexpectedly")
    }

    /** Uses a row-specific observer URI to distinguish a deletion from a changed row. */
    suspend fun applyRowHint(key: MediaKey): RowHintResult = withContext(Dispatchers.IO) {
        if (!currentAccess().canReadAny()) return@withContext RowHintResult.PermissionLost
        val row = try {
            source.readRow(key)
        } catch (_: SecurityException) {
            return@withContext RowHintResult.PermissionLost
        }
        when (row) {
            MediaStoreRow.Absent -> {
                // With partial permissions, an absent row can mean a revoked selection, not deletion.
                val access = currentAccess()
                if (access.images != GrantLevel.Full || access.videos != GrantLevel.Full)
                    return@withContext RowHintResult.PermissionLost
                val location = store.mediaLocation(key.volumeName, key.mediaStoreId)
                if (location != null && fileStillExists(key.volumeName, location.relativePath, location.displayName)) {
                    store.markMediaInaccessible(key.volumeName, key.mediaStoreId)
                    return@withContext RowHintResult.Hidden
                }
                store.deleteMedia(key.volumeName, key.mediaStoreId)
                RowHintResult.Deleted
            }
            // The file is still there (a .nomedia folder, by this app or another one). Hiding keeps
            // favourites, edits, albums and every other saved decision for when it comes back.
            MediaStoreRow.Hidden -> {
                store.markMediaInaccessible(key.volumeName, key.mediaStoreId)
                RowHintResult.Hidden
            }
            is MediaStoreRow.Present -> {
                store.checkpoint(key.volumeName) ?: return@withContext RowHintResult.PermissionLost
                // Hints run outside the refresh lock: writing back a checkpoint read here could roll
                // back a scan or delta sync that finished meanwhile, so only the row is written.
                store.upsertHintedMedia(listOf(row.record.toEntity(0)))
                RowHintResult.Upserted
            }
        }
    }

    /**
     * Re-reads one folder subtree from MediaStore: returned rows are upserted, accessible rows under
     * [relativePathPrefix] that it did not return (moved, deleted or hidden) become inaccessible.
     * Runs under the refresh lock, so a scan's mark-and-sweep cannot interleave.
     */
    suspend fun reconcileFolder(volumeName: String, relativePathPrefix: String): FolderReconcileResult =
        withContext(Dispatchers.IO) {
            refreshMutex.withLock {
                if (!currentAccess().canReadAny()) return@withLock FolderReconcileResult.PermissionLost
                val seen = HashSet<Long>()
                var afterId = -1L
                while (true) {
                    coroutineContext.ensureActive()
                    val page = try {
                        source.readFolderPage(volumeName, relativePathPrefix, afterId, pageSize)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        return@withLock FolderReconcileResult.PermissionLost
                    }
                    if (page.records.isEmpty()) break
                    val nextId = requireNotNull(page.nextAfterId)
                    check(nextId > afterId) { "MediaStore folder page did not advance" }
                    afterId = nextId
                    // Token 0 keeps a paused full scan's visibility rules (see LibraryDao.upsertMedia).
                    store.upsertHintedMedia(page.records.map { it.toEntity(0) })
                    page.records.forEach { seen += it.key.mediaStoreId }
                }
                // Permission may have been revoked mid-read: absence would then mean "not shown to us".
                if (!currentAccess().canReadAny()) return@withLock FolderReconcileResult.PermissionLost
                val hidden = store.hideFolderRowsNotIn(volumeName, relativePathPrefix, seen)
                FolderReconcileResult.Complete(seen.size, hidden)
            }
        }
}

private fun LibraryAccess.canReadAny(): Boolean =
    images != GrantLevel.None || videos != GrantLevel.None
