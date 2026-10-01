package com.librestatic.lightforge.feature.picker

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.FolderSelectionPolicy
import com.librestatic.lightforge.core.preferences.FolderSelectionTarget
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import kotlinx.coroutines.flow.first

/** One photo or video offered to another app. */
data class PickerMedia(
    val key: MediaKey,
    val isVideo: Boolean,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long,
    val durationMillis: Long,
    val dateModifiedSeconds: Long,
    val generationModified: Long,
    val isFavorite: Boolean,
) {
    /**
     * Typed MediaStore URI handed to the caller. Older callers still query `_data` or call
     * MediaStore helpers on it, which a FileProvider or documents URI would not support.
     */
    val contentUri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.getContentUri(key.volumeName)
            else MediaStore.Images.Media.getContentUri(key.volumeName),
            key.mediaStoreId,
        )
}

/** A MediaStore bucket (device folder) that holds at least one item the caller accepts. */
data class PickerAlbum(
    val volumeName: String,
    val bucketId: Long,
    /** Folder name, or its path when two folders share a name; null for the storage root. */
    val name: String?,
    val relativePath: String?,
    val count: Int,
    val cover: MediaKey,
    val coverIsVideo: Boolean,
    val latestModifiedSeconds: Long,
)

data class SqlSelection(val sql: String, val args: List<String> = emptyList()) {
    companion object {
        val Nothing = SqlSelection("0")

        fun allOf(vararg parts: SqlSelection?): SqlSelection? {
            val present = parts.filterNotNull()
            if (present.isEmpty()) return null
            if (present.size == 1) return present.single()
            return SqlSelection(present.joinToString(" AND ") { "(${it.sql})" }, present.flatMap(SqlSelection::args))
        }
    }
}

/** Library folder rules from Settings → Library, so excluded folders stay out of other apps too. */
data class FolderRules(
    val defaultSelected: Boolean,
    val rules: Map<FolderSelectionTarget, Boolean>,
) {
    fun isVisible(volumeName: String, bucketId: Long, relativePath: String?): Boolean =
        FolderSelectionPolicy.isSelected(defaultSelected, rules, volumeName, bucketId, relativePath)

    companion object {
        val Everything = FolderRules(defaultSelected = true, rules = emptyMap())

        suspend fun load(context: Context): FolderRules {
            val library = GallerySettingsRepository(context).settings.first().library
            return FolderRules(
                defaultSelected = library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded,
                rules = library.folderRules,
            )
        }
    }
}

data class BucketVisibility(val volumeName: String, val bucketId: Long, val visible: Boolean)

/** SQL for MediaStore's Files table; kept free of Android types so it can be unit tested. */
object PickerSql {
    fun kindSelection(request: PickRequest): SqlSelection {
        val parts = listOfNotNull(
            kindPart(FileColumns.MEDIA_TYPE_IMAGE, request.images),
            kindPart(FileColumns.MEDIA_TYPE_VIDEO, request.videos),
        )
        if (parts.isEmpty()) return SqlSelection.Nothing
        return SqlSelection(parts.joinToString(" OR ") { "(${it.sql})" }, parts.flatMap(SqlSelection::args))
    }

    private fun kindPart(mediaType: Int, filter: KindFilter): SqlSelection? = when (filter) {
        KindFilter.None -> null
        KindFilter.Any -> SqlSelection("${FileColumns.MEDIA_TYPE}=$mediaType")
        is KindFilter.Exact -> {
            val types = filter.mimeTypes.sorted()
            SqlSelection(
                "${FileColumns.MEDIA_TYPE}=$mediaType AND ${FileColumns.MIME_TYPE} IN (${types.joinToString(",") { "?" }})",
                types,
            )
        }
    }

    /**
     * Folder predicate equivalent to [FolderRules] for the buckets that exist right now. Null means
     * every folder is visible. Bucket ids are numeric and inlined; volume names travel as arguments.
     */
    fun folderSelection(rules: FolderRules, buckets: Collection<BucketVisibility>): SqlSelection? {
        if (rules.rules.isEmpty()) return if (rules.defaultSelected) null else SqlSelection.Nothing
        return if (rules.defaultSelected) {
            val hidden = buckets.filterNot(BucketVisibility::visible)
            if (hidden.isEmpty()) null
            else bucketGroups(hidden).let {
                // NOT over a NULL bucket_id would be NULL, dropping loose files the rules never named.
                SqlSelection("${FileColumns.BUCKET_ID} IS NULL OR NOT (${it.sql})", it.args)
            }
        } else {
            val visible = buckets.filter(BucketVisibility::visible)
            if (visible.isEmpty()) SqlSelection.Nothing else bucketGroups(visible)
        }
    }

    private fun bucketGroups(buckets: Collection<BucketVisibility>): SqlSelection {
        val byVolume = buckets.groupBy(BucketVisibility::volumeName).toSortedMap()
        return SqlSelection(
            byVolume.values.joinToString(" OR ") { group ->
                val ids = group.map(BucketVisibility::bucketId).distinct().sorted().joinToString(",")
                "(${FileColumns.VOLUME_NAME}=? AND ${FileColumns.BUCKET_ID} IN ($ids))"
            },
            byVolume.keys.toList(),
        )
    }

    /** Folders that share a name (Pictures/UGallery, Movies/UGallery) are labelled by their path. */
    fun disambiguate(albums: List<PickerAlbum>): List<PickerAlbum> {
        val clashing = albums.groupingBy { it.name?.lowercase() }.eachCount().filter { it.value > 1 }.keys
        return albums.map { album ->
            val path = album.relativePath?.trim('/')?.takeIf(String::isNotBlank)
            if (album.name != null && album.name.lowercase() in clashing && path != null) album.copy(name = path) else album
        }
    }

    fun albumSelection(volumeName: String, bucketId: Long) = SqlSelection(
        "${FileColumns.VOLUME_NAME}=? AND ${FileColumns.BUCKET_ID}=$bucketId",
        listOf(volumeName),
    )

    fun nameSelection(query: String): SqlSelection {
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return SqlSelection("${FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'", listOf("%$escaped%"))
    }
}

/** Everything a picker session needs before it can page media: folders and the folder filter. */
data class PickerCatalog(
    val albums: List<PickerAlbum>,
    val baseSelection: SqlSelection?,
)

/** Direct MediaStore reads: they work before the gallery has finished (or ever run) its own index. */
class PickerMediaStore(private val resolver: ContentResolver) {
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Scans the accepted media once to list folders, newest first, and build the folder filter. */
    fun catalog(request: PickRequest, rules: FolderRules, signal: CancellationSignal? = null): PickerCatalog {
        val kind = PickerSql.kindSelection(request)
        val albums = LinkedHashMap<Pair<String, Long>, AlbumAccumulator>()
        query(filesUri, ScanProjection, kind, sortOrder = SORT_NEWEST, signal = signal)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(FileColumns._ID)
            val volume = cursor.getColumnIndexOrThrow(FileColumns.VOLUME_NAME)
            val type = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
            val bucket = cursor.getColumnIndexOrThrow(FileColumns.BUCKET_ID)
            val bucketName = cursor.getColumnIndexOrThrow(FileColumns.BUCKET_DISPLAY_NAME)
            val path = cursor.getColumnIndexOrThrow(FileColumns.RELATIVE_PATH)
            val modified = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                signal?.throwIfCanceled()
                if (cursor.isNull(bucket)) continue
                val volumeName = cursor.getString(volume) ?: continue
                val bucketId = cursor.getLong(bucket)
                val album = albums.getOrPut(volumeName to bucketId) {
                    AlbumAccumulator(
                        volumeName = volumeName,
                        bucketId = bucketId,
                        name = cursor.getString(bucketName)?.takeIf(String::isNotBlank)
                            ?: cursor.getString(path)?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank),
                        relativePath = cursor.getString(path),
                        cover = MediaKey(volumeName, cursor.getLong(id)),
                        coverIsVideo = cursor.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO,
                    )
                }
                album.count++
                album.latestModifiedSeconds = maxOf(album.latestModifiedSeconds, cursor.getLong(modified))
            }
        }
        val visibility = albums.values.map {
            BucketVisibility(it.volumeName, it.bucketId, rules.isVisible(it.volumeName, it.bucketId, it.relativePath))
        }
        val visibleKeys = visibility.filter(BucketVisibility::visible).mapTo(HashSet()) { it.volumeName to it.bucketId }
        return PickerCatalog(
            albums = PickerSql.disambiguate(
                albums.values.filter { (it.volumeName to it.bucketId) in visibleKeys }.map(AlbumAccumulator::toAlbum),
            ),
            baseSelection = SqlSelection.allOf(kind, PickerSql.folderSelection(rules, visibility)),
        )
    }

    fun page(selection: SqlSelection?, offset: Int, limit: Int, signal: CancellationSignal? = null): List<PickerMedia> =
        query(filesUri, MediaProjection, selection, SORT_NEWEST, limit = limit, offset = offset, signal = signal)
            ?.use(::readMedia)
            .orEmpty()

    fun find(key: MediaKey): PickerMedia? = query(
        MediaStore.Files.getContentUri(key.volumeName),
        MediaProjection,
        SqlSelection(
            "${FileColumns._ID}=${key.mediaStoreId} AND ${FileColumns.MEDIA_TYPE} IN " +
                "(${FileColumns.MEDIA_TYPE_IMAGE},${FileColumns.MEDIA_TYPE_VIDEO})",
        ),
        sortOrder = null,
    )?.use(::readMedia)?.firstOrNull()

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: SqlSelection?,
        sortOrder: String?,
        limit: Int? = null,
        offset: Int? = null,
        signal: CancellationSignal? = null,
    ): Cursor? {
        val args = Bundle().apply {
            selection?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it.sql)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it.args.toTypedArray())
            }
            sortOrder?.let { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, it) }
            limit?.let { putInt(ContentResolver.QUERY_ARG_LIMIT, it) }
            offset?.let { putInt(ContentResolver.QUERY_ARG_OFFSET, it) }
        }
        return resolver.query(uri, projection, args, signal)
    }

    private fun readMedia(cursor: Cursor): List<PickerMedia> {
        val id = cursor.getColumnIndexOrThrow(FileColumns._ID)
        val volume = cursor.getColumnIndexOrThrow(FileColumns.VOLUME_NAME)
        val type = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val mime = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
        val name = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
        val size = cursor.getColumnIndexOrThrow(FileColumns.SIZE)
        val duration = cursor.getColumnIndexOrThrow(FileColumns.DURATION)
        val modified = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        val generation = cursor.getColumnIndexOrThrow(FileColumns.GENERATION_MODIFIED)
        val favorite = cursor.getColumnIndexOrThrow(FileColumns.IS_FAVORITE)
        return buildList(cursor.count) {
            while (cursor.moveToNext()) {
                val volumeName = cursor.getString(volume) ?: continue
                add(
                    PickerMedia(
                        key = MediaKey(volumeName, cursor.getLong(id)),
                        isVideo = cursor.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO,
                        mimeType = cursor.getString(mime),
                        displayName = cursor.getString(name),
                        sizeBytes = cursor.getLong(size),
                        durationMillis = cursor.getLong(duration),
                        dateModifiedSeconds = cursor.getLong(modified),
                        generationModified = cursor.getLong(generation).coerceAtLeast(0),
                        isFavorite = cursor.getInt(favorite) != 0,
                    ),
                )
            }
        }
    }

    private class AlbumAccumulator(
        val volumeName: String,
        val bucketId: Long,
        val name: String?,
        val relativePath: String?,
        val cover: MediaKey,
        val coverIsVideo: Boolean,
    ) {
        var count = 0
        var latestModifiedSeconds = 0L

        fun toAlbum() = PickerAlbum(volumeName, bucketId, name, relativePath, count, cover, coverIsVideo, latestModifiedSeconds)
    }

    companion object {
        /** Capture time when known, else the file's own timestamp, so screenshots and downloads interleave. */
        const val SORT_NEWEST =
            "COALESCE(${FileColumns.DATE_TAKEN}, ${FileColumns.DATE_MODIFIED} * 1000) DESC, ${FileColumns._ID} DESC"

        private val ScanProjection = arrayOf(
            FileColumns._ID,
            FileColumns.VOLUME_NAME,
            FileColumns.MEDIA_TYPE,
            FileColumns.BUCKET_ID,
            FileColumns.BUCKET_DISPLAY_NAME,
            FileColumns.RELATIVE_PATH,
            FileColumns.DATE_MODIFIED,
        )

        private val MediaProjection = arrayOf(
            FileColumns._ID,
            FileColumns.VOLUME_NAME,
            FileColumns.MEDIA_TYPE,
            FileColumns.MIME_TYPE,
            FileColumns.DISPLAY_NAME,
            FileColumns.SIZE,
            FileColumns.DURATION,
            FileColumns.DATE_MODIFIED,
            FileColumns.GENERATION_MODIFIED,
            FileColumns.IS_FAVORITE,
        )
    }
}
