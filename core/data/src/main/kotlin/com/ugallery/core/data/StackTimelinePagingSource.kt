package com.ugallery.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.ugallery.core.database.*
import com.ugallery.core.preferences.LibrarySettings
import kotlinx.coroutines.CancellationException

/** Bounded keyset pages; stack collapse happens in SQLite, not per loaded UI page. */
internal class StackTimelinePagingSource(private val database: GalleryDatabase) :
    PagingSource<TimelineKeyset, StackTimelineRow>() {
    private val observer =
        object :
            InvalidationTracker.Observer(
                "media_items",
                "archived_media",
                "photo_stacks",
                "photo_stack_members",
            ) {
            override fun onInvalidated(tables: Set<String>) = invalidate()
        }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override fun getRefreshKey(
        state: PagingState<TimelineKeyset, StackTimelineRow>
    ): TimelineKeyset? = null

    override suspend fun load(
        params: LoadParams<TimelineKeyset>
    ): LoadResult<TimelineKeyset, StackTimelineRow> =
        try {
            val limit = params.loadSize.coerceIn(1, 500)
            val rows =
                database
                    .libraryDao()
                    .stackTimelinePage(
                        GalleryTimelineQuery.stacked(LibrarySettings(), params.key, limit)
                    )
            LoadResult.Page(
                rows,
                null,
                rows
                    .lastOrNull()
                    ?.takeIf { rows.size == limit }
                    ?.media
                    ?.let { TimelineKeyset(it.timelineSortMillis, it.mediaStoreId, it.volumeName) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoadResult.Error(failure)
        }
}
