package com.librestatic.lightforge.feature.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Fills the editor's filmstrip one frame at a time (bug 6).
 *
 * Exact-frame decoding (`OPTION_CLOSEST`) decodes from the previous key frame up to every
 * requested time, which took seconds before anything appeared. The strip only needs a
 * representative picture per slot, so this reads the nearest sync frame, which is a single
 * decode, and emits each one as soon as it is ready, coarse to fine, so the strip is roughly
 * covered after the first few frames.
 */
internal object VideoFilmstripLoader {
    private const val FrameWidthPx = 160
    private const val FrameHeightPx = 96

    /** Slot order that spreads early frames across the strip: ends and middle first, then the gaps. */
    fun progressiveOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val order = mutableListOf<Int>()
        val seen = BooleanArray(count)
        fun add(index: Int) {
            if (index in 0 until count && !seen[index]) {
                seen[index] = true
                order += index
            }
        }
        add(0)
        add(count - 1)
        var step = count - 1
        while (step > 1) {
            step = (step + 1) / 2
            var index = step
            while (index < count) {
                add(index)
                index += step
            }
        }
        (0 until count).forEach(::add)
        return order
    }

    /** Sampling time (µs) for [slot]: the slot's centre, so the strip reads as the whole clip. */
    fun slotTimeMicros(slot: Int, count: Int, durationMillis: Long): Long {
        val duration = durationMillis.coerceAtLeast(1L)
        val centre = (slot + 0.5) / count.coerceAtLeast(1)
        return (duration * centre).toLong().coerceIn(0L, duration - 1L) * 1_000L
    }

    /** Emits `slot to frame` as each frame decodes; the collector owns (and recycles) the bitmaps. */
    fun frames(context: Context, uri: Uri, durationMillis: Long, count: Int): Flow<Pair<Int, Bitmap>> = flow {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context.applicationContext, uri)
            val duration = durationMillis.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@flow
            for (slot in progressiveOrder(count)) {
                currentCoroutineContext().ensureActive()
                val frame = frameAt(retriever, slotTimeMicros(slot, count, duration)) ?: continue
                emit(slot to frame)
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Some decoders refuse the scaled path for streams such as HikVision HEVC (`getScaledFrameAtTime`
     * returns null or throws), so a full-size sync frame scaled here is the fallback. One bad slot
     * is skipped rather than ending the strip.
     */
    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        runCatching {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                FrameWidthPx,
                FrameHeightPx,
            )
        }.getOrNull()?.let { return it }
        val full = runCatching {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
        }.getOrNull() ?: return null
        val (width, height) = fitWithin(full.width, full.height, FrameWidthPx, FrameHeightPx)
        if (width == full.width && height == full.height) return full
        return Bitmap.createScaledBitmap(full, width, height, true).also {
            if (it !== full) full.recycle()
        }
    }

    /** The largest size with the source's aspect ratio inside `maxWidth x maxHeight`, never upscaled. */
    fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxWidth.coerceAtLeast(1) to maxHeight.coerceAtLeast(1)
        val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height, 1f)
        return Math.round(width * scale).coerceIn(1, maxWidth) to Math.round(height * scale).coerceIn(1, maxHeight)
    }
}
