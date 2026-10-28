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

    /** Ids of every accessible (not hidden) row of the volume. */
    suspend fun accessibleMediaIds(volumeName: String): List<Long>

    /** Hides the rows with these ids (isAccessible=0), keeping everything saved about them. */
    suspend fun markMediaIdsInaccessible(volumeName: String, ids: List<Long>): Int
}

/** The MediaStore folder and file name of an indexed row. */
data class MediaLocation(val relativePath: String?, val displayName: String?)

sealed interface IncrementalSyncResult {
    data object NoChange : IncrementalSyncResult
    data object NeedsInitialScan : IncrementalSyncResult
    /** The delta cannot be trusted (provider version changed, generation went back) or the sweep found too many unindexed rows. */
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

/**
 * Applies generation-bounded deltas. Deltas never materialize records beyond a page; the optional
 * cold-start absence sweep only holds the volume's ids (a few bytes each), never its records.
 */
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

        // Generation deltas carry changed rows, not tombstones, so removals made while this process was
        // absent are invisible to them. With [reconcileUnobservedChanges] the delta is followed by an
        // id-only absence sweep (cheap: no records, no file opens) instead of a full volume scan.
        suspend fun finish(changed: Long, generationMoved: Boolean): IncrementalSyncResult {
            if (!reconcileUnobservedChanges) return IncrementalSyncResult.Complete(changed)
            return when (val swept = sweepAbsences(volume.volumeName)) {
                is SweepOutcome.Done -> {
                    val total = changed + swept.changed
                    if (total == 0L && !generationMoved) IncrementalSyncResult.NoChange
                    else IncrementalSyncResult.Complete(total)
                }
                SweepOutcome.NeedsFullScan -> IncrementalSyncResult.NeedsFullVolumeReconciliation
                SweepOutcome.PermissionLost -> IncrementalSyncResult.PausedPermission(changed)
            }
        }

        val target = checkpoint.deltaTargetGeneration ?: volume.generation
        if (checkpoint.deltaTargetGeneration == null) {
            if (target == checkpoint.generation) {
                return@withContext if (reconcileUnobservedChanges) finish(0, generationMoved = false)
                else IncrementalSyncResult.NoChange
            }
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
                return@withContext finish(changed, generationMoved = true)
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

    private sealed interface SweepOutcome {
        data class Done(val changed: Long) : SweepOutcome
        data object NeedsFullScan : SweepOutcome
        data object PermissionLost : SweepOutcome
    }

    /**
     * Compares the ids MediaStore lists for the volume with the accessible indexed ids. Indexed ids it no
     * longer lists are hidden (not deleted, like the end of a full scan: absence can also mean a revoked
     * selection); listed ids the index lacks are read and upserted, unless there are so many that a full
     * scan is the better tool. Runs inside the caller's refresh lock; it must not take [refreshMutex].
     */
    private suspend fun sweepAbsences(volumeName: String): SweepOutcome {
        // Indexed ids first: a row a concurrent hint adds meanwhile is then never mistaken for a ghost.
        val indexed = store.accessibleMediaIds(volumeName).toLongArray().also { it.sort() }
        var current = LongArray(maxOf(indexed.size, 1_024))
        var count = 0
        var afterId = -1L
        while (true) {
            coroutineContext.ensureActive()
            val ids = try {
                source.readIdsOnly(volumeName, afterId, SweepPageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return SweepOutcome.PermissionLost
            }
            if (ids.isEmpty()) break
            check(ids.last() > afterId) { "MediaStore id page did not advance" }
            afterId = ids.last()
            if (count + ids.size > current.size) current = current.copyOf(maxOf(current.size * 2, count + ids.size))
            ids.copyInto(current, count)
            count += ids.size
        }
        // Permission may have been revoked mid-read: absence would then mean "not shown to us".
        if (!currentAccess().canReadAny()) return SweepOutcome.PermissionLost

        // Both arrays are ascending: one merge pass yields the two differences.
        val gone = ArrayList<Long>()
        val unindexed = ArrayList<Long>()
        var i = 0
        var j = 0
        while (i < indexed.size || j < count) {
            when {
                j >= count -> gone += indexed[i++]
                i >= indexed.size -> unindexed += current[j++]
                indexed[i] == current[j] -> { i++; j++ }
                indexed[i] < current[j] -> gone += indexed[i++]
                else -> unindexed += current[j++]
            }
        }
        if (unindexed.size > SweepReadLimit) return SweepOutcome.NeedsFullScan

        val records = ArrayList<MediaItemEntity>(unindexed.size)
        for (id in unindexed) {
            coroutineContext.ensureActive()
            val row = try {
                source.readRow(MediaKey(volumeName, id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return SweepOutcome.PermissionLost
            }
            if (row is MediaStoreRow.Present) records += row.record.toEntity(0)
        }
        if (!currentAccess().canReadAny()) return SweepOutcome.PermissionLost

        gone.chunked(SweepReadLimit).forEach { store.markMediaIdsInaccessible(volumeName, it) }
        // Token 0 keeps a paused full scan's visibility rules (see LibraryDao.upsertMedia).
        if (records.isNotEmpty()) store.upsertHintedMedia(records)
        return SweepOutcome.Done((gone.size + records.size).toLong())
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

private const val SweepPageSize = 5_000
private const val SweepReadLimit = 500

private fun LibraryAccess.canReadAny(): Boolean =
    images != GrantLevel.None || videos != GrantLevel.None
