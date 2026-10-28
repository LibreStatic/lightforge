package com.librestatic.lightforge.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Keys whose AppSearch document may be stale. Rows are written only by the triggers in
 * [SearchIndexJournalSchema]; duplicates are allowed and collapsed when the journal is drained.
 */
@Entity(
    tableName = "search_index_journal",
    indices = [Index(value = ["volumeName", "mediaStoreId"], name = "index_search_index_journal_key")],
)
data class SearchIndexJournalEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "volumeName") val volumeName: String,
    @ColumnInfo(name = "mediaStoreId") val mediaStoreId: Long,
)

data class SearchJournalKey(val volumeName: String, val mediaStoreId: Long)

/** Triggers that journal every change to an input of a search document. */
object SearchIndexJournalSchema {
    val Callback = object : RoomDatabase.Callback() {
        // Fresh installs and destructive fallbacks skip the migration, so the triggers are
        // (re)asserted on open. IF NOT EXISTS makes this a read-only no-op when they exist.
        override fun onOpen(db: SupportSQLiteDatabase) = installTriggers(db)
    }

    /** media_items columns copied into a search document (see GallerySearchIndexRepository). */
    private val MediaColumns = listOf(
        "mediaType", "mimeType", "displayName", "bucketDisplayName", "timelineSortMillis",
        "generationModified", "isFavorite", "width", "height", "durationMillis",
        "isAccessible", "isTrashed",
    )

    fun createTable(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_index_journal` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `volumeName` TEXT NOT NULL, `mediaStoreId` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_index_journal_key` ON `search_index_journal` (`volumeName`, `mediaStoreId`)")
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        if (!tableExists(db)) return
        fun key(row: String) = "INSERT INTO search_index_journal(volumeName,mediaStoreId) VALUES($row.volumeName,$row.mediaStoreId);"
        fun create(name: String, event: String, table: String, whenClause: String?, body: String) {
            db.execSQL("CREATE TRIGGER IF NOT EXISTS search_journal_$name AFTER $event ON $table " +
                (whenClause?.let { "WHEN $it " } ?: "") + "BEGIN $body END")
        }
        create("media_items_insert", "INSERT", "media_items", null, key("NEW"))
        create("media_items_delete", "DELETE", "media_items", null, key("OLD"))
        create(
            "media_items_update", "UPDATE", "media_items",
            MediaColumns.joinToString(" OR ") { "OLD.$it IS NOT NEW.$it" }, key("NEW"),
        )
        for (table in listOf("media_ocr", "media_labels")) {
            create("${table}_insert", "INSERT", table, null, key("NEW"))
            create("${table}_delete", "DELETE", table, null, key("OLD"))
            // A key change journals both ends; any other column change can alter the document.
            create("${table}_update", "UPDATE", table, null, key("OLD") + key("NEW"))
        }
        // A suppression hides or restores one canonical label on every media row that carries it.
        for ((event, row) in listOf("INSERT" to "NEW", "DELETE" to "OLD")) {
            create(
                "label_suppressions_${event.lowercase()}", event, "label_suppressions", null,
                "INSERT INTO search_index_journal(volumeName,mediaStoreId) " +
                    "SELECT volumeName,mediaStoreId FROM media_labels WHERE canonicalLabel=$row.canonicalLabel;",
            )
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='search_index_journal'").use { it.moveToFirst() }
}
