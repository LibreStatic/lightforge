package com.librestatic.lightforge.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.ArchivePagingSource
import com.librestatic.lightforge.core.database.ArchivedMediaEntity
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.TimelineMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GalleryArchiveRepository(
    private val database: GalleryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.libraryDao()

    fun media(): Flow<PagingData<TimelineMedia>> =
        Pager(
                config =
                    PagingConfig(
                        120,
                        initialLoadSize = 180,
                        prefetchDistance = 120,
                        enablePlaceholders = false,
                        maxSize = 600,
                    ),
                pagingSourceFactory = { ArchivePagingSource(database) },
            )
            .flow
            .map { page -> page.map { it.toTimelineMedia() } }

    fun count(): Flow<Long> = dao.observeArchiveCount()

    fun isArchived(key: MediaKey): Flow<Boolean> =
        dao.observeArchived(key.volumeName, key.mediaStoreId)

    suspend fun setArchived(keys: Collection<MediaKey>, archived: Boolean) {
        keys.distinct().chunked(500).forEach { chunk ->
            database.withTransaction {
                chunk.forEach { key ->
                    database.documentArchiveDao().relinquish(key.volumeName, key.mediaStoreId)
                    if (archived)
                        dao.upsertArchived(
                            ArchivedMediaEntity(key.volumeName, key.mediaStoreId, nowMillis())
                        )
                    else dao.deleteArchived(key.volumeName, key.mediaStoreId)
                }
            }
        }
    }
}
