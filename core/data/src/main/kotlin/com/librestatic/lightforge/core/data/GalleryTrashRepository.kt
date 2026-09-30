package com.librestatic.lightforge.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.TrashPagingSource
import com.librestatic.lightforge.core.mediastore.MediaAction
import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.selection.MediaQuery
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
    fun countFlow(): Flow<Long> = dao.observeTrashCount()

    fun emptyTrashQuery(): MediaQuery = MediaQuery(trashedOnly = true)

    fun restoreAction(): MediaAction = MediaAction.Trash(enabled = false)

    fun permanentDeleteAction(): MediaAction = MediaAction.Delete

    fun TimelineMedia.actionTarget() = MediaActionTarget(
        key,
        if (kind == MediaKind.Video) MediaKind.Video else MediaKind.Image,
    )
}
