package com.ugallery.core.data

import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class MemoryDateRangeTest {
    private fun range(start: String, end: String = start, zone: String = "UTC") =
        MemoryDateRange(LocalDate.parse(start), LocalDate.parse(end), zone)

    @Test
    fun springDayHasTwentyThreeHours() {
        val (a, b) = range("2026-03-08", zone = "America/New_York").bounds()
        assertEquals(23 * 3_600_000L, b - a)
    }

    @Test
    fun autumnDayHasTwentyFiveHours() {
        val (a, b) = range("2026-11-01", zone = "America/New_York").bounds()
        assertEquals(25 * 3_600_000L, b - a)
    }

    @Test
    fun inclusiveLeapRangeIncludesLeapDay() {
        val (a, b) = range("2024-02-28", "2024-03-01").bounds()
        assertEquals(72 * 3_600_000L, b - a)
    }

    @Test
    fun endIsExclusiveStartOfFollowingDay() {
        val (a, b) = range("2026-06-01").bounds()
        assertEquals(Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(), a)
        assertEquals(Instant.parse("2026-06-02T00:00:00Z").toEpochMilli(), b)
    }

    @Test
    fun deviceTimezoneChangeDoesNotChangeExplicitBounds() {
        val old = TimeZone.getDefault()
        try {
            val r = range("2026-06-01", zone = "Asia/Kathmandu")
            val bounds = r.bounds()
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals(bounds, r.bounds())
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun invertedDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { range("2026-06-02", "2026-06-01") }
    }

    @Test
    fun entirelySkippedCivilDayIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            range("2011-12-30", zone = "Pacific/Apia")
        }
    }

    @Test
    fun dateAndTimezoneBoundsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { range("0000-01-01") }
        assertThrows(IllegalArgumentException::class.java) { range("9999-12-31") }
        assertThrows(java.time.DateTimeException::class.java) {
            range("2026-01-01", zone = "not/a/zone")
        }
        assertTrue(range("0001-01-01", "9998-12-31").bounds().let { it.first < it.second })
    }
}
