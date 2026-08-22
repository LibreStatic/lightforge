package com.ugallery.core.search

import com.ugallery.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SearchQueryParserTest {
    private val zone = ZoneId.of("UTC")
    private val parser = SearchQueryParser(zone)

    @Test fun `Spanish diacritics synonyms and structured filters combine`() {
        val parsed = parser.parse("FÓTOS playa fecha:2025-08 persona:abc-1 favoritos")

        assertEquals(MediaKind.Image, parsed.kind)
        assertEquals(listOf("beach"), parsed.normalizedTerms)
        assertEquals(listOf("abc-1"), parsed.personIds)
        assertTrue(parsed.favoriteOnly)
        assertEquals(LocalDate.of(2025, 8, 1).start(), parsed.fromMillisInclusive)
        assertEquals(LocalDate.of(2025, 9, 1).start(), parsed.toMillisExclusive)
        assertEquals(
            "beach AND kind:image AND favoriteToken:favorite AND personIds:abc-1 AND " +
                "timelineSortMillis >= ${LocalDate.of(2025, 8, 1).start()} AND " +
                "timelineSortMillis < ${LocalDate.of(2025, 9, 1).start()}",
            parsed.expression(),
        )
    }

    @Test fun `date bounds and video filter remain composable`() {
        val parsed = parser.parse("tipo:vídeo desde:2024-01-02 hasta:2024-01-05 documento")

        assertEquals(MediaKind.Video, parsed.kind)
        assertEquals(listOf("document"), parsed.normalizedTerms)
        assertEquals(LocalDate.of(2024, 1, 2).start(), parsed.fromMillisInclusive)
        assertEquals(LocalDate.of(2024, 1, 6).start(), parsed.toMillisExclusive)
    }

    @Test fun `pet discovery plurals match canonical labels`() {
        assertEquals(listOf("dog"), parser.parse("dogs").normalizedTerms)
        assertEquals(listOf("cat"), parser.parse("cats").normalizedTerms)
        assertEquals(listOf("dog"), parser.parse("perros").normalizedTerms)
        assertEquals(listOf("dog"), parser.parse("cães").normalizedTerms)
        assertEquals(listOf("cat"), parser.parse("Katzen").normalizedTerms)
    }

    @Test fun `localized phrases and discovery terms match canonical index labels`() {
        assertEquals(listOf("screenshot"), parser.parse("Capturas de pantalla").normalizedTerms)
        assertEquals(listOf("camera"), parser.parse("Appareil photo").normalizedTerms)
        assertEquals(listOf("landscape"), parser.parse("Landschaften").normalizedTerms)
        assertEquals(listOf("dog", "beach"), parser.parse("perros playas").normalizedTerms)
        assertEquals(MediaKind.Image, parser.parse("Fotos").kind)
        assertEquals(MediaKind.Video, parser.parse("tipo:Vidéos").kind)
        assertEquals(listOf("me"), parser.parse("Yo").normalizedTerms)
        assertEquals(listOf("person"), parser.parse("Gesichter").normalizedTerms)
    }

    @Test fun `normalization is locale independent and stable for indexing`() {
        assertEquals("camara nino sao-paulo", SearchTextNormalizer.normalize("Cámara NIÑO São-Paulo"))
        assertEquals(
            "camara nino beach",
            SearchTextNormalizer.normalizeForIndex(listOf("Cámara", "niño", "cámara", "beach")),
        )
    }

    @Test fun `invalid opaque people and dates are not turned into unsafe expressions`() {
        val parsed = parser.parse("persona:../../etc fecha:not-a-date")
        assertTrue(parsed.personIds.isEmpty())
        assertEquals(null, parsed.fromMillisInclusive)
        assertEquals(null, parsed.toMillisExclusive)
        assertFalse(parsed.expression().contains(".."))
    }

    private fun LocalDate.start() = atStartOfDay(zone).toInstant().toEpochMilli()
}
