package com.ugallery.feature.privatealbum

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase

/** Lives only inside the authenticated SQLCipher index; never a clear-text recovery manifest. */
@Entity(tableName = "private_export_receipts", indices = [Index(value = ["mediaId"], unique = true)])
data class PrivateExportReceiptEntity(
    @PrimaryKey val id: String,
    val mediaId: Long,
    val displayName: String,
    val mimeType: String,
    val mediaKind: String,
    val expectedSha256: String,
    val phase: String = "Intent",
    val snapshotJson: String? = null,
    val createdAtMillis: Long,
)

@Dao
interface PrivateExportDao {
    @Query("SELECT * FROM private_export_receipts WHERE id = :id")
    suspend fun get(id: String): PrivateExportReceiptEntity?
    @Query("SELECT * FROM private_export_receipts WHERE mediaId = :mediaId")
    suspend fun getForMedia(mediaId: Long): PrivateExportReceiptEntity?
    @Query("SELECT * FROM private_export_receipts ORDER BY createdAtMillis DESC, id")
    suspend fun list(): List<PrivateExportReceiptEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PrivateExportReceiptEntity)
    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(entity: PrivateExportReceiptEntity): Int
    @Query("DELETE FROM private_export_receipts WHERE id = :id")
    suspend fun delete(id: String): Int
}

internal object PrivateExportSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS private_export_receipts (id TEXT NOT NULL PRIMARY KEY, mediaId INTEGER NOT NULL, displayName TEXT NOT NULL, mimeType TEXT NOT NULL, mediaKind TEXT NOT NULL, expectedSha256 TEXT NOT NULL, phase TEXT NOT NULL, snapshotJson TEXT, createdAtMillis INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_private_export_receipts_mediaId ON private_export_receipts(mediaId)")
    }
}
