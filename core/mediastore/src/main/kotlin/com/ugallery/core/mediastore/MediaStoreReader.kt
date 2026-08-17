package com.ugallery.core.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind

data class MediaStoreRecord(
    val key: MediaKey,
    val kind: MediaKind,
    val mimeType: String?,
    val displayName: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val orientationDegrees: Int,
    val dateTakenMillis: Long?,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val generationAdded: Long,
    val generationModified: Long,
    val bucketId: Long?,
    val bucketDisplayName: String?,
    val relativePath: String?,
    val isFavorite: Boolean,
    val isTrashed: Boolean,
)

data class MediaStoreIdPage(
    val records: List<MediaStoreRecord>,
    val nextAfterId: Long?,
)

object MediaStoreProjection {
    val Columns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.VOLUME_NAME,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.Video.VideoColumns.DURATION,
        MediaStore.Images.ImageColumns.ORIENTATION,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.GENERATION_ADDED,
        MediaStore.MediaColumns.GENERATION_MODIFIED,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_FAVORITE,
        MediaStore.MediaColumns.IS_TRASHED,
    )
}

object MediaStoreUriFactory {
    fun uriFor(key: MediaKey): Uri = ContentUris.withAppendedId(
        MediaStore.Files.getContentUri(key.volumeName),
        key.mediaStoreId,
    )
}

/** Bounded, per-volume MediaStore reads for initial indexing. */
class MediaStoreReader(private val resolver: ContentResolver) {
    fun readIdPage(
        volumeName: String,
        afterId: Long = -1,
        limit: Int = 256,
    ): MediaStoreIdPage {
        require(volumeName.isNotBlank())
        require(afterId >= -1)
        require(limit in 1..1_000)

        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?) AND " +
                    "${MediaStore.MediaColumns._ID}>?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    afterId.toString(),
                ),
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

        val records = resolver.query(
            MediaStore.Files.getContentUri(volumeName),
            MediaStoreProjection.Columns,
            queryArgs,
            null,
        )?.use { cursor ->
            buildList(capacity = minOf(limit, cursor.count.coerceAtLeast(0))) {
                while (size < limit && cursor.moveToNext()) {
                    cursor.toRecord(volumeName)?.let(::add)
                }
            }
        }.orEmpty()

        return MediaStoreIdPage(
            records = records,
            nextAfterId = records.lastOrNull()?.key?.mediaStoreId,
        )
    }
}

private fun Cursor.toRecord(requestedVolume: String): MediaStoreRecord? {
    val mediaType = int(MediaStore.Files.FileColumns.MEDIA_TYPE)
    val kind = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaKind.Image
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.Video
        else -> return null
    }
    val rowVolume = stringOrNull(MediaStore.MediaColumns.VOLUME_NAME)
    check(rowVolume == null || rowVolume == requestedVolume) {
        "MediaStore returned volume $rowVolume for query volume $requestedVolume"
    }

    return MediaStoreRecord(
        key = MediaKey(requestedVolume, long(MediaStore.MediaColumns._ID)),
        kind = kind,
        mimeType = stringOrNull(MediaStore.MediaColumns.MIME_TYPE),
        displayName = stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME),
        sizeBytes = long(MediaStore.MediaColumns.SIZE),
        width = int(MediaStore.MediaColumns.WIDTH),
        height = int(MediaStore.MediaColumns.HEIGHT),
        durationMillis = long(MediaStore.Video.VideoColumns.DURATION),
        orientationDegrees = int(MediaStore.Images.ImageColumns.ORIENTATION),
        dateTakenMillis = longOrNull(MediaStore.MediaColumns.DATE_TAKEN)?.takeIf { it > 0 },
        dateAddedSeconds = long(MediaStore.MediaColumns.DATE_ADDED),
        dateModifiedSeconds = long(MediaStore.MediaColumns.DATE_MODIFIED),
        generationAdded = long(MediaStore.MediaColumns.GENERATION_ADDED),
        generationModified = long(MediaStore.MediaColumns.GENERATION_MODIFIED),
        bucketId = longOrNull(MediaStore.MediaColumns.BUCKET_ID),
        bucketDisplayName = stringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
        relativePath = stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
        isFavorite = int(MediaStore.MediaColumns.IS_FAVORITE) != 0,
        isTrashed = int(MediaStore.MediaColumns.IS_TRASHED) != 0,
    )
}

private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)
private fun Cursor.int(name: String): Int = getInt(column(name))
private fun Cursor.long(name: String): Long = getLong(column(name))
private fun Cursor.stringOrNull(name: String): String? = column(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.longOrNull(name: String): Long? = column(name).let { if (isNull(it)) null else getLong(it) }
