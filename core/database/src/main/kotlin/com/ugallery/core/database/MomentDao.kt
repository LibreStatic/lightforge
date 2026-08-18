package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MomentDao {
    @Query(
        "SELECT m.*, CASE WHEN e.locationReadWithPermission=1 AND e.generationModified=m.generationModified " +
            "THEN e.latitude END AS latitude, CASE WHEN e.locationReadWithPermission=1 " +
            "AND e.generationModified=m.generationModified THEN e.longitude END AS longitude, " +
            "f.blurScore AS blurScore, f.pHash AS pHash, sm.clusterId AS similarityClusterId " +
            "FROM media_items m LEFT JOIN media_exif_cache e ON e.volumeName=m.volumeName " +
            "AND e.mediaStoreId=m.mediaStoreId LEFT JOIN similarity_features f ON " +
            "f.volumeName=m.volumeName AND f.mediaStoreId=m.mediaStoreId AND " +
            "f.generationModified=m.generationModified LEFT JOIN similarity_memberships sm ON " +
            "sm.volumeName=m.volumeName AND sm.mediaStoreId=m.mediaStoreId " +
            "WHERE m.mediaType=1 AND m.isAccessible=1 AND m.isTrashed=0 AND " +
            "(:afterMillis IS NULL OR m.timelineSortMillis>:afterMillis OR " +
            "(m.timelineSortMillis=:afterMillis AND m.mediaStoreId>:afterId) OR " +
            "(m.timelineSortMillis=:afterMillis AND m.mediaStoreId=:afterId AND m.volumeName>:afterVolume)) " +
            "ORDER BY m.timelineSortMillis ASC,m.mediaStoreId ASC,m.volumeName ASC LIMIT :limit",
    )
    suspend fun candidatePage(
        afterMillis: Long?, afterId: Long?, afterVolume: String?, limit: Int,
    ): List<MomentCandidateRow>

    @Query("SELECT * FROM moment_runs WHERE algorithmVersion=:version")
    suspend fun run(version: String): MomentRunEntity?

    @Upsert suspend fun upsertRun(run: MomentRunEntity)

    @Query("DELETE FROM moment_runs WHERE algorithmVersion=:version")
    suspend fun deleteRun(version: String): Int

    @Query("SELECT * FROM moment_run_candidates WHERE algorithmVersion=:version ORDER BY rank")
    suspend fun stagedCandidates(version: String): List<MomentRunCandidateEntity>

    @Query("DELETE FROM moment_run_candidates WHERE algorithmVersion=:version")
    suspend fun clearStagedCandidates(version: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStagedCandidates(candidates: List<MomentRunCandidateEntity>)

    @Transaction
    suspend fun checkpointRun(run: MomentRunEntity, candidates: List<MomentRunCandidateEntity>) {
        upsertRun(run)
        clearStagedCandidates(run.algorithmVersion)
        if (candidates.isNotEmpty()) insertStagedCandidates(candidates)
    }

    @Query("SELECT * FROM moments WHERE momentId=:momentId")
    suspend fun moment(momentId: String): MomentEntity?

    @Upsert suspend fun upsertMoment(moment: MomentEntity)

    @Query("DELETE FROM moment_members WHERE momentId=:momentId")
    suspend fun deleteMembers(momentId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<MomentMemberEntity>)

    @Upsert suspend fun upsertCover(cover: MomentCoverEntity)

    @Query("DELETE FROM moment_covers WHERE momentId=:momentId")
    suspend fun deleteCover(momentId: String): Int

    @Transaction
    suspend fun replaceGeneratedMoment(
        moment: MomentEntity,
        members: List<MomentMemberEntity>,
        cover: MomentCoverEntity,
    ): Boolean {
        if (moment(moment.momentId)?.isUserEdited == true) return false
        upsertMoment(moment)
        deleteMembers(moment.momentId)
        deleteCover(moment.momentId)
        insertMembers(members)
        upsertCover(cover)
        return true
    }

    @Query(
        "SELECT mo.*,COUNT(mm.mediaStoreId) AS memberCount," +
            "COALESCE(CASE WHEN cm.mediaStoreId IS NOT NULL THEN mc.volumeName END," +
            "(SELECT x.volumeName FROM moment_members x JOIN media_items xm " +
            "ON xm.volumeName=x.volumeName AND xm.mediaStoreId=x.mediaStoreId WHERE x.momentId=mo.momentId " +
            "AND xm.isAccessible=1 AND xm.isTrashed=0 ORDER BY x.ordinal LIMIT 1)) AS coverVolumeName," +
            "COALESCE(CASE WHEN cm.mediaStoreId IS NOT NULL THEN mc.mediaStoreId END," +
            "(SELECT x.mediaStoreId FROM moment_members x JOIN media_items xm " +
            "ON xm.volumeName=x.volumeName AND xm.mediaStoreId=x.mediaStoreId WHERE x.momentId=mo.momentId " +
            "AND xm.isAccessible=1 AND xm.isTrashed=0 ORDER BY x.ordinal LIMIT 1)) AS coverMediaStoreId " +
            "FROM moments mo JOIN moment_members mm ON mm.momentId=mo.momentId JOIN media_items m ON " +
            "m.volumeName=mm.volumeName AND m.mediaStoreId=mm.mediaStoreId AND m.isAccessible=1 " +
            "AND m.isTrashed=0 LEFT JOIN moment_covers mc ON mc.momentId=mo.momentId LEFT JOIN media_items cm " +
            "ON cm.volumeName=mc.volumeName AND cm.mediaStoreId=mc.mediaStoreId AND cm.isAccessible=1 " +
            "AND cm.isTrashed=0 WHERE mo.state!='DISMISSED' GROUP BY mo.momentId HAVING COUNT(mm.mediaStoreId)>=3 " +
            "ORDER BY mo.updatedAtMillis DESC LIMIT :limit",
    )
    fun summaries(limit: Int): Flow<List<MomentSummaryRow>>

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
            "m.dateExpiresSeconds AS media_dateExpiresSeconds FROM moment_members mm JOIN media_items m ON " +
            "m.volumeName=mm.volumeName AND m.mediaStoreId=mm.mediaStoreId WHERE mm.momentId=:momentId " +
            "AND m.isAccessible=1 AND m.isTrashed=0 ORDER BY mm.ordinal",
    )
    suspend fun members(momentId: String): List<MomentMemberRow>

    @Query("SELECT * FROM moment_members WHERE momentId=:momentId ORDER BY ordinal")
    suspend fun allMembers(momentId: String): List<MomentMemberEntity>

    @Query("UPDATE moments SET title=:title,titleMode='USER',isUserEdited=1,updatedAtMillis=:now WHERE momentId=:momentId")
    suspend fun rename(momentId: String, title: String, now: Long): Int

    @Query("UPDATE moments SET state='SAVED',isUserEdited=1,updatedAtMillis=:now WHERE momentId=:momentId")
    suspend fun save(momentId: String, now: Long): Int

    @Query("UPDATE moments SET state='DISMISSED',updatedAtMillis=:now WHERE momentId=:momentId")
    suspend fun dismiss(momentId: String, now: Long): Int

    @Query("DELETE FROM moments WHERE momentId=:momentId")
    suspend fun delete(momentId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM moment_members mm JOIN media_items m ON m.volumeName=mm.volumeName " +
        "AND m.mediaStoreId=mm.mediaStoreId WHERE mm.momentId=:momentId AND mm.volumeName=:volumeName " +
        "AND mm.mediaStoreId=:mediaStoreId AND m.isAccessible=1 AND m.isTrashed=0)")
    suspend fun isVisibleMember(momentId: String, volumeName: String, mediaStoreId: Long): Boolean

    @Transaction
    suspend fun setUserCover(momentId: String, volumeName: String, mediaStoreId: Long, now: Long): Boolean {
        if (!isVisibleMember(momentId, volumeName, mediaStoreId)) return false
        upsertCover(MomentCoverEntity(momentId, volumeName, mediaStoreId, true))
        markEdited(momentId, now)
        return true
    }

    @Query("UPDATE moments SET isUserEdited=1,updatedAtMillis=:now WHERE momentId=:momentId")
    suspend fun markEdited(momentId: String, now: Long): Int

    @Transaction
    suspend fun reorder(momentId: String, ordered: List<MomentMemberEntity>, now: Long): Boolean {
        val current = allMembers(momentId)
        if (current.map { it.volumeName to it.mediaStoreId }.toSet() !=
            ordered.map { it.volumeName to it.mediaStoreId }.toSet()
        ) return false
        deleteMembers(momentId)
        insertMembers(ordered.mapIndexed { index, member -> member.copy(ordinal = index) })
        markEdited(momentId, now)
        return true
    }
}
