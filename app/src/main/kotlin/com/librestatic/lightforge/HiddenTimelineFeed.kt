package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.HiddenMediaKind
import com.librestatic.lightforge.core.mediastore.HiddenMediaRow
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.preferences.FolderSelectionMode
import com.librestatic.lightforge.core.preferences.FolderSelectionPolicy
import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.activeFilters
import com.librestatic.lightforge.feature.folders.normalizedFolderPath

// Advanced storage mode: hidden items (inside a .nomedia folder) are not in the library, so Photos reads them
// from the live file scan and merges the ones the user asked for into the timeline.

private val RawExtensions = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "pef", "srw")
private val AnimatedMimes = setOf("image/gif", "image/webp", "image/avif", "image/heif", "image/heic")

/** What the timeline rules need to know about one hidden file. */
internal data class HiddenFacts(
    val volumeName: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
)

internal fun HiddenMediaRow.timelineFacts() =
    HiddenFacts(volumeName, relativePath, displayName, mimeType, kind == HiddenMediaKind.Video)

/** `volume|path/` of the folder holding the file, the identity the "show even if hidden" rule is stored under. */
internal fun HiddenFacts.folderIdentity(): String = "$volumeName|${normalizedFolderPath(relativePath)}"

/** Whether a "show even if hidden" rule reaches the file: the rule's folder is its folder or an ancestor. */
internal fun HiddenFacts.coveredBy(rules: Set<String>): Boolean {
    val own = folderIdentity()
    return rules.any { own.startsWith(it) }
}

private fun HiddenFacts.matches(filter: LibraryFilter): Boolean = when (filter) {
    LibraryFilter.All -> true
    LibraryFilter.Videos -> isVideo
    LibraryFilter.Images -> !isVideo
    LibraryFilter.Animated -> !isVideo && mimeType.lowercase() in AnimatedMimes
    LibraryFilter.Raw -> !isVideo && displayName.substringAfterLast('.', "").lowercase() in RawExtensions
}

/**
 * The hidden rows Photos lists: all of them while hidden items are shown, otherwise only those under a folder
 * with the "show even if hidden" rule. The library's type filters and folder rules apply as they do to every
 * other item; an excluded folder stays out.
 */
internal fun <T> hiddenRowsForTimeline(
    rows: List<T>,
    facts: (T) -> HiddenFacts,
    showAll: Boolean,
    evenIfHidden: Set<String>,
    library: LibrarySettings,
): List<T> {
    if (rows.isEmpty() || (!showAll && evenIfHidden.isEmpty())) return emptyList()
    val kinds = library.activeFilters()
    val defaultSelected = library.folderSelectionMode == FolderSelectionMode.AllExceptExcluded
    return rows.filter { row ->
        val f = facts(row)
        (showAll || f.coveredBy(evenIfHidden)) &&
            (kinds.isEmpty() || kinds.any { f.matches(it) }) &&
            FolderSelectionPolicy.isSelected(defaultSelected, library.folderRules, f.volumeName, Long.MIN_VALUE, f.relativePath)
    }
}

internal fun HiddenMediaRow.toOverlayMedia() = TimelineMedia(
    key = MediaKey(volumeName, mediaStoreId),
    kind = if (kind == HiddenMediaKind.Video) MediaKind.Video else MediaKind.Image,
    generationModified = 1L,
    timelineSortMillis = dateTakenOrModifiedMillis,
    width = 0,
    height = 0,
    durationMillis = 0L,
    displayName = displayName,
    sizeBytes = sizeBytes,
    dateModifiedSeconds = dateTakenOrModifiedMillis / 1_000,
    isHiddenOverlay = true,
)
