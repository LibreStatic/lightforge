package com.ugallery.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.MediaQuery

data class ViewerMediaWindow(
    val items: List<TimelineMedia>,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/** Loads a bounded, source-ordered window around the viewer anchor. */
class RoomViewerMediaSource(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    suspend fun window(
        query: MediaQuery,
        anchor: TimelineMedia,
        radius: Int = DefaultRadius,
    ): ViewerMediaWindow {
        require(radius in 1..MaxRadius)
        require(query.scope !is MediaQuery.Scope.Search) {
            "Search viewer windows are resolved by the search result source"
        }
        val previous = page(query, anchor, PageSide.Previous, radius + 1)
        val next = page(query, anchor, PageSide.Next, radius + 1)
        return ViewerMediaWindow(
            items = previous.take(radius).asReversed().map(MediaItemEntity::toTimelineMedia) +
                anchor + next.take(radius).map(MediaItemEntity::toTimelineMedia),
            hasPrevious = previous.size > radius,
            hasNext = next.size > radius,
        )
    }

    private suspend fun page(
        query: MediaQuery,
        anchor: TimelineMedia,
        side: PageSide,
        limit: Int,
    ): List<MediaItemEntity> = dao.rawSelectionPage(build(query, anchor, side, limit))

    private fun build(
        query: MediaQuery,
        anchor: TimelineMedia,
        side: PageSide,
        limit: Int,
    ): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val from = when (val scope = query.scope) {
            is MediaQuery.Scope.VirtualAlbum -> {
                args += scope.albumId
                "virtual_album_media vm JOIN media_items m ON m.volumeName=vm.volumeName " +
                    "AND m.mediaStoreId=vm.mediaStoreId"
            }
            is MediaQuery.Scope.PhysicalAlbum,
            MediaQuery.Scope.Timeline -> "media_items m"
            else -> error("Viewer navigation does not support ${scope::class.simpleName}")
        }
        val where = mutableListOf<String>()
        if (query.scope is MediaQuery.Scope.VirtualAlbum) where += "vm.albumId=?"
        (query.scope as? MediaQuery.Scope.PhysicalAlbum)?.let { scope ->
            where += "m.volumeName=?"; args += scope.volumeName
            where += "m.bucketId=?"; args += scope.bucketId
        }
        where += "m.isAccessible=1"
        where += "m.isTrashed=?"; args += if (query.trashedOnly) 1 else 0
        if (query.favoriteOnly) where += "m.isFavorite=1"
        when (query.kindFilter) {
            MediaQuery.KindFilter.Images -> { where += "m.mediaType=?"; args += 1 }
            MediaQuery.KindFilter.Videos -> { where += "m.mediaType=?"; args += 3 }
            MediaQuery.KindFilter.ImagesAndVideos -> Unit
        }
        query.fromTimelineMillisInclusive?.let { where += "m.timelineSortMillis>=?"; args += it }
        query.toTimelineMillisExclusive?.let { where += "m.timelineSortMillis<?"; args += it }

        val ascendingSource = query.sort == MediaQuery.Sort.OldestFirst
        val beforeInSource = side == PageSide.Previous
        val useGreaterThan = ascendingSource == beforeInSource
        val comparison = if (useGreaterThan) ">" else "<"
        where += "(" +
            "m.timelineSortMillis $comparison ? OR " +
            "(m.timelineSortMillis=? AND m.mediaStoreId $comparison ?) OR " +
            "(m.timelineSortMillis=? AND m.mediaStoreId=? AND m.volumeName $comparison ?)" +
            ")"
        args += anchor.timelineSortMillis
        args += anchor.timelineSortMillis
        args += anchor.key.mediaStoreId
        args += anchor.timelineSortMillis
        args += anchor.key.mediaStoreId
        args += anchor.key.volumeName

        // Previous rows are queried nearest-first and reversed when the window is assembled.
        val queryAscending = if (beforeInSource) !ascendingSource else ascendingSource
        val direction = if (queryAscending) "ASC" else "DESC"
        args += limit
        return SimpleSQLiteQuery(
            "SELECT m.* FROM $from WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY m.timelineSortMillis $direction, m.mediaStoreId $direction, " +
                "m.volumeName $direction LIMIT ?",
            args.toTypedArray(),
        )
    }

    private enum class PageSide { Previous, Next }

    companion object {
        const val DefaultRadius = 80
        const val MaxRadius = 200
    }
}
