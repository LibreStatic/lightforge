package com.ugallery.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException

sealed interface AlbumTarget {
    data class Physical(val volumeName: String, val bucketId: Long) : AlbumTarget
    data class Virtual(val albumId: Long) : AlbumTarget
}

enum class AlbumMediaFilter(val mediaStoreType: Int) { All(0), Images(1), Videos(3) }
enum class AlbumSort {
    NewestFirst, OldestFirst, NameAscending, NameDescending, SizeAscending, SizeDescending;
    val ascending: Boolean get() = this == OldestFirst || this == NameAscending || this == SizeAscending
}

/** Carries the exact stored field; SQLite normalizes names on both sides of the cursor. */
data class AlbumKeyset(
    val timelineSortMillis: Long,
    val mediaStoreId: Long,
    val volumeName: String,
    val displayName: String?,
    val sizeBytes: Long,
)

class AlbumPagingSource(
    private val database: GalleryDatabase,
    private val target: AlbumTarget,
    private val filter: AlbumMediaFilter,
    private val sort: AlbumSort,
    private val dao: LibraryDao = database.libraryDao(),
) : PagingSource<AlbumKeyset, MediaItemEntity>() {
    private val observer = object : InvalidationTracker.Observer("media_items", "virtual_album_media") {
        override fun onInvalidated(tables: Set<String>) = invalidate()
    }

    init {
        database.invalidationTracker.addObserver(observer)
        registerInvalidatedCallback { database.invalidationTracker.removeObserver(observer) }
    }

    override fun getRefreshKey(state: PagingState<AlbumKeyset, MediaItemEntity>): AlbumKeyset? = null

    override suspend fun load(params: LoadParams<AlbumKeyset>): LoadResult<AlbumKeyset, MediaItemEntity> =
        try {
            val limit = params.loadSize.coerceIn(1, 500)
            val queriedRows = loadRows(params.key, limit + 1)
            val hasMore = queriedRows.size > limit
            val rows = queriedRows.take(limit)
            LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = rows.lastOrNull()?.takeIf { hasMore }?.let {
                    AlbumKeyset(it.timelineSortMillis, it.mediaStoreId, it.volumeName, it.displayName, it.sizeBytes)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            LoadResult.Error(failure)
        }

    private suspend fun loadRows(key: AlbumKeyset?, limit: Int): List<MediaItemEntity> =
        if (sort == AlbumSort.NewestFirst || sort == AlbumSort.OldestFirst) {
            when (val album = target) {
                is AlbumTarget.Physical -> loadPhysical(album, key, limit)
                is AlbumTarget.Virtual -> loadVirtual(album, key, limit)
            }
        } else dao.rawSelectionPage(orderedQuery(key, limit))

    private fun orderedQuery(key: AlbumKeyset?, limit: Int): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = mutableListOf("m.isAccessible=1", "m.isTrashed=0")
        val from = when (val album = target) {
            is AlbumTarget.Physical -> {
                where += "m.volumeName=?"; args += album.volumeName
                where += "m.bucketId=?"; args += album.bucketId
                "media_items m"
            }
            is AlbumTarget.Virtual -> {
                where += "vm.albumId=?"; args += album.albumId
                "virtual_album_media vm JOIN media_items m ON m.volumeName=vm.volumeName AND m.mediaStoreId=vm.mediaStoreId"
            }
        }
        if (filter != AlbumMediaFilter.All) {
            where += "m.mediaType=?"; args += filter.mediaStoreType
        }
        val byName = sort == AlbumSort.NameAscending || sort == AlbumSort.NameDescending
        val field = if (byName) "LOWER(COALESCE(m.displayName,''))" else "m.sizeBytes"
        val placeholder = if (byName) "LOWER(?)" else "?"
        val direction = if (sort.ascending) "ASC" else "DESC"
        val comparison = if (sort.ascending) ">" else "<"
        if (key != null) {
            val value: Any = if (byName) key.displayName.orEmpty() else key.sizeBytes
            where += "($field $comparison $placeholder OR " +
                "($field=$placeholder AND m.mediaStoreId $comparison ?) OR " +
                "($field=$placeholder AND m.mediaStoreId=? AND m.volumeName $comparison ?))"
            args.addAll(listOf(value, value, key.mediaStoreId, value, key.mediaStoreId, key.volumeName))
        }
        args += limit
        return SimpleSQLiteQuery("SELECT m.* FROM $from WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY $field $direction, m.mediaStoreId $direction, m.volumeName $direction LIMIT ?", args.toTypedArray())
    }

    private suspend fun loadPhysical(
        album: AlbumTarget.Physical,
        key: AlbumKeyset?,
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
        else -> error("Non-date ordering uses orderedQuery")
    }

    private suspend fun loadVirtual(
        album: AlbumTarget.Virtual,
        key: AlbumKeyset?,
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
        else -> error("Non-date ordering uses orderedQuery")
    }
}
