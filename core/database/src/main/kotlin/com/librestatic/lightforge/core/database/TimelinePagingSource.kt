package com.librestatic.lightforge.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException

/**
 * Bidirectional keyset source; no OFFSET query or full ID list is created. A null key loads from
 * the newest row and has nothing above it; any other key is an exclusive upper bound that can
 * still be paged upward toward the newest row.
 */
class TimelinePagingSource(
    private val database: GalleryDatabase,
    private val dao: LibraryDao = database.libraryDao(),
) : PagingSource<TimelineKeyset, MediaItemEntity>() {
    private val observer = object : InvalidationTracker.Observer("media_items", "archived_media") {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override fun getRefreshKey(state: PagingState<TimelineKeyset, MediaItemEntity>): TimelineKeyset? =
        state.timelineRefreshKey { it.toKeyset() }

    override suspend fun load(params: LoadParams<TimelineKeyset>): LoadResult<TimelineKeyset, MediaItemEntity> =
        try {
            val limit = params.loadSize.coerceIn(1, 500)
            val key = params.key
            val prepending = params is LoadParams.Prepend
            val rows = when {
                prepending -> dao.timelinePageBefore(
                    key!!.timelineSortMillis,
                    key.mediaStoreId,
                    key.volumeName,
                    limit,
                ).asReversed()
                key != null -> dao.timelinePageAfter(
                    key.timelineSortMillis,
                    key.mediaStoreId,
                    key.volumeName,
                    limit,
                )
                else -> dao.firstTimelinePage(limit)
            }
            LoadResult.Page(
                data = rows,
                prevKey = timelinePrevKey(rows, prepending, key, limit) { it.toKeyset() },
                nextKey = timelineNextKey(rows, prepending, limit) { it.toKeyset() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LoadResult.Error(failure)
        }
}

fun MediaItemEntity.toKeyset() = TimelineKeyset(timelineSortMillis, mediaStoreId, volumeName)

/**
 * A page loaded from the newest end (null key) has nothing above it. Every other page can be
 * extended upward, which also lets Paging re-load pages it dropped after exceeding maxSize.
 */
fun <T : Any> timelinePrevKey(
    rows: List<T>,
    prepending: Boolean,
    key: TimelineKeyset?,
    limit: Int,
    keyOf: (T) -> TimelineKeyset,
): TimelineKeyset? = when {
    rows.isEmpty() -> null
    prepending -> rows.first().takeIf { rows.size == limit }?.let(keyOf)
    key == null -> null
    else -> keyOf(rows.first())
}

fun <T : Any> timelineNextKey(
    rows: List<T>,
    prepending: Boolean,
    limit: Int,
    keyOf: (T) -> TimelineKeyset,
): TimelineKeyset? = when {
    rows.isEmpty() -> null
    prepending -> keyOf(rows.last())
    else -> rows.last().takeIf { rows.size == limit }?.let(keyOf)
}

private const val NearTopRows = 60

/**
 * Keeps the reader where they are when a source is invalidated. A window that still starts at
 * the newest row and is scrolled near it reloads from the top so newly added photos appear
 * there as before; otherwise the reload restarts at the item under the viewport.
 */
fun <T : Any> PagingState<TimelineKeyset, T>.timelineRefreshKey(
    keyOf: (T) -> TimelineKeyset,
): TimelineKeyset? {
    val position = anchorPosition ?: return null
    val atNewestEnd = pages.firstOrNull()?.prevKey == null
    if (atNewestEnd && position <= NearTopRows) return null
    val item = closestItemToPosition(position) ?: return null
    // The key is exclusive, so widen it past the item itself to keep that item in the reload.
    return keyOf(item).let { it.copy(volumeName = it.volumeName + "￿") }
}
