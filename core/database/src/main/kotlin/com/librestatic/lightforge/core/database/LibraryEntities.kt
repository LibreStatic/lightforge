package com.librestatic.lightforge.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_items",
    primaryKeys = ["volumeName", "mediaStoreId"],
    indices = [
        Index(
            name = "index_media_timeline_keyset",
            value = ["timelineSortMillis", "mediaStoreId", "volumeName"],
            orders = [Index.Order.DESC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(
            name = "index_media_volume_generation",
            value = ["volumeName", "generationModified"],
        ),
        Index(
            name = "index_media_volume_bucket",
            value = ["volumeName", "bucketId"],
        ),
        Index(
            name = "index_media_volume_relative_path",
            value = ["volumeName", "relativePath"],
        ),
        Index(
            name = "index_media_needs_probe",
            value = ["needsProbe", "timelineSortMillis", "mediaStoreId"],
        ),
        Index(
            name = "index_media_moment_scan",
            value = ["isAccessible", "isTrashed", "timelineSortMillis", "mediaStoreId", "volumeName"],
        ),
    ],
)
data class MediaItemEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val mediaType: Int,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val orientationDegrees: Int,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val timelineSortMillis: Long,
    val generationAdded: Long,
    val generationModified: Long,
    val bucketId: Long?,
    val bucketDisplayName: String?,
    val relativePath: String?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
    val isAccessible: Boolean,
    val lastSeenScanId: Long,
    val dateExpiresSeconds: Long? = null,
    /** Set at index time from the file header (GIF, animated WebP, APNG, AVIF/HEIF sequence); drives the Animated filter. */
    @ColumnInfo(defaultValue = "0") val isAnimated: Boolean = false,
    /**
     * Pages are indexed without opening files; a background pass reads the video size and image header of the
     * rows flagged here and clears the flag. Until then duration, size and [isAnimated] may be missing.
     */
    @ColumnInfo(defaultValue = "0") val needsProbe: Boolean = false,
)

data class KnownMediaGeneration(
    val mediaStoreId: Long,
    val generationModified: Long,
    val durationMillis: Long,
    val width: Int,
    val isAnimated: Boolean,
    val height: Int = 0,
    val needsProbe: Boolean = false,
)

/** A row waiting for [LibraryDao.applyProbedDetails]. */
data class PendingProbeRow(
    val volumeName: String,
    val mediaStoreId: Long,
    val mediaType: Int,
    val mimeType: String?,
    val generationModified: Long,
    val dateModifiedSeconds: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
)

/** The details a probe found for one row; [generationModified] guards against a row rewritten meanwhile. */
data class ProbeResultRow(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean,
)

@Entity(
    tableName = "archived_media",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["volumeName", "mediaStoreId"],
            childColumns = ["volumeName", "mediaStoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_archived_media_archivedAtMillis", value = ["archivedAtMillis"])],
)
data class ArchivedMediaEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val archivedAtMillis: Long,
)

@Entity(
    tableName = "activity_events",
    indices = [Index(name = "index_activity_events_occurredAtMillis", value = ["occurredAtMillis"])],
)
data class ActivityEventEntity(
    @PrimaryKey(autoGenerate = true) val eventId: Long = 0,
    val type: String,
    val occurredAtMillis: Long,
    val itemCount: Long,
    val detail: String? = null,
)

/**
 * Rows a full scan saw again without changing them. Kept outside media_items because every UPDATE of
 * media_items invalidates all observed timeline/folder queries; nothing in the UI reads this table.
 * Persistent so an interrupted scan resumes with its marks.
 */
@Entity(tableName = "media_scan_marks", primaryKeys = ["volumeName", "mediaStoreId"])
data class MediaScanMarkEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val scanId: Long,
)

@Entity(tableName = "media_store_checkpoints")
data class MediaStoreCheckpointEntity(
    @androidx.room.PrimaryKey val volumeName: String,
    val providerVersion: String,
    val generation: Long,
    val lastScannedId: Long,
    val activeScanId: Long?,
    val scanState: String,
    val lastSuccessfulSyncMillis: Long?,
    val deltaTargetGeneration: Long? = null,
    val deltaGenerationCursor: Long = 0,
    val deltaMediaStoreIdCursor: Long = -1,
)

@Entity(
    tableName = "album_aggregates",
    primaryKeys = ["volumeName", "bucketId"],
    indices = [Index(name = "index_album_latest", value = ["latestSortMillis"])],
)
data class AlbumAggregateEntity(
    val volumeName: String,
    val bucketId: Long,
    val displayName: String?,
    val itemCount: Long,
    val latestSortMillis: Long,
    val coverMediaStoreId: Long?,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "virtual_albums",
    indices = [Index(name = "index_virtual_album_name", value = ["normalizedName"], unique = true)],
)
data class VirtualAlbumEntity(
    @PrimaryKey(autoGenerate = true) val albumId: Long = 0,
    val name: String,
    val normalizedName: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val chosenCoverVolumeName: String? = null,
    val chosenCoverMediaStoreId: Long? = null,
)

@Entity(
    tableName = "virtual_album_media",
    primaryKeys = ["albumId", "volumeName", "mediaStoreId"],
    foreignKeys = [
        ForeignKey(
            entity = VirtualAlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_virtual_album_media_key", value = ["volumeName", "mediaStoreId"]),
        Index(name = "index_virtual_album_added", value = ["albumId", "addedAtMillis"]),
    ],
)
data class VirtualAlbumMediaEntity(
    val albumId: Long,
    val volumeName: String,
    val mediaStoreId: Long,
    val addedAtMillis: Long,
)

data class PhysicalAlbumRow(
    val volumeName: String,
    val bucketId: Long,
    val displayName: String?,
    val relativePath: String?,
    val itemCount: Long,
    val latestSortMillis: Long,
    val coverMediaStoreId: Long?,
    val isAvailable: Boolean,
)

/** One physical folder (exact volume + relative path) of the folder browser. */
data class FolderSummaryRow(
    val volumeName: String,
    val relativePath: String?,
    val bucketId: Long?,
    val displayName: String?,
    val directCount: Long,
    val imageCount: Long,
    val videoCount: Long,
    val totalBytes: Long,
    val minSortMillis: Long,
    val maxSortMillis: Long,
    val coverMediaStoreId: Long?,
    val isAvailable: Boolean,
    /** Accessible, non-trashed, non-archived rows; 0 when the folder is hidden or its volume is gone. */
    val accessibleCount: Long,
)

data class VirtualAlbumRow(
    val albumId: Long,
    val name: String,
    val itemCount: Long,
    val latestSortMillis: Long?,
    val coverVolumeName: String?,
    val coverMediaStoreId: Long?,
)

@Entity(
    tableName = "media_exif_cache",
    primaryKeys = ["volumeName", "mediaStoreId"],
)
data class MediaExifEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val orientation: Int,
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val make: String?,
    val model: String?,
    val lensModel: String?,
    val focalLength: String?,
    val aperture: String?,
    val exposureTime: String?,
    val iso: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val locationReadWithPermission: Boolean,
    val cachedAtMillis: Long,
)

data class TimelineKeyset(
    val timelineSortMillis: Long,
    val mediaStoreId: Long,
    val volumeName: String,
)
