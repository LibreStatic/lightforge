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
    @RawQuery(observedEntities = [MediaItemEntity::class, ArchivedMediaEntity::class,
        PhotoStackEntity::class, PhotoStackMemberEntity::class])
    fun rawStackTimelinePagingSource(query: SupportSQLiteQuery): androidx.paging.PagingSource<Int, StackTimelineRow>

    @RawQuery
    suspend fun stackTimelinePage(query: SupportSQLiteQuery): List<StackTimelineRow>

    @RawQuery
    suspend fun stackTimelineSelection(query: SupportSQLiteQuery): List<MediaItemEntity>

    @RawQuery(observedEntities = [MediaItemEntity::class, ArchivedMediaEntity::class])
    fun rawTimelinePagingSource(query: SupportSQLiteQuery): androidx.paging.PagingSource<Int, MediaItemEntity>

    @Query(
        "SELECT * FROM media_items WHERE isAccessible=1 AND isTrashed=0 AND isFavorite=1 " +
            "ORDER BY volumeName, mediaStoreId",
    )
    suspend fun favoriteMediaForBackup(): List<MediaItemEntity>

    @Query(
        "SELECT * FROM media_items WHERE isAccessible=1 AND isTrashed=0 " +
            "AND displayName IS :displayName AND mimeType IS :mimeType AND sizeBytes=:sizeBytes LIMIT 3",
    )
    suspend fun mediaByBackupFingerprint(
        displayName: String?,
        mimeType: String?,
        sizeBytes: Long,
    ): List<MediaItemEntity>

    @Upsert
    suspend fun upsertVideoPlaybackPosition(position: VideoPlaybackPositionEntity)

    @Query(
        "SELECT * FROM video_playback_positions WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId",
    )
    suspend fun videoPlaybackPosition(volumeName: String, mediaStoreId: Long): VideoPlaybackPositionEntity?

    @Query(
        "DELETE FROM video_playback_positions WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId",
    )
    suspend fun deleteVideoPlaybackPosition(volumeName: String, mediaStoreId: Long): Int

    @Query(
        "SELECT " +
            "(SELECT COUNT(DISTINCT l.volumeName || ':' || l.mediaStoreId) FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='dog' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='dog')) AS dogCount, " +
            "(SELECT COUNT(DISTINCT l.volumeName || ':' || l.mediaStoreId) FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='cat' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='cat')) AS catCount, " +
            "(SELECT l.volumeName FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='dog' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='dog') " +
            "ORDER BY m.timelineSortMillis DESC LIMIT 1) AS dogCoverVolumeName, " +
            "(SELECT l.mediaStoreId FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='dog' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='dog') " +
            "ORDER BY m.timelineSortMillis DESC LIMIT 1) AS dogCoverMediaStoreId, " +
            "(SELECT l.volumeName FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='cat' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='cat') " +
            "ORDER BY m.timelineSortMillis DESC LIMIT 1) AS catCoverVolumeName, " +
            "(SELECT l.mediaStoreId FROM media_labels l " +
            "JOIN media_items m ON m.volumeName=l.volumeName AND m.mediaStoreId=l.mediaStoreId " +
            "WHERE l.canonicalLabel='cat' AND m.isAccessible=1 AND m.isTrashed=0 AND NOT EXISTS " +
            "(SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel='cat') " +
            "ORDER BY m.timelineSortMillis DESC LIMIT 1) AS catCoverMediaStoreId",
    )
    fun petCollectionSummaryFlow(): Flow<PetCollectionSummaryRow>

    @Query(
        "SELECT m.* FROM media_items m WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "AND NOT EXISTS (SELECT 1 FROM face_detection_runs r WHERE r.volumeName=m.volumeName " +
            "AND r.mediaStoreId=m.mediaStoreId AND r.generationModified=m.generationModified " +
            "AND r.modelVersion=:modelVersion) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingFaceDetectionCandidates(modelVersion: String, limit: Int): List<MediaItemEntity>

    @Query("DELETE FROM detected_faces WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun deleteDetectedFaces(volumeName: String, mediaStoreId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaceDetectionRun(run: FaceDetectionRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetectedFaces(faces: List<DetectedFaceEntity>)

    @Transaction
    suspend fun replaceFaceDetection(run: FaceDetectionRunEntity, faces: List<DetectedFaceEntity>) {
        deleteDetectedFaces(run.volumeName, run.mediaStoreId)
        upsertFaceDetectionRun(run)
        if (faces.isNotEmpty()) upsertDetectedFaces(faces)
    }

    @Query("SELECT * FROM detected_faces WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId ORDER BY faceOrdinal")
    suspend fun detectedFaces(volumeName: String, mediaStoreId: Long): List<DetectedFaceEntity>

    @Query("SELECT COUNT(*) FROM detected_faces")
    suspend fun detectedFaceCount(): Long

    @Query("DELETE FROM face_detection_runs")
    suspend fun purgeFaceDetections(): Int

    @Query(
        "SELECT m.* FROM media_items m " +
            "WHERE m.isAccessible=1 AND m.isTrashed=0 AND EXISTS " +
            "(SELECT 1 FROM detected_faces f WHERE f.volumeName=m.volumeName AND f.mediaStoreId=m.mediaStoreId " +
            "AND NOT EXISTS (SELECT 1 FROM face_embeddings e WHERE e.volumeName=f.volumeName " +
            "AND e.mediaStoreId=f.mediaStoreId AND e.faceOrdinal=f.faceOrdinal " +
            "AND e.detectionModelVersion=f.modelVersion AND e.embeddingModelVersion=:embeddingModelVersion)) " +
            "ORDER BY m.volumeName ASC, m.mediaStoreId ASC LIMIT :limit",
    )
    suspend fun pendingFaceEmbeddingMedia(
        embeddingModelVersion: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaceEmbeddings(embeddings: List<FaceEmbeddingEntity>)

    @Query("SELECT * FROM face_embeddings WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId ORDER BY faceOrdinal")
    suspend fun faceEmbeddings(volumeName: String, mediaStoreId: Long): List<FaceEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun faceEmbeddingCount(): Long

    @Query("SELECT COALESCE(SUM(length(quantizedVector)), 0) FROM face_embeddings")
    suspend fun faceEmbeddingPayloadBytes(): Long

    @Query("DELETE FROM face_embeddings")
    suspend fun purgeFaceEmbeddings(): Int

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
        "SELECT m.*, o.rawText AS ocrText, o.modelVersion AS ocrModelVersion, " +
            "GROUP_CONCAT(l.canonicalLabel) AS canonicalLabelsCsv, MAX(l.modelVersion) AS labelModelVersion " +
            "FROM media_items m LEFT JOIN media_ocr o ON o.volumeName=m.volumeName AND o.mediaStoreId=m.mediaStoreId " +
            "LEFT JOIN media_labels l ON l.volumeName=m.volumeName AND l.mediaStoreId=m.mediaStoreId " +
            "AND NOT EXISTS (SELECT 1 FROM label_suppressions s WHERE s.canonicalLabel=l.canonicalLabel) " +
            "WHERE m.volumeName=:volumeName AND m.mediaStoreId=:mediaStoreId AND m.isAccessible=1 AND m.isTrashed=0 " +
            "GROUP BY m.volumeName,m.mediaStoreId",
    )
    suspend fun searchRebuildRow(volumeName: String, mediaStoreId: Long): SearchRebuildRow?

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

    @Query(
        "SELECT m.* FROM media_items m WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "AND NOT EXISTS (SELECT 1 FROM similarity_features f WHERE f.volumeName=m.volumeName " +
            "AND f.mediaStoreId=m.mediaStoreId AND f.generationModified=m.generationModified " +
            "AND f.algorithmVersion=:algorithmVersion) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun pendingSimilarityCandidates(algorithmVersion: String, limit: Int): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSimilarityFeature(feature: SimilarityFeatureEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSimilarityFeatures(features: List<SimilarityFeatureEntity>)

    @Query("SELECT * FROM similarity_features WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun similarityFeature(volumeName: String, mediaStoreId: Long): SimilarityFeatureEntity?

    @Query("DELETE FROM similarity_features WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun deleteSimilarityFeature(volumeName: String, mediaStoreId: Long): Int

    @Query(
        "SELECT f.* FROM similarity_features f JOIN media_items m ON m.volumeName=f.volumeName " +
            "AND m.mediaStoreId=f.mediaStoreId LEFT JOIN similarity_exclusions x " +
            "ON x.volumeName=f.volumeName AND x.mediaStoreId=f.mediaStoreId " +
            "WHERE f.algorithmVersion=:algorithmVersion AND f.generationModified=m.generationModified " +
            "AND m.isAccessible=1 AND m.isTrashed=0 AND x.mediaStoreId IS NULL " +
            "AND (f.volumeName!=:volumeName OR f.mediaStoreId!=:mediaStoreId) AND " +
            "(f.lsh0=:lsh0 OR f.lsh1=:lsh1 OR f.lsh2=:lsh2 OR f.lsh3=:lsh3) " +
            "ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT :limit",
    )
    suspend fun similarityLshCandidates(
        algorithmVersion: String,
        volumeName: String,
        mediaStoreId: Long,
        lsh0: Int,
        lsh1: Int,
        lsh2: Int,
        lsh3: Int,
        limit: Int,
    ): List<SimilarityFeatureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSimilarityEdges(edges: List<SimilarityEdgeEntity>)

    @Query("DELETE FROM similarity_edges WHERE (aVolumeName=:volumeName AND aMediaStoreId=:mediaStoreId) OR (bVolumeName=:volumeName AND bMediaStoreId=:mediaStoreId)")
    suspend fun deleteSimilarityEdges(volumeName: String, mediaStoreId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSimilarityMemberships(memberships: List<SimilarityMembershipEntity>)

    @Query("SELECT * FROM similarity_memberships WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun similarityMembership(volumeName: String, mediaStoreId: Long): SimilarityMembershipEntity?

    @Query("SELECT COUNT(*) FROM similarity_memberships WHERE clusterId=:clusterId")
    suspend fun similarityClusterSize(clusterId: String): Int

    @Query("UPDATE similarity_memberships SET clusterId=:toClusterId WHERE clusterId=:fromClusterId")
    suspend fun moveSimilarityCluster(fromClusterId: String, toClusterId: String): Int

    @Query("DELETE FROM similarity_memberships WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun deleteSimilarityMembership(volumeName: String, mediaStoreId: Long): Int

    @Query(
        "SELECT f.* FROM similarity_memberships s JOIN similarity_features f " +
            "ON f.volumeName=s.volumeName AND f.mediaStoreId=s.mediaStoreId " +
            "WHERE s.clusterId=:clusterId ORDER BY f.volumeName, f.mediaStoreId LIMIT :limit",
    )
    suspend fun similarityClusterFeatures(clusterId: String, limit: Int): List<SimilarityFeatureEntity>

    @Query(
        "SELECT e.* FROM similarity_edges e JOIN similarity_memberships a " +
            "ON a.volumeName=e.aVolumeName AND a.mediaStoreId=e.aMediaStoreId " +
            "JOIN similarity_memberships b ON b.volumeName=e.bVolumeName AND b.mediaStoreId=e.bMediaStoreId " +
            "WHERE a.clusterId=:clusterId AND b.clusterId=:clusterId",
    )
    suspend fun similarityClusterEdges(clusterId: String): List<SimilarityEdgeEntity>

    @Query("DELETE FROM similarity_memberships WHERE clusterId=:clusterId")
    suspend fun deleteSimilarityClusterMemberships(clusterId: String): Int

    @Query(
        """WITH ranked AS (
            SELECT s.clusterId AS clusterId,
                COUNT(*) OVER (PARTITION BY s.clusterId) AS memberCount,
                MAX(s.bestScore) OVER (PARTITION BY s.clusterId) AS bestScore,
                m.volumeName AS recommendedVolumeName, m.mediaStoreId AS recommendedMediaStoreId,
                ROW_NUMBER() OVER (PARTITION BY s.clusterId ORDER BY m.isFavorite DESC,
                    (CAST(m.width AS INTEGER) * m.height) DESC, s.bestScore DESC,
                    m.timelineSortMillis DESC, m.volumeName, m.mediaStoreId) AS keepRank
            FROM similarity_memberships s JOIN media_items m ON m.volumeName=s.volumeName
                AND m.mediaStoreId=s.mediaStoreId WHERE m.isAccessible=1 AND m.isTrashed=0)
        SELECT clusterId, memberCount, bestScore, recommendedVolumeName, recommendedMediaStoreId
        FROM ranked WHERE keepRank=1 AND memberCount>1 AND (:afterClusterId IS NULL OR clusterId>:afterClusterId)
        ORDER BY clusterId ASC LIMIT :limit""",
    )
    suspend fun similarityStacks(afterClusterId: String?, limit: Int): List<SimilarityStackRow>

    @Query(
        "SELECT m.* FROM similarity_memberships s JOIN media_items m ON m.volumeName=s.volumeName " +
            "AND m.mediaStoreId=s.mediaStoreId WHERE s.clusterId=:clusterId AND " +
            "(:afterVolume IS NULL OR m.volumeName>:afterVolume OR " +
            "(m.volumeName=:afterVolume AND m.mediaStoreId>:afterId)) " +
            "ORDER BY m.volumeName, m.mediaStoreId LIMIT :limit",
    )
    suspend fun similarityStackMembers(
        clusterId: String,
        afterVolume: String?,
        afterId: Long,
        limit: Int,
    ): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun excludeSimilarity(exclusion: SimilarityExclusionEntity)

    @Query("DELETE FROM similarity_exclusions WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun restoreSimilarity(volumeName: String, mediaStoreId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM similarity_exclusions WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId)")
    suspend fun isSimilarityExcluded(volumeName: String, mediaStoreId: Long): Boolean

    @Query("DELETE FROM similarity_features")
    suspend fun purgeSimilarityFeatures(): Int

    @Query("DELETE FROM similarity_exclusions")
    suspend fun purgeSimilarityExclusions(): Int

    @Query(
        """SELECT
            (SELECT COUNT(*) FROM (SELECT 1 FROM duplicate_hashes h JOIN media_items m
                ON m.volumeName=h.volumeName AND m.mediaStoreId=h.mediaStoreId
                WHERE h.hashVersion=:hashVersion AND h.sha256 IS NOT NULL
                    AND h.generationModified=m.generationModified AND h.sizeBytes=m.sizeBytes
                    AND m.isAccessible=1 AND m.isTrashed=0 GROUP BY h.sizeBytes,h.sha256 HAVING COUNT(*)>1))
                AS exactGroupCount,
            COALESCE((SELECT SUM(recoverable) FROM (SELECT h.sizeBytes*(COUNT(*)-1) AS recoverable
                FROM duplicate_hashes h JOIN media_items m ON m.volumeName=h.volumeName
                    AND m.mediaStoreId=h.mediaStoreId WHERE h.hashVersion=:hashVersion
                    AND h.sha256 IS NOT NULL AND h.generationModified=m.generationModified
                    AND h.sizeBytes=m.sizeBytes AND m.isAccessible=1 AND m.isTrashed=0
                    GROUP BY h.sizeBytes,h.sha256 HAVING COUNT(*)>1)),0) AS exactRecoverableBytes,
            (SELECT COUNT(*) FROM media_items WHERE mediaType=3 AND sizeBytes>=:largeVideoBytes
                AND isAccessible=1 AND isTrashed=0) AS largeVideoCount,
            COALESCE((SELECT SUM(sizeBytes) FROM media_items WHERE mediaType=3 AND sizeBytes>=:largeVideoBytes
                AND isAccessible=1 AND isTrashed=0),0) AS largeVideoBytes,
            (SELECT COUNT(*) FROM media_items WHERE mediaType=1 AND isAccessible=1 AND isTrashed=0
                AND (LOWER(COALESCE(bucketDisplayName,'')) LIKE '%screenshot%'
                OR LOWER(COALESCE(relativePath,'')) LIKE '%screenshot%'
                OR LOWER(COALESCE(displayName,'')) LIKE '%screenshot%')) AS screenshotCount,
            (SELECT COUNT(*) FROM similarity_features f JOIN media_items m ON m.volumeName=f.volumeName
                AND m.mediaStoreId=f.mediaStoreId WHERE f.algorithmVersion=:similarityVersion
                AND f.generationModified=m.generationModified AND f.blurScore<=:maximumBlurScore
                AND m.isAccessible=1 AND m.isTrashed=0) AS blurryCandidateCount""",
    )
    fun cleanupSummaryFlow(
        hashVersion: String,
        similarityVersion: String,
        largeVideoBytes: Long,
        maximumBlurScore: Float,
    ): Flow<CleanupSummaryRow>

    @Upsert
    suspend fun upsertMediaRows(items: List<MediaItemEntity>)

    @Query("SELECT * FROM portable_timeline_overrides WHERE volumeName=:volume AND mediaStoreId IN (:ids)")
    suspend fun portableDateChoices(volume: String, ids: List<Long>): List<PortableTimelineOverrideEntity>

    @Transaction
    suspend fun upsertMedia(items: List<MediaItemEntity>) {
        // Indexed bounded queries, not one database lookup per scanned photo.
        val choices = items.groupBy { it.volumeName }.flatMap { (volume, rows) ->
            rows.map { it.mediaStoreId }.chunked(500).flatMap { portableDateChoices(volume, it) }
        }.associateBy { it.volumeName to it.mediaStoreId }
        // Verified foreground/restore publications have no scanner token. If a full scan is
        // active, preserve their visibility even when its cursor already passed this row.
        // This transaction serializes with scan completion; absent, unverified rows still hide.
        val activeScans = items.filter { it.isAccessible && it.lastSeenScanId == 0L }
            .map { it.volumeName }.distinct().associateWith { checkpoint(it)?.activeScanId }
        upsertMediaRows(items.map { row ->
            val choice = choices[row.volumeName to row.mediaStoreId]?.takeIf { it.generationAdded == row.generationAdded }
            val dated = if (choice == null) row else row.copy(timelineSortMillis = choice.timelineSortMillis, dateTakenMillis = choice.dateTakenMillis)
            val activeScan = activeScans[row.volumeName]
            if (dated.isAccessible && dated.lastSeenScanId == 0L && activeScan != null)
                dated.copy(lastSeenScanId = activeScan)
            else dated
        })
    }

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

    @Query(
        "SELECT mediaStoreId, generationModified FROM media_items " +
            "WHERE volumeName=:volumeName AND mediaStoreId IN (:mediaStoreIds)",
    )
    suspend fun knownMediaGenerations(
        volumeName: String,
        mediaStoreIds: List<Long>,
    ): List<KnownMediaGeneration>

    @Query(
        "UPDATE media_items SET lastSeenScanId=:scanId, isAccessible=1 " +
            "WHERE volumeName=:volumeName AND mediaStoreId IN (:mediaStoreIds)",
    )
    suspend fun markMediaSeen(volumeName: String, mediaStoreIds: List<Long>, scanId: Long): Int

    /** Full reconciliation touches known rows but writes complete entities only for new/stale files. */
    @Transaction
    suspend fun reconcileMediaPage(
        items: List<MediaItemEntity>,
        checkpoint: MediaStoreCheckpointEntity,
    ) {
        if (items.isNotEmpty()) {
            val volumeName = items.first().volumeName
            require(items.all { it.volumeName == volumeName && it.isAccessible })
            val known = knownMediaGenerations(volumeName, items.map(MediaItemEntity::mediaStoreId))
                .associate { it.mediaStoreId to it.generationModified }
            markMediaSeen(volumeName, items.map(MediaItemEntity::mediaStoreId), items.first().lastSeenScanId)
            val newOrChanged = items.filter { known[it.mediaStoreId] != it.generationModified }
            if (newOrChanged.isNotEmpty()) upsertMedia(newOrChanged)
        }
        upsertCheckpoint(checkpoint)
    }

    @Transaction
    suspend fun resetVolumeForScan(checkpoint: MediaStoreCheckpointEntity) {
        upsertCheckpoint(checkpoint)
    }

    @Query("DELETE FROM media_items WHERE volumeName=:volumeName AND lastSeenScanId!=:scanId")
    suspend fun deleteItemsNotSeenInScan(volumeName: String, scanId: Long): Int

    @Query("UPDATE media_items SET isAccessible=0 WHERE volumeName=:volumeName AND lastSeenScanId!=:scanId")
    suspend fun hideItemsNotSeenInScan(volumeName: String, scanId: Long): Int

    @Transaction
    suspend fun completeVolumeScan(checkpoint: MediaStoreCheckpointEntity, scanId: Long) {
        // A provider scan cannot distinguish deleted media from revoked selected-photo access.
        // Hide absent rows without cascading away saved decisions; an explicit fully-authorized
        // deletion hint remains the path for removal. Reappearing rows retain their identity.
        hideItemsNotSeenInScan(checkpoint.volumeName, scanId)
        upsertCheckpoint(checkpoint)
    }

    @Query(
        """
        SELECT * FROM media_items
        WHERE isAccessible = 1 AND isTrashed = 0
            AND NOT EXISTS (SELECT 1 FROM archived_media a
                WHERE a.volumeName=media_items.volumeName AND a.mediaStoreId=media_items.mediaStoreId)
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
        AND NOT EXISTS (SELECT 1 FROM archived_media a
            WHERE a.volumeName=media_items.volumeName AND a.mediaStoreId=media_items.mediaStoreId)
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

    @Query(
        """
        SELECT m.* FROM archived_media a
        JOIN media_items m ON m.volumeName=a.volumeName AND m.mediaStoreId=a.mediaStoreId
        WHERE m.isAccessible=1 AND m.isTrashed=0
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun firstArchivePage(limit: Int): List<MediaItemEntity>

    @Query(
        """
        SELECT m.* FROM archived_media a
        JOIN media_items m ON m.volumeName=a.volumeName AND m.mediaStoreId=a.mediaStoreId
        WHERE m.isAccessible=1 AND m.isTrashed=0 AND (
            m.timelineSortMillis < :afterSortMillis OR
            (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId < :afterMediaStoreId) OR
            (m.timelineSortMillis = :afterSortMillis AND m.mediaStoreId = :afterMediaStoreId
                AND m.volumeName < :afterVolumeName)
        )
        ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC
        LIMIT :limit
        """,
    )
    suspend fun archivePageAfter(
        afterSortMillis: Long,
        afterMediaStoreId: Long,
        afterVolumeName: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query(
        """SELECT COUNT(*) FROM archived_media a JOIN media_items m
            ON m.volumeName=a.volumeName AND m.mediaStoreId=a.mediaStoreId
            WHERE m.isAccessible=1 AND m.isTrashed=0""",
    )
    fun observeArchiveCount(): Flow<Long>

    @Upsert
    suspend fun upsertArchived(media: ArchivedMediaEntity)

    @Query("DELETE FROM archived_media WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId")
    suspend fun deleteArchived(volumeName: String, mediaStoreId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM archived_media WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId)")
    fun observeArchived(volumeName: String, mediaStoreId: Long): Flow<Boolean>

    @Query(
        """SELECT COUNT(*) FROM media_items m WHERE m.isAccessible=1 AND m.isTrashed=0
            AND NOT EXISTS (SELECT 1 FROM archived_media a
                WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)""",
    )
    fun observeHighlightInvalidation(): Flow<Long>

    @Query(
        """SELECT COUNT(*) FROM media_items m WHERE m.isAccessible=1 AND m.isTrashed=0
            AND m.timelineSortMillis>=:fromMillis AND m.timelineSortMillis<:toMillis
            AND NOT EXISTS (SELECT 1 FROM archived_media a
                WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)""",
    )
    suspend fun highlightCount(fromMillis: Long, toMillis: Long): Long

    @Query(
        """SELECT m.* FROM media_items m WHERE m.isAccessible=1 AND m.isTrashed=0
            AND m.timelineSortMillis>=:fromMillis AND m.timelineSortMillis<:toMillis
            AND NOT EXISTS (SELECT 1 FROM archived_media a
                WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)
            ORDER BY m.timelineSortMillis DESC, m.mediaStoreId DESC, m.volumeName DESC LIMIT 1""",
    )
    suspend fun highlightCover(fromMillis: Long, toMillis: Long): MediaItemEntity?

    @Query(
        """SELECT m.volumeName AS volumeName, m.bucketId AS bucketId,
            MAX(m.bucketDisplayName) AS displayName, MAX(m.relativePath) AS relativePath,
            COUNT(*) AS itemCount,
            MAX(m.timelineSortMillis) AS latestSortMillis,
            (SELECT cover.mediaStoreId FROM media_items cover
                WHERE cover.volumeName=m.volumeName AND cover.bucketId=m.bucketId
                    AND cover.isAccessible=1 AND cover.isTrashed=0
                    AND NOT EXISTS (SELECT 1 FROM archived_media ca
                        WHERE ca.volumeName=cover.volumeName AND ca.mediaStoreId=cover.mediaStoreId)
                ORDER BY cover.timelineSortMillis DESC LIMIT 1) AS coverMediaStoreId,
            1 AS isAvailable
            FROM media_items m
            WHERE m.bucketId IS NOT NULL AND m.isAccessible=1 AND m.isTrashed=0
                AND (LOWER(COALESCE(m.bucketDisplayName,'')) LIKE '%selfie%'
                    OR LOWER(COALESCE(m.relativePath,'')) LIKE '%selfie%')
                AND NOT EXISTS (SELECT 1 FROM archived_media a
                    WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)
            GROUP BY m.volumeName,m.bucketId ORDER BY latestSortMillis DESC""",
    )
    suspend fun selfieFolders(): List<PhysicalAlbumRow>

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
            MAX(m.relativePath) AS relativePath,
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

    @Query(
        """
        SELECT m.volumeName, m.bucketId, MAX(m.bucketDisplayName) AS displayName,
            MAX(m.relativePath) AS relativePath,
            COUNT(*) AS itemCount, MAX(m.timelineSortMillis) AS latestSortMillis,
            (SELECT cover.mediaStoreId FROM media_items AS cover
             WHERE cover.volumeName=m.volumeName AND cover.bucketId=m.bucketId AND cover.isTrashed=0
             ORDER BY cover.isAccessible DESC, cover.timelineSortMillis DESC, cover.mediaStoreId DESC LIMIT 1)
                AS coverMediaStoreId,
            MAX(m.isAccessible) AS isAvailable
        FROM media_items AS m
        WHERE m.bucketId IS NOT NULL AND m.isTrashed=0
        GROUP BY m.volumeName, m.bucketId
        ORDER BY latestSortMillis DESC, m.bucketId DESC, m.volumeName DESC
        """,
    )
    fun physicalAlbumOptions(): Flow<List<PhysicalAlbumRow>>

    @Insert
    suspend fun insertVirtualAlbum(album: VirtualAlbumEntity): Long

    @Query("SELECT * FROM virtual_albums WHERE albumId = :albumId")
    suspend fun virtualAlbum(albumId: Long): VirtualAlbumEntity?

    /** One atomic statement validates membership/access at the write, or clears explicitly. */
    @Query("""
        UPDATE virtual_albums SET chosenCoverVolumeName=:volumeName,
            chosenCoverMediaStoreId=:mediaStoreId, updatedAtMillis=:updatedAtMillis
        WHERE albumId=:albumId AND (
            (:volumeName IS NULL AND :mediaStoreId IS NULL) OR EXISTS (
                SELECT 1 FROM virtual_album_media vm JOIN media_items m
                ON m.volumeName=vm.volumeName AND m.mediaStoreId=vm.mediaStoreId
                WHERE vm.albumId=:albumId AND vm.volumeName=:volumeName AND vm.mediaStoreId=:mediaStoreId
                AND m.isAccessible=1 AND m.isTrashed=0))
    """)
    suspend fun setVirtualAlbumCover(albumId: Long, volumeName: String?, mediaStoreId: Long?, updatedAtMillis: Long): Int

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
             JOIN virtual_albums choice ON choice.albumId=vm2.albumId
             JOIN media_items m2 ON m2.volumeName = vm2.volumeName AND m2.mediaStoreId = vm2.mediaStoreId
             WHERE vm2.albumId = a.albumId AND m2.isAccessible = 1 AND m2.isTrashed = 0
             ORDER BY CASE WHEN vm2.volumeName=choice.chosenCoverVolumeName AND vm2.mediaStoreId=choice.chosenCoverMediaStoreId THEN 0 ELSE 1 END,
                m2.timelineSortMillis DESC, m2.mediaStoreId DESC, m2.volumeName DESC LIMIT 1) AS coverVolumeName,
            (SELECT vm2.mediaStoreId FROM virtual_album_media vm2
             JOIN virtual_albums choice ON choice.albumId=vm2.albumId
             JOIN media_items m2 ON m2.volumeName = vm2.volumeName AND m2.mediaStoreId = vm2.mediaStoreId
             WHERE vm2.albumId = a.albumId AND m2.isAccessible = 1 AND m2.isTrashed = 0
             ORDER BY CASE WHEN vm2.volumeName=choice.chosenCoverVolumeName AND vm2.mediaStoreId=choice.chosenCoverMediaStoreId THEN 0 ELSE 1 END,
                m2.timelineSortMillis DESC, m2.mediaStoreId DESC, m2.volumeName DESC LIMIT 1) AS coverMediaStoreId
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
