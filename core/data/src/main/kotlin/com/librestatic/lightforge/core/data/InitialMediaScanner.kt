package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.database.LibraryDao
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.MediaStoreCheckpointEntity
import com.librestatic.lightforge.core.mediastore.MediaStorePageSource
import com.librestatic.lightforge.core.mediastore.AnimatedImageSniffer
import com.librestatic.lightforge.core.mediastore.MediaStoreRecord
import com.librestatic.lightforge.core.mediastore.needsFileProbe
import com.librestatic.lightforge.core.mediastore.VolumeGeneration
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class ScanState { Running, PausedPermission, Complete }

data class ScanProgress(
    val volumeName: String,
    val indexedItems: Long,
    val lastScannedId: Long,
)

sealed interface InitialScanResult {
    data class Complete(val indexedItems: Long) : InitialScanResult
    data class PausedPermission(val indexedItems: Long) : InitialScanResult
}

interface MediaIndexStore {
    suspend fun checkpoint(volumeName: String): MediaStoreCheckpointEntity?
    suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity)
    suspend fun commitMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    )
    suspend fun reconcileMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    )
    suspend fun upsertCheckpoint(checkpoint: MediaStoreCheckpointEntity)
    suspend fun completeScan(checkpoint: MediaStoreCheckpointEntity, scanId: Long)

    /** Queues the rows of [volumeName] whose files may give more than they did (an index-format upgrade). */
    suspend fun markProbeEligibleRowsPending(volumeName: String, sniffedMimeTypes: List<String>): Int = 0
}

class RoomMediaIndexStore(private val dao: LibraryDao) : IncrementalMediaIndexStore {
    override suspend fun checkpoint(volumeName: String) = dao.checkpoint(volumeName)
    override suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity) =
        dao.resetVolumeForScan(checkpoint)
    override suspend fun commitMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    ) = dao.commitMediaPage(items, checkpoint)
    override suspend fun reconcileMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    ) = dao.reconcileMediaPage(items, checkpoint)
    override suspend fun upsertCheckpoint(checkpoint: MediaStoreCheckpointEntity) =
        dao.upsertCheckpoint(checkpoint)
    override suspend fun completeScan(checkpoint: MediaStoreCheckpointEntity, scanId: Long) =
        dao.completeVolumeScan(checkpoint, scanId)
    override suspend fun markProbeEligibleRowsPending(volumeName: String, sniffedMimeTypes: List<String>): Int =
        dao.markProbeEligibleRowsPending(volumeName, sniffedMimeTypes)
    override suspend fun accessibleMediaIds(volumeName: String): List<Long> =
        dao.accessibleIdsUnderPrefix(volumeName, "")
    override suspend fun markMediaIdsInaccessible(volumeName: String, ids: List<Long>): Int =
        dao.markMediaIdsInaccessible(volumeName, ids)
    override suspend fun deleteMedia(volumeName: String, id: Long): Int =
        dao.deleteMedia(volumeName, id)
    override suspend fun upsertHintedMedia(items: List<MediaItemEntity>) = dao.upsertMedia(items)
    override suspend fun markMediaInaccessible(volumeName: String, id: Long): Int =
        dao.markMediaInaccessible(volumeName, id)
    override suspend fun hideFolderRowsNotIn(
        volumeName: String,
        relativePathPrefix: String,
        seenIds: Set<Long>,
    ): Int = dao.hideFolderRowsNotIn(volumeName, relativePathPrefix, seenIds)
    override suspend fun mediaLocation(volumeName: String, id: Long): MediaLocation? =
        dao.media(volumeName, id)?.let { MediaLocation(it.relativePath, it.displayName) }
}

/** Performs bounded, resumable reads; each page and its checkpoint are one short transaction. */
class InitialMediaScanner(
    private val pages: MediaStorePageSource,
    private val store: MediaIndexStore,
    private val currentAccess: () -> LibraryAccess,
    private val scanId: () -> Long = System::currentTimeMillis,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 256,
) {
    init { require(pageSize in 1..1_000) }

    suspend fun scan(
        volume: VolumeGeneration,
        onProgress: (ScanProgress) -> Unit = {},
    ): InitialScanResult = withContext(Dispatchers.IO) {
        var checkpoint = prepareCheckpoint(volume)
        var indexed = 0L

        while (true) {
            coroutineContext.ensureActive()
            if (!currentAccess().canReadAnyMedia()) {
                store.upsertCheckpoint(checkpoint.copy(scanState = ScanState.PausedPermission.name))
                return@withContext InitialScanResult.PausedPermission(indexed)
            }

            val page = try {
                pages.readIdPage(volume.volumeName, checkpoint.lastScannedId, pageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                store.upsertCheckpoint(checkpoint.copy(scanState = ScanState.PausedPermission.name))
                return@withContext InitialScanResult.PausedPermission(indexed)
            }
            coroutineContext.ensureActive()
            if (!currentAccess().canReadAnyMedia()) {
                store.upsertCheckpoint(checkpoint.copy(scanState = ScanState.PausedPermission.name))
                return@withContext InitialScanResult.PausedPermission(indexed)
            }

            if (page.records.isEmpty()) {
                val completedScanId = requireNotNull(checkpoint.activeScanId)
                checkpoint = checkpoint.copy(
                    scanState = ScanState.Complete.name,
                    activeScanId = null,
                    lastSuccessfulSyncMillis = nowMillis(),
                )
                // Mark-and-sweep keeps unchanged rows (and their ML foreign-key children)
                // throughout reconciliation, then hides unavailable files without erasing
                // decisions when a partial media permission hides a provider row.
                store.completeScan(checkpoint, completedScanId)
                return@withContext InitialScanResult.Complete(indexed)
            }

            val nextId = requireNotNull(page.nextAfterId)
            check(nextId > checkpoint.lastScannedId) { "MediaStore page did not advance" }
            val items = page.records.map { it.toEntity(checkpoint.activeScanId!!) }
            checkpoint = checkpoint.copy(lastScannedId = nextId)
            store.reconcileMediaPage(items, checkpoint)
            indexed += items.size
            onProgress(ScanProgress(volume.volumeName, indexed, nextId))
        }
        @Suppress("UNREACHABLE_CODE")
        error("Scan loop terminated unexpectedly")
    }

    private suspend fun prepareCheckpoint(volume: VolumeGeneration): MediaStoreCheckpointEntity {
        val existing = store.checkpoint(volume.volumeName)
        val canResume = existing != null &&
            existing.providerVersion == volume.providerVersion &&
            existing.activeScanId != null &&
            existing.scanState != ScanState.Complete.name
        if (canResume) return existing!!

        val fresh = MediaStoreCheckpointEntity(
            volumeName = volume.volumeName,
            providerVersion = volume.providerVersion,
            generation = volume.generation,
            lastScannedId = -1,
            activeScanId = scanId(),
            scanState = ScanState.Running.name,
            lastSuccessfulSyncMillis = null,
        )
        // Pages are indexed without opening files. An index-format upgrade changes providerVersion (the
        // "+lfN" suffix) precisely because the files now give more, so the rows already indexed are queued
        // for the background pass; unchanged rows are not rewritten by this scan and would never get it. Done before
        // the new checkpoint is stored, so a crash in between repeats it instead of skipping it.
        if (existing != null && existing.providerVersion != volume.providerVersion) {
            store.markProbeEligibleRowsPending(volume.volumeName, AnimatedImageSniffer.SniffedMimeTypes.toList())
        }
        store.resetVolumeForScan(fresh)
        return fresh
    }
}

private fun LibraryAccess.canReadAnyMedia(): Boolean =
    images != GrantLevel.None || videos != GrantLevel.None

internal fun MediaStoreRecord.toEntity(scanId: Long): MediaItemEntity = MediaItemEntity(
    volumeName = key.volumeName,
    mediaStoreId = key.mediaStoreId,
    mediaType = when (kind) { MediaKind.Image -> 1; MediaKind.Video -> 3 },
    mimeType = mimeType,
    displayName = displayName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMillis = durationMillis,
    orientationDegrees = orientationDegrees,
    dateTakenMillis = dateTakenMillis,
    dateAddedSeconds = dateAddedSeconds,
    dateModifiedSeconds = dateModifiedSeconds,
    timelineSortMillis = dateTakenMillis ?: dateAddedSeconds * 1_000,
    generationAdded = generationAdded,
    generationModified = generationModified,
    bucketId = bucketId,
    bucketDisplayName = bucketDisplayName,
    relativePath = relativePath,
    isFavorite = isFavorite,
    isTrashed = isTrashed,
    isAccessible = true,
    lastSeenScanId = scanId,
    dateExpiresSeconds = dateExpiresSeconds,
    isAnimated = isAnimated,
    // Page reads are raw; a single-row read already opened the file.
    needsProbe = !probed && needsFileProbe(),
)
