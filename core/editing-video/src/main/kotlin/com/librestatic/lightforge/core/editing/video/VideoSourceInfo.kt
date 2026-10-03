package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the output plan needs to know about a source video. Bitrates are bits/s. */
data class VideoSourceInfo(
    /** Stored frame size, before rotation and pixel aspect. */
    val width: Int,
    val height: Int,
    /** Container rotation: 0, 90, 180 or 270. */
    val rotationDegrees: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
    /** Real average frame rate (frame count / duration) when known, else [nominalFrameRate]. */
    val frameRate: Float,
    /** The rate the container claims; CCTV recorders often claim 25 while delivering ~15. */
    val nominalFrameRate: Float? = null,
    val videoBitrate: Int? = null,
    val audioBitrate: Int? = null,
    val videoMimeType: String? = null,
    val audioMimeType: String? = null,
    val durationMs: Long,
    val hasAudio: Boolean,
    /** HLG or PQ transfer. */
    val isHdr: Boolean = false,
    val sizeBytes: Long? = null,
) {
    init {
        require(width > 0 && height > 0)
        require(pixelWidthHeightRatio > 0f)
    }

    private val rotated: Boolean get() = (rotationDegrees % 180 + 180) % 180 == 90

    /** Width as displayed: pixel aspect applied to the stored width, then rotation. */
    val displayWidth: Int
        get() = if (rotated) height else (width * pixelWidthHeightRatio).roundToInt()

    val displayHeight: Int
        get() = if (rotated) (width * pixelWidthHeightRatio).roundToInt() else height
}

object VideoSourceInfoReader {
    /** Reads [uri] on [Dispatchers.IO]. Returns null when it has no readable video track. */
    suspend fun read(context: Context, uri: Uri): VideoSourceInfo? = withContext(Dispatchers.IO) {
        runCatching { readBlocking(context.applicationContext, uri) }.getOrNull()
    }

    private fun readBlocking(context: Context, uri: Uri): VideoSourceInfo? {
        var videoFormat: MediaFormat? = null
        var audioFormat: MediaFormat? = null
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (videoFormat == null && mime.startsWith("video/")) videoFormat = format
                if (audioFormat == null && mime.startsWith("audio/")) audioFormat = format
            }
        } finally {
            extractor.release()
        }
        val video = videoFormat ?: return null
        val metadata = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            RetrieverValues(
                durationMs = retriever.long(MediaMetadataRetriever.METADATA_KEY_DURATION),
                frameCount = retriever.long(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT),
                bitrate = retriever.long(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toInt(),
                rotation = retriever.long(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt(),
            )
        }
        val durationMs = metadata.durationMs
            ?: video.longOrNull(MediaFormat.KEY_DURATION)?.div(1_000L)
            ?: return null
        val sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it > 0 }
        val nominalFps = video.numberOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat()?.takeIf { it > 0f }
        val measuredFps = metadata.frameCount?.takeIf { it > 0 && durationMs > 0 }
            ?.let { it * 1_000f / durationMs }
        val audioBitrate = audioFormat?.intOrNull(MediaFormat.KEY_BIT_RATE)
        val videoBitrate = video.intOrNull(MediaFormat.KEY_BIT_RATE)
            ?: metadata.bitrate?.let { it - (audioBitrate ?: 0) }?.takeIf { it > 0 }
            ?: sizeBytes?.takeIf { durationMs > 0 }?.let { (it * 8_000L / durationMs).toInt() - (audioBitrate ?: 0) }
                ?.takeIf { it > 0 }
        val parWidth = video.intOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
        val parHeight = video.intOrNull(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
        val transfer = video.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        return VideoSourceInfo(
            width = video.getInteger(MediaFormat.KEY_WIDTH),
            height = video.getInteger(MediaFormat.KEY_HEIGHT),
            rotationDegrees = metadata.rotation ?: video.intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
            pixelWidthHeightRatio = if (parWidth != null && parHeight != null && parWidth > 0 && parHeight > 0) {
                parWidth.toFloat() / parHeight
            } else 1f,
            frameRate = measuredFps ?: nominalFps ?: VideoOutputPlan.FallbackFrameRate,
            nominalFrameRate = nominalFps,
            videoBitrate = videoBitrate,
            audioBitrate = audioBitrate,
            videoMimeType = video.getString(MediaFormat.KEY_MIME),
            audioMimeType = audioFormat?.getString(MediaFormat.KEY_MIME),
            durationMs = durationMs,
            hasAudio = audioFormat != null,
            isHdr = transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084,
            sizeBytes = sizeBytes,
        )
    }

    private class RetrieverValues(val durationMs: Long?, val frameCount: Long?, val bitrate: Int?, val rotation: Int?)

    private fun MediaMetadataRetriever.long(key: Int): Long? = extractMetadata(key)?.trim()?.toLongOrNull()

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    /** KEY_FRAME_RATE is an Integer or a Float depending on the extractor. */
    private fun MediaFormat.numberOrNull(key: String): Number? =
        if (containsKey(key)) runCatching { getNumber(key) }.getOrNull() else null
}
