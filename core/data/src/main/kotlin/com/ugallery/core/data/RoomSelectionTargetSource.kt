package com.ugallery.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.selection.MediaQuery

class RoomSelectionTargetSource(database: GalleryDatabase) {
    private val dao = database.libraryDao()

    suspend fun page(query: MediaQuery, afterExclusive: MediaKey?, limit: Int): List<MediaActionTarget> {
        require(limit in 1..500)
        return dao.rawSelectionPage(build(query, afterExclusive, limit)).map { row ->
            MediaActionTarget(
                MediaKey(row.volumeName, row.mediaStoreId),
                if (row.mediaType == 3) MediaKind.Video else MediaKind.Image,
            )
        }
    }

    suspend fun count(query: MediaQuery): Long = dao.rawSelectionCount(build(query, null, null))

    private fun build(query: MediaQuery, after: MediaKey?, limit: Int?): SimpleSQLiteQuery {
        require(query.sort == MediaQuery.Sort.NewestFirst || query.sort == MediaQuery.Sort.OldestFirst)
        val args = mutableListOf<Any>()
        val from = when (val scope = query.scope) {
            is MediaQuery.Scope.VirtualAlbum -> {
                args += scope.albumId
                "virtual_album_media vm JOIN media_items m ON m.volumeName=vm.volumeName " +
                    "AND m.mediaStoreId=vm.mediaStoreId"
            }
            is MediaQuery.Scope.Search -> error("Search selection requires the M3 AppSearch bridge")
            is MediaQuery.Scope.BlurryCandidates ->
                "similarity_features sf JOIN media_items m ON m.volumeName=sf.volumeName " +
                    "AND m.mediaStoreId=sf.mediaStoreId"
            is MediaQuery.Scope.ExactDuplicateGroup ->
                "duplicate_hashes dh JOIN media_items m ON m.volumeName=dh.volumeName " +
                    "AND m.mediaStoreId=dh.mediaStoreId"
            else -> "media_items m"
        }
        val where = mutableListOf<String>()
        if (query.scope is MediaQuery.Scope.VirtualAlbum) where += "vm.albumId=?"
        val physicalScope = query.scope as? MediaQuery.Scope.PhysicalAlbum
        if (physicalScope != null) {
            where += "m.volumeName=?"; args += physicalScope.volumeName
            where += "m.bucketId=?"; args += physicalScope.bucketId
        }
        when (val scope = query.scope) {
            is MediaQuery.Scope.LargeVideos -> {
                where += "m.mediaType=3"; where += "m.sizeBytes>=?"; args += scope.minimumBytes
            }
            MediaQuery.Scope.Screenshots -> where += "(LOWER(COALESCE(m.bucketDisplayName,'')) LIKE '%screenshot%' OR LOWER(COALESCE(m.relativePath,'')) LIKE '%screenshot%' OR LOWER(COALESCE(m.displayName,'')) LIKE '%screenshot%')"
            is MediaQuery.Scope.BlurryCandidates -> {
                where += "sf.algorithmVersion=?"; args += scope.algorithmVersion
                where += "sf.generationModified=m.generationModified"
                where += "sf.blurScore<=?"; args += scope.maximumScore
            }
            is MediaQuery.Scope.ExactDuplicateGroup -> {
                where += "dh.hashVersion=?"; args += scope.hashVersion
                where += "dh.sha256=?"; args += scope.sha256.lowercase()
                where += "dh.sizeBytes=?"; args += scope.sizeBytes
                where += "dh.generationModified=m.generationModified"
            }
            else -> Unit
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
        if (after != null) {
            where += "(m.volumeName>? OR (m.volumeName=? AND m.mediaStoreId>?))"
            args += after.volumeName; args += after.volumeName; args += after.mediaStoreId
        }
        val select = if (limit == null) "SELECT COUNT(*)" else "SELECT m.*"
        val tail = if (limit == null) "" else {
            args += limit
            " ORDER BY m.volumeName ASC, m.mediaStoreId ASC LIMIT ?"
        }
        return SimpleSQLiteQuery("$select FROM $from WHERE ${where.joinToString(" AND ")}$tail", args.toTypedArray())
    }
}
