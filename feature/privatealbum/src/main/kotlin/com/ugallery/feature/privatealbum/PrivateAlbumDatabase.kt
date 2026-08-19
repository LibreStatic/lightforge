package com.ugallery.feature.privatealbum

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.OnConflictStrategy
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Entity(tableName = "private_media")
data class PrivateMediaEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalMediaKey: String,
    val originalMimeType: String,
    val originalDisplayName: String,
    val containerPath: String,
    val containerSizeBytes: Long,
    val encryptedDataKey: ByteArray,
    val dataKeyIv: ByteArray,
    val chunkSize: Int,
    val totalChunks: Int,
    val ivBase: ByteArray,
    val sha256: ByteArray,
    val addedAtMillis: Long,
    val mediaKind: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrivateMediaEntity) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "private_album_metadata")
data class PrivateAlbumMetadataEntity(
    @androidx.room.PrimaryKey val id: Int = 0,
    val isSetup: Boolean = false,
    val keyAlias: String? = null,
    val createdAtMillis: Long? = null,
)

@Dao
interface PrivateMediaDao {
    @Query("SELECT * FROM private_media ORDER BY addedAtMillis DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<PrivateMediaEntity>>

    @Query("SELECT * FROM private_media WHERE id = :id")
    suspend fun getById(id: Long): PrivateMediaEntity?

    @Query("SELECT COUNT(*) FROM private_media")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PrivateMediaEntity): Long

    @Query("DELETE FROM private_media WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM private_media")
    suspend fun deleteAll()

    @Query("SELECT * FROM private_media ORDER BY addedAtMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<PrivateMediaEntity>
}

@Dao
interface PrivateAlbumMetadataDao {
    @Query("SELECT * FROM private_album_metadata WHERE id = 0")
    suspend fun get(): PrivateAlbumMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PrivateAlbumMetadataEntity)

    @Query("DELETE FROM private_album_metadata")
    suspend fun delete()
}

@Database(
    entities = [PrivateMediaEntity::class, PrivateAlbumMetadataEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PrivateAlbumDatabase : RoomDatabase() {
    abstract fun privateMediaDao(): PrivateMediaDao
    abstract fun metadataDao(): PrivateAlbumMetadataDao

    companion object {
        const val DatabaseName = "ugallery-private.db"

        fun open(context: Context): PrivateAlbumDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                PrivateAlbumDatabase::class.java,
                DatabaseName,
            ).fallbackToDestructiveMigration().build()
        }
    }
}
