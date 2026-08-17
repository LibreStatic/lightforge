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
    ],
    version = 4,
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

    private fun build(context: Context, name: String): GalleryDatabase = Room.databaseBuilder(
        context.applicationContext,
        GalleryDatabase::class.java,
        name,
    ).addMigrations(Migration1To2, Migration2To3, Migration3To4).build()
}
