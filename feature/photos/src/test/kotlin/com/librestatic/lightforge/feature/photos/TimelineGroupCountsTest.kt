package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineGrouping
import com.librestatic.lightforge.core.model.TimelineIndex
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineGroupCountsTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()

    private val counts = TimelineGroupCounts(
        TimelineIndex(
            listOf(
                TimelineDayBucket(day(2026, 9, 28), 3),
                TimelineDayBucket(day(2026, 9, 2), 4),
                TimelineDayBucket(day(2026, 8, 31), 1),
                TimelineDayBucket(day(2025, 12, 31), 5),
            ),
        ),
    )

    @Test
    fun dayHeadersCountTheirDay() {
        assertEquals(3, counts.count(day(2026, 9, 28), TimelineGrouping.Day))
        assertNull(counts.count(day(2026, 9, 27), TimelineGrouping.Day))
    }

    @Test
    fun monthAndYearHeadersSumTheirDaysWhateverDayTheyCarry() {
        assertEquals(7, counts.count(day(2026, 9, 1), TimelineGrouping.Month))
        assertEquals(7, counts.count(day(2026, 9, 28), TimelineGrouping.Month))
        assertEquals(1, counts.count(day(2026, 8, 31), TimelineGrouping.Month))
        assertEquals(8, counts.count(day(2026, 1, 1), TimelineGrouping.Year))
        assertEquals(5, counts.count(day(2025, 12, 31), TimelineGrouping.Year))
    }
}
