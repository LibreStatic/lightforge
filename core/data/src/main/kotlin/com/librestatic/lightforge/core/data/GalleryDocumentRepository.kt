package com.librestatic.lightforge.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.model.MediaKey

/** Suggested means local text/document evidence, never an inferred receipt/ticket category. */
enum class DocumentCategory {
    All,
    Suggested,
    Receipt,
    Ticket,
    Note,
    Other,
    Excluded,
}

data class DocumentArchiveUndo(
    val key: MediaKey,
    val generation: Long,
    val previous: Long?,
    val written: Long?,
)

class GalleryDocumentRepository(
    private val database: GalleryDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.documentDao()
    val autoArchive = DocumentAutoArchiveRepository(database, now)

    fun count() = dao.count()

    fun observe(key: MediaKey) = dao.observe(key.volumeName, key.mediaStoreId)

    /** Bounded, non-paged list of the most recent eligible documents (PDF Studio's Media panel,
     * Phase F item 3 - a scrollable grid, not an infinite timeline, so it never needs Paging). */
    suspend fun recent(limit: Int = 200): List<DocumentRow> = dao.recent(limit)

    fun pages(category: DocumentCategory, query: String) =
        Pager(
                PagingConfig(
                    pageSize = 40,
                    initialLoadSize = 60,
                    prefetchDistance = 20,
                    enablePlaceholders = false,
                    maxSize = 200,
                ),
                pagingSourceFactory = { dao.page(category.name, searchPattern(query)) },
            )
            .flow

    suspend fun classify(keys: Collection<MediaKey>, category: DocumentCategory) {
        require(category !in setOf(DocumentCategory.All, DocumentCategory.Suggested))
        require(keys.isNotEmpty() && keys.size <= 100)
        database.withTransaction {
            for (key in keys.distinct()) {
                checkNotNull(dao.get(key.volumeName, key.mediaStoreId)) { "DocumentUnavailable" }
                dao.put(
                    DocumentAnnotationEntity(key.volumeName, key.mediaStoreId, category.name, now())
                )
            }
        }
    }

    suspend fun clearClassification(key: MediaKey) =
        database.withTransaction { dao.clear(key.volumeName, key.mediaStoreId) }

    suspend fun archive(key: MediaKey, archived: Boolean): DocumentArchiveUndo =
        database.withTransaction {
            val row =
                checkNotNull(dao.get(key.volumeName, key.mediaStoreId)) { "DocumentUnavailable" }
            database.documentArchiveDao().relinquish(key.volumeName, key.mediaStoreId)
            val previous = dao.archivedAt(key.volumeName, key.mediaStoreId)
            val written = if (archived) now() else null
            if (written == null)
                database.libraryDao().deleteArchived(key.volumeName, key.mediaStoreId)
            else
                database
                    .libraryDao()
                    .upsertArchived(ArchivedMediaEntity(key.volumeName, key.mediaStoreId, written))
            GalleryActivityRepository(database, now)
                .record(
                    if (archived) GalleryActivityType.Archived else GalleryActivityType.Unarchived,
                    1,
                )
            DocumentArchiveUndo(key, row.media.generationModified, previous, written)
        }

    /** Undo only our unchanged archive state, never another action or a replaced source. */
    suspend fun undo(token: DocumentArchiveUndo): Boolean =
        database.withTransaction {
            val key = token.key
            val row = dao.get(key.volumeName, key.mediaStoreId) ?: return@withTransaction false
            if (
                row.media.generationModified != token.generation ||
                    dao.archivedAt(key.volumeName, key.mediaStoreId) != token.written
            )
                return@withTransaction false
            database.documentArchiveDao().relinquish(key.volumeName, key.mediaStoreId)
            if (token.previous == null)
                database.libraryDao().deleteArchived(key.volumeName, key.mediaStoreId)
            else
                database
                    .libraryDao()
                    .upsertArchived(
                        ArchivedMediaEntity(key.volumeName, key.mediaStoreId, token.previous)
                    )
            GalleryActivityRepository(database, now)
                .record(
                    if (token.previous == null) GalleryActivityType.Unarchived
                    else GalleryActivityType.Archived,
                    1,
                )
            true
        }

    suspend fun pdfKeys(keys: List<MediaKey>): List<MediaKey> =
        database.withTransaction {
            require(keys.size in 1..24 && keys.distinct().size == keys.size)
            keys.onEach {
                checkNotNull(dao.get(it.volumeName, it.mediaStoreId)) { "DocumentUnavailable" }
            }
        }

    companion object {
        fun searchPattern(query: String): String =
            "%" +
                query
                    .take(120)
                    .trim()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") +
                "%"
    }
}
