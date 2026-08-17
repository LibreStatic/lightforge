package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @RawQuery
    suspend fun rawSelectionPage(query: SupportSQLiteQuery): List<MediaItemEntity>

    @RawQuery
    suspend fun rawSelectionCount(query: SupportSQLiteQuery): Long

    @Query(
        "SELECT m.*, o.rawText AS ocrText, o.modelVersion AS ocrModelVersion, " +
            "GROUP_CONCAT(l.canonicalLabel) AS canonicalLabelsCsv, " +
            "MAX(l.modelVersion) AS labelModelVersion FROM media_items m " +
            "LEFT JOIN media_ocr o ON o.volumeName=m.volumeName AND o.mediaStoreId=m.mediaStoreId " +
            "LEFT JOIN media_labels l ON l.volumeName=m.volumeName AND l.mediaStoreId=m.mediaStoreId " +
            "AND NOT EXISTS (SELECT 1 FROM label_suppressions s " +
            "WHERE s.canonicalLabel=l.canonicalLabel) " +
            "WHERE m.isAccessible=1 AND m.isTrashed=0 AND " +
            "(:afterVolume IS NULL OR m.volumeName>:afterVolume OR " +
            "(m.volumeName=:afterVolume AND m.mediaStoreId>:afterId)) " +
            "GROUP BY m.volumeName, m.mediaStoreId " +
            "ORDER BY m.volumeName ASC, m.mediaStoreId ASC LIMIT :limit",
    )
    suspend fun searchRebuildPage(
        afterVolume: String?,
        afterId: Long,
        limit: Int,
    ): List<SearchRebuildRow>

    @Query(
        "SELECT m.* FROM media_items m WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "AND NOT EXISTS (SELECT 1 FROM media_label_runs r WHERE r.volumeName=m.volumeName " +
            "AND r.mediaStoreId=m.mediaStoreId AND r.generationModified=m.generationModified " +
            "AND r.modelVersion=:modelVersion) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingLabelCandidates(modelVersion: String, limit: Int): List<MediaItemEntity>

    @Query(
        "SELECT m.* FROM media_items m WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "AND NOT EXISTS (SELECT 1 FROM media_ocr o WHERE o.volumeName=m.volumeName " +
            "AND o.mediaStoreId=m.mediaStoreId AND o.generationModified=m.generationModified " +
            "AND o.modelVersion=:modelVersion) ORDER BY CASE WHEN " +
            "LOWER(COALESCE(m.bucketDisplayName,'')) LIKE '%screenshot%' OR " +
            "LOWER(COALESCE(m.relativePath,'')) LIKE '%screenshot%' OR " +
            "LOWER(COALESCE(m.displayName,'')) LIKE '%scan%' THEN 0 ELSE 1 END, " +
            "m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingOcrCandidates(modelVersion: String, limit: Int): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabelRun(run: MediaLabelRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLabels(labels: List<MediaLabelEntity>)

    @Query("DELETE FROM media_labels WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun deleteLabels(volumeName: String, mediaStoreId: Long)

    @Transaction
    suspend fun replaceLabelResult(run: MediaLabelRunEntity, labels: List<MediaLabelEntity>) {
        deleteLabels(run.volumeName, run.mediaStoreId)
        if (labels.isNotEmpty()) upsertLabels(labels)
        upsertLabelRun(run)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOcr(result: MediaOcrEntity)

    @Query("SELECT * FROM media_labels WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId ORDER BY confidence DESC")
    suspend fun labels(volumeName: String, mediaStoreId: Long): List<MediaLabelEntity>

    @Query("SELECT * FROM media_ocr WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun ocr(volumeName: String, mediaStoreId: Long): MediaOcrEntity?

    @Query("SELECT canonicalLabel FROM label_suppressions")
    suspend fun suppressedLabels(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun suppressLabel(suppression: LabelSuppressionEntity)

    @Query("DELETE FROM label_suppressions WHERE canonicalLabel=:canonicalLabel")
    suspend fun unsuppressLabel(canonicalLabel: String): Int

    @Query("DELETE FROM media_label_runs")
    suspend fun purgeLabelRuns(): Int

    @Query("DELETE FROM media_labels")
    suspend fun purgeLabels(): Int

    @Query("DELETE FROM media_ocr")
    suspend fun purgeOcr(): Int

    @Query(
        "SELECT m.* FROM media_items m WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.sizeBytes>0 " +
            "AND NOT EXISTS (SELECT 1 FROM duplicate_hashes h WHERE h.volumeName=m.volumeName " +
            "AND h.mediaStoreId=m.mediaStoreId AND h.generationModified=m.generationModified " +
            "AND h.sizeBytes=m.sizeBytes AND h.hashVersion=:hashVersion) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingDuplicateSampleCandidates(hashVersion: String, limit: Int): List<MediaItemEntity>

    @Query(
        "SELECT m.* FROM media_items m JOIN duplicate_hashes h ON h.volumeName=m.volumeName " +
            "AND h.mediaStoreId=m.mediaStoreId WHERE m.isAccessible=1 AND m.isTrashed=0 " +
            "AND h.hashVersion=:hashVersion AND h.generationModified=m.generationModified " +
            "AND h.sizeBytes=m.sizeBytes AND h.sha256 IS NULL AND EXISTS (SELECT 1 " +
            "FROM duplicate_hashes h2 JOIN media_items m2 ON m2.volumeName=h2.volumeName " +
            "AND m2.mediaStoreId=h2.mediaStoreId WHERE h2.hashVersion=:hashVersion " +
            "AND h2.sizeBytes=h.sizeBytes AND h2.sampleSha256=h.sampleSha256 " +
            "AND h2.generationModified=m2.generationModified AND m2.sizeBytes=h2.sizeBytes " +
            "AND m2.isAccessible=1 AND m2.isTrashed=0 AND " +
            "(h2.volumeName!=h.volumeName OR h2.mediaStoreId!=h.mediaStoreId)) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingDuplicateFullCandidates(hashVersion: String, limit: Int): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDuplicateHash(hash: DuplicateHashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDuplicateHashes(hashes: List<DuplicateHashEntity>)

    @Query(
        "UPDATE duplicate_hashes SET sha256=:sha256, updatedAtMillis=:updatedAtMillis " +
            "WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId " +
            "AND generationModified=:generationModified AND hashVersion=:hashVersion",
    )
    suspend fun setDuplicateFullHash(
        volumeName: String,
        mediaStoreId: Long,
        generationModified: Long,
        hashVersion: String,
        sha256: String,
        updatedAtMillis: Long,
    ): Int

    @Query("SELECT * FROM duplicate_hashes WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun duplicateHash(volumeName: String, mediaStoreId: Long): DuplicateHashEntity?

    @Query(
        """WITH ranked AS (
            SELECT (CAST(h.sizeBytes AS TEXT) || ':' || h.sha256) AS groupId,
                h.sha256 AS sha256, h.sizeBytes AS sizeBytes,
                COUNT(*) OVER (PARTITION BY h.sizeBytes, h.sha256) AS memberCount,
                h.sizeBytes * (COUNT(*) OVER (PARTITION BY h.sizeBytes, h.sha256) - 1) AS recoverableBytes,
                m.volumeName AS recommendedVolumeName,
                m.mediaStoreId AS recommendedMediaStoreId,
                ROW_NUMBER() OVER (PARTITION BY h.sizeBytes, h.sha256 ORDER BY
                    m.isFavorite DESC, (CAST(m.width AS INTEGER) * m.height) DESC,
                    m.timelineSortMillis DESC, m.volumeName ASC, m.mediaStoreId ASC) AS keepRank
            FROM duplicate_hashes h JOIN media_items m ON m.volumeName=h.volumeName
                AND m.mediaStoreId=h.mediaStoreId
            WHERE h.hashVersion=:hashVersion AND h.sha256 IS NOT NULL
                AND h.generationModified=m.generationModified AND h.sizeBytes=m.sizeBytes
                AND m.isAccessible=1 AND m.isTrashed=0)
        SELECT groupId, sha256, sizeBytes, memberCount, recoverableBytes,
            recommendedVolumeName, recommendedMediaStoreId FROM ranked
        WHERE keepRank=1 AND memberCount>1 AND (
            :afterRecoverableBytes IS NULL OR recoverableBytes<:afterRecoverableBytes OR
            (recoverableBytes=:afterRecoverableBytes AND groupId>:afterGroupId))
        ORDER BY recoverableBytes DESC, groupId ASC LIMIT :limit""",
    )
    suspend fun exactDuplicateGroups(
        hashVersion: String,
        afterRecoverableBytes: Long?,
        afterGroupId: String?,
        limit: Int,
    ): List<ExactDuplicateGroupRow>

    @Query(
        "SELECT m.* FROM duplicate_hashes h JOIN media_items m ON m.volumeName=h.volumeName " +
            "AND m.mediaStoreId=h.mediaStoreId WHERE h.hashVersion=:hashVersion " +
            "AND h.sha256=:sha256 AND h.sizeBytes=:sizeBytes AND h.generationModified=m.generationModified " +
            "AND m.sizeBytes=h.sizeBytes AND m.isAccessible=1 AND m.isTrashed=0 AND " +
            "(:afterVolume IS NULL OR m.volumeName>:afterVolume OR " +
            "(m.volumeName=:afterVolume AND m.mediaStoreId>:afterId)) " +
            "ORDER BY m.volumeName ASC, m.mediaStoreId ASC LIMIT :limit",
    )
    suspend fun exactDuplicateMembers(
        hashVersion: String,
        sha256: String,
        sizeBytes: Long,
        afterVolume: String?,
        afterId: Long,
        limit: Int,
    ): List<MediaItemEntity>

    @Query("DELETE FROM duplicate_hashes")
    suspend fun purgeDuplicateHashes(): Int

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

    @Query("SELECT COUNT(*) FROM media_items WHERE isAccessible = 1 AND isTrashed = 1")
    fun observeTrashCount(): Flow<Long>

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
