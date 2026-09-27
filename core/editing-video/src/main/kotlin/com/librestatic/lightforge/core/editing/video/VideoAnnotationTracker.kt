package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

sealed interface VideoAnnotationTrackingResult {
    data class Complete(val keyframes: List<VideoAnnotationKeyframe>) : VideoAnnotationTrackingResult
    data class NeedsCorrection(
        val timeMillis: Long,
        val completedKeyframes: List<VideoAnnotationKeyframe>,
        val confidence: Float,
    ) : VideoAnnotationTrackingResult
}

/** Bounded, offline region tracker used for generic annotation and redaction layers. */
class VideoAnnotationTracker(private val context: Context) {
    suspend fun track(
        uri: Uri,
        layer: VideoAnnotationLayer,
        seedTimeMillis: Long,
        onProgress: (Float) -> Unit = {},
    ): VideoAnnotationTrackingResult = withContext(Dispatchers.IO) {
        require(seedTimeMillis in layer.startMillis until layer.endMillis)
        val authoredBounds = normalizedBounds(layer.points)
        val seedTransform = layer.transformAt(seedTimeMillis)
        val seedBounds = RectF(
            authoredBounds.centerX() + seedTransform.translationX - authoredBounds.width() * seedTransform.scaleX / 2f,
            authoredBounds.centerY() + seedTransform.translationY - authoredBounds.height() * seedTransform.scaleY / 2f,
            authoredBounds.centerX() + seedTransform.translationX + authoredBounds.width() * seedTransform.scaleX / 2f,
            authoredBounds.centerY() + seedTransform.translationY + authoredBounds.height() * seedTransform.scaleY / 2f,
        )
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context.applicationContext, uri)
            val seed = frame(retriever, seedTimeMillis) ?: error("Unable to decode tracking frame")
            try {
                val template = sample(seed, seedBounds)
                val times = buildList {
                    var time = layer.startMillis
                    while (time < layer.endMillis) {
                        add(time)
                        time += FRAME_INTERVAL_MILLIS
                    }
                    if (lastOrNull() != layer.endMillis - 1) add(layer.endMillis - 1)
                }
                val ordered = times.sortedBy { abs(it - seedTimeMillis) }
                val output = mutableListOf(
                    VideoAnnotationKeyframe(seedTimeMillis, seedTransform),
                )
                var completed = 0
                val stateByDirection = mutableMapOf(
                    -1 to seedBounds,
                    1 to seedBounds,
                )
                ordered.filterNot { it == seedTimeMillis }.forEach { time ->
                    coroutineContext.ensureActive()
                    val direction = if (time < seedTimeMillis) -1 else 1
                    val bitmap = frame(retriever, time) ?: return@use VideoAnnotationTrackingResult.NeedsCorrection(
                        time, output.sortedBy(VideoAnnotationKeyframe::timeMillis), 0f,
                    )
                    val match = try {
                        bestMatch(bitmap, template, stateByDirection.getValue(direction))
                    } finally {
                        bitmap.recycle()
                    }
                    if (match.confidence < MIN_CONFIDENCE) {
                        return@use VideoAnnotationTrackingResult.NeedsCorrection(
                            time,
                            simplify(output.sortedBy(VideoAnnotationKeyframe::timeMillis)),
                            match.confidence,
                        )
                    }
                    stateByDirection[direction] = match.bounds
                    output += VideoAnnotationKeyframe(
                        timeMillis = time,
                        transform = VideoAnnotationTransform(
                            translationX = match.bounds.centerX() - authoredBounds.centerX(),
                            translationY = match.bounds.centerY() - authoredBounds.centerY(),
                            scaleX = match.bounds.width() / authoredBounds.width().coerceAtLeast(0.001f),
                            scaleY = match.bounds.height() / authoredBounds.height().coerceAtLeast(0.001f),
                        ),
                        confidence = match.confidence,
                    )
                    completed++
                    onProgress(completed.toFloat() / (ordered.size - 1).coerceAtLeast(1))
                }
                VideoAnnotationTrackingResult.Complete(
                    simplify(output.sortedBy(VideoAnnotationKeyframe::timeMillis)),
                )
            } finally {
                seed.recycle()
            }
        }
    }

    private fun frame(retriever: MediaMetadataRetriever, timeMillis: Long): Bitmap? =
        retriever.getScaledFrameAtTime(
            timeMillis * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            FRAME_WIDTH,
            FRAME_HEIGHT,
        )

    private data class Match(val bounds: RectF, val confidence: Float)

    private fun bestMatch(bitmap: Bitmap, template: IntArray, previous: RectF): Match {
        var best = Match(previous, 0f)
        for (scale in SCALE_CANDIDATES) {
            for (dx in SEARCH_OFFSETS) {
                for (dy in SEARCH_OFFSETS) {
                    val width = previous.width() * scale
                    val height = previous.height() * scale
                    val candidate = RectF(
                        previous.centerX() + dx - width / 2f,
                        previous.centerY() + dy - height / 2f,
                        previous.centerX() + dx + width / 2f,
                        previous.centerY() + dy + height / 2f,
                    )
                    if (candidate.left < 0f || candidate.top < 0f || candidate.right > 1f || candidate.bottom > 1f) continue
                    val pixels = sample(bitmap, candidate)
                    var error = 0L
                    pixels.indices.forEach { index -> error += abs(pixels[index] - template[index]) }
                    val confidence = (1f - error.toFloat() / (pixels.size * 255f)).coerceIn(0f, 1f)
                    if (confidence > best.confidence) best = Match(candidate, confidence)
                }
            }
        }
        return best
    }

    private fun sample(bitmap: Bitmap, bounds: RectF): IntArray = IntArray(SAMPLE_GRID * SAMPLE_GRID) { index ->
        val xIndex = index % SAMPLE_GRID
        val yIndex = index / SAMPLE_GRID
        val x = ((bounds.left + (xIndex + 0.5f) / SAMPLE_GRID * bounds.width()) * bitmap.width)
            .toInt().coerceIn(0, bitmap.width - 1)
        val y = ((bounds.top + (yIndex + 0.5f) / SAMPLE_GRID * bounds.height()) * bitmap.height)
            .toInt().coerceIn(0, bitmap.height - 1)
        val color = bitmap.getPixel(x, y)
        ((color shr 16 and 0xFF) * 54 + (color shr 8 and 0xFF) * 183 + (color and 0xFF) * 19) / 256
    }

    private fun normalizedBounds(points: List<NormalizedPoint>): RectF {
        val left = points.minOf(NormalizedPoint::x)
        val top = points.minOf(NormalizedPoint::y)
        val right = points.maxOf(NormalizedPoint::x)
        val bottom = points.maxOf(NormalizedPoint::y)
        return RectF(left, top, right.coerceAtLeast(left + 0.02f), bottom.coerceAtLeast(top + 0.02f))
    }

    /** Drops near-linear samples while retaining endpoints and confidence discontinuities. */
    internal fun simplify(keyframes: List<VideoAnnotationKeyframe>): List<VideoAnnotationKeyframe> {
        if (keyframes.size <= 2) return keyframes
        return buildList {
            add(keyframes.first())
            keyframes.windowed(3).forEach { (left, middle, right) ->
                val expected = interpolate(left.transform, right.transform, 0.5f)
                val actual = middle.transform
                val error = abs(expected.translationX - actual.translationX) +
                    abs(expected.translationY - actual.translationY) +
                    abs(expected.scaleX - actual.scaleX) + abs(expected.scaleY - actual.scaleY)
                if (error > SIMPLIFICATION_ERROR || middle.confidence < 0.7f) add(middle)
            }
            add(keyframes.last())
        }
    }

    private companion object {
        const val FRAME_WIDTH = 480
        const val FRAME_HEIGHT = 270
        const val FRAME_INTERVAL_MILLIS = 200L
        const val SAMPLE_GRID = 14
        const val MIN_CONFIDENCE = 0.52f
        const val SIMPLIFICATION_ERROR = 0.012f
        val SCALE_CANDIDATES = floatArrayOf(0.9f, 1f, 1.1f)
        val SEARCH_OFFSETS = floatArrayOf(-0.08f, -0.04f, 0f, 0.04f, 0.08f)
    }
}
