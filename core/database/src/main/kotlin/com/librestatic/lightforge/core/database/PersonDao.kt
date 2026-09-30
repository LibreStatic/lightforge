package com.librestatic.lightforge.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Query(
        "SELECT c.*, COUNT(p.faceOrdinal) AS visibleMemberCount, " +
            "(SELECT pm.volumeName FROM person_memberships pm JOIN media_items m ON " +
            "m.volumeName=pm.volumeName AND m.mediaStoreId=pm.mediaStoreId WHERE pm.clusterId=c.clusterId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY pm.similarity DESC, pm.volumeName, pm.mediaStoreId LIMIT 1) " +
            "AS coverVolumeName, " +
            "(SELECT pm.mediaStoreId FROM person_memberships pm JOIN media_items m ON " +
            "m.volumeName=pm.volumeName AND m.mediaStoreId=pm.mediaStoreId WHERE pm.clusterId=c.clusterId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY pm.similarity DESC, pm.volumeName, pm.mediaStoreId LIMIT 1) " +
            "AS coverMediaStoreId FROM person_clusters c JOIN person_memberships p ON p.clusterId=c.clusterId " +
            "JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId " +
            "WHERE c.algorithmVersion=:algorithmVersion AND c.isHidden=0 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "GROUP BY c.clusterId HAVING visibleMemberCount>0 ORDER BY visibleMemberCount DESC, c.updatedAtMillis DESC LIMIT :limit",
    )
    fun visiblePersonSummaries(algorithmVersion: String, limit: Int): Flow<List<PersonClusterSummaryRow>>

    @Query(
        "SELECT c.*, COUNT(p.faceOrdinal) AS visibleMemberCount, " +
            "(SELECT pm.volumeName FROM person_memberships pm JOIN media_items m ON " +
            "m.volumeName=pm.volumeName AND m.mediaStoreId=pm.mediaStoreId WHERE pm.clusterId=c.clusterId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY pm.similarity DESC, pm.volumeName, pm.mediaStoreId LIMIT 1) " +
            "AS coverVolumeName, " +
            "(SELECT pm.mediaStoreId FROM person_memberships pm JOIN media_items m ON " +
            "m.volumeName=pm.volumeName AND m.mediaStoreId=pm.mediaStoreId WHERE pm.clusterId=c.clusterId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY pm.similarity DESC, pm.volumeName, pm.mediaStoreId LIMIT 1) " +
            "AS coverMediaStoreId FROM person_clusters c JOIN person_memberships p ON p.clusterId=c.clusterId " +
            "JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId " +
            "WHERE c.algorithmVersion=:algorithmVersion AND c.isHidden=1 AND m.isAccessible=1 AND m.isTrashed=0 " +
            "GROUP BY c.clusterId HAVING visibleMemberCount>0 ORDER BY c.updatedAtMillis DESC LIMIT :limit",
    )
    fun hiddenPersonSummaries(algorithmVersion: String, limit: Int): Flow<List<PersonClusterSummaryRow>>

    @Query(
        "SELECT p.*,m.volumeName AS media_volumeName,m.mediaStoreId AS media_mediaStoreId," +
            "m.mediaType AS media_mediaType,m.mimeType AS media_mimeType,m.displayName AS media_displayName," +
            "m.sizeBytes AS media_sizeBytes,m.width AS media_width,m.height AS media_height," +
            "m.durationMillis AS media_durationMillis,m.orientationDegrees AS media_orientationDegrees," +
            "m.dateTakenMillis AS media_dateTakenMillis,m.dateAddedSeconds AS media_dateAddedSeconds," +
            "m.dateModifiedSeconds AS media_dateModifiedSeconds,m.timelineSortMillis AS media_timelineSortMillis," +
            "m.generationAdded AS media_generationAdded,m.generationModified AS media_generationModified," +
            "m.bucketId AS media_bucketId,m.bucketDisplayName AS media_bucketDisplayName," +
            "m.relativePath AS media_relativePath,m.isFavorite AS media_isFavorite,m.isTrashed AS media_isTrashed," +
            "m.isAccessible AS media_isAccessible,m.lastSeenScanId AS media_lastSeenScanId," +
            "m.dateExpiresSeconds AS media_dateExpiresSeconds FROM person_memberships p JOIN media_items m ON " +
            "m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId WHERE p.clusterId=:clusterId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY p.similarity DESC, p.volumeName, p.mediaStoreId LIMIT :limit",
    )
    suspend fun visibleClusterMembers(clusterId: String, limit: Int): List<PersonMembershipMediaRow>

    @Query(
        "SELECT mm.*,m.volumeName AS media_volumeName,m.mediaStoreId AS media_mediaStoreId," +
            "m.mediaType AS media_mediaType,m.mimeType AS media_mimeType,m.displayName AS media_displayName," +
            "m.sizeBytes AS media_sizeBytes,m.width AS media_width,m.height AS media_height," +
            "m.durationMillis AS media_durationMillis,m.orientationDegrees AS media_orientationDegrees," +
            "m.dateTakenMillis AS media_dateTakenMillis,m.dateAddedSeconds AS media_dateAddedSeconds," +
            "m.dateModifiedSeconds AS media_dateModifiedSeconds,m.timelineSortMillis AS media_timelineSortMillis," +
            "m.generationAdded AS media_generationAdded,m.generationModified AS media_generationModified," +
            "m.bucketId AS media_bucketId,m.bucketDisplayName AS media_bucketDisplayName," +
            "m.relativePath AS media_relativePath,m.isFavorite AS media_isFavorite,m.isTrashed AS media_isTrashed," +
            "m.isAccessible AS media_isAccessible,m.lastSeenScanId AS media_lastSeenScanId," +
            "m.dateExpiresSeconds AS media_dateExpiresSeconds FROM me_matches mm JOIN media_items m ON " +
            "m.volumeName=mm.volumeName AND m.mediaStoreId=mm.mediaStoreId WHERE mm.profileId=:profileId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY mm.similarity DESC LIMIT :limit",
    )
    suspend fun visibleMeMatches(profileId: Int = 0, limit: Int): List<MeMatchMediaRow>

    @Query("SELECT * FROM face_embeddings WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId AND faceOrdinal=:faceOrdinal")
    suspend fun embedding(volumeName: String, mediaStoreId: Long, faceOrdinal: Int): FaceEmbeddingEntity?

    @Query(
        "SELECT * FROM face_embeddings WHERE embeddingModelVersion=:modelVersion AND " +
            "(:afterVolume IS NULL OR volumeName>:afterVolume OR (volumeName=:afterVolume AND mediaStoreId>:afterId) OR " +
            "(volumeName=:afterVolume AND mediaStoreId=:afterId AND faceOrdinal>:afterOrdinal)) " +
            "ORDER BY volumeName, mediaStoreId, faceOrdinal LIMIT :limit",
    )
    suspend fun embeddingPage(
        modelVersion: String,
        afterVolume: String?,
        afterId: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<FaceEmbeddingEntity>

    @Query(
        "SELECT m.* FROM media_items m WHERE m.isAccessible=1 AND m.isTrashed=0 AND EXISTS (" +
            "SELECT 1 FROM face_embeddings e WHERE e.volumeName=m.volumeName AND e.mediaStoreId=m.mediaStoreId " +
            "AND e.embeddingModelVersion=:embeddingModelVersion AND NOT EXISTS (" +
            "SELECT 1 FROM person_memberships p WHERE p.volumeName=e.volumeName AND p.mediaStoreId=e.mediaStoreId " +
            "AND p.faceOrdinal=e.faceOrdinal AND p.algorithmVersion=:algorithmVersion)) " +
            "ORDER BY m.volumeName, m.mediaStoreId LIMIT :limit",
    )
    suspend fun pendingPersonClusterMedia(
        embeddingModelVersion: String,
        algorithmVersion: String,
        limit: Int,
    ): List<MediaItemEntity>

    @Query("SELECT * FROM face_embeddings WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId ORDER BY faceOrdinal")
    suspend fun mediaEmbeddings(volumeName: String, mediaStoreId: Long): List<FaceEmbeddingEntity>

    @Query(
        "SELECT DISTINCT c.* FROM person_clusters c INNER JOIN person_cluster_projections p " +
            "ON p.clusterId=c.clusterId WHERE c.algorithmVersion=:algorithmVersion AND p.band=:band " +
            "AND p.q0 BETWEEN :q0Min AND :q0Max AND p.q1 BETWEEN :q1Min AND :q1Max " +
            "AND p.q2 BETWEEN :q2Min AND :q2Max AND p.q3 BETWEEN :q3Min AND :q3Max " +
            "AND p.q4 BETWEEN :q4Min AND :q4Max AND p.q5 BETWEEN :q5Min AND :q5Max " +
            "ORDER BY c.updatedAtMillis DESC LIMIT :limit",
    )
    suspend fun projectionCandidates(
        algorithmVersion: String,
        band: Int,
        q0Min: Int, q0Max: Int,
        q1Min: Int, q1Max: Int,
        q2Min: Int, q2Max: Int,
        q3Min: Int, q3Max: Int,
        q4Min: Int, q4Max: Int,
        q5Min: Int, q5Max: Int,
        limit: Int,
    ): List<PersonClusterEntity>

    @Query(
        "SELECT e.* FROM person_memberships p INNER JOIN face_embeddings e ON " +
            "e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal " +
            "WHERE p.clusterId=:clusterId ORDER BY p.similarity ASC LIMIT :limit",
    )
    suspend fun boundaryEmbeddings(clusterId: String, limit: Int): List<FaceEmbeddingEntity>

    @Query(
        "SELECT relation, preferredClusterId, CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightVolumeName ELSE leftVolumeName END AS otherVolumeName, " +
            "CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightMediaStoreId ELSE leftMediaStoreId END AS otherMediaStoreId, " +
            "CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightFaceOrdinal ELSE leftFaceOrdinal END AS otherFaceOrdinal, " +
            "p.clusterId AS otherClusterId FROM person_constraints c LEFT JOIN person_memberships p ON " +
            "p.volumeName=(CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightVolumeName ELSE leftVolumeName END) AND " +
            "p.mediaStoreId=(CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightMediaStoreId ELSE leftMediaStoreId END) AND " +
            "p.faceOrdinal=(CASE WHEN leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId " +
            "AND leftFaceOrdinal=:faceOrdinal THEN rightFaceOrdinal ELSE leftFaceOrdinal END) WHERE " +
            "(leftVolumeName=:volumeName AND leftMediaStoreId=:mediaStoreId AND leftFaceOrdinal=:faceOrdinal) OR " +
            "(rightVolumeName=:volumeName AND rightMediaStoreId=:mediaStoreId AND rightFaceOrdinal=:faceOrdinal)",
    )
    suspend fun constraintsForFace(volumeName: String, mediaStoreId: Long, faceOrdinal: Int): List<PersonConstraintWithMembership>

    @Upsert
    suspend fun upsertCluster(cluster: PersonClusterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembership(membership: PersonMembershipEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjections(projections: List<PersonClusterProjectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConstraints(constraints: List<PersonConstraintEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFaceOverrides(overrides: List<PersonFaceOverrideEntity>)

    @Query("SELECT clusterId FROM person_face_overrides WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId AND faceOrdinal=:faceOrdinal")
    suspend fun forcedClusterId(volumeName: String, mediaStoreId: Long, faceOrdinal: Int): String?

    @Query("SELECT * FROM person_clusters WHERE clusterId=:clusterId")
    suspend fun cluster(clusterId: String): PersonClusterEntity?

    @Query("SELECT * FROM person_memberships WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId AND faceOrdinal=:faceOrdinal")
    suspend fun membership(volumeName: String, mediaStoreId: Long, faceOrdinal: Int): PersonMembershipEntity?

    @Query("SELECT * FROM person_memberships WHERE clusterId=:clusterId ORDER BY volumeName, mediaStoreId, faceOrdinal LIMIT :limit")
    suspend fun clusterMemberships(clusterId: String, limit: Int): List<PersonMembershipEntity>

    @Query(
        "SELECT * FROM person_memberships WHERE clusterId=:clusterId AND " +
            "(:afterVolume IS NULL OR volumeName>:afterVolume OR (volumeName=:afterVolume AND mediaStoreId>:afterId) OR " +
            "(volumeName=:afterVolume AND mediaStoreId=:afterId AND faceOrdinal>:afterOrdinal)) " +
            "ORDER BY volumeName, mediaStoreId, faceOrdinal LIMIT :limit",
    )
    suspend fun clusterMembershipPage(
        clusterId: String,
        afterVolume: String?,
        afterId: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<PersonMembershipEntity>

    @Query(
        "SELECT e.* FROM person_memberships p INNER JOIN face_embeddings e ON " +
            "e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal " +
            "WHERE p.clusterId=:clusterId AND (:afterVolume IS NULL OR e.volumeName>:afterVolume OR " +
            "(e.volumeName=:afterVolume AND e.mediaStoreId>:afterId) OR " +
            "(e.volumeName=:afterVolume AND e.mediaStoreId=:afterId AND e.faceOrdinal>:afterOrdinal)) " +
            "ORDER BY e.volumeName, e.mediaStoreId, e.faceOrdinal LIMIT :limit",
    )
    suspend fun clusterEmbeddingPage(
        clusterId: String,
        afterVolume: String?,
        afterId: Long,
        afterOrdinal: Int,
        limit: Int,
    ): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM person_memberships WHERE clusterId=:clusterId ORDER BY volumeName, mediaStoreId, faceOrdinal LIMIT 1")
    suspend fun clusterAnchor(clusterId: String): PersonMembershipEntity?

    @Query("SELECT COUNT(*) FROM person_memberships WHERE clusterId=:clusterId")
    suspend fun clusterMembershipCount(clusterId: String): Int

    @Query("UPDATE person_memberships SET clusterId=:targetClusterId, assignmentSource='MANUAL' WHERE clusterId=:sourceClusterId")
    suspend fun moveClusterMemberships(sourceClusterId: String, targetClusterId: String): Int

    @Query("UPDATE person_memberships SET clusterId=:targetClusterId, assignmentSource='MANUAL' WHERE volumeName=:volumeName AND mediaStoreId=:mediaStoreId AND faceOrdinal=:faceOrdinal")
    suspend fun moveMembership(volumeName: String, mediaStoreId: Long, faceOrdinal: Int, targetClusterId: String): Int

    @Query("UPDATE person_clusters SET displayName=:name, isUserEdited=1, updatedAtMillis=:updatedAt WHERE clusterId=:clusterId")
    suspend fun rename(clusterId: String, name: String?, updatedAt: Long): Int

    @Query("UPDATE person_clusters SET isHidden=:hidden, isUserEdited=1, updatedAtMillis=:updatedAt WHERE clusterId=:clusterId")
    suspend fun setHidden(clusterId: String, hidden: Boolean, updatedAt: Long): Int

    @Query("UPDATE person_clusters SET isUserEdited=1, updatedAtMillis=:updatedAt WHERE clusterId=:clusterId")
    suspend fun markEdited(clusterId: String, updatedAt: Long): Int

    @Query("DELETE FROM person_clusters WHERE clusterId=:clusterId")
    suspend fun deleteCluster(clusterId: String): Int

    @Query("DELETE FROM person_cluster_projections WHERE clusterId=:clusterId")
    suspend fun deleteProjections(clusterId: String): Int

    @Query("DELETE FROM person_memberships WHERE algorithmVersion=:algorithmVersion AND assignmentSource='AUTO'")
    suspend fun purgeAutomaticMemberships(algorithmVersion: String): Int

    @Query("DELETE FROM person_clusters WHERE algorithmVersion=:algorithmVersion AND isUserEdited=0 AND NOT EXISTS (SELECT 1 FROM person_memberships p WHERE p.clusterId=person_clusters.clusterId)")
    suspend fun deleteEmptyAutomaticClusters(algorithmVersion: String): Int

    @Query("DELETE FROM person_memberships")
    suspend fun purgeMemberships(): Int

    @Query("DELETE FROM person_clusters")
    suspend fun purgeClusters(): Int

    @Query("DELETE FROM person_constraints")
    suspend fun purgeConstraints(): Int

    @Query("DELETE FROM person_face_overrides")
    suspend fun purgeFaceOverrides(): Int

    @Upsert
    suspend fun upsertMeProfile(profile: MeProfileEntity)

    @Query("SELECT * FROM me_profiles WHERE profileId=:profileId")
    suspend fun meProfile(profileId: Int = 0): MeProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeReferences(references: List<MeReferenceEntity>)

    @Query("SELECT COUNT(*) FROM me_references WHERE profileId=:profileId")
    suspend fun meReferenceCount(profileId: Int = 0): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeMatches(matches: List<MeMatchEntity>)

    @Query("SELECT COUNT(*) FROM me_matches WHERE profileId=:profileId")
    suspend fun meMatchCount(profileId: Int = 0): Int

    @Query("DELETE FROM me_matches WHERE profileId=:profileId")
    suspend fun deleteMeMatches(profileId: Int = 0): Int

    @Query("DELETE FROM me_references WHERE profileId=:profileId")
    suspend fun deleteMeReferences(profileId: Int = 0): Int

    @Query("DELETE FROM me_profiles WHERE profileId=:profileId")
    suspend fun deleteMeProfile(profileId: Int = 0): Int
}
