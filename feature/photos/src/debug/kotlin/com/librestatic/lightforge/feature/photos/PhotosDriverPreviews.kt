package com.librestatic.lightforge.feature.photos

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineDayBucket
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import java.time.LocalDate
import java.time.ZoneOffset
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
fun PhotosFilterEmptyPreview() = PhotosFrame(initialFilter = PhotosFilter.Raw, days = emptyList())

@Composable
private fun PhotosFrame(
    initialSelection: Set<Long> = emptySet(),
    initialFilter: PhotosFilter = PhotosFilter.All,
    days: List<Pair<LocalDate, Int>> = SampleDays,
) {
    var filter by remember { mutableStateOf(initialFilter) }
    var sort by remember { mutableStateOf(PhotosSort.Newest) }
    var selected by remember { mutableStateOf(initialSelection) }
    val loader = remember { previewThumbnailLoader() }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val timeline = remember(days) { sampleTimeline(days) }
    val entries = remember(timeline) { flowOf(PagingData.from(timeline)) }.collectAsLazyPagingItems()
    val index = remember(days) {
        TimelineIndex(days.map { (day, count) -> TimelineDayBucket(day.toEpochDay(), count) })
    }
    val media = timeline.filterIsInstance<TimelineEntry.Media>().map { it.value }
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            LibraryPhotosRoute(
                access = LibraryAccess(GrantLevel.Full, GrantLevel.Full, unredactedLocation = true),
                engineState = LibraryUiState.Ready,
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
                filter = filter,
                onFilterChange = { filter = it },
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

private fun sampleTimeline(days: List<Pair<LocalDate, Int>>): List<TimelineEntry> {
    var id = 0L
    return days.flatMap { (day, count) ->
        val noon = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        listOf(TimelineEntry.DayHeader(day.toEpochDay())) + List(count) { position ->
            id += 1
            val video = id % 5 == 0L
            TimelineEntry.Media(
                TimelineMedia(
                    key = MediaKey("external_primary", id),
                    kind = if (video) MediaKind.Video else MediaKind.Image,
                    generationModified = 1,
                    timelineSortMillis = noon - position * 60_000L,
                    width = 4000,
                    height = 3000,
                    durationMillis = if (video) 42_000 else 0,
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
