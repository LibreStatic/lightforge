package com.ugallery.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import kotlinx.coroutines.CancellationException

sealed interface AlbumTarget {
    data class Physical(val volumeName: String, val bucketId: Long) : AlbumTarget
    data class Virtual(val albumId: Long) : AlbumTarget
}

enum class AlbumMediaFilter(val mediaStoreType: Int) { All(0), Images(1), Videos(3) }
enum class AlbumSort { NewestFirst, OldestFirst }

class AlbumPagingSource(
    private val database: GalleryDatabase,
    private val target: AlbumTarget,
    private val filter: AlbumMediaFilter,
    private val sort: AlbumSort,
    private val dao: LibraryDao = database.libraryDao(),
) : PagingSource<TimelineKeyset, MediaItemEntity>() {
    private val observer = object : InvalidationTracker.Observer("media_items", "virtual_album_media") {
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
            val queriedRows = loadRows(params.key, limit + 1)
            val hasMore = queriedRows.size > limit
            val rows = queriedRows.take(limit)
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

    private suspend fun loadRows(key: TimelineKeyset?, limit: Int): List<MediaItemEntity> =
        when (val album = target) {
            is AlbumTarget.Physical -> loadPhysical(album, key, limit)
            is AlbumTarget.Virtual -> loadVirtual(album, key, limit)
        }

    private suspend fun loadPhysical(
        album: AlbumTarget.Physical,
        key: TimelineKeyset?,
        limit: Int,
    ) = when (sort) {
        AlbumSort.NewestFirst -> key?.let {
            dao.physicalAlbumPageAfter(
                album.volumeName, album.bucketId, filter.mediaStoreType,
                it.timelineSortMillis, it.mediaStoreId, limit,
            )
        } ?: dao.firstPhysicalAlbumPage(album.volumeName, album.bucketId, filter.mediaStoreType, limit)
        AlbumSort.OldestFirst -> key?.let {
            dao.physicalAlbumPageAfterOldest(
                album.volumeName, album.bucketId, filter.mediaStoreType,
                it.timelineSortMillis, it.mediaStoreId, limit,
            )
        } ?: dao.firstPhysicalAlbumPageOldest(album.volumeName, album.bucketId, filter.mediaStoreType, limit)
    }

    private suspend fun loadVirtual(
        album: AlbumTarget.Virtual,
        key: TimelineKeyset?,
        limit: Int,
    ) = when (sort) {
        AlbumSort.NewestFirst -> key?.let {
            dao.virtualAlbumPageAfter(
                album.albumId, filter.mediaStoreType, it.timelineSortMillis,
                it.mediaStoreId, it.volumeName, limit,
            )
        } ?: dao.firstVirtualAlbumPage(album.albumId, filter.mediaStoreType, limit)
        AlbumSort.OldestFirst -> key?.let {
            dao.virtualAlbumPageAfterOldest(
                album.albumId, filter.mediaStoreType, it.timelineSortMillis,
                it.mediaStoreId, it.volumeName, limit,
            )
        } ?: dao.firstVirtualAlbumPageOldest(album.albumId, filter.mediaStoreType, limit)
    }
}
