package com.librestatic.lightforge.feature.folders

import java.time.Instant
import java.time.ZoneId

/**
 * What the first line of a folder card says. Items count everything below the folder, subfolders included; a
 * folder without media says how many folders it holds, and one with nothing at all says it is empty.
 */
sealed interface CardCountLine {
    data object Empty : CardCountLine

    data class Items(val count: Long, val bytes: Long) : CardCountLine

    data class Folders(val count: Int) : CardCountLine
}

fun cardCountLine(folder: FolderCardUi): CardCountLine = when {
    folder.itemCount > 0 -> CardCountLine.Items(folder.itemCount, folder.sizeBytes)
    folder.subfolderCount > 0 -> CardCountLine.Folders(folder.subfolderCount)
    else -> CardCountLine.Empty
}

/**
 * The ICU skeleton that keeps a folder's date range short: the days when both ends fall in one month ("Jul 3 – 9,
 * 2026"), the months when they fall in one year ("Jun – Oct 2026"), the years otherwise ("2023 – 2026"). The
 * platform interval formatter turns it into the locale's wording and collapses what the two ends share.
 */
fun dateRangeSkeleton(minMillis: Long, maxMillis: Long, zone: ZoneId): String {
    val from = Instant.ofEpochMilli(minOf(minMillis, maxMillis)).atZone(zone)
    val to = Instant.ofEpochMilli(maxOf(minMillis, maxMillis)).atZone(zone)
    return when {
        from.year != to.year -> "y"
        from.monthValue != to.monthValue -> "yMMM"
        else -> "yMMMd"
    }
}
