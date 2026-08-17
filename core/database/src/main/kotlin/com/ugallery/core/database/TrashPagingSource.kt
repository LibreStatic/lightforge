package com.ugallery.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException

class TrashPagingSource(
    private val database: GalleryDatabase,
    private val dao: LibraryDao = database.libraryDao(),
) : PagingSource<TimelineKeyset, MediaItemEntity>() {
    private val observer = object : InvalidationTracker.Observer("media_items") {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override fun getRefreshKey(state: PagingState<TimelineKeyset, MediaItemEntity>): TimelineKeyset? = null

    override suspend fun load(params: LoadParams<TimelineKeyset>): LoadResult<TimelineKeyset, MediaItemEntity> =
        try {
            val limit = params.loadSize.coerceIn(1, 500)
            val queried = params.key?.let { key ->
                dao.trashPageAfter(
                    key.timelineSortMillis, key.mediaStoreId, key.volumeName, limit + 1,
                )
            } ?: dao.firstTrashPage(limit + 1)
            val hasMore = queried.size > limit
            val rows = queried.take(limit)
            LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = rows.lastOrNull()?.takeIf { hasMore }?.let {
                    TimelineKeyset(it.timelineSortMillis, it.mediaStoreId, it.volumeName)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LoadResult.Error(failure)
        }
}
