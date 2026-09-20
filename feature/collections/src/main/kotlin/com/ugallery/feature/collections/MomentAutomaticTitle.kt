package com.ugallery.feature.collections

import android.content.Context
import android.content.res.Configuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Presentation-only fallback: never writes a title or replaces a non-blank manual title.
 * The caller provides its current Configuration locale and the moment's display time zone.
 * Place labels must already have been resolved from current local data by the caller.
 */
fun momentAutomaticTitle(
    context: Context,
    startMillis: Long,
    endMillis: Long,
    zoneId: ZoneId,
    locale: Locale,
    placeLabel: String? = null,
    manualTitle: String? = null,
): String {
    if (!manualTitle.isNullOrBlank()) return manualTitle
    val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
    val resources = context.createConfigurationContext(configuration).resources
    return formatMomentAutomaticTitle(
        startMillis = startMillis,
        endMillis = endMillis,
        zoneId = zoneId,
        locale = locale,
        placeLabel = placeLabel,
        manualTitle = manualTitle,
        rangeTemplate = resources.getString(R.string.moment_automatic_title_range),
        placeTemplate = resources.getString(R.string.moment_automatic_title_place),
    )
}

/** Pure formatter; positional resource templates also let locales choose another argument order. */
internal fun formatMomentAutomaticTitle(
    startMillis: Long,
    endMillis: Long,
    zoneId: ZoneId,
    locale: Locale,
    placeLabel: String? = null,
    manualTitle: String? = null,
    rangeTemplate: String,
    placeTemplate: String,
): String {
    if (!manualTitle.isNullOrBlank()) return manualTitle
    val start = Instant.ofEpochMilli(minOf(startMillis, endMillis)).atZone(zoneId).toLocalDate()
    val end = Instant.ofEpochMilli(maxOf(startMillis, endMillis)).atZone(zoneId).toLocalDate()
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    val dates = if (start == end) dateFormat.format(start)
        else String.format(locale, rangeTemplate, dateFormat.format(start), dateFormat.format(end))
    val place = placeLabel?.trim()?.takeIf(String::isNotBlank)
    return if (place == null) dates else String.format(locale, placeTemplate, dates, place)
}
