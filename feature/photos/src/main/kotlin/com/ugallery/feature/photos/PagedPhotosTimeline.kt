package com.ugallery.feature.photos

import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.MediaSelectionOverlay
import com.ugallery.core.designsystem.RetainGridThumbnailViewport
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.lazyGridDragSelection
import com.ugallery.core.designsystem.videoDurationDescription
import com.ugallery.core.designsystem.rememberGalleryReducedMotion
import com.ugallery.core.model.TimelineGrouping
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.model.MediaKind
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import com.ugallery.core.thumbnail.ThumbnailRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AdaptivePagedPhotosTimeline(
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    densityState: TimelineDensityState = rememberTimelineDensityState(),
    onMediaClick: (TimelineMedia) -> Unit = {},
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit = { _, _ -> },
    preferredColumns: Int? = null,
    cropThumbnails: Boolean = true,
    onDensityChange: ((Int) -> Unit)? = null,
    isMediaSelected: (TimelineMedia) -> Boolean = { false },
) {
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toInt()
        // Seed once from the persisted preference BEFORE deriving columns so the
        // first frame already shows the stored density; afterwards the density
        // state owns the value and gestures/buttons change it freely.
        densityState.seedFromPreferredColumns(preferredColumns, widthDp)
        val columns = densityState.columns(widthDp)
        if (onDensityChange != null) {
            LaunchedEffect(columns) {
                if (columns in 2..13) onDensityChange(columns)
            }
        }
        val leadingIndex = state.firstVisibleItemIndex
        densityState.prepareColumnChange(
            columns = columns,
            index = leadingIndex,
            stableKey = if (leadingIndex < entries.itemCount) {
                entries.peek(leadingIndex)?.stableKey
            } else {
                null
            },
        )
        val sizePx = with(LocalDensity.current) {
            ((maxWidth - GalleryGridMetrics.Gap * (columns - 1)) / columns).roundToPx()
        }.coerceAtLeast(1)
        PagedPhotosTimeline(
            entries = entries,
            thumbnailLoader = thumbnailLoader,
            columns = columns,
            thumbnailSizePx = sizePx,
            modifier = Modifier.fillMaxWidth(),
            state = state,
            densityState = densityState,
            onMediaClick = onMediaClick,
            onMediaSelectionChange = onMediaSelectionChange,
            cropThumbnails = cropThumbnails,
            isMediaSelected = isMediaSelected,
        )
    }
}

@Composable
fun PagedPhotosTimeline(
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader,
    columns: Int,
    thumbnailSizePx: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    densityState: TimelineDensityState? = null,
    onMediaClick: (TimelineMedia) -> Unit = {},
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit = { _, _ -> },
    cropThumbnails: Boolean = true,
    isMediaSelected: (TimelineMedia) -> Boolean = { false },
) {
    require(columns > 0 && thumbnailSizePx > 0)
    val reducedMotion = rememberGalleryReducedMotion()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        modifier = (if (densityState == null) modifier else
        modifier.timelinePinchDensity(densityState, state) { index ->
            entries.peek(index)?.stableKey
        })
            .lazyGridDragSelection(
                state = state,
                itemAtIndex = { index -> (entries.peek(index) as? TimelineEntry.Media)?.value },
                itemKey = { it.key },
                isSelected = isMediaSelected,
                onSelectionChange = onMediaSelectionChange,
            )
            .testTag("timeline_grid")
            .semantics { testTagsAsResourceId = true },
    ) {
        items(
            count = entries.itemCount,
            key = { index -> entries.peek(index)?.stableKey ?: "unloaded:$index" },
            span = { index ->
                if (entries.peek(index) is TimelineEntry.DayHeader) GridItemSpan(maxLineSpan)
                else GridItemSpan(1)
            },
            contentType = { index -> entries.peek(index)?.javaClass?.simpleName ?: "unloaded" },
        ) { index ->
            when (val entry = entries[index]) {
                is TimelineEntry.DayHeader -> TimelineDayHeader(entry.epochDay, entry.granularity)
                is TimelineEntry.Media -> TimelineThumbnail(
                    entry = entry,
                    loader = thumbnailLoader,
                    sizePx = thumbnailSizePx,
                    modifier = Modifier.animateItem(
                        fadeInSpec = snap(),
                        placementSpec = if (reducedMotion) {
                            snap()
                        } else {
                            MaterialTheme.motionScheme.defaultSpatialSpec()
                        },
                        fadeOutSpec = snap(),
                    ),
                    onClick = { onMediaClick(entry.value) },
                    onLongClick = {
                        onMediaSelectionChange(entry.value, !isMediaSelected(entry.value))
                    },
                    cropToFill = cropThumbnails,
                    selected = isMediaSelected(entry.value),
                )
                null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
    RetainGridThumbnailViewport(
        state = state,
        loader = thumbnailLoader,
        columns = columns,
        itemCount = entries.itemCount,
        contentKey = entries.itemSnapshotList,
        itemAtIndex = { index ->
            val media = (entries.peek(index) as? TimelineEntry.Media)?.value ?: return@RetainGridThumbnailViewport null
            ThumbnailPrefetchCandidate(
                request = media.thumbnailRequest(thumbnailSizePx),
                sourceWidth = media.width,
                sourceHeight = media.height,
                distanceFromViewportCenter = 0,
            )
        },
    )
    if (densityState != null) {
        PreserveTimelineAnchor(
            densityState = densityState,
            gridState = state,
            columns = columns,
            itemCount = entries.itemCount,
            resolveStableKey = { stableKey ->
                val snapshot = entries.itemSnapshotList
                val loadedIndex = snapshot.items.indexOfFirst { it.stableKey == stableKey }
                if (loadedIndex < 0) null else snapshot.placeholdersBefore + loadedIndex
            },
        )
    }
}

@Composable
private fun TimelineDayHeader(epochDay: Long, granularity: TimelineGrouping) {
    val locale = LocalConfiguration.current.locales[0]
    val date = LocalDate.ofEpochDay(epochDay)
    val text = when (granularity) {
        TimelineGrouping.Day -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        TimelineGrouping.Month -> date.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        TimelineGrouping.Year -> date.year.toString()
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(
            start = GallerySpacing.Lg,
            end = GallerySpacing.Lg,
            top = GallerySpacing.Lg,
            bottom = GallerySpacing.Sm,
        ),
    )
}

@Composable
private fun TimelineThumbnail(
    entry: TimelineEntry.Media,
    loader: ThumbnailLoader,
    sizePx: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    cropToFill: Boolean = true,
    selected: Boolean = false,
) {
    val isVideo = entry.value.kind == MediaKind.Video
    val contentDescription = if (isVideo) {
        videoDurationDescription(entry.value.durationMillis)
    } else {
        stringResource(R.string.photo_thumbnail_description)
    }
    val request = entry.value.thumbnailRequest(sizePx)
    val bitmap by produceState(loader.cached(request), request, loader) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val loaded = bitmap
    val cellModifier = modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .testTag("media_${entry.value.key.volumeName}_${entry.value.key.mediaStoreId}")
        .semantics {
            this.contentDescription = contentDescription
            this.selected = selected
        }
        .semantics {
            onLongClick {
                onLongClick()
                true
            }
        }
        .clickable(onClick = onClick)
    Box(cellModifier) {
        if (loaded == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
        } else {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = if (cropToFill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            VideoDurationBadge(
                durationMillis = entry.value.durationMillis,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
        MediaSelectionOverlay(selected)
    }
}

private fun TimelineMedia.thumbnailRequest(sizePx: Int) = ThumbnailRequest(
    mediaKey = key,
    generationModified = generationModified,
    widthPx = sizePx,
    heightPx = sizePx,
)
