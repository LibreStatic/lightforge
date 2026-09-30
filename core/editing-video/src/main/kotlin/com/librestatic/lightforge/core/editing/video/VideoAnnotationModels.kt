package com.librestatic.lightforge.core.editing.video

import java.util.UUID
import kotlin.math.abs

enum class VideoAnnotationShape {
    Freehand, Line, Arrow, Rectangle, Oval, Triangle, Star, SpeechBubble,
}

enum class VideoAnnotationAppearance { Pen, Highlighter, Blur, Mosaic }

enum class VideoAnnotationTrackingMode { Fixed, Keyframes, Automatic }

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite())
        require(x in -1f..2f && y in -1f..2f)
    }
}

data class VideoAnnotationStyle(
    val appearance: VideoAnnotationAppearance = VideoAnnotationAppearance.Pen,
    val colorArgb: Int = 0xFFFF3B30.toInt(),
    val strokeWidth: Float = 0.012f,
    val opacity: Float = 1f,
    val filled: Boolean = false,
    val intensity: Float = 0.55f,
) {
    init {
        require(strokeWidth in 0.001f..0.25f)
        require(opacity in 0f..1f)
        require(intensity in 0f..1f)
    }
}

/** A transform relative to the layer's authored geometry. Translation uses normalized frame units. */
data class VideoAnnotationTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(listOf(translationX, translationY, scaleX, scaleY, rotationDegrees).all(Float::isFinite))
        require(scaleX in 0.05f..20f && scaleY in 0.05f..20f)
    }
}

data class VideoAnnotationKeyframe(
    val timeMillis: Long,
    val transform: VideoAnnotationTransform,
    /** Confidence is populated by automatic tracking and remains 1 for manual keyframes. */
    val confidence: Float = 1f,
) {
    init {
        require(timeMillis >= 0)
        require(confidence in 0f..1f)
    }
}

data class VideoAnnotationLayer(
    val id: String = UUID.randomUUID().toString(),
    val shape: VideoAnnotationShape,
    val points: List<NormalizedPoint>,
    val style: VideoAnnotationStyle = VideoAnnotationStyle(),
    val startMillis: Long,
    val endMillis: Long,
    val trackingMode: VideoAnnotationTrackingMode = VideoAnnotationTrackingMode.Fixed,
    val keyframes: List<VideoAnnotationKeyframe> = emptyList(),
) {
    init {
        require(startMillis >= 0 && endMillis > startMillis)
        require(points.isNotEmpty())
        require(shape == VideoAnnotationShape.Freehand || points.size >= 2)
        require(keyframes == keyframes.sortedBy(VideoAnnotationKeyframe::timeMillis))
        require(keyframes.all { it.timeMillis in startMillis..endMillis })
    }

    fun transformAt(timeMillis: Long): VideoAnnotationTransform {
        if (trackingMode == VideoAnnotationTrackingMode.Fixed || keyframes.isEmpty()) {
            return keyframes.firstOrNull()?.transform ?: VideoAnnotationTransform()
        }
        val rightIndex = keyframes.indexOfFirst { it.timeMillis >= timeMillis }
        if (rightIndex <= 0) return keyframes.first().transform
        if (rightIndex < 0) return keyframes.last().transform
        val left = keyframes[rightIndex - 1]
        val right = keyframes[rightIndex]
        val span = (right.timeMillis - left.timeMillis).coerceAtLeast(1)
        val fraction = ((timeMillis - left.timeMillis).toFloat() / span).coerceIn(0f, 1f)
        return interpolate(left.transform, right.transform, fraction)
    }

    val isVisibleAt: (Long) -> Boolean get() = { it in startMillis until endMillis }
}

internal fun interpolate(
    start: VideoAnnotationTransform,
    end: VideoAnnotationTransform,
    fraction: Float,
): VideoAnnotationTransform {
    fun lerp(a: Float, b: Float) = a + (b - a) * fraction
    var rotationDelta = (end.rotationDegrees - start.rotationDegrees) % 360f
    if (abs(rotationDelta) > 180f) rotationDelta -= 360f * kotlin.math.sign(rotationDelta)
    return VideoAnnotationTransform(
        translationX = lerp(start.translationX, end.translationX),
        translationY = lerp(start.translationY, end.translationY),
        scaleX = lerp(start.scaleX, end.scaleX),
        scaleY = lerp(start.scaleY, end.scaleY),
        rotationDegrees = start.rotationDegrees + rotationDelta * fraction,
    )
}
