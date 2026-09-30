package com.librestatic.lightforge.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineIndexTest {
    private fun day(year: Int, month: Int, dom: Int) = LocalDate.of(year, month, dom).toEpochDay()

    // Newest first: 2 rows on 2026-03-02, 6 on 2025-07-10, 2 on 2024-01-01.
    private val descending = TimelineIndex(
        listOf(
            TimelineDayBucket(day(2026, 3, 2), 2),
            TimelineDayBucket(day(2025, 7, 10), 6),
            TimelineDayBucket(day(2024, 1, 1), 2),
        ),
    )

    @Test fun `totals and fractions follow row counts`() {
        assertEquals(10, descending.total)
        assertEquals(0f, descending.fractionOf(day(2026, 3, 2)), 0f)
        assertEquals(0.2f, descending.fractionOf(day(2025, 7, 10)), 1e-6f)
        assertEquals(0.5f, descending.fractionOf(day(2025, 7, 10), 0.5f), 1e-6f)
        assertEquals(0.8f, descending.fractionOf(day(2024, 1, 1)), 1e-6f)
    }

    @Test fun `positionAt inverts fractionOf`() {
        val position = descending.positionAt(0.5f)!!
        assertEquals(day(2025, 7, 10), position.bucket.epochDay)
        assertEquals(0.5f, position.fractionInBucket, 1e-6f)
        assertEquals(day(2026, 3, 2), descending.positionAt(0f)!!.bucket.epochDay)
        assertEquals(day(2024, 1, 1), descending.positionAt(1f)!!.bucket.epochDay)
    }

    @Test fun `missing day resolves to the next bucket in display order`() {
        assertEquals(1, descending.bucketIndexOf(day(2025, 12, 25)))
        assertEquals(2, descending.bucketIndexOf(day(2020, 1, 1)))
        assertEquals(0, descending.bucketIndexOf(day(2030, 1, 1)))
    }

    @Test fun `ascending order searches the other way`() {
        val ascending = TimelineIndex(descending.days.reversed(), ascending = true)
        assertEquals(2, ascending.bucketIndexOf(day(2025, 12, 25)))
        assertEquals(0.2f, ascending.fractionOf(day(2025, 7, 10)), 1e-6f)
    }

    @Test fun `year ticks sit where each year starts`() {
        val ticks = descending.yearTicks()
        assertEquals(listOf(2026, 2025, 2024), ticks.map { it.year })
        assertEquals(listOf(0f, 0.2f, 0.8f), ticks.map { it.fraction })
    }

    @Test fun `empty index has no position`() {
        val empty = TimelineIndex(emptyList())
        assertTrue(empty.isEmpty)
        assertNull(empty.positionAt(0.5f))
        assertEquals(0f, empty.fractionOf(day(2026, 1, 1)), 0f)
        assertTrue(empty.yearTicks().isEmpty())
    }

    @Test fun `single day maps every fraction to that day`() {
        val single = TimelineIndex(listOf(TimelineDayBucket(day(2026, 1, 1), 5)))
        assertEquals(day(2026, 1, 1), single.positionAt(0.9f)!!.bucket.epochDay)
        assertEquals(1, single.yearTicks().size)
    }
}
