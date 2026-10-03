package com.librestatic.lightforge.core.search

import com.librestatic.lightforge.core.model.MediaKind
import java.text.Normalizer
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

data class ParsedSearchQuery(
    val original: String,
    val normalizedTerms: List<String>,
    val kind: MediaKind? = null,
    val favoriteOnly: Boolean = false,
    val personIds: List<String> = emptyList(),
    val fromMillisInclusive: Long? = null,
    val toMillisExclusive: Long? = null,
) {
    val isEmpty: Boolean get() = normalizedTerms.isEmpty() && kind == null && !favoriteOnly &&
        personIds.isEmpty() && fromMillisInclusive == null && toMillisExclusive == null
}

object SearchTextNormalizer {
    private val CombiningMarks = Regex("\\p{M}+")
    private val NonWord = Regex("[^\\p{L}\\p{N}_-]+")
    private val Whitespace = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(CombiningMarks, "")
        .lowercase(Locale.ROOT)
        .replace(NonWord, " ")
        .replace(Whitespace, " ")
        .trim()

    fun normalizeForIndex(values: List<String>): String = values.asSequence()
        .map(::normalize)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
}

/**
 * Words that turn a query into the favorites filter, in every app language, so the localized
 * "Favorites" label can be searched as typed (en, es, pt, fr, de, it).
 */
private val FavoriteTokens = setOf(
    "favorite", "favorites", "favorito", "favoritos", "favori", "favoris",
    "favorit", "favoriten", "preferito", "preferiti",
)

class SearchQueryParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(raw: String): ParsedSearchQuery {
        SearchVocabulary.resolve(raw)?.let { concept ->
            return concept.asExactQuery(raw)
        }
        var kind: MediaKind? = null
        var favoriteOnly = false
        var from: Long? = null
        var to: Long? = null
        val people = linkedSetOf<String>()
        val terms = mutableListOf<String>()

        raw.trim().split(Whitespace).filter(String::isNotBlank).forEach { rawToken ->
            val token = SearchTextNormalizer.normalize(rawToken)
            val separator = rawToken.indexOf(':')
            val key = if (separator > 0) SearchTextNormalizer.normalize(rawToken.substring(0, separator)) else ""
            val rawValue = if (separator > 0) rawToken.substring(separator + 1) else ""
            val value = SearchTextNormalizer.normalize(rawValue)
            when (key) {
                "tipo", "type", "typ" -> kind = parseKind(value) ?: kind
                "persona", "person" -> rawValue.lowercase(Locale.ROOT).validOpaqueId()?.let(people::add)
                "fecha", "date" -> parseDateRange(value)?.let { (start, end) -> from = start; to = end }
                "desde", "from" -> parseDateStart(value)?.let { from = it }
                "hasta", "to" -> parseDateStart(value)?.let {
                    to = java.time.Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
                        .plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
                else -> when (val concept = SearchVocabulary.resolve(rawToken)) {
                    SearchConcept.Image -> kind = MediaKind.Image
                    SearchConcept.Video -> kind = MediaKind.Video
                    null -> when (token) {
                        in FavoriteTokens -> favoriteOnly = true
                        else -> terms += token
                    }
                    else -> terms += concept.canonicalTerm ?: token
                }
            }
        }
        return ParsedSearchQuery(
            original = raw,
            normalizedTerms = terms.filter(String::isNotBlank).distinct(),
            kind = kind,
            favoriteOnly = favoriteOnly,
            personIds = people.toList(),
            fromMillisInclusive = from,
            toMillisExclusive = to,
        )
    }

    private fun parseKind(value: String) = when (SearchVocabulary.resolve(value)) {
        SearchConcept.Image -> MediaKind.Image
        SearchConcept.Video -> MediaKind.Video
        else -> null
    }

    private fun SearchConcept.asExactQuery(raw: String) = ParsedSearchQuery(
        original = raw,
        normalizedTerms = canonicalTerm?.let(::listOf).orEmpty(),
        kind = when (this) {
            SearchConcept.Image -> MediaKind.Image
            SearchConcept.Video -> MediaKind.Video
            else -> null
        },
    )

    private fun parseDateRange(value: String): Pair<Long, Long>? = try {
        when (value.length) {
            4 -> Year.parse(value).let { year ->
                year.atDay(1).startMillis() to year.plusYears(1).atDay(1).startMillis()
            }
            7 -> YearMonth.parse(value).let { month ->
                month.atDay(1).startMillis() to month.plusMonths(1).atDay(1).startMillis()
            }
            10 -> LocalDate.parse(value).let { date ->
                date.startMillis() to date.plusDays(1).startMillis()
            }
            else -> null
        }
    } catch (_: DateTimeParseException) { null }

    private fun parseDateStart(value: String): Long? = try {
        LocalDate.parse(value).startMillis()
    } catch (_: DateTimeParseException) { null }

    private fun LocalDate.startMillis() = atStartOfDay(zoneId).toInstant().toEpochMilli()

    private fun String.validOpaqueId() = takeIf { it.matches(OpaqueId) }

    private companion object {
        val Whitespace = Regex("\\s+")
        val OpaqueId = Regex("[a-z0-9_-]{1,100}")
    }
}
