package com.librestatic.lightforge.core.data

import androidx.paging.PagingData
import androidx.paging.flatMap
import androidx.paging.insertSeparators
import androidx.paging.map
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort

/**
 * Merges items that are not in the library (hidden ones, read from disk) into the timeline in the order the
 * library settings give, so a hidden item sits where its date, name or size puts it.
 */
internal fun hiddenOrder(settings: LibrarySettings): Comparator<TimelineMedia> {
    val base: Comparator<TimelineMedia> = when (settings.sort) {
        LibrarySort.DateTaken -> compareBy { it.timelineSortMillis }
        LibrarySort.DateModified -> compareBy { it.dateModifiedSeconds }
        LibrarySort.Name -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.orEmpty() }
        LibrarySort.Size -> compareBy { it.sizeBytes }
    }
    val directed = if (settings.ascending) base else base.reversed()
    return directed.thenBy { it.key.mediaStoreId }
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
): PagingData<TimelineMedia> {
    if (hidden.isEmpty()) return data
    val order = hiddenOrder(settings)
    val sorted = hidden.sortedWith(order)
    return data.map { Slot(listOf(it)) }
        .insertSeparators { before: Slot?, after: Slot? ->
            val run = hiddenBetween(before?.items?.single(), after?.items?.single(), sorted, order)
            if (run.isEmpty()) null else Slot(run)
        }
        .flatMap { it.items }
}
