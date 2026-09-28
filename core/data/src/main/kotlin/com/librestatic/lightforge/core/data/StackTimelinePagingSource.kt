package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import com.librestatic.lightforge.core.database.*
import com.librestatic.lightforge.core.preferences.LibrarySettings
import kotlinx.coroutines.CancellationException

/**
 * Bounded bidirectional keyset pages; stack collapse happens in SQLite, not per loaded UI page.
 * A null key loads from the newest row; any other key is an exclusive bound that can still be
 * paged upward.
 */
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
    ): TimelineKeyset? = state.timelineRefreshKey { it.media.toKeyset() }

    override suspend fun load(
        params: LoadParams<TimelineKeyset>
    ): LoadResult<TimelineKeyset, StackTimelineRow> =
        try {
            val limit = params.loadSize.coerceIn(1, 500)
            val key = params.key
            val prepending = params is LoadParams.Prepend
            val dao = database.libraryDao()
            val rows =
                if (prepending) {
                    dao.stackTimelinePage(
                            GalleryTimelineQuery.stackedBefore(LibrarySettings(), key!!, limit)
                        )
                        .asReversed()
                } else {
                    dao.stackTimelinePage(GalleryTimelineQuery.stacked(LibrarySettings(), key, limit))
                }
            LoadResult.Page(
                rows,
                timelinePrevKey(rows, prepending, key, limit) { it.media.toKeyset() },
                timelineNextKey(rows, prepending, limit) { it.media.toKeyset() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LoadResult.Error(failure)
        }
}
