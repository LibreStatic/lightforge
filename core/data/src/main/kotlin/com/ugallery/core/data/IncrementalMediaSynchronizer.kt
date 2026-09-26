package com.ugallery.core.data

import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.MediaStoreCheckpointEntity
import com.ugallery.core.mediastore.MediaStoreDeltaSource
import com.ugallery.core.mediastore.VolumeGeneration
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

interface IncrementalMediaIndexStore : MediaIndexStore {
    suspend fun deleteMedia(volumeName: String, id: Long): Int

    /** Upserts rows without a checkpoint; an active scan token is resolved in the same transaction. */
    suspend fun upsertHintedMedia(items: List<MediaItemEntity>)
}

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
    data object PermissionLost : RowHintResult
}

/** Applies generation-bounded deltas without ever materializing the library or its IDs. */
class IncrementalMediaSynchronizer(
    private val source: MediaStoreDeltaSource,
    private val store: IncrementalMediaIndexStore,
    private val currentAccess: () -> LibraryAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 256,
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
        val record = try {
            source.readOne(key)
        } catch (_: SecurityException) {
            return@withContext RowHintResult.PermissionLost
        }
        if (record == null) {
            // With partial permissions, an absent row can mean a revoked selection, not deletion.
            val access = currentAccess()
            if (access.images != GrantLevel.Full || access.videos != GrantLevel.Full)
                return@withContext RowHintResult.PermissionLost
            store.deleteMedia(key.volumeName, key.mediaStoreId)
            RowHintResult.Deleted
        } else {
            store.checkpoint(key.volumeName) ?: return@withContext RowHintResult.PermissionLost
            // Hints run outside the refresh lock: writing back a checkpoint read here could roll
            // back a scan or delta sync that finished meanwhile, so only the row is written.
            store.upsertHintedMedia(listOf(record.toEntity(0)))
            RowHintResult.Upserted
        }
    }
}

private fun LibraryAccess.canReadAny(): Boolean =
    images != GrantLevel.None || videos != GrantLevel.None
