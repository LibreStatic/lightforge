package com.ugallery.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ugallery.core.preferences.FolderSelectionMode
import com.ugallery.core.preferences.LibraryFilter
import com.ugallery.core.preferences.LibraryGrouping
import com.ugallery.core.preferences.LibrarySettings
import com.ugallery.core.preferences.LibrarySort

internal object GalleryTimelineQuery {
    fun build(settings: LibrarySettings): SupportSQLiteQuery = buildQuery(settings)

    fun stacked(
        settings: LibrarySettings,
        after: com.ugallery.core.database.TimelineKeyset? = null,
        limit: Int? = null,
    ): SupportSQLiteQuery = buildQuery(settings, true, after, limit)

    fun stackSelection(
        settings: LibrarySettings,
        id: String,
        revision: String,
    ): SupportSQLiteQuery = buildQuery(settings, stackId = id, revision = revision)

    private fun buildQuery(
        settings: LibrarySettings,
        collapsed: Boolean = false,
        after: com.ugallery.core.database.TimelineKeyset? = null,
        limit: Int? = null,
        stackId: String? = null,
        revision: String? = null,
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
            LibraryFilter.Animated ->
                where +=
                    "mediaType=1 AND LOWER(COALESCE(mimeType,'')) IN ('image/gif','image/webp')"
            LibraryFilter.Raw ->
                where +=
                    "mediaType=1 AND (LOWER(COALESCE(mimeType,'')) IN (" +
                        "'image/x-adobe-dng','image/x-canon-cr2','image/x-canon-cr3','image/x-nikon-nef'," +
                        "'image/x-sony-arw','image/x-fuji-raf','image/x-panasonic-rw2','image/x-olympus-orf') " +
                        "OR LOWER(COALESCE(displayName,'')) GLOB '*.[dD][nN][gG]' " +
                        "OR LOWER(COALESCE(displayName,'')) GLOB '*.[cC][rR][23]' " +
                        "OR LOWER(COALESCE(displayName,'')) GLOB '*.[nN][eE][fF]' " +
                        "OR LOWER(COALESCE(displayName,'')) GLOB '*.[aA][rR][wW]' " +
                        "OR LOWER(COALESCE(displayName,'')) GLOB '*.[rR][aA][fF]')"
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
            if (after == null) ""
            else {
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
        val bound =
            if (limit == null) ""
            else {
                require(limit in 1..500)
                args += limit
                " LIMIT ?"
            }
        val select = if (collapsed) "SELECT * FROM ($projection)$cursor" else projection
        return SimpleSQLiteQuery(
            "$select ORDER BY $groupSort$selectedSort $direction, " +
                "mediaStoreId $direction, volumeName $direction$bound",
            args.toTypedArray(),
        )
    }
}
