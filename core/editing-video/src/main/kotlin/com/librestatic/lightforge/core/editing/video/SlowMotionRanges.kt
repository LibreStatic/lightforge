package com.librestatic.lightforge.core.editing.video

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * One contiguous stretch of the trimmed clip that is exported with a single playback [speed].
 *
 * [interpolationFactor] is null when the stretch is played by Transformer straight from the source
 * (speed change only, frames repeated when slowed). When set (2, 4 or 8) the stretch is first
 * rendered to an intermediate video holding that many times the frames, with synthesized in-between
 * frames, and then played at [videoPlaybackSpeed] (1 for the exact power-of-two speeds).
 */
internal data class PlannedRange(
    val startMillis: Long,
    val endMillis: Long,
    /** Overall speed of this stretch: output length is `(endMillis - startMillis) / speed`. */
    val speed: Float,
    val interpolationFactor: Int?,
    /** Audio treatment of a slow-motion segment; null for the stretches that follow the global speed. */
    val audioMode: SlowMotionAudioMode? = null,
) {
    val sourceMillis: Long get() = endMillis - startMillis
    val outputMillis: Double get() = sourceMillis / speed.toDouble()

    /** Speed applied to the intermediate video, whose frames already carry [interpolationFactor]. */
    val videoPlaybackSpeed: Float get() = speed * (interpolationFactor ?: 1)
}

/** Global speeds above this stay on Transformer's own speed change: too little slowdown to be worth RIFE. */
private const val MaxInterpolatedGlobalSpeed = 0.75f

/** Closest supported RIFE factor (2, 4 or 8) for a slow-down to [speed], or null when it is not slowed enough. */
internal fun globalInterpolationFactor(speed: Float): Int? {
    if (speed > MaxInterpolatedGlobalSpeed) return null
    val exponent = (ln(1.0 / speed) / ln(2.0)).roundToInt().coerceIn(1, 3)
    return 1 shl exponent
}

/**
 * Splits the trimmed clip into the stretches that are exported separately: each slow-motion segment
 * and the gaps around it (which follow the recipe's global speed). With [VideoEditRecipe.interpolateSlowMotion]
 * off no range carries an interpolation factor. The ranges are contiguous and cover
 * `recipe.startMillis until clipEndMillis`.
 */
internal fun slowMotionRanges(recipe: VideoEditRecipe, clipEndMillis: Long): List<PlannedRange> {
    val interpolate = recipe.interpolateSlowMotion
    val globalFactor = if (interpolate) globalInterpolationFactor(recipe.speed) else null
    val ranges = mutableListOf<PlannedRange>()
    fun gap(from: Long, to: Long) {
        if (to > from) ranges += PlannedRange(from, to, recipe.speed, globalFactor)
    }
    var cursor = recipe.startMillis
    recipe.slowMotionSegments.forEach { segment ->
        gap(cursor, segment.startMillis)
        ranges += PlannedRange(
            segment.startMillis,
            segment.endMillis,
            segment.speed,
            if (interpolate) segment.interpolationFactor else null,
            segment.audioMode,
        )
        cursor = segment.endMillis
    }
    gap(cursor, clipEndMillis)
    return ranges
}

/** True when the speed ramp 1/[PlannedRange.speed] does not match the factor, so the video item needs a speed. */
internal fun PlannedRange.needsVideoSpeedChange(): Boolean = abs(videoPlaybackSpeed - 1f) > 0.001f
