package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineYearTick
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A year label placed on the scrub track; [y] is the label's center from the track's top. */
data class PlacedYearTick(val year: Int, val y: Float)

/**
 * Lays year ticks out along a track [heightPx] tall. Ticks sit at their proportional position but
 * are pushed apart to keep [minGapPx] between labels, as Google Photos does for sparse years. When
 * the years cannot all fit, the ones closest to their predecessor are dropped first.
 */
fun layoutYearTicks(ticks: List<TimelineYearTick>, heightPx: Float, minGapPx: Float): List<PlacedYearTick> {
    if (ticks.isEmpty() || heightPx <= 0f) return emptyList()
    val capacity = if (minGapPx <= 0f) ticks.size else (heightPx / minGapPx).toInt() + 1
    val kept = ticks.toMutableList()
    while (kept.size > capacity.coerceAtLeast(1)) {
        var drop = 1
        var smallest = Float.MAX_VALUE
        for (i in 1 until kept.size) {
            val gap = kept[i].fraction - kept[i - 1].fraction
            if (gap < smallest) {
                smallest = gap
                drop = i
            }
        }
        kept.removeAt(drop)
    }
    val positions = FloatArray(kept.size) { (kept[it].fraction * heightPx).coerceIn(0f, heightPx) }
    for (i in 1 until positions.size) {
        positions[i] = maxOf(positions[i], positions[i - 1] + minGapPx)
    }
    if (positions.isNotEmpty() && positions.last() > heightPx) {
        positions[positions.lastIndex] = heightPx
        for (i in positions.lastIndex - 1 downTo 0) {
            positions[i] = minOf(positions[i], positions[i + 1] - minGapPx)
        }
    }
    return kept.mapIndexed { i, tick -> PlacedYearTick(tick.year, positions[i]) }
}

/** Handle offset from the track top for a fraction of the whole timeline. */
fun scrubberHandleOffset(fraction: Float, travelPx: Float): Float =
    fraction.coerceIn(0f, 1f) * travelPx.coerceAtLeast(0f)

/** Inverse of [scrubberHandleOffset]; a zero-length track always reads as the newest end. */
fun scrubberFraction(offsetPx: Float, travelPx: Float): Float =
    if (travelPx <= 0f) 0f else (offsetPx / travelPx).coerceIn(0f, 1f)

/**
 * Filters the tremor of a resting finger out of a scrub. Steps that keep the current direction pass
 * as they come, so a moving finger is followed pixel for pixel; turning back has to travel
 * [deadbandPx] first. A held finger therefore stops the grid instead of rocking it between rows.
 */
class ScrubberDeadband(private val deadbandPx: Float) {
    var applied = 0f
        private set
    private var direction = 0

    fun reset(positionPx: Float) {
        applied = positionPx
        direction = 0
    }

    /** True when [positionPx] should move the grid; [applied] then follows it. */
    fun accept(positionPx: Float): Boolean {
        val diff = positionPx - applied
        if (diff == 0f) return false
        val step = if (diff > 0f) 1 else -1
        if (direction != 0 && step != direction && kotlin.math.abs(diff) < deadbandPx) return false
        applied = positionPx
        direction = step
        return true
    }
}

/**
 * Where [fractionInDay] of one day lands in a grid: the item to scroll to, counted from the day's
 * first item (-1 is its header), and how many pixels past the top of that line. The day is measured
 * in pixels, header plus rows, so a slow drag scrolls smoothly instead of snapping row by row.
 */
data class ScrubberDayTarget(val itemOffset: Int, val scrollPx: Int)

fun scrubberDayTarget(
    fractionInDay: Float,
    count: Int,
    columns: Int,
    headerPx: Float,
    rowPx: Float,
): ScrubberDayTarget {
    val rows = (count.coerceAtLeast(1) + columns - 1) / columns.coerceAtLeast(1)
    val header = headerPx.coerceAtLeast(0f)
    val row = rowPx.coerceAtLeast(1f)
    val px = fractionInDay.coerceIn(0f, 1f) * (header + rows * row)
    if (px < header) return ScrubberDayTarget(-1, px.toInt())
    val rowIndex = ((px - header) / row).toInt().coerceAtMost(rows - 1)
    val within = (px - header - rowIndex * row).coerceIn(0f, row)
    return ScrubberDayTarget((rowIndex * columns).coerceAtMost(count - 1).coerceAtLeast(0), within.toInt())
}

/**
 * Text for the sticky date pill: "Today" / "Yesterday" for the last two days, otherwise a short
 * localized date that only carries the year when it is not the current one. [bestPattern] maps a
 * skeleton such as "EEEdMMM" to the locale's pattern (the platform's getBestDateTimePattern).
 */
fun formatScrubberDate(
    epochDay: Long,
    today: LocalDate,
    locale: Locale,
    todayLabel: String,
    yesterdayLabel: String,
    bestPattern: (String) -> String,
): String {
    val date = LocalDate.ofEpochDay(epochDay)
    return when (date) {
        today -> todayLabel
        today.minusDays(1) -> yesterdayLabel
        else -> formatWithSkeleton(date, locale, if (date.year == today.year) "EEEdMMM" else "EEEdMMMy", bestPattern)
    }
}

/** "Sept 2026"-style label shown next to the handle while scrubbing. */
fun formatScrubberMonth(
    epochDay: Long,
    locale: Locale,
    bestPattern: (String) -> String,
): String = formatWithSkeleton(LocalDate.ofEpochDay(epochDay), locale, "MMMy", bestPattern)

internal fun formatWithSkeleton(
    date: LocalDate,
    locale: Locale,
    skeleton: String,
    bestPattern: (String) -> String,
): String = try {
    DateTimeFormatter.ofPattern(bestPattern(skeleton), locale).format(date)
} catch (_: IllegalArgumentException) {
    date.toString()
}
