package com.ugallery.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        MediaStoreCheckpointEntity::class,
        AlbumAggregateEntity::class,
    ],
    version = 1,
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

    private fun build(context: Context, name: String): GalleryDatabase = Room.databaseBuilder(
        context.applicationContext,
        GalleryDatabase::class.java,
        name,
    ).build()
}
