package com.librestatic.lightforge.feature.privatealbum

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "private_key_protection")
data class PrivateKeyProtectionJob(
    @PrimaryKey val id: Int = 0,
    val migrationId: String,
    val sourceAlias: String?,
    val targetAlias: String,
    val initialized: Boolean = false,
    val committed: Boolean = false,
    val cancelled: Boolean = false,
)

@Entity(tableName = "private_key_rewrap")
data class PrivateKeyRewrapEntity(
    @PrimaryKey val mediaId: Long,
    val migrationId: String,
    val sourceKey: ByteArray,
    val sourceIv: ByteArray,
    val targetKey: ByteArray,
    val targetIv: ByteArray,
)

@Dao
interface PrivateKeyProtectionDao {
    @Query("SELECT * FROM private_key_protection WHERE id = 0")
    suspend fun job(): PrivateKeyProtectionJob?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: PrivateKeyProtectionJob)
    @Query("UPDATE private_key_protection SET initialized = 1 WHERE id = 0 AND migrationId = :id AND initialized = 0 AND cancelled = 0")
    suspend fun markInitialized(id: String): Int
    @Query("UPDATE private_key_protection SET committed = 1 WHERE id = 0 AND migrationId = :id AND committed = 0")
    suspend fun markCommitted(id: String): Int
    @Query("UPDATE private_key_protection SET cancelled = 1 WHERE id = 0 AND migrationId = :id AND committed = 0 AND cancelled = 0")
    suspend fun markCancelled(id: String): Int
    @Query("DELETE FROM private_key_protection WHERE id = 0 AND migrationId = :id")
    suspend fun deleteJob(id: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun stage(row: PrivateKeyRewrapEntity)
    @Query("DELETE FROM private_key_rewrap")
    suspend fun clearStaging()
    @Query("SELECT m.* FROM private_media m LEFT JOIN private_key_rewrap r ON r.mediaId=m.id AND r.migrationId=:id AND r.sourceKey=m.encryptedDataKey AND r.sourceIv=m.dataKeyIv WHERE r.mediaId IS NULL ORDER BY m.id LIMIT :limit")
    suspend fun pending(id: String, limit: Int): List<PrivateMediaEntity>
    @Query("SELECT COUNT(*) FROM private_media m JOIN private_key_rewrap r ON r.mediaId=m.id AND r.migrationId=:id AND r.sourceKey=m.encryptedDataKey AND r.sourceIv=m.dataKeyIv")
    suspend fun preparedCount(id: String): Int
    @Query("UPDATE private_media SET encryptedDataKey=(SELECT targetKey FROM private_key_rewrap WHERE mediaId=private_media.id AND migrationId=:id), dataKeyIv=(SELECT targetIv FROM private_key_rewrap WHERE mediaId=private_media.id AND migrationId=:id)")
    suspend fun publish(id: String): Int
}

internal object PrivateKeyProtectionSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS private_key_protection (id INTEGER NOT NULL PRIMARY KEY, migrationId TEXT NOT NULL, sourceAlias TEXT, targetAlias TEXT NOT NULL, initialized INTEGER NOT NULL, committed INTEGER NOT NULL, cancelled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS private_key_rewrap (mediaId INTEGER NOT NULL PRIMARY KEY, migrationId TEXT NOT NULL, sourceKey BLOB NOT NULL, sourceIv BLOB NOT NULL, targetKey BLOB NOT NULL, targetIv BLOB NOT NULL)")
    }
}
