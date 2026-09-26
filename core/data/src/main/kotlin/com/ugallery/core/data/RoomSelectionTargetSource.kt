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
            else -> CleanupScopeSql.from(scope) ?: "media_items m"
        }
        val where = mutableListOf<String>()
        if (query.scope is MediaQuery.Scope.VirtualAlbum) where += "vm.albumId=?"
        val physicalScope = query.scope as? MediaQuery.Scope.PhysicalAlbum
        if (physicalScope != null) {
            where += "m.volumeName=?"; args += physicalScope.volumeName
            where += "m.bucketId=?"; args += physicalScope.bucketId
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
        when (query.kindFilter) {
            MediaQuery.KindFilter.Images -> { where += "m.mediaType=?"; args += 1 }
            MediaQuery.KindFilter.Videos -> { where += "m.mediaType=?"; args += 3 }
            MediaQuery.KindFilter.Animated -> where +=
                "m.mediaType=1 AND LOWER(COALESCE(m.mimeType,'')) IN ('image/gif','image/webp')"
            MediaQuery.KindFilter.Raw -> where += rawImagePredicate("m")
            MediaQuery.KindFilter.ImagesAndVideos -> Unit
        }
        FolderSelectionSql.predicate(
            alias = "m",
            defaultSelected = query.folderMode == MediaQuery.FolderMode.AllExceptExcluded,
            rules = query.folderRules,
            args = args,
        )?.let(where::add)
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

    private fun rawImagePredicate(alias: String) =
        "$alias.mediaType=1 AND (LOWER(COALESCE($alias.mimeType,'')) IN (" +
            "'image/x-adobe-dng','image/x-canon-cr2','image/x-canon-cr3','image/x-nikon-nef'," +
            "'image/x-sony-arw','image/x-fuji-raf','image/x-panasonic-rw2','image/x-olympus-orf') " +
            "OR LOWER(COALESCE($alias.displayName,'')) GLOB '*.[dD][nN][gG]' " +
            "OR LOWER(COALESCE($alias.displayName,'')) GLOB '*.[cC][rR][23]' " +
            "OR LOWER(COALESCE($alias.displayName,'')) GLOB '*.[nN][eE][fF]' " +
            "OR LOWER(COALESCE($alias.displayName,'')) GLOB '*.[aA][rR][wW]' " +
            "OR LOWER(COALESCE($alias.displayName,'')) GLOB '*.[rR][aA][fF]')"
}

/** Shared FROM/WHERE fragments for the "Free up space" scopes, used by selection and viewer paging. */
internal object CleanupScopeSql {
    fun from(scope: MediaQuery.Scope): String? = when (scope) {
        is MediaQuery.Scope.BlurryCandidates ->
            "similarity_features sf JOIN media_items m ON m.volumeName=sf.volumeName " +
                "AND m.mediaStoreId=sf.mediaStoreId"
        is MediaQuery.Scope.ExactDuplicateGroup ->
            "duplicate_hashes dh JOIN media_items m ON m.volumeName=dh.volumeName " +
                "AND m.mediaStoreId=dh.mediaStoreId"
        is MediaQuery.Scope.LargeVideos, MediaQuery.Scope.Screenshots -> "media_items m"
        else -> null
    }

    fun addPredicates(scope: MediaQuery.Scope, where: MutableList<String>, args: MutableList<Any>) {
        when (scope) {
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
    }
}
