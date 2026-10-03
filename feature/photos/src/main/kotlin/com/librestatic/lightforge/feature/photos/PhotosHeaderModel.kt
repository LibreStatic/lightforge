package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineGrouping
import com.librestatic.lightforge.core.model.TimelineIndex
import java.time.LocalDate

/** What the Photos timeline shows. The app maps it onto the library filter setting. */
enum class PhotosFilter { All, Photos, Videos, Animated, Raw }

/** How the Photos timeline is ordered. The app maps it onto the library sort setting. */
enum class PhotosSort { Newest, Oldest, RecentlyModified, Name, Largest }

/**
 * Item counts per timeline group (day, month or year) from the timeline's day histogram, so a
 * header can say how many items it holds before its rows are paged in.
 */
class TimelineGroupCounts(index: TimelineIndex) {
    private val byDay = HashMap<Long, Int>()
    private val byMonth = HashMap<Int, Int>()
    private val byYear = HashMap<Int, Int>()

    init {
        index.days.forEach { bucket ->
            val date = LocalDate.ofEpochDay(bucket.epochDay)
            byDay.merge(bucket.epochDay, bucket.count, Int::plus)
            byMonth.merge(date.year * 12 + date.monthValue - 1, bucket.count, Int::plus)
            byYear.merge(date.year, bucket.count, Int::plus)
        }
    }

    /** Items in the group a header for [epochDay] at [granularity] stands for; null when unknown. */
    fun count(epochDay: Long, granularity: TimelineGrouping): Int? {
        val date = LocalDate.ofEpochDay(epochDay)
        return when (granularity) {
            TimelineGrouping.Day -> byDay[epochDay]
            TimelineGrouping.Month -> byMonth[date.year * 12 + date.monthValue - 1]
            TimelineGrouping.Year -> byYear[date.year]
        }
    }
}
