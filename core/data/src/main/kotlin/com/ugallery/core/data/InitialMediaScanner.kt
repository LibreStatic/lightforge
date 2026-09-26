package com.ugallery.core.data

import com.ugallery.core.database.LibraryDao
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.MediaStoreCheckpointEntity
import com.ugallery.core.mediastore.MediaStorePageSource
import com.ugallery.core.mediastore.MediaStoreRecord
import com.ugallery.core.mediastore.VolumeGeneration
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.MediaKind
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
    override suspend fun deleteMedia(volumeName: String, id: Long): Int =
        dao.deleteMedia(volumeName, id)
    override suspend fun upsertHintedMedia(items: List<MediaItemEntity>) = dao.upsertMedia(items)
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
)
