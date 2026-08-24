package com.ugallery.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object VideoFrameExtractor {
    suspend fun extract(
        context: Context,
        uri: Uri,
        durationMillis: Long,
        frameCount: Int = DEFAULT_FRAME_COUNT,
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        require(frameCount > 0)
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context.applicationContext, uri)
            val resolvedDuration = durationMillis.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@use emptyList()
            val lastPosition = (resolvedDuration - 1L).coerceAtLeast(0L)
            val frames = mutableListOf<Bitmap>()
            try {
                repeat(frameCount) { index ->
                    ensureActive()
                    val fraction = if (frameCount == 1) 0f else index.toFloat() / (frameCount - 1)
                    val positionUs = (lastPosition * fraction).toLong() * 1_000L
                    retriever.getScaledFrameAtTime(
                        positionUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        FRAME_WIDTH_PX,
                        FRAME_HEIGHT_PX,
                    )?.let(frames::add)
                }
                frames
            } catch (failure: Throwable) {
                frames.forEach { frame -> if (!frame.isRecycled) frame.recycle() }
                throw failure
            }
        }
    }

    private const val DEFAULT_FRAME_COUNT = 10
    private const val FRAME_WIDTH_PX = 160
    private const val FRAME_HEIGHT_PX = 96
}
