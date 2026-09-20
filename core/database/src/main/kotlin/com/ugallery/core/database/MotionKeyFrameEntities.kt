package com.ugallery.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Non-destructive, generation-bound display cover. Original media bytes are never rewritten. */
@Entity(
    tableName = "motion_key_frames",
    primaryKeys = ["volumeName", "mediaStoreId"],
    foreignKeys = [ForeignKey(entity = MediaItemEntity::class,
        parentColumns = ["volumeName", "mediaStoreId"], childColumns = ["volumeName", "mediaStoreId"],
        onDelete = ForeignKey.CASCADE)],
)
data class MotionKeyFrameEntity(
    val volumeName: String,
    val mediaStoreId: Long,
    val generationModified: Long,
    val timeUs: Long,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val revision: String,
    val updatedAtMillis: Long,
)

data class MotionKeyFrameVersion(val volumeName: String, val mediaStoreId: Long, val revision: String)

@Dao
interface MotionKeyFrameDao {
    @Query("SELECT * FROM motion_key_frames WHERE volumeName=:volume AND mediaStoreId=:id")
    suspend fun get(volume: String, id: Long): MotionKeyFrameEntity?

    @Query("SELECT * FROM motion_key_frames WHERE volumeName=:volume AND mediaStoreId=:id")
    fun observe(volume: String, id: Long): Flow<MotionKeyFrameEntity?>

    @Query("SELECT volumeName, mediaStoreId, revision FROM motion_key_frames ORDER BY volumeName, mediaStoreId")
    fun versions(): Flow<List<MotionKeyFrameVersion>>

    @Query("SELECT k.* FROM motion_key_frames k JOIN media_items m ON m.volumeName=k.volumeName AND m.mediaStoreId=k.mediaStoreId WHERE k.volumeName=:volume AND k.mediaStoreId=:id AND k.generationModified=:generation AND m.generationModified=:generation AND m.isAccessible=1 AND m.isTrashed=0")
    fun currentForDisplay(volume: String, id: Long, generation: Long): MotionKeyFrameEntity?

    @Query("SELECT fileName FROM motion_key_frames")
    suspend fun retainedFiles(): List<String>

    @Upsert suspend fun put(value: MotionKeyFrameEntity)

    @Query("DELETE FROM motion_key_frames WHERE volumeName=:volume AND mediaStoreId=:id AND revision=:revision")
    suspend fun remove(volume: String, id: Long, revision: String): Int
}

/** Commit receipt shares the organization import transaction; media publication is journalled separately. */
@Entity(tableName = "gallery_restore_receipts")
data class GalleryRestoreReceiptEntity(
    @PrimaryKey val operationId: String,
    val snapshotId: String,
    val files: Int,
    val importedObjects: Int,
    val skippedObjects: Int,
    val appliedAtMillis: Long,
)

@Dao
interface GalleryRestoreReceiptDao {
    @Query("SELECT * FROM gallery_restore_receipts WHERE operationId=:id")
    suspend fun get(id: String): GalleryRestoreReceiptEntity?
    @Insert suspend fun insert(receipt: GalleryRestoreReceiptEntity)
}
