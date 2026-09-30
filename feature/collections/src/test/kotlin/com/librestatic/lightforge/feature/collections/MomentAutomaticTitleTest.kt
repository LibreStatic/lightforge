package com.librestatic.lightforge.feature.collections

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MomentAutomaticTitleTest {
    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()
    private fun title(
        start: String = "2026-09-06T12:00:00Z",
        end: String = start,
        zone: String = "UTC",
        locale: Locale = Locale.US,
        place: String? = null,
        manual: String? = null,
        rangeTemplate: String = "%1\$s – %2\$s",
        placeTemplate: String = "%1\$s · %2\$s",
    ) = formatMomentAutomaticTitle(millis(start), millis(end), ZoneId.of(zone), locale,
        place, manual, rangeTemplate, placeTemplate)

    @Test fun sameCivilDayDoesNotRepeatItsDate() {
        assertEquals("September 6, 2026", title(end = "2026-09-06T23:59:59Z"))
    }

    @Test fun rangeContainsBothYearsAndNormalizesReversedBounds() {
        val start = "2025-12-31T12:00:00Z"
        val end = "2026-01-02T12:00:00Z"
        assertEquals("December 31, 2025 – January 2, 2026", title(start, end))
        assertEquals(title(start, end), title(end, start))
    }

    @Test fun zoneDeterminesCivilDatesInsteadOfUtcOrDeviceDefault() {
        assertEquals("September 5, 2026", title(start = "2026-09-06T01:00:00Z", zone = "America/Argentina/Buenos_Aires"))
        assertEquals("September 6, 2026", title(start = "2026-09-06T01:00:00Z", zone = "UTC"))
    }

    @Test fun daylightSavingDayIsOneCivilDateWithoutAssumingTwentyFourHours() {
        assertEquals("March 8, 2026", title("2026-03-08T05:00:00Z", "2026-03-09T03:59:59Z", "America/New_York"))
    }

    @Test fun allSixLocalesUseLocalizedDates() {
        val expected = mapOf(
            "en-US" to "September 6, 2026",
            "es" to "6 de septiembre de 2026",
            "fr" to "6 septembre 2026",
            "pt" to "6 de setembro de 2026",
            "it" to "6 settembre 2026",
            "de" to "6. September 2026",
        )
        expected.forEach { (language, value) -> assertEquals(language, value, title(locale = Locale.forLanguageTag(language))) }
    }

    @Test fun optionalPlaceIsTrimmedButMissingOrBlankPlaceAddsNoSeparator() {
        assertEquals("September 6, 2026 · Córdoba", title(place = "  Córdoba  "))
        assertEquals(title(), title(place = " \t\n"))
        assertEquals("September 6, 2026 · 100% local", title(place = "100% local"))
    }

    @Test fun manualTitleRemainsByteForByteAndBlankManualUsesFallback() {
        val manual = "  Nuestro viaje — 2026  "
        assertEquals(manual, title(place = "Córdoba", manual = manual))
        assertEquals(title(), title(manual = " \t"))
    }

    @Test fun positionalTemplatesSupportLocaleSpecificOrdering() {
        assertEquals("Córdoba: September 6, 2026", title(place = "Córdoba", placeTemplate = "%2\$s: %1\$s"))
        assertEquals("September 7, 2026 / September 6, 2026", title(end = "2026-09-07T12:00:00Z", rangeTemplate = "%2\$s / %1\$s"))
    }
}
