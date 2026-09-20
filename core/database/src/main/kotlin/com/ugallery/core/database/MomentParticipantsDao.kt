package com.ugallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Current, visible local identities; no new recognition is performed by this selector. */
private const val ParticipantPeopleCte = MemoryVisibleCte + """
, participant_people_base AS (
SELECT DISTINCT c.clusterId,c.displayName,m.volumeName,m.mediaStoreId,m.generationModified
FROM person_clusters c JOIN person_memberships p ON p.clusterId=c.clusterId AND p.algorithmVersion=c.algorithmVersion
JOIN memory_base m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId
JOIN face_detection_runs r ON r.volumeName=p.volumeName AND r.mediaStoreId=p.mediaStoreId AND r.generationModified=m.generationModified
JOIN detected_faces f ON f.volumeName=p.volumeName AND f.mediaStoreId=p.mediaStoreId AND f.faceOrdinal=p.faceOrdinal AND f.modelVersion=r.modelVersion
JOIN face_embeddings e ON e.volumeName=p.volumeName AND e.mediaStoreId=p.mediaStoreId AND e.faceOrdinal=p.faceOrdinal AND e.detectionModelVersion=r.modelVersion
WHERE c.isHidden=0 AND c.algorithmVersion=:algorithmVersion
), participant_people AS (
SELECT m.* FROM participant_people_base m WHERE """ + MemoryNotConfirmedDocument + """
OR EXISTS (SELECT 1 FROM memory_visible visible WHERE visible.visibleMomentId=:momentId
AND visible.volumeName=m.volumeName AND visible.mediaStoreId=m.mediaStoreId)
)
"""

@Dao
interface MomentParticipantsDao {
    @Query("SELECT * FROM moment_participant_state WHERE momentId=:momentId")
    suspend fun state(momentId: String): MomentParticipantStateEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initialize(state: MomentParticipantStateEntity)
    @Query("UPDATE moment_participant_state SET mode=:mode, revision=revision+1 WHERE momentId=:momentId AND revision=:expectedRevision")
    suspend fun compareAndSet(momentId: String, expectedRevision: Long, mode: String): Int
    @Query("SELECT clusterId FROM moment_participants WHERE momentId=:momentId ORDER BY clusterId")
    suspend fun selected(momentId: String): List<String>
    @Query("DELETE FROM moment_participants WHERE momentId=:momentId")
    suspend fun clear(momentId: String)
    @Insert suspend fun insert(rows: List<MomentParticipantEntity>)
    @Query("UPDATE moments SET isUserEdited=1,updatedAtMillis=:now WHERE momentId=:momentId")
    suspend fun markEdited(momentId: String, now: Long)
    @Query(ParticipantPeopleCte + "SELECT p.clusterId,p.displayName,COUNT(*) AS memberCount," +
        "(SELECT volumeName FROM participant_people x WHERE x.clusterId=p.clusterId ORDER BY volumeName,mediaStoreId LIMIT 1) AS coverVolumeName," +
        "(SELECT mediaStoreId FROM participant_people x WHERE x.clusterId=p.clusterId ORDER BY volumeName,mediaStoreId LIMIT 1) AS coverMediaStoreId," +
        "(SELECT generationModified FROM participant_people x WHERE x.clusterId=p.clusterId ORDER BY volumeName,mediaStoreId LIMIT 1) AS coverGenerationModified " +
        "FROM participant_people p GROUP BY p.clusterId ORDER BY p.displayName COLLATE NOCASE,p.clusterId")
    suspend fun people(algorithmVersion: String, momentId: String? = null): List<MomentParticipantPersonRow>
    @Query(ParticipantPeopleCte + "SELECT DISTINCT p.clusterId FROM participant_people_base p JOIN moment_members mm ON " +
        "mm.volumeName=p.volumeName AND mm.mediaStoreId=p.mediaStoreId JOIN memory_visible visible ON visible.visibleMomentId=mm.momentId AND visible.volumeName=mm.volumeName AND visible.mediaStoreId=mm.mediaStoreId WHERE mm.momentId=:momentId ORDER BY p.clusterId")
    suspend fun automatic(momentId: String, algorithmVersion: String): List<String>
}
