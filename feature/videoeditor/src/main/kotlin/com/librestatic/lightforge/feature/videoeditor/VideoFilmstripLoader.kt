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
                val frame = retriever.getScaledFrameAtTime(
                    slotTimeMicros(slot, count, duration),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    FrameWidthPx,
                    FrameHeightPx,
                ) ?: continue
                emit(slot to frame)
            }
        } finally {
            retriever.release()
        }
    }.flowOn(Dispatchers.IO)
}
