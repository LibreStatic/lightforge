package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ugallery.core.database.ArchivePagingSource
import com.ugallery.core.database.ArchivedMediaEntity
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.TimelineMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GalleryArchiveRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()

    fun media(): Flow<PagingData<TimelineMedia>> = Pager(
        config = PagingConfig(120, initialLoadSize = 180, prefetchDistance = 120, enablePlaceholders = false, maxSize = 600),
        pagingSourceFactory = { ArchivePagingSource(database) },
    ).flow.map { page -> page.map { it.toTimelineMedia() } }

    fun count(): Flow<Long> = dao.observeArchiveCount()

    fun isArchived(key: MediaKey): Flow<Boolean> = dao.observeArchived(key.volumeName, key.mediaStoreId)

    suspend fun setArchived(keys: Collection<MediaKey>, archived: Boolean) {
        keys.distinct().chunked(500).forEach { chunk ->
            chunk.forEach { key ->
                if (archived) dao.upsertArchived(ArchivedMediaEntity(key.volumeName, key.mediaStoreId, nowMillis()))
                else dao.deleteArchived(key.volumeName, key.mediaStoreId)
            }
        }
    }
}
