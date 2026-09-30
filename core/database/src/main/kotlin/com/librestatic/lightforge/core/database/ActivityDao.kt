package com.librestatic.lightforge.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_events ORDER BY occurredAtMillis DESC, eventId DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<ActivityEventEntity>>

    @Insert
    suspend fun insert(event: ActivityEventEntity): Long

    @Query(
        "DELETE FROM activity_events WHERE eventId NOT IN " +
            "(SELECT eventId FROM activity_events ORDER BY occurredAtMillis DESC, eventId DESC LIMIT :limit)",
    )
    suspend fun prune(limit: Int): Int

    @Transaction
    suspend fun insertAndPrune(event: ActivityEventEntity, limit: Int = 100) {
        require(limit in 1..500)
        insert(event)
        prune(limit)
    }
}
