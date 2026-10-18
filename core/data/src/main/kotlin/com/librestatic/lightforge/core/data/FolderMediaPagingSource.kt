package com.librestatic.lightforge.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.librestatic.lightforge.core.database.AlbumKeyset
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import kotlinx.coroutines.CancellationException

/** Builds the folder listing SQL; kept separate so the predicate is testable without a database. */
internal object FolderMediaSql {
    private const val NOT_ARCHIVED =
        "NOT EXISTS (SELECT 1 FROM archived_media a WHERE a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)"

    /** Same visibility as the folder cards: accessible, non-trashed, non-archived rows of one folder. */
    private fun where(
        volumeName: String,
        relativePath: String,
        includeSubfolders: Boolean,
        filter: AlbumMediaFilter,
        args: MutableList<Any>,
    ): MutableList<String> {
        val clauses = mutableListOf(
            FolderSelectionSql.folderPredicate("m", volumeName, relativePath, includeSubfolders, args),
            "m.isAccessible=1",
            "m.isTrashed=0",
            NOT_ARCHIVED,
        )
        if (filter != AlbumMediaFilter.All) {
            clauses += "m.mediaType=?"
            args += filter.mediaStoreType
        }
        return clauses
    }

    fun count(
        volumeName: String,
        relativePath: String,
        includeSubfolders: Boolean,
        filter: AlbumMediaFilter = AlbumMediaFilter.All,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val clauses = where(volumeName, relativePath, includeSubfolders, filter, args)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM media_items m WHERE ${clauses.joinToString(" AND ")}", args.toTypedArray())
    }

    fun page(
        volumeName: String,
        relativePath: String,
        includeSubfolders: Boolean,
        filter: AlbumMediaFilter,
        sort: AlbumSort,
        key: AlbumKeyset?,
        limit: Int,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val clauses = where(volumeName, relativePath, includeSubfolders, filter, args)
        val byName = sort == AlbumSort.NameAscending || sort == AlbumSort.NameDescending
        val bySize = sort == AlbumSort.SizeAscending || sort == AlbumSort.SizeDescending
        val field = when {
            byName -> "LOWER(COALESCE(m.displayName,''))"
            bySize -> "m.sizeBytes"
            else -> "m.timelineSortMillis"
        }
        val placeholder = if (byName) "LOWER(?)" else "?"
        val direction = if (sort.ascending) "ASC" else "DESC"
        val comparison = if (sort.ascending) ">" else "<"
        if (key != null) {
            val value: Any = when {
                byName -> key.displayName.orEmpty()
                bySize -> key.sizeBytes
                else -> key.timelineSortMillis
            }
            clauses += "($field $comparison $placeholder OR " +
                "($field=$placeholder AND m.mediaStoreId $comparison ?) OR " +
                "($field=$placeholder AND m.mediaStoreId=? AND m.volumeName $comparison ?))"
            args.addAll(listOf(value, value, key.mediaStoreId, value, key.mediaStoreId, key.volumeName))
        }
        args += limit
        return SimpleSQLiteQuery(
            "SELECT m.* FROM media_items m WHERE ${clauses.joinToString(" AND ")} " +
                "ORDER BY $field $direction, m.mediaStoreId $direction, m.volumeName $direction LIMIT ?",
            args.toTypedArray(),
        )
    }
}

/**
 * Pages the media stored in one folder (optionally with everything below it), with the album sorts.
 * Its row count equals the direct count on the folder's card when subfolders are excluded.
 */
class FolderMediaPagingSource(
    private val database: GalleryDatabase,
    private val volumeName: String,
    private val relativePath: String,
    private val includeSubfolders: Boolean,
    private val filter: AlbumMediaFilter = AlbumMediaFilter.All,
    private val sort: AlbumSort = AlbumSort.NewestFirst,
) : PagingSource<AlbumKeyset, MediaItemEntity>() {
    private val dao = database.libraryDao()
    private val observer = object : InvalidationTracker.Observer("media_items", "archived_media") {
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
            val queried = dao.rawSelectionPage(
                FolderMediaSql.page(volumeName, relativePath, includeSubfolders, filter, sort, params.key, limit + 1),
            )
            val hasMore = queried.size > limit
            val rows = queried.take(limit)
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
}
