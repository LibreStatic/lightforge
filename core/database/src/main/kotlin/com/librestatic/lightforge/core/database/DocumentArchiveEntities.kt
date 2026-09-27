package com.librestatic.lightforge.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "document_archive_rule")
data class DocumentArchiveRuleEntity(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val category: String = "Receipt",
    val minimumAgeDays: Int = 30,
    val revision: String = "",
    val lastRunId: String? = null,
    val lastRunMillis: Long? = null,
    val lastRunCount: Int = 0,
    val afterSortMillis: Long? = null,
    val afterMediaStoreId: Long? = null,
    val afterVolumeName: String? = null,
)

/**
 * Also retains keep/undo decisions for this source generation, across rule edits and process death.
 */
@Entity(
    tableName = "document_archive_history",
    primaryKeys = ["volumeName", "mediaStoreId"],
    indices = [Index(value = ["runId"])],
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
data class DocumentArchiveHistoryEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val runId: String,
    val writtenAtMillis: Long?,
)

data class DocumentArchiveCandidate(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val displayName: String?,
    val category: String?,
    val timelineSortMillis: Long,
)

@Dao
interface DocumentArchiveDao {
    @Query("SELECT * FROM document_archive_rule WHERE id=1")
    fun observeRule(): Flow<DocumentArchiveRuleEntity?>

    @Query("SELECT * FROM document_archive_rule WHERE id=1")
    suspend fun rule(): DocumentArchiveRuleEntity?

    @Upsert suspend fun putRule(rule: DocumentArchiveRuleEntity)

    @Query(
        "UPDATE document_archive_history SET writtenAtMillis=NULL WHERE volumeName=:volume AND mediaStoreId=:id"
    )
    suspend fun relinquish(volume: String, id: Long)

    @Upsert suspend fun putHistory(item: DocumentArchiveHistoryEntity)

    @Query(
        "SELECT * FROM document_archive_history WHERE runId=:runId AND writtenAtMillis IS NOT NULL LIMIT 100"
    )
    suspend fun history(runId: String): List<DocumentArchiveHistoryEntity>

    @Query(
        "SELECT m.volumeName, m.mediaStoreId, m.generationModified, m.displayName, c.category, m.timelineSortMillis " +
            DocumentFrom +
            DocumentEligible +
            """
        AND (:category='All' OR (:category='Suggested' AND c.category IS NULL) OR c.category=:category)
        AND m.isFavorite=0 AND a.mediaStoreId IS NULL
        AND m.timelineSortMillis>0 AND m.timelineSortMillis<=:cutoff
        AND NOT EXISTS (SELECT 1 FROM document_archive_history h WHERE h.volumeName=m.volumeName
            AND h.mediaStoreId=m.mediaStoreId AND h.generationModified=m.generationModified)
        AND (:afterSort IS NULL OR m.timelineSortMillis>:afterSort
            OR (m.timelineSortMillis=:afterSort AND m.mediaStoreId>:afterId)
            OR (m.timelineSortMillis=:afterSort AND m.mediaStoreId=:afterId AND m.volumeName>:afterVolume))
        ORDER BY m.timelineSortMillis, m.mediaStoreId, m.volumeName LIMIT 101"""
    )
    suspend fun candidates(
        category: String,
        cutoff: Long,
        afterSort: Long? = null,
        afterId: Long? = null,
        afterVolume: String? = null,
    ): List<DocumentArchiveCandidate>
}
