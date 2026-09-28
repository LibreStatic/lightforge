package com.librestatic.lightforge.core.model

import java.time.LocalDate

/** Rows the timeline shows for one local calendar day. */
data class TimelineDayBucket(val epochDay: Long, val count: Int)

/** A jump target: the timeline restarts at the first row of this local day. */
data class TimelineAnchor(val epochDay: Long)

data class TimelineYearTick(val year: Int, val fraction: Float)

/** A position inside the index: the day under a scrub fraction and how far into it we are. */
data class TimelineIndexPosition(val bucket: TimelineDayBucket, val fractionInBucket: Float)

/**
 * Per-day histogram of the rows the timeline displays, in display order. It maps a scrub
 * fraction (0 = first row shown, 1 = last) to a day and back without loading any page.
 */
class TimelineIndex(val days: List<TimelineDayBucket>, val ascending: Boolean = false) {
    private val cumulative = IntArray(days.size + 1).also { sums ->
        days.forEachIndexed { index, day -> sums[index + 1] = sums[index] + day.count }
    }

    val total: Int get() = cumulative[days.size]
    val isEmpty: Boolean get() = total == 0

    /** Index of the bucket that shows [epochDay], or the first bucket after it in display order. */
    fun bucketIndexOf(epochDay: Long): Int {
        if (days.isEmpty()) return -1
        var low = 0
        var high = days.size - 1
        var found = days.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            val shownAtOrAfter =
                if (ascending) days[mid].epochDay >= epochDay else days[mid].epochDay <= epochDay
            if (shownAtOrAfter) {
                found = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return found.coerceAtMost(days.size - 1)
    }

    /** Fraction of the whole timeline that lies before [epochDay]'s first row plus [fractionInDay]. */
    fun fractionOf(epochDay: Long, fractionInDay: Float = 0f): Float {
        if (total == 0) return 0f
        val index = bucketIndexOf(epochDay)
        val within = fractionInDay.coerceIn(0f, 1f) * days[index].count
        return ((cumulative[index] + within) / total).coerceIn(0f, 1f)
    }

    fun positionAt(fraction: Float): TimelineIndexPosition? {
        if (total == 0) return null
        val rank = fraction.coerceIn(0f, 1f) * total
        var low = 0
        var high = days.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulative[mid + 1] <= rank) low = mid + 1 else high = mid
        }
        val bucket = days[low]
        val within = ((rank - cumulative[low]) / bucket.count).coerceIn(0f, 1f)
        return TimelineIndexPosition(bucket, within)
    }

    /** One tick per calendar year, at the fraction where that year's first row is shown. */
    fun yearTicks(): List<TimelineYearTick> {
        if (total == 0) return emptyList()
        val ticks = ArrayList<TimelineYearTick>()
        var previousYear = Int.MIN_VALUE
        days.forEachIndexed { index, day ->
            val year = LocalDate.ofEpochDay(day.epochDay).year
            if (year != previousYear) {
                ticks += TimelineYearTick(year, cumulative[index].toFloat() / total)
                previousYear = year
            }
        }
        return ticks
    }
}
