package com.librestatic.lightforge.feature.viewer

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Windows start on this grid and each spans [SlowMotionWindowSpanMillis], so neighbours overlap. The
 * grid is fine because rendering is sequential from the window start: a coarse grid would make a hold
 * render up to a grid step of source before its first frame.
 */
internal const val SlowMotionWindowGridMillis = 500L
internal const val SlowMotionWindowSpanMillis = 6_000L

/** How far before the target a window opened for a backwards move starts, so the move has room to run. */
internal const val SlowMotionBackwardLeadMillis = 2_000L

/** A window still rendering is reused only if at most this much source remains before the target. */
internal const val SlowMotionReuseRenderMillis = 1_000L

/** A window is not reused for a target this close to its end (except at the end of the video). */
internal const val SlowMotionReuseTailMillis = 1_000L

internal const val SlowMotionMinimumOutputFrameMillis = 16L
internal const val SlowMotionMaximumOutputFrameMillis = 100L

internal fun Long.slowMotionWindowStart(): Long = this / SlowMotionWindowGridMillis * SlowMotionWindowGridMillis

/**
 * Window to render for [positionMillis] when moving in [direction] (negative = backwards). Forwards
 * it starts at the position's grid step. Backwards it starts [SlowMotionBackwardLeadMillis] before
 * p - 1, so a cursor sitting exactly on a window start moves into an earlier window, which then
 * covers the move instead of re-rendering the range already seen.
 */
internal fun slowMotionWindowFor(positionMillis: Long, direction: Int): Long {
    if (direction >= 0) return positionMillis.coerceAtLeast(0L).slowMotionWindowStart()
    return (positionMillis - 1 - SlowMotionBackwardLeadMillis).coerceAtLeast(0L).slowMotionWindowStart()
}

/** A window that exists on disk or is being prepared, as offered to [chooseSlowMotionWindow]. */
internal data class SlowMotionWindowCandidate(
    val start: Long,
    /** Source position of the last frame written, null while the window has none. */
    val lastRenderedMillis: Long?,
    val complete: Boolean,
    /** From the `complete` file when complete, otherwise start + span. */
    val endMillis: Long,
    /** True for the window the session is currently loading or rendering. */
    val preparing: Boolean,
)

/**
 * Window to use for [targetMillis]: an existing one that can serve it soon, else [freshStart].
 * A candidate qualifies when it starts at or before the target, the target is not in the last
 * [SlowMotionReuseTailMillis] of its range (unless it ends the video), and it either is complete and
 * covers the target or has at most [SlowMotionReuseRenderMillis] of source left to render before the
 * target. Prefers the least source left to render, then the one reaching furthest past the target,
 * then the one being prepared.
 */
internal fun chooseSlowMotionWindow(
    targetMillis: Long,
    freshStart: Long,
    candidates: List<SlowMotionWindowCandidate>,
): Long {
    fun SlowMotionWindowCandidate.remaining() =
        if (complete) 0L else (targetMillis - maxOf(start, lastRenderedMillis ?: start)).coerceAtLeast(0L)

    val best = candidates.asSequence().filter { candidate ->
        val endsVideo = candidate.complete && candidate.endMillis < candidate.start + SlowMotionWindowSpanMillis
        candidate.start <= targetMillis &&
            (endsVideo || targetMillis < candidate.endMillis - SlowMotionReuseTailMillis) &&
            if (candidate.complete) endsVideo || targetMillis < candidate.endMillis
            else candidate.remaining() <= SlowMotionReuseRenderMillis
    }.minWithOrNull(
        compareBy<SlowMotionWindowCandidate> { it.remaining() }
            .thenByDescending { it.endMillis }
            .thenByDescending { it.preparing },
    )
    return best?.start ?: freshStart
}

/** Signed source speed, limited to [-1, 1]; NaN holds the frame. */
internal fun clampSlowMotionRate(rate: Float): Float = if (rate.isNaN()) 0f else rate.coerceIn(-1f, 1f)

/** Wall-clock delay before showing a frame [sourceGapMillis] away at [rate]; null while holding. */
internal fun slowMotionPaceDelayMillis(sourceGapMillis: Long, rate: Float): Long? {
    val speed = abs(rate)
    if (speed == 0f) return null
    return (abs(sourceGapMillis) / speed).toLong()
        .coerceIn(SlowMotionMinimumOutputFrameMillis, SlowMotionMaximumOutputFrameMillis)
}

/** Index of the frame whose position is closest to [target] (ties go to the earlier frame); -1 if empty. */
internal inline fun nearestFrameIndex(size: Int, positionAt: (Int) -> Long, target: Long): Int {
    if (size <= 0) return -1
    var low = 0
    var high = size - 1
    while (low < high) {
        val middle = (low + high) ushr 1
        if (positionAt(middle) < target) low = middle + 1 else high = middle
    }
    // low is the first frame at or after target.
    if (low > 0 && target - positionAt(low - 1) <= positionAt(low) - target) return low - 1
    return low
}

internal data class SlowMotionBufferSnapshot(
    val windowStart: Long,
    val count: Int,
    val firstPositionMillis: Long,
    val lastPositionMillis: Long,
    val complete: Boolean,
    /** End of the source range the window covers; below start + span only at the end of the video. */
    val windowEndMillis: Long,
)

internal sealed interface SlowMotionBufferPlan {
    /** Show the frame nearest the target; outside the buffer's range this holds its first/last frame. */
    data object Show : SlowMotionBufferPlan
    data class Switch(val windowStart: Long) : SlowMotionBufferPlan
    data object AwaitFrames : SlowMotionBufferPlan
    data object Empty : SlowMotionBufferPlan
}

/** Decides whether the in-memory window can serve [targetMillis] or another one must be loaded. */
internal fun planSlowMotionBuffer(targetMillis: Long, buffer: SlowMotionBufferSnapshot): SlowMotionBufferPlan {
    if (buffer.count == 0) {
        return if (buffer.complete) SlowMotionBufferPlan.Empty else SlowMotionBufferPlan.AwaitFrames
    }
    if (targetMillis < buffer.firstPositionMillis) {
        // A target at or after the window start is only before the first frame by a frame gap.
        if (buffer.windowStart <= 0L || buffer.windowStart <= targetMillis) return SlowMotionBufferPlan.Show
        val window = slowMotionWindowFor(targetMillis, -1)
        return if (window == buffer.windowStart) SlowMotionBufferPlan.Show else SlowMotionBufferPlan.Switch(window)
    }
    if (targetMillis > buffer.lastPositionMillis) {
        if (!buffer.complete) return SlowMotionBufferPlan.AwaitFrames
        val endOfVideo = buffer.windowEndMillis < buffer.windowStart + SlowMotionWindowSpanMillis
        if (endOfVideo || targetMillis < buffer.windowEndMillis) return SlowMotionBufferPlan.Show
        val window = slowMotionWindowFor(targetMillis, 1)
        return if (window == buffer.windowStart) SlowMotionBufferPlan.Show else SlowMotionBufferPlan.Switch(window)
    }
    return SlowMotionBufferPlan.Show
}

/** Tracks the source range a hold has shown so the saved clip covers everything the user saw. */
internal class SlowMotionVisitedRange {
    private var minimum = Long.MAX_VALUE
    private var maximum = Long.MIN_VALUE

    fun include(positionMillis: Long) {
        if (positionMillis < minimum) minimum = positionMillis
        if (positionMillis > maximum) maximum = positionMillis
    }

    /** Null when nothing was shown or the range is a single instant. */
    fun range(): LongRange? = if (maximum > minimum) minimum..maximum else null
}

/** "0.25×", "−0.5×", "0×"; trailing zeros are dropped and the decimal separator follows [locale]. */
internal fun slowMotionRateLabel(rate: Float, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 3
    }
    val sign = if (rate < 0f) "−" else ""
    return sign + format.format(abs(rate).toDouble()) + "×"
}
