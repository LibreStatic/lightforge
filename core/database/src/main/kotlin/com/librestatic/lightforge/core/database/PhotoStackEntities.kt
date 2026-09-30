package com.librestatic.lightforge.core.database

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photo_stacks")
data class PhotoStackEntity(
    @PrimaryKey val stackId: String,
    val title: String?,
    val coverVolumeName: String,
    val coverMediaStoreId: Long,
    val revision: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "photo_stack_members",
    primaryKeys = ["volumeName", "mediaStoreId"],
    indices = [Index(value = ["stackId", "ordinal"])],
    foreignKeys =
        [
            ForeignKey(
                entity = PhotoStackEntity::class,
                parentColumns = ["stackId"],
                childColumns = ["stackId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["volumeName", "mediaStoreId"],
                childColumns = ["volumeName", "mediaStoreId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class PhotoStackMemberEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val stackId: String,
    val ordinal: Int,
)

/** Explicit separation is independent of replaceable similarity analysis. */
@Entity(
    tableName = "photo_stack_exclusions",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys =
        [
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["volumeName", "mediaStoreId"],
                childColumns = ["volumeName", "mediaStoreId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class PhotoStackExclusionEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val separatedAtMillis: Long,
)

data class PhotoStackPhoto(
    @Embedded val media: MediaItemEntity,
    val detailScore: Float?,
    val similarity: Float?,
)

data class PhotoStackSummary(
    @Embedded val stack: PhotoStackEntity,
    val totalCount: Long,
    val availableCount: Long,
    val displayCoverVolumeName: String?,
    val displayCoverMediaStoreId: Long?,
    val displayCoverGeneration: Long?,
)

data class PhotoStackSuggestion(
    val clusterId: String,
    val algorithmVersion: String,
    val memberCount: Long,
    val recommendedVolumeName: String,
    val recommendedMediaStoreId: Long,
)

internal const val StackSuggestionFrom =
    """ FROM similarity_memberships sm
JOIN media_items m ON m.volumeName=sm.volumeName AND m.mediaStoreId=sm.mediaStoreId
JOIN similarity_features f ON f.volumeName=m.volumeName AND f.mediaStoreId=m.mediaStoreId AND f.generationModified=m.generationModified
WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1
AND NOT EXISTS(SELECT 1 FROM photo_stack_members p WHERE p.volumeName=m.volumeName AND p.mediaStoreId=m.mediaStoreId)
AND NOT EXISTS(SELECT 1 FROM photo_stack_exclusions x WHERE x.volumeName=m.volumeName AND x.mediaStoreId=m.mediaStoreId)
AND NOT EXISTS(SELECT 1 FROM similarity_exclusions x WHERE x.volumeName=m.volumeName AND x.mediaStoreId=m.mediaStoreId) """

@Dao
interface PhotoStackDao {
    @Query(
        """WITH ranked AS (
        SELECT p.stackId,m.volumeName,m.mediaStoreId,m.generationModified,
            COUNT(*) OVER(PARTITION BY p.stackId) AS availableCount,
            ROW_NUMBER() OVER(PARTITION BY p.stackId ORDER BY
                (m.volumeName=s.coverVolumeName AND m.mediaStoreId=s.coverMediaStoreId) DESC,p.ordinal,m.volumeName,m.mediaStoreId) AS keepRank
        FROM photo_stack_members p JOIN photo_stacks s ON s.stackId=p.stackId
        JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId
        WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1)
        SELECT s.*, (SELECT COUNT(*) FROM photo_stack_members p WHERE p.stackId=s.stackId) AS totalCount,
            coalesce(r.availableCount,0) AS availableCount, r.volumeName AS displayCoverVolumeName,
            r.mediaStoreId AS displayCoverMediaStoreId, r.generationModified AS displayCoverGeneration
        FROM photo_stacks s LEFT JOIN ranked r ON r.stackId=s.stackId AND r.keepRank=1
        ORDER BY s.updatedAtMillis DESC,s.stackId"""
    )
    fun pages(): PagingSource<Int, PhotoStackSummary>

    @Query(
        """WITH ranked AS (
        SELECT sm.clusterId,f.algorithmVersion,m.volumeName AS recommendedVolumeName,m.mediaStoreId AS recommendedMediaStoreId,
            COUNT(*) OVER(PARTITION BY sm.clusterId,f.algorithmVersion) AS memberCount,
            ROW_NUMBER() OVER(PARTITION BY sm.clusterId,f.algorithmVersion ORDER BY m.isFavorite DESC,
                f.blurScore DESC,(CAST(m.width AS INTEGER)*m.height) DESC,sm.bestScore DESC,m.volumeName,m.mediaStoreId) AS keepRank
        """ +
            StackSuggestionFrom +
            """ )
        SELECT clusterId,algorithmVersion,memberCount,recommendedVolumeName,recommendedMediaStoreId
        FROM ranked WHERE keepRank=1 AND memberCount BETWEEN 2 AND 500 ORDER BY clusterId,algorithmVersion"""
    )
    fun suggestions(): PagingSource<Int, PhotoStackSuggestion>

    @Query(
        "SELECT m.*,f.blurScore AS detailScore,sm.bestScore AS similarity " +
            StackSuggestionFrom +
            " AND sm.clusterId=:clusterId AND f.algorithmVersion=:algorithmVersion ORDER BY m.volumeName,m.mediaStoreId LIMIT 501"
    )
    suspend fun suggestionMembers(
        clusterId: String,
        algorithmVersion: String,
    ): List<PhotoStackPhoto>

    @Query("SELECT * FROM photo_stacks WHERE stackId=:id")
    fun observe(id: String): Flow<PhotoStackEntity?>

    @Query("SELECT * FROM photo_stacks WHERE stackId=:id")
    suspend fun get(id: String): PhotoStackEntity?

    @Query("SELECT COUNT(*) FROM photo_stacks") fun count(): Flow<Long>

    @Insert suspend fun insert(stack: PhotoStackEntity)

    @Update suspend fun update(stack: PhotoStackEntity)

    @Insert suspend fun insertMembers(members: List<PhotoStackMemberEntity>)

    @Query(
        "SELECT * FROM photo_stack_members WHERE stackId=:id ORDER BY ordinal,volumeName,mediaStoreId LIMIT 501"
    )
    suspend fun rawMembers(id: String): List<PhotoStackMemberEntity>

    @Query("SELECT * FROM photo_stack_members WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun membership(volume: String, id: Long): PhotoStackMemberEntity?

    @Query("DELETE FROM photo_stack_members WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun removeMember(volume: String, id: Long)

    @Query("DELETE FROM photo_stacks WHERE stackId=:id") suspend fun dissolve(id: String)

    @Upsert suspend fun exclude(exclusion: PhotoStackExclusionEntity)

    @Query("DELETE FROM photo_stack_exclusions WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun allowSuggestion(volume: String, id: Long)

    @Query(
        "SELECT m.* FROM photo_stack_exclusions e JOIN media_items m ON m.volumeName=e.volumeName AND m.mediaStoreId=e.mediaStoreId WHERE m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1 ORDER BY e.separatedAtMillis DESC,m.volumeName,m.mediaStoreId"
    )
    fun separated(): PagingSource<Int, MediaItemEntity>

    @Query(
        """SELECT m.*, f.blurScore AS detailScore,
        CASE WHEN f.mediaStoreId IS NOT NULL THEN sm.bestScore ELSE NULL END AS similarity
        FROM photo_stack_members p JOIN media_items m ON m.volumeName=p.volumeName AND m.mediaStoreId=p.mediaStoreId
        LEFT JOIN similarity_features f ON f.volumeName=m.volumeName AND f.mediaStoreId=m.mediaStoreId AND f.generationModified=m.generationModified
        LEFT JOIN similarity_memberships sm ON sm.volumeName=f.volumeName AND sm.mediaStoreId=f.mediaStoreId
        WHERE p.stackId=:id AND m.isAccessible=1 AND m.isTrashed=0 AND m.mediaType=1
        ORDER BY p.ordinal,m.volumeName,m.mediaStoreId LIMIT 500"""
    )
    fun members(id: String): Flow<List<PhotoStackPhoto>>
}
