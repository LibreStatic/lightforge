package com.ugallery.core.database

import androidx.room.Entity
import androidx.room.Index

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

data class TimelineKeyset(
    val timelineSortMillis: Long,
    val mediaStoreId: Long,
    val volumeName: String,
)
