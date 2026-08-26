package com.ugallery.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.ugallery.core.preferences.FolderSelectionMode
import com.ugallery.core.preferences.LibraryFilter
import com.ugallery.core.preferences.LibraryGrouping
import com.ugallery.core.preferences.LibrarySettings
import com.ugallery.core.preferences.LibrarySort
import com.ugallery.core.preferences.GalleryFolderToken

internal object GalleryTimelineQuery {
    fun build(settings: LibrarySettings): SupportSQLiteQuery {
        val args = mutableListOf<Any>()
        val where = mutableListOf(
            "isAccessible=1",
            "isTrashed=0",
            "NOT EXISTS (SELECT 1 FROM archived_media a WHERE " +
                "a.volumeName=media_items.volumeName AND a.mediaStoreId=media_items.mediaStoreId)",
        )
        when (settings.filter) {
            LibraryFilter.All -> Unit
            LibraryFilter.Images -> where += "mediaType=1"
            LibraryFilter.Videos -> where += "mediaType=3"
            LibraryFilter.Animated -> where += "mediaType=1 AND LOWER(COALESCE(mimeType,'')) IN ('image/gif','image/webp')"
            LibraryFilter.Raw -> where += "mediaType=1 AND (LOWER(COALESCE(mimeType,'')) IN (" +
                "'image/x-adobe-dng','image/x-canon-cr2','image/x-canon-cr3','image/x-nikon-nef'," +
                "'image/x-sony-arw','image/x-fuji-raf','image/x-panasonic-rw2','image/x-olympus-orf') " +
                "OR LOWER(COALESCE(displayName,'')) GLOB '*.[dD][nN][gG]' " +
                "OR LOWER(COALESCE(displayName,'')) GLOB '*.[cC][rR][23]' " +
                "OR LOWER(COALESCE(displayName,'')) GLOB '*.[nN][eE][fF]' " +
                "OR LOWER(COALESCE(displayName,'')) GLOB '*.[aA][rR][wW]' " +
                "OR LOWER(COALESCE(displayName,'')) GLOB '*.[rR][aA][fF]')"
        }
        val selectedTokens = when (settings.folderSelectionMode) {
            FolderSelectionMode.AllExceptExcluded -> settings.excludedFolders
            FolderSelectionMode.OnlyIncluded -> settings.includedFolders
        }.mapNotNull(GalleryFolderToken::decode)
        if (settings.folderSelectionMode == FolderSelectionMode.OnlyIncluded && selectedTokens.isEmpty()) {
            where += "0"
        } else if (selectedTokens.isNotEmpty()) {
            val clauses = selectedTokens.map {
                args += it.first
                args += it.second
                "(volumeName=? AND bucketId=?)"
            }
            where += when (settings.folderSelectionMode) {
                FolderSelectionMode.AllExceptExcluded -> "NOT (${clauses.joinToString(" OR ")})"
                FolderSelectionMode.OnlyIncluded -> "(${clauses.joinToString(" OR ")})"
            }
        }
        val direction = if (settings.ascending) "ASC" else "DESC"
        val selectedSort = when (settings.sort) {
            LibrarySort.DateTaken -> "timelineSortMillis"
            LibrarySort.DateModified -> "dateModifiedSeconds"
            LibrarySort.Name -> "LOWER(COALESCE(displayName,''))"
            LibrarySort.Size -> "sizeBytes"
        }
        val groupSort = when (settings.grouping) {
            LibraryGrouping.Day -> "strftime('%Y-%m-%d', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
            LibraryGrouping.Month -> "strftime('%Y-%m', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
            LibraryGrouping.Year -> "strftime('%Y', timelineSortMillis/1000, 'unixepoch', 'localtime') $direction, "
            LibraryGrouping.None -> ""
        }
        val sql = "SELECT * FROM media_items WHERE ${where.joinToString(" AND ")} " +
            "ORDER BY $groupSort$selectedSort $direction, mediaStoreId $direction, volumeName $direction"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

}
