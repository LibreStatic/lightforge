package com.ugallery.feature.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryColors
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
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
    onMediaLongClick: (TimelineMedia) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val widthDp = maxWidth.value.toInt()
        val columns = densityState.columns(widthDp)
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
            onMediaLongClick = onMediaLongClick,
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
    onMediaLongClick: (TimelineMedia) -> Unit = {},
) {
    require(columns > 0 && thumbnailSizePx > 0)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        modifier = (if (densityState == null) modifier else
        modifier.timelinePinchDensity(densityState, state) { index ->
            entries.peek(index)?.stableKey
        })
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
                is TimelineEntry.DayHeader -> TimelineDayHeader(entry.epochDay)
                is TimelineEntry.Media -> TimelineThumbnail(
                    entry = entry,
                    loader = thumbnailLoader,
                    sizePx = thumbnailSizePx,
                    onClick = { onMediaClick(entry.value) },
                    onLongClick = { onMediaLongClick(entry.value) },
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
private fun TimelineDayHeader(epochDay: Long) {
    val locale = LocalConfiguration.current.locales[0]
    val text = LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
    )
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val contentDescription = stringResource(
        if (entry.value.kind == com.ugallery.core.model.MediaKind.Video) {
            R.string.video_thumbnail_description
        } else {
            R.string.photo_thumbnail_description
        },
    )
    val request = ThumbnailRequest(
        mediaKey = entry.value.key,
        generationModified = entry.value.generationModified,
        widthPx = sizePx,
        heightPx = sizePx,
    )
    val bitmap by produceState(loader.cached(request), request, loader) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val loaded = bitmap
    val cellModifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .testTag("media_${entry.value.key.volumeName}_${entry.value.key.mediaStoreId}")
        .semantics { this.contentDescription = contentDescription }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    if (loaded == null) {
        Box(
            cellModifier.background(GalleryColors.Muted.copy(alpha = 0.16f)),
        )
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = cellModifier,
        )
    }
}
