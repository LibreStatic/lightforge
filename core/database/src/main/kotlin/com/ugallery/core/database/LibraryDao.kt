package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Upsert
    suspend fun upsertExif(exif: MediaExifEntity)

    @Query("SELECT * FROM media_exif_cache WHERE volumeName = :volumeName AND mediaStoreId = :id")
    suspend fun exif(volumeName: String, id: Long): MediaExifEntity?

    @Query("DELETE FROM media_exif_cache WHERE volumeName = :volumeName AND mediaStoreId = :id")
    suspend fun deleteExif(volumeName: String, id: Long): Int

    @Query(
        """
        UPDATE media_exif_cache SET latitude = NULL, longitude = NULL,
            locationReadWithPermission = 0
        """,
    )
    suspend fun purgeCachedLocations(): Int

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
        WHERE isAccessible = 1 AND isTrashed = 1
        ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun firstTrashPage(limit: Int): List<MediaItemEntity>

    @Query(
        """
        SELECT * FROM media_items
        WHERE isAccessible = 1 AND isTrashed = 1 AND (
            timelineSortMillis < :afterSortMillis OR
            (timelineSortMillis = :afterSortMillis AND mediaStoreId < :afterMediaStoreId) OR
            (timelineSortMillis = :afterSortMillis AND mediaStoreId = :afterMediaStoreId
                AND volumeName < :afterVolumeName)
        )
        ORDER BY timelineSortMillis DESC, mediaStoreId DESC, volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun trashPageAfter(
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        afterVolumeName: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items WHERE isAccessible = 1 AND isTrashed = 1")
    suspend fun trashCount(): Long

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

    @Query(
        """
        SELECT m.volumeName, m.bucketId, MAX(m.bucketDisplayName) AS displayName,
            COUNT(*) AS itemCount, MAX(m.timelineSortMillis) AS latestSortMillis,
            (SELECT cover.mediaStoreId FROM media_items AS cover
             WHERE cover.volumeName = m.volumeName AND cover.bucketId = m.bucketId
                AND cover.isTrashed = 0
             ORDER BY cover.isAccessible DESC, cover.timelineSortMillis DESC,
                cover.mediaStoreId DESC LIMIT 1) AS coverMediaStoreId,
            MAX(m.isAccessible) AS isAvailable
        FROM media_items AS m
        WHERE m.bucketId IS NOT NULL AND m.isTrashed = 0
        GROUP BY m.volumeName, m.bucketId
        ORDER BY latestSortMillis DESC, m.bucketId DESC, m.volumeName DESC
        """,
    )
    fun physicalAlbums(): androidx.paging.PagingSource<Int, PhysicalAlbumRow>

    @Insert
    suspend fun insertVirtualAlbum(album: VirtualAlbumEntity): Long

    @Query("SELECT * FROM virtual_albums WHERE albumId = :albumId")
    suspend fun virtualAlbum(albumId: Long): VirtualAlbumEntity?

    @Query("SELECT COUNT(*) FROM virtual_album_media WHERE albumId = :albumId")
    suspend fun virtualAlbumMediaCount(albumId: Long): Long

    @Query(
        """
        UPDATE virtual_albums SET name = :name, normalizedName = :normalizedName,
            updatedAtMillis = :updatedAtMillis WHERE albumId = :albumId
        """,
    )
    suspend fun renameVirtualAlbum(
        albumId: Long,
        name: String,
        normalizedName: String,
        updatedAtMillis: Long,
    ): Int

    @Query("DELETE FROM virtual_albums WHERE albumId = :albumId")
    suspend fun deleteVirtualAlbum(albumId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addVirtualAlbumMedia(items: List<VirtualAlbumMediaEntity>): List<Long>

    @Query(
        """
        DELETE FROM virtual_album_media
        WHERE albumId = :albumId AND volumeName = :volumeName AND mediaStoreId = :mediaStoreId
        """,
    )
    suspend fun removeVirtualAlbumMedia(albumId: Long, volumeName: String, mediaStoreId: Long): Int

    @Query(
        """
        SELECT a.albumId, a.name, COUNT(m.mediaStoreId) AS itemCount,
            MAX(m.timelineSortMillis) AS latestSortMillis,
            (SELECT vm2.volumeName FROM virtual_album_media vm2
             JOIN media_items m2 ON m2.volumeName = vm2.volumeName AND m2.mediaStoreId = vm2.mediaStoreId
             WHERE vm2.albumId = a.albumId AND m2.isAccessible = 1 AND m2.isTrashed = 0
             ORDER BY m2.timelineSortMillis DESC, m2.mediaStoreId DESC LIMIT 1) AS coverVolumeName,
            (SELECT vm2.mediaStoreId FROM virtual_album_media vm2
             JOIN media_items m2 ON m2.volumeName = vm2.volumeName AND m2.mediaStoreId = vm2.mediaStoreId
             WHERE vm2.albumId = a.albumId AND m2.isAccessible = 1 AND m2.isTrashed = 0
             ORDER BY m2.timelineSortMillis DESC, m2.mediaStoreId DESC LIMIT 1) AS coverMediaStoreId
        FROM virtual_albums a
        LEFT JOIN virtual_album_media vm ON vm.albumId = a.albumId
        LEFT JOIN media_items m ON m.volumeName = vm.volumeName AND m.mediaStoreId = vm.mediaStoreId
            AND m.isAccessible = 1 AND m.isTrashed = 0
        GROUP BY a.albumId
        ORDER BY a.updatedAtMillis DESC, a.albumId DESC
        """,
    )
    fun virtualAlbums(): androidx.paging.PagingSource<Int, VirtualAlbumRow>

    @Query(
        """
        SELECT m.* FROM media_items m
        WHERE m.volumeName = :volumeName AND m.bucketId = :bucketId
            AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType)
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit
        """,
    )
    suspend fun firstPhysicalAlbumPage(
        volumeName: String,
        bucketId: Long,
        mediaType: Int,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM media_items m
        WHERE m.volumeName = :volumeName AND m.bucketId = :bucketId
            AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType) AND (
                m.timelineSortMillis < :afterSortMillis OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId < :afterMediaStoreId)
            )
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit
        """,
    )
    suspend fun physicalAlbumPageAfter(
        volumeName: String,
        bucketId: Long,
        mediaType: Int,
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM media_items m
        WHERE m.volumeName = :volumeName AND m.bucketId = :bucketId
            AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType)
        ORDER BY m.timelineSortMillis ASC, m.mediaStoreId ASC, m.volumeName ASC LIMIT :limit
        """,
    )
    suspend fun firstPhysicalAlbumPageOldest(
        volumeName: String,
        bucketId: Long,
        mediaType: Int,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM media_items m
        WHERE m.volumeName = :volumeName AND m.bucketId = :bucketId
            AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType) AND (
                m.timelineSortMillis > :afterSortMillis OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId > :afterMediaStoreId)
            )
        ORDER BY m.timelineSortMillis ASC, m.mediaStoreId ASC, m.volumeName ASC LIMIT :limit
        """,
    )
    suspend fun physicalAlbumPageAfterOldest(
        volumeName: String,
        bucketId: Long,
        mediaType: Int,
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM virtual_album_media vm
        JOIN media_items m ON m.volumeName = vm.volumeName AND m.mediaStoreId = vm.mediaStoreId
        WHERE vm.albumId = :albumId AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType)
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit
        """,
    )
    suspend fun firstVirtualAlbumPage(albumId: Long, mediaType: Int, limit: Int): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM virtual_album_media vm
        JOIN media_items m ON m.volumeName = vm.volumeName AND m.mediaStoreId = vm.mediaStoreId
        WHERE vm.albumId = :albumId AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType) AND (
                m.timelineSortMillis < :afterSortMillis OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId < :afterMediaStoreId) OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId = :afterMediaStoreId
                    AND m.volumeName < :afterVolumeName)
            )
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit
        """,
    )
    suspend fun virtualAlbumPageAfter(
        albumId: Long,
        mediaType: Int,
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        afterVolumeName: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM virtual_album_media vm
        JOIN media_items m ON m.volumeName = vm.volumeName AND m.mediaStoreId = vm.mediaStoreId
        WHERE vm.albumId = :albumId AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType)
        ORDER BY m.timelineSortMillis ASC, m.mediaStoreId ASC, m.volumeName ASC LIMIT :limit
        """,
    )
    suspend fun firstVirtualAlbumPageOldest(albumId: Long, mediaType: Int, limit: Int): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM virtual_album_media vm
        JOIN media_items m ON m.volumeName = vm.volumeName AND m.mediaStoreId = vm.mediaStoreId
        WHERE vm.albumId = :albumId AND m.isAccessible = 1 AND m.isTrashed = 0
            AND (:mediaType = 0 OR m.mediaType = :mediaType) AND (
                m.timelineSortMillis > :afterSortMillis OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId > :afterMediaStoreId) OR
                (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId = :afterMediaStoreId
                    AND m.volumeName > :afterVolumeName)
            )
        ORDER BY m.timelineSortMillis ASC, m.mediaStoreId ASC, m.volumeName ASC LIMIT :limit
        """,
    )
    suspend fun virtualAlbumPageAfterOldest(
        albumId: Long,
        mediaType: Int,
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        afterVolumeName: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """
        SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE MAX(isAccessible) END FROM media_items
        WHERE volumeName = :volumeName AND bucketId = :bucketId
        """,
    )
    suspend fun isPhysicalAlbumAvailable(volumeName: String, bucketId: Long): Boolean

    @Query("DELETE FROM media_items WHERE volumeName = :volumeName")
    suspend fun deleteVolumeIndex(volumeName: String): Int
}
