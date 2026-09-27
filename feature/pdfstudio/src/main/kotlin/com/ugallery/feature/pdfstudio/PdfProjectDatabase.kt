package com.ugallery.feature.pdfstudio

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class PdfProjectRow(
    @PrimaryKey val id: String,
    val name: String,
    val updated: Long,
    val manifest: String,
    @ColumnInfo(defaultValue = "''") val editor: String = "",
    @ColumnInfo(defaultValue = "0") val pageCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val sourceBytes: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val coverPageId: String? = null,
)

/** Row returned by the list query: the heavy [PdfProjectRow.manifest]/[PdfProjectRow.editor] blobs
 * are left as empty strings so the library list does not decode every project's manifest. */
@Dao
interface PdfProjectDao {
    @Query(
        "SELECT id, name, updated, '' AS manifest, '' AS editor, pageCount, sourceBytes, coverPageId FROM projects ORDER BY updated DESC"
    )
    fun observe(): Flow<List<PdfProjectRow>>

    @Query("SELECT * FROM projects") suspend fun all(): List<PdfProjectRow>

    @Query("SELECT * FROM projects WHERE id = :id") suspend fun get(id: String): PdfProjectRow?

    @Query("SELECT * FROM projects WHERE pageCount = 0") suspend fun withoutSummary(): List<PdfProjectRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: PdfProjectRow)

    @Query(
        "UPDATE projects SET pageCount = :pageCount, sourceBytes = :sourceBytes, coverPageId = :coverPageId WHERE id = :id"
    )
    suspend fun updateSummary(id: String, pageCount: Int, sourceBytes: Long, coverPageId: String?)

    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: String)
}

@Database(
    entities =
        [
            PdfProjectRow::class,
            PdfExportJob::class,
            PdfDestinationGrant::class,
            PdfImportReceipt::class,
            PdfImportDelivery::class,
            PdfGalleryDelivery::class,
        ],
    version = 9,
    exportSchema = true,
)
abstract class PdfProjectDatabase : RoomDatabase() {
    abstract fun projects(): PdfProjectDao

    abstract fun exports(): PdfExportDao

    abstract fun destinationGrants(): PdfDestinationGrantDao

    abstract fun galleryDeliveries(): PdfGalleryDeliveryDao

    abstract fun imports(): PdfImportReceiptDao

    abstract fun importDeliveries(): PdfImportDeliveryDao

    companion object {
        val MIGRATION_1_2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE projects ADD COLUMN editor TEXT NOT NULL DEFAULT ''")
                }
            }

        val MIGRATION_2_3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS export_jobs (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, projectName TEXT NOT NULL, manifest TEXT NOT NULL, compact INTEGER NOT NULL, status TEXT NOT NULL, completed INTEGER NOT NULL, total INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, destination TEXT, error TEXT)"
                    )
                }
            }

        val MIGRATION_3_4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE export_jobs ADD COLUMN outputHash TEXT")
                    db.execSQL(
                        "ALTER TABLE export_jobs ADD COLUMN outputBytes INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS destination_grants (uri TEXT NOT NULL PRIMARY KEY, ownedFlags INTEGER NOT NULL)"
                    )
                    // Pre-journal grants remain externally owned: never revoke permissions adopted
                    // by old builds.
                    db.execSQL(
                        "INSERT OR IGNORE INTO destination_grants(uri,ownedFlags) SELECT DISTINCT destination,0 FROM export_jobs WHERE destination IS NOT NULL"
                    )
                }
            }

        val MIGRATION_4_5 =
            object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE export_jobs ADD COLUMN portable INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }

        val MIGRATION_5_6 =
            object : androidx.room.migration.Migration(5, 6) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS import_receipts (requestId TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL)"
                    )
                }
            }

        val MIGRATION_6_7 =
            object : androidx.room.migration.Migration(6, 7) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS import_deliveries (id TEXT NOT NULL PRIMARY KEY, portable INTEGER NOT NULL, projectId TEXT, pageId TEXT, uris TEXT NOT NULL)"
                    )
                }
            }

        val MIGRATION_7_8 =
            object : androidx.room.migration.Migration(7, 8) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS gallery_deliveries (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, uris TEXT NOT NULL, error TEXT)"
                    )
                }
            }

        val MIGRATION_8_9 =
            object : androidx.room.migration.Migration(8, 9) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE projects ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        "ALTER TABLE projects ADD COLUMN sourceBytes INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE projects ADD COLUMN coverPageId TEXT")
                    // Existing rows are backfilled lazily off the main thread (see
                    // PdfProjectRepository.backfillSummaries); pageCount = 0 marks them as pending.
                    db.execSQL("ALTER TABLE gallery_deliveries ADD COLUMN targetProjectId TEXT")
                    db.execSQL("ALTER TABLE gallery_deliveries ADD COLUMN targetPageId TEXT")
                    db.execSQL("ALTER TABLE gallery_deliveries ADD COLUMN placementX REAL")
                    db.execSQL("ALTER TABLE gallery_deliveries ADD COLUMN placementY REAL")
                }
            }

        @Volatile private var instance: PdfProjectDatabase? = null

        fun get(context: Context): PdfProjectDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                PdfProjectDatabase::class.java,
                                "pdf-projects.db",
                            )
                            .addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                                MIGRATION_5_6,
                                MIGRATION_6_7,
                                MIGRATION_7_8,
                                MIGRATION_8_9,
                            )
                            .build()
                            .also { instance = it }
                }
    }
}
