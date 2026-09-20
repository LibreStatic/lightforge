package com.ugallery.core.data

import java.time.LocalDate
import java.time.ZoneId

/** Inclusive calendar dates with stored half-open instants, including 23/25-hour DST days. */
data class MemoryDateRange(val start: LocalDate, val end: LocalDate, val zoneId: String) {
    init {
        require(start.year in 1..9998 && end.year in 1..9998 && !end.isBefore(start))
        ZoneId.of(zoneId)
        require(bounds().first < bounds().second)
    }

    fun bounds(): Pair<Long, Long> {
        val zone = ZoneId.of(zoneId)
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to
            end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
