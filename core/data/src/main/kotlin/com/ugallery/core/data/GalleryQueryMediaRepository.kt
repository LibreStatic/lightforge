package com.ugallery.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Paged media for deterministic local smart collections used by the Photos highlight carousel. */
class GalleryQueryMediaRepository(private val database: GalleryDatabase) {
    fun media(query: MediaQuery): Flow<PagingData<TimelineMedia>> {
        require(query.scope == MediaQuery.Scope.Timeline)
        return Pager(
            config = PagingConfig(120, initialLoadSize = 180, prefetchDistance = 120, enablePlaceholders = false, maxSize = 600),
            pagingSourceFactory = { database.libraryDao().rawTimelinePagingSource(build(query)) },
        ).flow.map { page -> page.map { it.toTimelineMedia() } }
    }

    private fun build(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = mutableListOf("m.isAccessible=1", "m.isTrashed=0")
        when (query.archiveMode) {
            MediaQuery.ArchiveMode.Exclude -> where +=
                "NOT EXISTS (SELECT 1 FROM archived_media a WHERE " +
                    "a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)"
            MediaQuery.ArchiveMode.Include -> Unit
            MediaQuery.ArchiveMode.Only -> where +=
                "EXISTS (SELECT 1 FROM archived_media a WHERE " +
                    "a.volumeName=m.volumeName AND a.mediaStoreId=m.mediaStoreId)"
        }
        when (query.kindFilter) {
            MediaQuery.KindFilter.Images -> where += "m.mediaType=1"
            MediaQuery.KindFilter.Videos -> where += "m.mediaType=3"
            MediaQuery.KindFilter.Animated -> where +=
                "m.mediaType=1 AND LOWER(COALESCE(m.mimeType,'')) IN ('image/gif','image/webp')"
            MediaQuery.KindFilter.Raw -> where += "m.mediaType=1"
            MediaQuery.KindFilter.ImagesAndVideos -> Unit
        }
        query.fromTimelineMillisInclusive?.let { where += "m.timelineSortMillis>=?"; args += it }
        query.toTimelineMillisExclusive?.let { where += "m.timelineSortMillis<?"; args += it }
        val folders = when (query.folderMode) {
            MediaQuery.FolderMode.AllExceptExcluded -> query.excludedFolders
            MediaQuery.FolderMode.OnlyIncluded -> query.includedFolders
        }
        if (query.folderMode == MediaQuery.FolderMode.OnlyIncluded && folders.isEmpty()) {
            where += "0"
        } else if (folders.isNotEmpty()) {
            val clauses = folders.map { folder ->
                args += folder.volumeName
                args += folder.bucketId
                "(m.volumeName=? AND m.bucketId=?)"
            }
            where += if (query.folderMode == MediaQuery.FolderMode.AllExceptExcluded) {
                "NOT (${clauses.joinToString(" OR ")})"
            } else "(${clauses.joinToString(" OR ")})"
        }
        val direction = if (query.sort == MediaQuery.Sort.NewestFirst) "DESC" else "ASC"
        return SimpleSQLiteQuery(
            "SELECT m.* FROM media_items m WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY m.timelineSortMillis $direction,m.mediaStoreId $direction,m.volumeName $direction",
            args.toTypedArray(),
        )
    }
}
