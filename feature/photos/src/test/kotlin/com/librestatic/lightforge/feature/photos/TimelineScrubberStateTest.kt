package com.librestatic.lightforge.feature.photos

import com.librestatic.lightforge.core.model.TimelineYearTick
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineScrubberStateTest {
    private val today = LocalDate.of(2026, 9, 28)
    private val patterns = mapOf("EEEdMMM" to "EEE, d MMM", "EEEdMMMy" to "EEE, d MMM y", "MMMy" to "MMM y")
    private fun format(date: LocalDate) = formatScrubberDate(
        date.toEpochDay(), today, Locale.ENGLISH, "Today", "Yesterday",
    ) { patterns.getValue(it) }

    @Test fun `today and yesterday use the relative labels`() {
        assertEquals("Today", format(today))
        assertEquals("Yesterday", format(today.minusDays(1)))
    }

    @Test fun `year only appears outside the current year`() {
        assertEquals("Fri, 20 Feb", format(LocalDate.of(2026, 2, 20)))
        assertEquals("Mon, 17 Mar 2025", format(LocalDate.of(2025, 3, 17)))
    }

    @Test fun `month bubble shows month and year`() {
        assertEquals(
            "Jul 2025",
            formatScrubberMonth(LocalDate.of(2025, 7, 3).toEpochDay(), Locale.ENGLISH) { patterns.getValue(it) },
        )
    }

    @Test fun `invalid platform pattern falls back to ISO date`() {
        val text = formatScrubberDate(
            LocalDate.of(2025, 3, 17).toEpochDay(), today, Locale.ENGLISH, "Today", "Yesterday",
        ) { "'" }
        assertEquals("2025-03-17", text)
    }

    @Test fun `ticks keep the minimum gap and stay inside the track`() {
        val ticks = listOf(0f, 0.01f, 0.02f, 0.5f, 0.99f, 1f).mapIndexed { i, f -> TimelineYearTick(2026 - i, f) }
        val placed = layoutYearTicks(ticks, heightPx = 1000f, minGapPx = 40f)
        assertEquals(ticks.size, placed.size)
        placed.zipWithNext().forEach { (a, b) -> assertTrue(b.y - a.y >= 40f - 1e-3f) }
        assertTrue(placed.first().y >= 0f)
        assertTrue(placed.last().y <= 1000f)
    }

    @Test fun `ticks that cannot fit are thinned instead of overlapping`() {
        val ticks = (0 until 60).map { TimelineYearTick(2026 - it, it / 60f) }
        val placed = layoutYearTicks(ticks, heightPx = 400f, minGapPx = 40f)
        assertTrue(placed.size <= 11)
        placed.zipWithNext().forEach { (a, b) -> assertTrue(b.y - a.y >= 40f - 1e-3f) }
        assertEquals(2026, placed.first().year)
    }

    @Test fun `handle offset and fraction round trip`() {
        assertEquals(250f, scrubberHandleOffset(0.5f, 500f), 0f)
        assertEquals(0.5f, scrubberFraction(250f, 500f), 0f)
        assertEquals(1f, scrubberFraction(900f, 500f), 0f)
        assertEquals(0f, scrubberFraction(10f, 0f), 0f)
    }
}
