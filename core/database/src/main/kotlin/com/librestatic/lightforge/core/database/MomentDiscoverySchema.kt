package com.librestatic.lightforge.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** Durable invalidation of the inputs actually consumed by the memory heuristic. */
object MomentDiscoverySchema {
    val Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
        // Creation and explicit migrations install durable triggers once. Opening a second
        // Room connection must not take a write lock before its repository lease.
    }

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO moment_discovery_revision(id,revision) VALUES(1,0)")
        installTriggers(
            db, "media_items",
            listOf("mediaType", "timelineSortMillis", "isAccessible", "isTrashed",
                "generationAdded", "generationModified", "width", "height", "isFavorite",
                "bucketDisplayName", "relativePath", "displayName"),
            insertWhen = "NEW.mediaType=1", deleteWhen = "OLD.mediaType=1",
            updateWhen = "(OLD.mediaType=1 OR NEW.mediaType=1)",
        )
        installTriggers(db, "media_exif_cache", listOf(
            "generationModified", "locationReadWithPermission", "latitude", "longitude",
        ))
        installTriggers(db, "similarity_features", listOf("generationModified", "blurScore", "pHash"))
        installTriggers(db, "similarity_memberships", listOf("clusterId"))
        installDocumentTriggers(db)
    }

    /** Category timestamps and changes between two confirmed categories do not change inputs. */
    fun installDocumentTriggers(db: SupportSQLiteDatabase) {
        val oldConfirmed = "OLD.category IN $MemoryConfirmedDocumentCategories"
        val newConfirmed = "NEW.category IN $MemoryConfirmedDocumentCategories"
        installTriggers(
            db, "document_annotations", listOf("category", "volumeName", "mediaStoreId"),
            insertWhen = newConfirmed,
            deleteWhen = oldConfirmed,
            updateWhen = "(($oldConfirmed) IS NOT ($newConfirmed)) OR " +
                "(($oldConfirmed OR $newConfirmed) AND " +
                "(OLD.volumeName IS NOT NEW.volumeName OR OLD.mediaStoreId IS NOT NEW.mediaStoreId))",
        )
    }

    private fun installTriggers(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        insertWhen: String = "1",
        deleteWhen: String = "1",
        updateWhen: String = "1",
    ) {
        // All identifiers and expressions above are compile-time constants, never imported data.
        val increment = "UPDATE moment_discovery_revision SET revision=revision+1 WHERE id=1;"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_discovery_${table}_insert " +
            "AFTER INSERT ON $table WHEN $insertWhen BEGIN $increment END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_discovery_${table}_delete " +
            "AFTER DELETE ON $table WHEN $deleteWhen BEGIN $increment END")
        val changed = columns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }
        db.execSQL("CREATE TRIGGER IF NOT EXISTS memory_discovery_${table}_update " +
            "AFTER UPDATE OF ${columns.joinToString(",")} ON $table " +
            "WHEN ($updateWhen) AND ($changed) BEGIN $increment END")
    }
}
