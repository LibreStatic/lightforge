package com.librestatic.lightforge.core.mediastore

import android.content.ContentResolver
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind

/**
 * Whether a raw MediaStore record still needs its file opened: an image whose animation can only be told
 * from its header (GIF is decided by MIME type alone), or a video MediaProvider left without duration or
 * size (FLV and other containers its retriever cannot parse). The one rule shared by the probes, the
 * indexer that flags rows as pending and the background pass that resolves them.
 */
fun MediaStoreRecord.needsFileProbe(): Boolean = needsFileProbe(kind, mimeType, durationMillis, width, height)

fun needsFileProbe(kind: MediaKind, mimeType: String?, durationMillis: Long, width: Int, height: Int): Boolean =
    when (kind) {
        MediaKind.Image -> AnimatedImageSniffer.needsHeader(mimeType)
        MediaKind.Video -> durationMillis <= 0 || width <= 0 || height <= 0
    }

/** What opening a file found. Values the probe could not find keep the input value. */
data class ProbedDetails(val durationMillis: Long, val width: Int, val height: Int, val isAnimated: Boolean)

/** Opens one file to fill the details MediaStore lacks. A [SecurityException] means access was lost. */
fun interface MediaFileProbe {
    fun probe(
        key: MediaKey,
        kind: MediaKind,
        mimeType: String?,
        dateModifiedSeconds: Long,
        durationMillis: Long,
        width: Int,
        height: Int,
    ): ProbedDetails
}

class MediaFileProber(resolver: ContentResolver) : MediaFileProbe {
    private val videoProbe = UnindexedVideoProbe(resolver)
    private val animatedProbe = AnimatedImageProbe(resolver)

    override fun probe(
        key: MediaKey,
        kind: MediaKind,
        mimeType: String?,
        dateModifiedSeconds: Long,
        durationMillis: Long,
        width: Int,
        height: Int,
    ): ProbedDetails {
        val raw = MediaStoreRecord(
            key, kind, mimeType, null, 0, width, height, durationMillis, 0, null, 0, dateModifiedSeconds,
            0, 0, null, null, null, isFavorite = false, isTrashed = false,
        )
        val done = animatedProbe.complete(videoProbe.complete(raw))
        return ProbedDetails(done.durationMillis, done.width, done.height, done.isAnimated)
    }
}
