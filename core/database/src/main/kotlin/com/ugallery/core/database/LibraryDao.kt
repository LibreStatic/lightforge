package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface LibraryDao {
    @Upsert
    suspend fun upsertMedia(items: List<MediaItemEntity>)

    @Upsert
    suspend fun upsertCheckpoint(checkpoint: MediaStoreCheckpointEntity)

    @Upsert
    suspend fun upsertAlbumAggregates(aggregates: List<AlbumAggregateEntity>)

    @Transaction
    suspend fun commitMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    ) {
        upsertMedia(items)
        upsertCheckpoint(checkpoint)
    }

    @Transaction
    suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity) {
        deleteVolumeIndex(checkpoint.volumeName)
        upsertCheckpoint(checkpoint)
    }

    @Query(
        """
        SELECT * FROM media_items
        WHERE isAccessible = 1 AND isTrashed = 0
        ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun firstTimelinePage(limit: Int): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE isAccessible = 1 AND isTrashed = 0 AND (
            timelineSortMillis < :afterSortMillis OR
            (timelineSortMillis = :afterSortMillis AND mediaStoreId < :afterMediaStoreId) OR
            (timelineSortMillis = :afterSortMillis AND mediaStoreId = :afterMediaStoreId
                AND volumeName < :afterVolumeName)
        )
        ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun timelinePageAfter(
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        afterVolumeName: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE volumeName = :volumeName AND mediaStoreId = :id")
    suspend fun media(volumeName: String, id: Long): MediaItemEntity?

    @Query("DELETE FROM media_items WHERE volumeName = :volumeName AND mediaStoreId = :id")
    suspend fun deleteMedia(volumeName: String, id: Long): Int

    @Query("SELECT * FROM media_store_checkpoints WHERE volumeName = :volumeName")
    suspend fun checkpoint(volumeName: String): MediaStoreCheckpointEntity?

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun mediaCount(): Long

    @Query("UPDATE media_items SET isAccessible = 0 WHERE volumeName = :volumeName")
    suspend fun markVolumeInaccessible(volumeName: String): Int

    @Query("DELETE FROM media_items WHERE volumeName = :volumeName")
    suspend fun deleteVolumeIndex(volumeName: String): Int
}
