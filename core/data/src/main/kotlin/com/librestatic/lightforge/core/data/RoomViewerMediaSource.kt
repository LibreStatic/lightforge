package com.librestatic.lightforge.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.selection.MediaQuery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ViewerMediaWindow(
    val items: List<TimelineMedia>,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

/**
 * Builds the window from keyset pages fetched with `radius + 1` rows. A stale anchor (renamed file,
 * repaired date, edited size) can make its own row show up in either page; it is dropped so the
 * viewer never receives the same item twice.
 */
internal fun assembleViewerWindow(
    previous: List<TimelineMedia>,
    anchor: TimelineMedia,
    next: List<TimelineMedia>,
    radius: Int,
): ViewerMediaWindow {
    val before = previous.filterNot { it.key == anchor.key }
    val after = next.filterNot { it.key == anchor.key }
    return ViewerMediaWindow(
        items = before.take(radius).asReversed() + anchor + after.take(radius),
        hasPrevious = before.size > radius,
        hasNext = after.size > radius,
    )
}

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
        return assembleViewerWindow(
            previous = previous.map(MediaItemEntity::toTimelineMedia),
            anchor = anchor,
            next = next.map(MediaItemEntity::toTimelineMedia),
            radius = radius,
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
            else -> CleanupScopeSql.from(scope)
                ?: error("Viewer navigation does not support ${scope::class.simpleName}")
        }
        val where = mutableListOf<String>()
        if (query.scope is MediaQuery.Scope.VirtualAlbum) where += "vm.albumId=?"
        (query.scope as? MediaQuery.Scope.PhysicalAlbum)?.let { scope ->
            where += "m.volumeName=?"; args += scope.volumeName
            where += "m.bucketId=?"; args += scope.bucketId
        }
        CleanupScopeSql.addPredicates(query.scope, where, args)
        where += "m.isAccessible=1"
        where += "m.isTrashed=?"; args += if (query.trashedOnly) 1 else 0
        when (query.archiveMode) {
            MediaQuery.ArchiveMode.Exclude -> where +=
                "NOT EXISTS (SELECT 1 FROM archived_media am WHERE " +
                    "am.volumeName=m.volumeName AND am.mediaStoreId=m.mediaStoreId)"
            MediaQuery.ArchiveMode.Include -> Unit
            MediaQuery.ArchiveMode.Only -> where +=
                "EXISTS (SELECT 1 FROM archived_media am WHERE " +
                    "am.volumeName=m.volumeName AND am.mediaStoreId=m.mediaStoreId)"
        }
        if (query.favoriteOnly) where += "m.isFavorite=1"
        kindsClause(listOf(query.kindFilter) + query.extraKinds, "m")?.let(where::add)
        FolderSelectionSql.predicate(
            alias = "m",
            defaultSelected = query.folderMode == MediaQuery.FolderMode.AllExceptExcluded,
            rules = query.folderRules,
            args = args,
        )?.let(where::add)
        query.fromTimelineMillisInclusive?.let { where += "m.timelineSortMillis>=?"; args += it }
        query.toTimelineMillisExclusive?.let { where += "m.timelineSortMillis<?"; args += it }

        val ascendingSource = query.sort == MediaQuery.Sort.OldestFirst
        val beforeInSource = side == PageSide.Previous
        // In ascending order, rows before/after the anchor have smaller/larger keys.
        // Descending order reverses those comparisons while keeping the assembled
        // viewer window in exactly the same order as the source gallery.
        val useGreaterThan = ascendingSource != beforeInSource
        val comparison = if (useGreaterThan) ">" else "<"
        val sortExpression = when (query.sortField) {
            MediaQuery.SortField.DateTaken -> "m.timelineSortMillis"
            MediaQuery.SortField.DateModified -> "m.dateModifiedSeconds"
            MediaQuery.SortField.Name -> "LOWER(COALESCE(m.displayName,''))"
            MediaQuery.SortField.Size -> "m.sizeBytes"
        }
        val sortAnchor: Any = when (query.sortField) {
            MediaQuery.SortField.DateTaken -> anchor.timelineSortMillis
            MediaQuery.SortField.DateModified -> anchor.dateModifiedSeconds
            MediaQuery.SortField.Name -> anchor.displayName.orEmpty()
            MediaQuery.SortField.Size -> anchor.sizeBytes
        }
        val components = buildList<Pair<String, Any>> {
            groupComponent(query.grouping, anchor)?.let(::add)
            add(sortExpression to sortAnchor)
            add("m.mediaStoreId" to anchor.key.mediaStoreId)
            add("m.volumeName" to anchor.key.volumeName)
        }
        fun placeholder(index: Int) = if (components[index].first == "LOWER(COALESCE(m.displayName,''))") "LOWER(?)" else "?"
        where += components.indices.joinToString(prefix = "(", postfix = ")", separator = " OR ") { index ->
            buildString {
                append('(')
                for (prefix in 0 until index) {
                    if (prefix > 0) append(" AND ")
                    append(components[prefix].first).append("=").append(placeholder(prefix))
                    args += components[prefix].second
                }
                if (index > 0) append(" AND ")
                append(components[index].first).append(' ').append(comparison).append(' ').append(placeholder(index)).append(')')
                args += components[index].second
            }
        }

        // Previous rows are queried nearest-first and reversed when the window is assembled.
        val queryAscending = if (beforeInSource) !ascendingSource else ascendingSource
        val direction = if (queryAscending) "ASC" else "DESC"
        args += limit
        val order = components.joinToString { "${it.first} $direction" }
        return SimpleSQLiteQuery(
            "SELECT m.* FROM $from WHERE ${where.joinToString(" AND ")} " +
                "ORDER BY $order LIMIT ?",
            args.toTypedArray(),
        )
    }

    private fun groupComponent(
        grouping: MediaQuery.Grouping,
        anchor: TimelineMedia,
    ): Pair<String, Any>? {
        if (grouping == MediaQuery.Grouping.None) return null
        val pattern = when (grouping) {
            MediaQuery.Grouping.Day -> "yyyy-MM-dd"
            MediaQuery.Grouping.Month -> "yyyy-MM"
            MediaQuery.Grouping.Year -> "yyyy"
            MediaQuery.Grouping.None -> error("Handled above")
        }
        val sqlPattern = when (grouping) {
            MediaQuery.Grouping.Day -> "%Y-%m-%d"
            MediaQuery.Grouping.Month -> "%Y-%m"
            MediaQuery.Grouping.Year -> "%Y"
            MediaQuery.Grouping.None -> error("Handled above")
        }
        val key = Instant.ofEpochMilli(anchor.timelineSortMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern))
        return "strftime('$sqlPattern', m.timelineSortMillis/1000, 'unixepoch', 'localtime')" to key
    }

    private enum class PageSide { Previous, Next }

    companion object {
        const val DefaultRadius = 80
        const val MaxRadius = 200
    }
}
