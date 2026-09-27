package com.librestatic.lightforge.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Immutable civil bounds remain stable across device/timezone database changes. */
@Entity(
    tableName = "memory_date_exclusions",
    indices = [Index(value = ["startDay", "endDay", "zoneId"], unique = true)],
)
data class MemoryDateExclusionEntity(
    @PrimaryKey val ruleId: String,
    val startDay: Long,
    val endDay: Long,
    val zoneId: String,
    val fromMillis: Long,
    val untilMillis: Long,
    val createdAtMillis: Long,
)

/** No FK to replaceable local analysis: removing a cluster must not erase a user choice. */
@Entity(
    tableName = "memory_person_exclusions",
    indices = [Index(value = ["clusterId", "algorithmVersion"], unique = true)],
)
data class MemoryPersonExclusionEntity(
    @PrimaryKey val ruleId: String,
    val clusterId: String,
    val algorithmVersion: String,
    val displayName: String?,
    val createdAtMillis: Long,
)

/** Known matches survive analysis deletion but never survive deletion/reuse of the source row. */
@Entity(
    tableName = "memory_person_sources",
    primaryKeys = ["ruleId", "volumeName", "mediaStoreId"],
    foreignKeys =
        [
            ForeignKey(
                entity = MemoryPersonExclusionEntity::class,
                parentColumns = ["ruleId"],
                childColumns = ["ruleId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = MediaItemEntity::class,
                parentColumns = ["volumeName", "mediaStoreId"],
                childColumns = ["volumeName", "mediaStoreId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["volumeName", "mediaStoreId"])],
)
data class MemoryPersonSourceEntity(
    val ruleId: String,
    val volumeName: String,
    val mediaStoreId: Long,
)

@Dao
interface MemoryExclusionDao {
    @Query("SELECT * FROM memory_date_exclusions ORDER BY createdAtMillis DESC,ruleId")
    fun dates(): Flow<List<MemoryDateExclusionEntity>>

    @Query("SELECT * FROM memory_person_exclusions ORDER BY createdAtMillis DESC,ruleId")
    fun people(): Flow<List<MemoryPersonExclusionEntity>>

    @Query(
        "SELECT * FROM memory_date_exclusions WHERE startDay=:start AND endDay=:end AND zoneId=:zone"
    )
    suspend fun date(start: Long, end: Long, zone: String): MemoryDateExclusionEntity?

    @Query(
        "SELECT * FROM memory_person_exclusions WHERE clusterId=:cluster AND algorithmVersion=:version"
    )
    suspend fun person(cluster: String, version: String): MemoryPersonExclusionEntity?

    @Query(
        "SELECT * FROM person_clusters WHERE clusterId=:cluster AND algorithmVersion=:version AND isHidden=0"
    )
    suspend fun currentPerson(cluster: String, version: String): PersonClusterEntity?

    @Query(
        "SELECT (SELECT COUNT(*) FROM memory_date_exclusions)+(SELECT COUNT(*) FROM memory_person_exclusions)"
    )
    suspend fun ruleCount(): Int

    @Insert suspend fun insertDate(rule: MemoryDateExclusionEntity)

    @Insert suspend fun insertPerson(rule: MemoryPersonExclusionEntity)

    @Query("DELETE FROM memory_date_exclusions WHERE ruleId=:id")
    suspend fun removeDate(id: String): Int

    @Query("DELETE FROM memory_person_exclusions WHERE ruleId=:id")
    suspend fun removePerson(id: String): Int

    @Query("SELECT COUNT(*) FROM memory_person_sources WHERE ruleId=:id")
    fun knownSourceCount(id: String): Flow<Long>

    @Query(
        "INSERT OR IGNORE INTO memory_person_sources (ruleId,volumeName,mediaStoreId) " +
            MemoryCurrentPersonMatches
    )
    suspend fun captureCurrentPersonMatches()
}
