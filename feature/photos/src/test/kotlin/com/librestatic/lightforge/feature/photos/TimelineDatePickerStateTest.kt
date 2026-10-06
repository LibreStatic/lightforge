package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineIndex
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineDatePickerStateTest {
    private fun bucket(date: LocalDate, count: Int = 1) = TimelineDayBucket(date.toEpochDay(), count)

    private val days = listOf(
        bucket(LocalDate.of(2026, 9, 27), 7),
        bucket(LocalDate.of(2026, 9, 4)),
        bucket(LocalDate.of(2026, 6, 1)),
        bucket(LocalDate.of(2025, 12, 31)),
    )

    @Test fun `only months with media are listed, newest first`() {
        val months = datePickerMonths(TimelineIndex(days))
        assertEquals(
            listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 6), YearMonth.of(2025, 12)),
            months.map { it.month },
        )
        assertEquals(setOf(4, 27), months.first().days.keys)
        assertEquals(7, months.first().days.getValue(27).count)
    }

    @Test fun `ascending timelines list the oldest month first`() {
        val months = datePickerMonths(TimelineIndex(days.reversed(), ascending = true))
        assertEquals(YearMonth.of(2025, 12), months.first().month)
    }

    @Test fun `the picker opens on the shown day's month or the newest one`() {
        val months = datePickerMonths(TimelineIndex(days))
        assertEquals(1, datePickerMonthIndex(months, LocalDate.of(2026, 6, 1).toEpochDay()))
        assertEquals(0, datePickerMonthIndex(months, LocalDate.of(2024, 1, 1).toEpochDay()))
        assertEquals(0, datePickerMonthIndex(months, null))
    }

    @Test fun `weeks start on the locale's first day`() {
        // September 2026 starts on a Tuesday.
        val sunday = datePickerWeeks(YearMonth.of(2026, 9), DayOfWeek.SUNDAY)
        assertEquals(listOf(null, null, 1, 2, 3, 4, 5), sunday.first())
        val monday = datePickerWeeks(YearMonth.of(2026, 9), DayOfWeek.MONDAY)
        assertEquals(listOf(null, 1, 2, 3, 4, 5, 6), monday.first())
        assertEquals(listOf(28, 29, 30, null, null, null, null), monday.last())
        assert(sunday.all { it.size == 7 })
    }
}
