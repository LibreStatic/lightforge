package com.ugallery.core.database

import androidx.room.*

/** Local date choices survive provider rescan, but never attach to a reused MediaStore identity. */
@Entity(tableName = "portable_timeline_overrides", primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [ForeignKey(entity = MediaItemEntity::class, parentColumns = ["volumeName", "mediaStoreId"],
        childColumns = ["volumeName", "mediaStoreId"], onDelete = ForeignKey.CASCADE)])
data class PortableTimelineOverrideEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationAdded: Long,
    val timelineSortMillis: Long,
    val dateTakenMillis: Long?,
)

@Dao
interface PortableTimelineOverrideDao {
    @Upsert suspend fun put(value: PortableTimelineOverrideEntity)
    @Query("DELETE FROM portable_timeline_overrides WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun remove(volume: String, id: Long): Int
}
