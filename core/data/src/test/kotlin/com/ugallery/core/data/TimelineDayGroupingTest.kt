package com.ugallery.core.data

import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimelineDayGroupingTest {
    @Test
    fun sameInstantUsesCurrentTimezoneBoundary() {
        val item = media("2026-01-01T01:30:00Z")
        val utcDay = item.epochDay(ZoneId.of("UTC"))
        val losAngelesDay = item.epochDay(ZoneId.of("America/Los_Angeles"))

        assertNotEquals(utcDay, losAngelesDay)
        assertEquals("2026-01-01", java.time.LocalDate.ofEpochDay(utcDay).toString())
        assertEquals("2025-12-31", java.time.LocalDate.ofEpochDay(losAngelesDay).toString())
    }

    private fun media(instant: String) = TimelineMedia(
        key = MediaKey("external_primary", 1),
        kind = MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = Instant.parse(instant).toEpochMilli(),
        width = 1,
        height = 1,
        durationMillis = 0,
    )
}
