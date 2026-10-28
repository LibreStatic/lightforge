package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryMenuDefaults
import com.librestatic.lightforge.core.designsystem.GalleryMenuDivider
import com.librestatic.lightforge.core.designsystem.GalleryMenuIcons
import com.librestatic.lightforge.core.designsystem.GalleryMenuItem
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.AnalysisProgress
import com.librestatic.lightforge.core.model.AnalysisWait
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineDayCover
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds a fake timeline into the real LibraryPhotosRoute. Thumbnails are flat tones decoded
// by a fake source (they stay placeholders where the renderer has no native bitmaps).

/** Three days of media (2, 7 and 12 items) with the filter row, sort menu and per-day counts. */
@Preview
@Composable
fun PhotosTimelinePreview() = PhotosFrame()

/** The same timeline mid-selection: tiles show their checkboxes, headers their group toggles. */
@Preview
@Composable
fun PhotosSelectionPreview() = PhotosFrame(initialSelection = setOf(3L, 4L, 10L))

/** A filter that matches nothing offers to show everything again. */
@Preview
@Composable
fun PhotosFilterEmptyPreview() = PhotosFrame(initialFilters = setOf(PhotosFilter.Raw), days = emptyList())

/** Two type chips on together (Photos and Videos) and, in advanced storage mode, the overflow offers hidden items and grouping. */
@Preview
@Composable
fun PhotosMultiFilterPreview() =
    PhotosFrame(initialFilters = setOf(PhotosFilter.Photos, PhotosFilter.Videos), canShowHidden = true)

/**
 * "Go to date": the calendar the date pill opens, over two years of media with gaps, each day
 * showing its first item. Rendered as the sheet's content so the driver sees it without a window.
 */
@Preview
@Composable
fun PhotosDatePickerPreview() {
    val loader = remember { previewThumbnailLoader() }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val index = remember { sampleIndex(sampleTimeline(PickerDays)) }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            TimelineDatePickerContent(
                index = index,
                loader = loader,
                shownDay = LocalDate.of(2026, 9, 27).toEpochDay(),
                onPick = {},
                today = LocalDate.of(2026, 9, 28),
            )
        }
    }
}

/**
 * A cold start that indexes for a while: the status card turns into a "Library ready" confirmation
 * (with the running face analysis under the title) that stays until "Got it", and then softens into
 * a quiet, non-dismissable information card.
 */
@Preview
@Composable
fun PhotosColdStartPreview() {
    var engineState by remember { mutableStateOf(LibraryUiState.Indexing) }
    LaunchedEffect(Unit) {
        delay(3_000)
        engineState = LibraryUiState.Ready
    }
    PhotosFrame(engineState = engineState, backgroundStatus = FacesRunning, libraryItemCount = 13_600)
}

/** Cold start over an already-indexed library while faces are analysed: the soft busy card, no confirmation. */
@Preview
@Composable
fun PhotosStatusSoftBusyPreview() =
    PhotosFrame(backgroundStatus = FacesRunning, libraryItemCount = 13_600)

/** Cold start over a library with nothing left to do: the soft "up to date" card. */
@Preview
@Composable
fun PhotosStatusSoftIdlePreview() = PhotosFrame(libraryItemCount = 13_600)

/** Analysis held back by the battery saver: the soft card gives the reason instead of progress. */
@Preview
@Composable
fun PhotosStatusSoftWaitingPreview() = PhotosFrame(
    backgroundStatus = LibraryBackgroundStatus(
        LibraryBackgroundWork.Faces,
        waitingOn = LibraryBackgroundWait.Battery,
        progress = FacesRunning.progress?.copy(running = false, etaMillis = null, waitReason = AnalysisWait.Battery),
    ),
)

/** "Show status in Photos" off: no card in any state, and no space reserved for it. */
@Preview
@Composable
fun PhotosStatusHiddenPreview() =
    PhotosFrame(backgroundStatus = FacesRunning, libraryItemCount = 13_600, showStatusCards = false)

private val FacesRunning = LibraryBackgroundStatus(
    work = LibraryBackgroundWork.Faces,
    progress = AnalysisProgress(
        processed = 4_210,
        total = 13_600,
        remaining = 9_390,
        etaMillis = 25 * 60_000L,
        running = true,
    ),
)

@Composable
private fun PhotosFrame(
    engineState: LibraryUiState = LibraryUiState.Ready,
    initialSelection: Set<Long> = emptySet(),
    initialFilters: Set<PhotosFilter> = emptySet(),
    canShowHidden: Boolean = false,
    days: List<Pair<LocalDate, Int>> = SampleDays,
    backgroundStatus: LibraryBackgroundStatus? = null,
    libraryItemCount: Long? = null,
    showStatusCards: Boolean = true,
) {
    var filters by remember { mutableStateOf(initialFilters) }
    var grouping by remember { mutableStateOf(PhotosGrouping.Day) }
    var showHidden by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(PhotosSort.Newest) }
    var selected by remember { mutableStateOf(initialSelection) }
    val loader = remember { previewThumbnailLoader() }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val timeline = remember(days) { sampleTimeline(days) }
    val entries = remember(timeline) { flowOf(PagingData.from(timeline)) }.collectAsLazyPagingItems()
    val index = remember(timeline) { sampleIndex(timeline) }
    val media = timeline.filterIsInstance<TimelineEntry.Media>().map { it.value }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            LibraryPhotosRoute(
                access = LibraryAccess(GrantLevel.Full, GrantLevel.Full, unredactedLocation = true),
                engineState = engineState,
                backgroundStatus = backgroundStatus,
                libraryItemCount = libraryItemCount,
                showStatusCards = showStatusCards,
                entries = entries,
                thumbnailLoader = loader,
                onRequestAccess = {},
                onMediaClick = { item ->
                    if (selected.isNotEmpty()) selected = selected.toggle(item.key.mediaStoreId)
                },
                isMediaSelected = { it.key.mediaStoreId in selected },
                onMediaSelectionChange = { item, on ->
                    selected = if (on) selected + item.key.mediaStoreId else selected - item.key.mediaStoreId
                },
                selectionMode = selected.isNotEmpty(),
                scrubberIndex = index,
                filters = filters,
                onFilterToggle = { kind ->
                    filters = when {
                        kind == PhotosFilter.All -> emptySet()
                        kind in filters -> filters - kind
                        else -> filters + kind
                    }
                },
                grouping = grouping,
                onGroupingChange = { grouping = it },
                canShowHidden = canShowHidden,
                showHidden = showHidden,
                onShowHiddenChange = { showHidden = it },
                sort = sort,
                onSortChange = { sort = it },
                onSelectAll = { selected = media.mapTo(mutableSetOf()) { it.key.mediaStoreId } },
                onClearSelection = { selected = emptySet() },
            )
        }
    }
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

private val SampleDays = listOf(
    LocalDate.of(2026, 9, 28) to 2,
    LocalDate.of(2026, 9, 27) to 7,
    LocalDate.of(2026, 9, 20) to 12,
)

/** Days with media for the date picker: busy and sparse months across a year boundary. */
private val PickerDays = buildList {
    listOf(4, 5, 6, 8, 9, 10, 12, 14, 15, 17, 18, 20, 22, 24, 25, 26, 27, 28)
        .reversed().forEach { add(LocalDate.of(2026, 9, it) to (it % 6) + 1) }
    listOf(30, 21, 7, 3, 1).forEach { add(LocalDate.of(2026, 8, it) to 3) }
    add(LocalDate.of(2026, 6, 14) to 4)
    listOf(31, 24, 2).forEach { add(LocalDate.of(2025, 12, it) to 5) }
}

/** The scrubber index of a sample timeline; each day's cover is its earliest (last listed) item. */
private fun sampleIndex(timeline: List<TimelineEntry>): TimelineIndex {
    val buckets = mutableListOf<TimelineDayBucket>()
    var day: Long? = null
    var items = emptyList<TimelineMedia>()
    fun flush() {
        val current = day ?: return
        val first = items.minByOrNull { it.timelineSortMillis }
        buckets += TimelineDayBucket(
            current,
            items.size,
            first?.let { TimelineDayCover(it.key, it.generationModified) },
        )
    }
    for (entry in timeline) {
        when (entry) {
            is TimelineEntry.DayHeader -> { flush(); day = entry.epochDay; items = emptyList() }
            is TimelineEntry.Media -> items = items + entry.value
        }
    }
    flush()
    return TimelineIndex(buckets)
}

private fun sampleTimeline(days: List<Pair<LocalDate, Int>>): List<TimelineEntry> {
    var id = 0L
    return days.flatMap { (day, count) ->
        val noon = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        listOf(TimelineEntry.DayHeader(day.toEpochDay())) + List(count) { position ->
            id += 1
            val video = id % 5 == 0L
            // Every other video is still waiting for the background pass that reads its duration.
            val pending = video && id % 10 == 0L
            TimelineEntry.Media(
                TimelineMedia(
                    key = MediaKey("external_primary", id),
                    kind = if (video) MediaKind.Video else MediaKind.Image,
                    generationModified = 1,
                    timelineSortMillis = noon - position * 60_000L,
                    width = 4000,
                    height = 3000,
                    durationMillis = if (video && !pending) 42_000 else 0,
                    detailsPending = pending,
                    isFavorite = id % 7 == 0L,
                    displayName = "IMG_$id.jpg",
                    sizeBytes = 2_400_000,
                ),
            )
        }
    }
}

/** Fake photo content: one flat tone per item, standing in for decoded pixels. */
private val SampleTones = intArrayOf(
    0xFF8DA9C4.toInt(), 0xFFC9A66B.toInt(), 0xFF7FA77A.toInt(),
    0xFFB4878F.toInt(), 0xFF9C8FC0.toInt(), 0xFF6F9A9E.toInt(),
)

private fun previewThumbnailLoader() = ThumbnailLoader(
    source = { request, _ ->
        Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888).apply {
            eraseColor(SampleTones[(request.mediaKey.mediaStoreId % SampleTones.size).toInt()])
        }
    },
    maxCacheBytes = 16L * 1024 * 1024,
    threadCount = 1,
)

/**
 * The overflow and sort menus drawn inline: the renderer does not capture popup windows, so this
 * lays the real [GalleryMenuItem] rows out in the same container the popup uses.
 */
@Preview
@Composable
fun PhotosMenusPreview() {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard {
                        GalleryMenuItem("Device folders", {}, icon = GalleryIcons.Folder)
                        GalleryMenuDivider()
                        GalleryMenuItem("Group by", {}, icon = GalleryMenuIcons.GroupBy)
                        GalleryMenuItem("Show hidden items for now", {}, icon = GalleryIcons.Lock, checked = true)
                        GalleryMenuDivider()
                        GalleryMenuItem("Settings", {}, icon = GalleryIcons.Settings)
                        GalleryMenuDivider()
                        GalleryMenuItem("Delete", {}, icon = GalleryIcons.Trash, destructive = true)
                        GalleryMenuItem("Disabled", {}, icon = GalleryIcons.Info, enabled = false)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MenuCard {
                        PhotosSort.entries.forEach { option ->
                            GalleryMenuItem(option.name, {}, icon = option.icon, checked = option == PhotosSort.Newest, radio = true)
                        }
                    }
                    MenuCard {
                        PhotosGrouping.entries.forEach { option ->
                            GalleryMenuItem(option.name, {}, icon = option.icon, checked = option == PhotosGrouping.Month, radio = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(min = GalleryMenuDefaults.MinWidth, max = GalleryMenuDefaults.MaxWidth),
    ) { Column(Modifier.padding(vertical = 8.dp), content = content) }
}
