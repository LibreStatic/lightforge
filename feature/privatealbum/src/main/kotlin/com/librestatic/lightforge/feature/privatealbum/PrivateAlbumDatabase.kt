package com.librestatic.lightforge.feature.privatealbum

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

/** Exact encrypted-archive import history; never a claim that a later-deleted item still exists. */
@Entity(tableName = "private_restore_receipts", indices = [androidx.room.Index(value = ["archiveSha256"], unique = true)])
data class PrivateRestoreReceiptEntity(
    @androidx.room.PrimaryKey val id: String,
    val archiveSha256: String,
    val itemCount: Int,
    val committedAtMillis: Long,
)

class PrivateRestoreAlreadyCommitted(val receipt: PrivateRestoreReceiptEntity) :
    IllegalStateException("Private archive import already committed")

@Dao
interface PrivatePortableRestoreDao {
    @Query("SELECT EXISTS(SELECT 1 FROM private_media WHERE containerPath = :path)")
    suspend fun isContainerPathReferenced(path: String): Boolean

    @Query("SELECT * FROM private_restore_receipts WHERE archiveSha256 = :sha256")
    suspend fun getReceiptByArchiveSha(sha256: String): PrivateRestoreReceiptEntity?

    @Query("SELECT * FROM private_restore_receipts WHERE id = :id")
    suspend fun getReceipt(id: String): PrivateRestoreReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: PrivateRestoreReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRestoredMedia(items: List<PrivateMediaEntity>): List<Long>

    @androidx.room.Transaction
    suspend fun commitPortableRestore(receipt: PrivateRestoreReceiptEntity, items: List<PrivateMediaEntity>): List<Long> {
        require(java.util.UUID.fromString(receipt.id).toString() == receipt.id)
        require(receipt.archiveSha256.matches(Regex("[0-9a-f]{64}")))
        require(items.size in 1..2000 && receipt.itemCount == items.size)
        require(items.all { it.id == 0L && it.containerSizeBytes > 0 && it.sha256.size == 32 })
        require(items.map { it.containerPath }.distinct().size == items.size)
        getReceiptByArchiveSha(receipt.archiveSha256)?.let { throw PrivateRestoreAlreadyCommitted(it) }
        check(getReceipt(receipt.id) == null) { "Private restore operation identity changed" }
        insertReceipt(receipt)
        return insertRestoredMedia(items)
    }
}

@Database(
    entities = [PrivateMediaEntity::class, PrivateAlbumMetadataEntity::class, PrivateRestoreReceiptEntity::class, PrivateKeyProtectionJob::class, PrivateKeyRewrapEntity::class, PrivateExportReceiptEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class PrivateAlbumDatabase : RoomDatabase() {
    abstract fun privateMediaDao(): PrivateMediaDao
    abstract fun metadataDao(): PrivateAlbumMetadataDao
    abstract fun portableRestoreDao(): PrivatePortableRestoreDao
    abstract fun keyProtectionDao(): PrivateKeyProtectionDao
    abstract fun privateExportDao(): PrivateExportDao

    companion object {
        const val DatabaseName = "lightforge-private.db"

        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS private_restore_receipts (id TEXT NOT NULL PRIMARY KEY, archiveSha256 TEXT NOT NULL, itemCount INTEGER NOT NULL, committedAtMillis INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_private_restore_receipts_archiveSha256 ON private_restore_receipts(archiveSha256)")
            }
        }

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = PrivateKeyProtectionSchema.create(db)
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = PrivateExportSchema.create(db)
        }

        fun open(context: Context, databaseName: String = DatabaseName): PrivateAlbumDatabase {
            require(databaseName.matches(Regex("[A-Za-z0-9._-]+")) && databaseName != "." && databaseName != "..")
            return Room.databaseBuilder(
                context.applicationContext,
                PrivateAlbumDatabase::class.java,
                databaseName,
            ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).openHelperFactory(PrivateIndexOpenHelperFactory())
                .addMigrations(Migration1To2, Migration2To3, Migration3To4).build()
        }
    }
}
