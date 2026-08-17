package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.TrashPagingSource
import com.ugallery.core.mediastore.MediaAction
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GalleryTrashRepository(private val database: GalleryDatabase) {
    private val dao = database.libraryDao()

    fun trash(): Flow<PagingData<TimelineMedia>> = Pager(
        config = PagingConfig(
            pageSize = 120,
            initialLoadSize = 180,
            prefetchDistance = 120,
            enablePlaceholders = false,
            maxSize = 600,
        ),
        pagingSourceFactory = { TrashPagingSource(database) },
    ).flow.map { page -> page.map { it.toTimelineMedia() } }

    suspend fun count(): Long = dao.trashCount()

    fun emptyTrashQuery(): MediaQuery = MediaQuery(trashedOnly = true)

    fun restoreAction(): MediaAction = MediaAction.Trash(enabled = false)

    fun permanentDeleteAction(): MediaAction = MediaAction.Delete

    fun TimelineMedia.actionTarget() = MediaActionTarget(
        key,
        if (kind == MediaKind.Video) MediaKind.Video else MediaKind.Image,
    )
}
