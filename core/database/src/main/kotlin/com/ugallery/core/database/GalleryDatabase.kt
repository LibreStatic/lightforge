package com.ugallery.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        MediaStoreCheckpointEntity::class,
        AlbumAggregateEntity::class,
        VirtualAlbumEntity::class,
        VirtualAlbumMediaEntity::class,
        MediaExifEntity::class,
        MediaLabelRunEntity::class,
        MediaLabelEntity::class,
        LabelSuppressionEntity::class,
        MediaOcrEntity::class,
        DuplicateHashEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

object GalleryDatabaseFactory {
    const val DatabaseName = "ugallery-library.db"

    fun open(context: Context, name: String = DatabaseName): GalleryDatabase {
        val database = build(context, name)
        return try {
            database.openHelper.writableDatabase
            database
        } catch (corrupt: SQLiteDatabaseCorruptException) {
            database.close()
            if (!context.deleteDatabase(name)) throw corrupt
            build(context, name).also { it.openHelper.writableDatabase }
        }
    }

    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_store_checkpoints ADD COLUMN deltaTargetGeneration INTEGER")
            db.execSQL(
                "ALTER TABLE media_store_checkpoints ADD COLUMN deltaGenerationCursor INTEGER " +
                    "NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE media_store_checkpoints ADD COLUMN deltaMediaStoreIdCursor INTEGER " +
                    "NOT NULL DEFAULT -1",
            )
        }
    }

    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `virtual_albums` (
                    `albumId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL,
                    `createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL)""",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_virtual_album_name` " +
                    "ON `virtual_albums` (`normalizedName`)",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `virtual_album_media` (
                    `albumId` INTEGER NOT NULL, `volumeName` TEXT NOT NULL,
                    `mediaStoreId` INTEGER NOT NULL, `addedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`albumId`, `volumeName`, `mediaStoreId`),
                    FOREIGN KEY(`albumId`) REFERENCES `virtual_albums`(`albumId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_virtual_album_media_key` " +
                    "ON `virtual_album_media` (`volumeName`, `mediaStoreId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_virtual_album_added` " +
                    "ON `virtual_album_media` (`albumId`, `addedAtMillis`)",
            )
        }
    }

    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `media_exif_cache` (
                    `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL,
                    `generationModified` INTEGER NOT NULL, `orientation` INTEGER NOT NULL,
                    `dateTimeOriginal` TEXT, `offsetTimeOriginal` TEXT, `make` TEXT, `model` TEXT,
                    `lensModel` TEXT, `focalLength` TEXT, `aperture` TEXT, `exposureTime` TEXT,
                    `iso` INTEGER, `latitude` REAL, `longitude` REAL,
                    `locationReadWithPermission` INTEGER NOT NULL, `cachedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`volumeName`, `mediaStoreId`))""",
            )
        }
    }

    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN dateExpiresSeconds INTEGER")
        }
    }

    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_label_runs` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_label_run_version` ON `media_label_runs` (`modelVersion`, `generationModified`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_labels` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `canonicalLabel` TEXT NOT NULL, `rawLabel` TEXT NOT NULL, `confidence` REAL NOT NULL, `modelVersion` TEXT NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`, `canonicalLabel`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_label_canonical` ON `media_labels` (`canonicalLabel`, `confidence`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `label_suppressions` (`canonicalLabel` TEXT NOT NULL, `suppressedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`canonicalLabel`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `media_ocr` (`volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL, `generationModified` INTEGER NOT NULL, `modelVersion` TEXT NOT NULL, `rawText` TEXT NOT NULL, `normalizedText` TEXT NOT NULL, `blocksJson` TEXT NOT NULL, `completedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`volumeName`, `mediaStoreId`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_ocr_version` ON `media_ocr` (`modelVersion`, `generationModified`)")
        }
    }

    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `duplicate_hashes` (
                    `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL,
                    `generationModified` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL,
                    `hashVersion` TEXT NOT NULL, `sampleSha256` TEXT NOT NULL,
                    `sha256` TEXT, `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`volumeName`, `mediaStoreId`),
                    FOREIGN KEY(`volumeName`, `mediaStoreId`) REFERENCES `media_items`(`volumeName`, `mediaStoreId`)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_sample` ON `duplicate_hashes` (`hashVersion`, `sizeBytes`, `sampleSha256`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_duplicate_full` ON `duplicate_hashes` (`hashVersion`, `sizeBytes`, `sha256`)")
        }
    }

    private fun build(context: Context, name: String): GalleryDatabase = Room.databaseBuilder(
        context.applicationContext,
        GalleryDatabase::class.java,
        name,
    ).addMigrations(
        Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7,
    ).build()
}
