package com.librestatic.lightforge.core.data

import com.librestatic.lightforge.core.model.MediaKey
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SmartAlbumRuleTest {
    @Test
    fun namesAndCanonicalTopicsNormalizeWithoutSqlInterpolation() {
        assertEquals("My album", SmartAlbumRule.normalizeName("  My   album  "))
        val rule = SmartAlbumRule(topic = "  BEACH  ")
        assertEquals("beach", rule.normalized().topic)
        val hostile = "x' OR 1=1 --"
        val query =
            SmartAlbumQuery.build(
                SmartAlbumRule(
                    topic = hostile,
                    personClusterId = hostile,
                    personAlgorithmVersion = hostile,
                ),
                hostile,
                hostile,
                excluded = listOf(MediaKey(hostile, 1)),
            )
        assertFalse(query.sql.contains(hostile))
        assertEquals(9, query.argCount)
    }

    @Test
    fun invalidNamesAndPartialIdentityAreRejected() {
        for (name in listOf("", "  ", "a".repeat(81))) assertThrows(
            IllegalArgumentException::class.java
        ) {
            SmartAlbumRule.normalizeName(name)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartAlbumRule(personClusterId = "id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartAlbumRule(personAlgorithmVersion = "v")
        }
        assertThrows(IllegalArgumentException::class.java) { SmartAlbumRule(topic = "  ") }
        assertThrows(IllegalArgumentException::class.java) { SmartAlbumRule(year = 0) }
        assertThrows(IllegalArgumentException::class.java) { SmartAlbumRule(year = 9999) }
    }

    @Test
    fun civilYearUsesHalfOpenLocalBoundariesIncludingLeapYear() {
        val bounds = SmartAlbumRule(year = 2024, zoneId = "America/Argentina/Buenos_Aires").bounds()
        assertEquals(Instant.parse("2024-01-01T03:00:00Z").toEpochMilli(), bounds.first)
        assertEquals(366L * 24 * 60 * 60 * 1000, bounds.second!! - bounds.first!!)
        assertEquals(null to null, SmartAlbumRule().bounds())
    }

    @Test
    fun explicitZoneSurvivesDeviceTimezoneChange() {
        val old = TimeZone.getDefault()
        try {
            val rule = SmartAlbumRule(year = 2026, zoneId = "America/New_York")
            val before = rule.bounds()
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(before, rule.bounds())
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun savedBoundsOverrideRecomputedZoneRules() {
        val query = SmartAlbumQuery.build(SmartAlbumRule(year = 2026), bounds = 10L to 20L)
        assertEquals(2, query.argCount)
        assertTrue(query.sql.contains("timelineSortMillis>=? AND m.timelineSortMillis<?"))
        assertThrows(IllegalArgumentException::class.java) {
            SmartAlbumQuery.build(SmartAlbumRule(), bounds = 20L to 10L)
        }
    }

    @Test
    fun allCriteriaAndEligibilityAreConjunctiveAndCurrent() {
        val sql =
            SmartAlbumQuery.build(SmartAlbumRule("beach", "person", "v1", 2026, "UTC", true)).sql
        for (part in
            listOf(
                "m.isAccessible=1",
                "m.isTrashed=0",
                "m.mediaType=1",
                "archived_media",
                "m.isFavorite=1",
                "r.generationModified=m.generationModified",
                "r.modelVersion=l.modelVersion",
                "label_suppressions",
                "p.algorithmVersion=c.algorithmVersion",
                "e.detectionModelVersion=r.modelVersion",
            )) assertTrue(part, sql.contains(part))
        assertFalse(sql.contains(" OR "))
    }

    @Test
    fun previewLimitStaysBelowLegacySqliteParameterLimit() {
        val keys = (1L..200L).map { MediaKey("external_primary", it) }
        val query =
            SmartAlbumQuery.build(
                SmartAlbumRule("beach", "person", "v1", 2026, "UTC", true),
                "album",
                "revision",
                keys,
            )
        assertTrue(query.argCount < 999)
        assertThrows(IllegalArgumentException::class.java) {
            SmartAlbumQuery.build(SmartAlbumRule(), excluded = keys + MediaKey("v", 201))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartAlbumQuery.build(SmartAlbumRule(), albumId = "id")
        }
    }

    @Test
    fun resultOrderingIsTotalAcrossVolumesAndCountHasNoOrder() {
        assertTrue(
            SmartAlbumQuery.build(SmartAlbumRule())
                .sql
                .endsWith("m.timelineSortMillis DESC,m.mediaStoreId DESC,m.volumeName DESC")
        )
        val count = SmartAlbumQuery.build(SmartAlbumRule(), count = true).sql
        assertTrue(count.startsWith("SELECT COUNT(*)"))
        assertFalse(count.contains("ORDER BY"))
    }
}
