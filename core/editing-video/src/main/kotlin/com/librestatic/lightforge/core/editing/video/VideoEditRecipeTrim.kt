package com.librestatic.lightforge.core.editing.video

/**
 * Applies a new trim range while keeping every time-bound edit valid.
 *
 * Annotation keyframes at a removed boundary are materialized at the new boundary so trimming
 * does not make a tracked annotation jump back to its identity transform.
 */
fun VideoEditRecipe.withTrimRange(startMillis: Long, endMillis: Long): VideoEditRecipe {
    require(startMillis >= 0)
    require(endMillis > startMillis)

    val trimmedSegments = slowMotionSegments.mapNotNull { segment ->
        val start = segment.startMillis.coerceAtLeast(startMillis)
        val end = segment.endMillis.coerceAtMost(endMillis)
        if (end <= start) null else segment.copy(startMillis = start, endMillis = end)
    }
    val trimmedAnnotations = annotations.mapNotNull { layer ->
        layer.withTrimRangeOrNull(startMillis, endMillis)
    }
    return copy(
        startMillis = startMillis,
        endMillis = endMillis,
        slowMotionSegments = trimmedSegments,
        annotations = trimmedAnnotations,
    )
}

private fun VideoAnnotationLayer.withTrimRangeOrNull(
    trimStartMillis: Long,
    trimEndMillis: Long,
): VideoAnnotationLayer? {
    val start = startMillis.coerceAtLeast(trimStartMillis)
    val end = endMillis.coerceAtMost(trimEndMillis)
    if (end <= start) return null
    if (start == startMillis && end == endMillis) return this

    val trimmedKeyframes = when {
        keyframes.isEmpty() -> emptyList()
        trackingMode == VideoAnnotationTrackingMode.Fixed -> listOf(keyframeAt(start))
        else -> buildList {
            add(keyframeAt(start))
            addAll(keyframes.filter { it.timeMillis in start..end })
            add(keyframeAt(end))
        }.distinctBy(VideoAnnotationKeyframe::timeMillis)
            .sortedBy(VideoAnnotationKeyframe::timeMillis)
    }
    return copy(
        startMillis = start,
        endMillis = end,
        keyframes = trimmedKeyframes,
    )
}

private fun VideoAnnotationLayer.keyframeAt(timeMillis: Long): VideoAnnotationKeyframe {
    keyframes.firstOrNull { it.timeMillis == timeMillis }?.let { return it }
    val rightIndex = keyframes.indexOfFirst { it.timeMillis > timeMillis }
    val confidence = when {
        rightIndex == 0 -> keyframes.first().confidence
        rightIndex < 0 -> keyframes.last().confidence
        else -> {
            val left = keyframes[rightIndex - 1]
            val right = keyframes[rightIndex]
            val span = (right.timeMillis - left.timeMillis).coerceAtLeast(1)
            val fraction = ((timeMillis - left.timeMillis).toFloat() / span).coerceIn(0f, 1f)
            left.confidence + (right.confidence - left.confidence) * fraction
        }
    }
    return VideoAnnotationKeyframe(
        timeMillis = timeMillis,
        transform = transformAt(timeMillis),
        confidence = confidence,
    )
}
