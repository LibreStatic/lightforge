package com.librestatic.lightforge.core.data

import androidx.paging.PagingData
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.preferences.LibraryGrouping
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import java.time.Instant
import java.time.ZoneId

/**
 * Merges items that are not in the library (hidden ones, read from disk) into the timeline in the order the
 * library query gives, so a hidden item sits where its date, name or size puts it. The query orders by the date
 * group first (unless the settings are the default, where the date sort already agrees), then by the sort field,
 * then by id, all in the chosen direction; this comparator repeats exactly that, so a group header is never
 * repeated and an item lands in one gap only.
 */
internal fun hiddenOrder(settings: LibrarySettings, zoneId: ZoneId = ZoneId.systemDefault()): Comparator<TimelineMedia> {
    val field: Comparator<TimelineMedia> = when (settings.sort) {
        LibrarySort.DateTaken -> compareBy { it.timelineSortMillis }
        LibrarySort.DateModified -> compareBy { it.dateModifiedSeconds }
        // SQLite LOWER folds ASCII only and compares bytes.
        LibrarySort.Name -> compareBy { it.displayName.orEmpty().asciiLower() }
        LibrarySort.Size -> compareBy { it.sizeBytes }
    }
    val grouped = settings != LibrarySettings() && settings.grouping != LibraryGrouping.None
    val withGroup: Comparator<TimelineMedia> =
        if (grouped) compareBy<TimelineMedia> { it.groupEpochDay(zoneId, settings.grouping) }.thenComparing(field) else field
    val directed = if (settings.ascending) withGroup else withGroup.reversed()
    return directed.thenComparing(
        if (settings.ascending) compareBy<TimelineMedia> { it.key.mediaStoreId }.thenBy { it.key.volumeName }
        else compareByDescending<TimelineMedia> { it.key.mediaStoreId }.thenByDescending { it.key.volumeName },
    )
}

private fun String.asciiLower(): String =
    String(CharArray(length) { i -> this[i].let { if (it in 'A'..'Z') it + 32 else it } })

private fun TimelineMedia.groupEpochDay(zoneId: ZoneId, grouping: LibraryGrouping): Long {
    val date = Instant.ofEpochMilli(timelineSortMillis).atZone(zoneId).toLocalDate()
    return when (grouping) {
        LibraryGrouping.Day -> date.toEpochDay()
        LibraryGrouping.Month -> date.withDayOfMonth(1).toEpochDay()
        LibraryGrouping.Year -> date.withDayOfYear(1).toEpochDay()
        LibraryGrouping.None -> 0L
    }
}

/**
 * The hidden items that belong in the gap between two consecutive library rows. [before] and [after] are null
 * at the ends of the list, where every item that sorts outside the neighbour belongs.
 */
internal fun hiddenBetween(
    before: TimelineMedia?,
    after: TimelineMedia?,
    sortedHidden: List<TimelineMedia>,
    order: Comparator<TimelineMedia>,
): List<TimelineMedia> = sortedHidden.filter { h ->
    (before == null || order.compare(h, before) >= 0) && (after == null || order.compare(h, after) < 0)
}

private data class Slot(val items: List<TimelineMedia>)

/**
 * [data] with [hidden] merged in. Paging allows one separator per gap, so a gap's whole run travels as one
 * [Slot] that is flattened afterwards.
 */
internal fun mergeHidden(
    data: PagingData<TimelineMedia>,
    hidden: List<TimelineMedia>,
    settings: LibrarySettings,
    zoneId: ZoneId = ZoneId.systemDefault(),
): PagingData<TimelineMedia> {
    if (hidden.isEmpty()) return data
    val order = hiddenOrder(settings, zoneId)
    val sorted = hidden.sortedWith(order)
    return data.map { Slot(listOf(it)) }
        .insertSeparators { before: Slot?, after: Slot? ->
            val run = hiddenBetween(before?.items?.single(), after?.items?.single(), sorted, order)
            if (run.isEmpty()) null else Slot(run)
        }
        .flatMap { it.items }
}
