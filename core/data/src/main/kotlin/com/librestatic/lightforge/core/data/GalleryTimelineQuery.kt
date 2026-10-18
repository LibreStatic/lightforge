package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.model.RawFormats
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibraryGrouping
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort

internal object GalleryTimelineQuery {
    fun build(settings: LibrarySettings): SupportSQLiteQuery = buildQuery(settings)

    fun stacked(
        settings: LibrarySettings,
        after: com.librestatic.lightforge.core.database.TimelineKeyset? = null,
        limit: Int? = null,
    ): SupportSQLiteQuery = buildQuery(settings, true, after, limit)

    /** Rows newer than [before], nearest first; the caller reverses them into display order. */
    fun stackedBefore(
        settings: LibrarySettings,
        before: com.librestatic.lightforge.core.database.TimelineKeyset,
        limit: Int,
    ): SupportSQLiteQuery = buildQuery(settings, true, before = before, limit = limit)

    /** Rows the timeline shows per local day, in display order. */
    fun dayCounts(settings: LibrarySettings, collapsed: Boolean): SupportSQLiteQuery =
        buildQuery(settings, collapsed, dayCounts = true)

    /** How many rows are displayed before the first row at or after [boundaryMillis] in display order. */
    fun rowsBefore(
        settings: LibrarySettings,
        collapsed: Boolean,
        boundaryMillis: Long,
    ): SupportSQLiteQuery = buildQuery(settings, collapsed, rowsBeforeMillis = boundaryMillis)

    fun stackSelection(
        settings: LibrarySettings,
        id: String,
        revision: String,
    ): SupportSQLiteQuery = buildQuery(settings, stackId = id, revision = revision)

    private fun buildQuery(
        settings: LibrarySettings,
        collapsed: Boolean = false,
        after: com.librestatic.lightforge.core.database.TimelineKeyset? = null,
        limit: Int? = null,
        stackId: String? = null,
        revision: String? = null,
        before: com.librestatic.lightforge.core.database.TimelineKeyset? = null,
        dayCounts: Boolean = false,
        rowsBeforeMillis: Long? = null,
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where =
            mutableListOf(
                "isAccessible=1",
                "isTrashed=0",
                "NOT EXISTS (SELECT 1 FROM archived_media a WHERE " +
                    "a.volumeName=media_items.volumeName AND a.mediaStoreId=media_items.mediaStoreId)",
            )
        when (settings.filter) {
            LibraryFilter.All -> Unit
            LibraryFilter.Images -> where += "mediaType=1"
            LibraryFilter.Videos -> where += "mediaType=3"
            LibraryFilter.Animated -> where += "mediaType=1 AND ${animatedPredicate()}"
            LibraryFilter.Raw -> where += "mediaType=1 AND $RawPredicate"
        }
        FolderSelectionSql.predicate(
                alias = "media_items",
                defaultSelected =
                    settings.folderSelectionMode == FolderSelectionMode.AllExceptExcluded,
                rules = settings.folderRules,
                args = args,
            )
            ?.let(where::add)
        val direction = if (settings.ascending) "ASC" else "DESC"
        val selectedSort =
            when (settings.sort) {
                LibrarySort.DateTaken -> "timelineSortMillis"
                LibrarySort.DateModified -> "dateModifiedSeconds"
                LibrarySort.Name -> "LOWER(COALESCE(displayName,''))"
                LibrarySort.Size -> "sizeBytes"
            }
        val groupSort =
            if (settings == LibrarySettings()) "" else when (settings.grouping) {
                LibraryGrouping.Day ->
                    "strftime('%Y-%m-%d', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
                LibraryGrouping.Month ->
                    "strftime('%Y-%m', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
                LibraryGrouping.Year ->
                    "strftime('%Y', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
                LibraryGrouping.None -> ""
            }
        val eligible = "SELECT * FROM media_items WHERE ${where.joinToString(" AND ")}"
        if (stackId != null) {
            args += stackId
            args += requireNotNull(revision)
            return SimpleSQLiteQuery(
                """WITH eligible AS ($eligible)
                SELECT e.* FROM eligible e JOIN photo_stack_members p
                ON p.volumeName=e.volumeName AND p.mediaStoreId=e.mediaStoreId
                JOIN photo_stacks s ON s.stackId=p.stackId
                WHERE e.mediaType=1 AND s.stackId=? AND s.revision=? ORDER BY p.ordinal LIMIT 501""",
                args.toTypedArray(),
            )
        }
        // Rank only saved memberships, not a materialized copy of the whole library.
        // The same bound filter appears in the rank and outer scan; preserve both arg sets.
        val predicate = where.joinToString(" AND ")
        if (collapsed) args.addAll(args.toList())
        val projection = if (!collapsed) eligible else """
            WITH ranked AS (
                SELECT media_items.volumeName, media_items.mediaStoreId, s.stackId, s.revision,
                    COUNT(*) OVER (PARTITION BY s.stackId) AS memberCount,
                    ROW_NUMBER() OVER (PARTITION BY s.stackId ORDER BY
                        CASE WHEN media_items.volumeName=s.coverVolumeName AND media_items.mediaStoreId=s.coverMediaStoreId
                            THEN 0 ELSE 1 END, p.ordinal, media_items.volumeName, media_items.mediaStoreId) AS keepRank
                FROM photo_stack_members p JOIN media_items
                    ON p.volumeName=media_items.volumeName AND p.mediaStoreId=media_items.mediaStoreId
                JOIN photo_stacks s ON s.stackId=p.stackId WHERE $predicate AND media_items.mediaType=1
            ), representatives AS (SELECT * FROM ranked WHERE keepRank=1)
            SELECT media_items.*,
                CASE WHEN r.memberCount>1 THEN r.stackId END AS timelineStackId,
                CASE WHEN r.memberCount>1 THEN r.revision END AS timelineStackRevision,
                COALESCE(r.memberCount,1) AS timelineStackCount
            FROM media_items LEFT JOIN photo_stack_members p
                ON p.volumeName=media_items.volumeName AND p.mediaStoreId=media_items.mediaStoreId AND media_items.mediaType=1
            LEFT JOIN representatives r ON r.volumeName=media_items.volumeName AND r.mediaStoreId=media_items.mediaStoreId
            WHERE $predicate AND (p.stackId IS NULL OR r.stackId IS NOT NULL)
        """.trimIndent()
        // Apply a keyset only AFTER selecting the representative. Pushing it into eligible
        // would make older members of the same stack reappear on a subsequent page.
        val cursor =
            when {
                after != null -> {
                    require(collapsed && settings == LibrarySettings())
                    args.addAll(
                        listOf(
                            after.timelineSortMillis,
                            after.timelineSortMillis,
                            after.mediaStoreId,
                            after.timelineSortMillis,
                            after.mediaStoreId,
                            after.volumeName,
                        )
                    )
                    " WHERE timelineSortMillis < ? OR (timelineSortMillis = ? AND mediaStoreId < ?) " +
                        "OR (timelineSortMillis = ? AND mediaStoreId = ? AND volumeName < ?)"
                }
                before != null -> {
                    require(collapsed && settings == LibrarySettings())
                    args.addAll(
                        listOf(
                            before.timelineSortMillis,
                            before.timelineSortMillis,
                            before.mediaStoreId,
                            before.timelineSortMillis,
                            before.mediaStoreId,
                            before.volumeName,
                        )
                    )
                    " WHERE timelineSortMillis > ? OR (timelineSortMillis = ? AND mediaStoreId > ?) " +
                        "OR (timelineSortMillis = ? AND mediaStoreId = ? AND volumeName > ?)"
                }
                else -> ""
            }
        val bound =
            if (limit == null) ""
            else {
                require(limit in 1..500)
                args += limit
                " LIMIT ?"
            }
        val select = if (collapsed) "SELECT * FROM ($projection)$cursor" else projection
        if (dayCounts) {
            // With a lone MIN() aggregate SQLite takes the bare columns from that row: the day's earliest item.
            return SimpleSQLiteQuery(
                "SELECT strftime('%Y-%m-%d', timelineSortMillis/1000, 'unixepoch', 'localtime') AS day, " +
                    "COUNT(*) AS count, MIN(timelineSortMillis) AS firstMillis, mediaStoreId, volumeName, generationModified " +
                    "FROM ($select) GROUP BY day ORDER BY day $direction",
                args.toTypedArray(),
            )
        }
        if (rowsBeforeMillis != null) {
            args += rowsBeforeMillis
            val beyond = if (settings.ascending) "<" else ">="
            return SimpleSQLiteQuery(
                "SELECT COUNT(*) FROM ($select) WHERE timelineSortMillis $beyond ?",
                args.toTypedArray(),
            )
        }
        val orderDirection =
            if (before == null) direction else if (settings.ascending) "DESC" else "ASC"
        return SimpleSQLiteQuery(
            "$select ORDER BY $groupSort$selectedSort $orderDirection, " +
                "mediaStoreId $orderDirection, volumeName $orderDirection$bound",
            args.toTypedArray(),
        )
    }
}

/**
 * RAW filter built from [RawFormats] so it matches what the decoder and grid badge treat as RAW.
 * Shared by the timeline, viewer navigation, select-all and query-backed actions so all of them
 * cover the same rows. [alias] qualifies the columns (`m` → `m.mimeType`).
 */
internal fun rawPredicate(alias: String? = null): String = buildString {
    val prefix = alias?.let { "$it." }.orEmpty()
    val mime = "LOWER(COALESCE(${prefix}mimeType,''))"
    val name = "LOWER(COALESCE(${prefix}displayName,''))"
    append('(').append(mime).append(" IN (")
    RawFormats.mimeTypes.sorted().joinTo(this) { "'$it'" }
    append(") OR ").append(mime).append(" LIKE '%raw%'")
    RawFormats.extensions.sorted().forEach { append(" OR ").append(name).append(" LIKE '%.").append(it).append('\'') }
    append(')')
}

internal val RawPredicate: String = rawPredicate()

/**
 * Animated filter shared by every query site. MIME-based for now, so a static WebP still counts and
 * animated AVIF/HEIF does not; an index-time "animated" flag will replace it in this one place.
 */
internal fun animatedPredicate(alias: String? = null): String {
    val prefix = alias?.let { "$it." }.orEmpty()
    return "LOWER(COALESCE(${prefix}mimeType,'')) IN ('image/gif','image/webp')"
}
