package com.ugallery.feature.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryColors
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun PagedPhotosTimeline(
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader,
    columns: Int,
    thumbnailSizePx: Int,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
) {
    require(columns > 0 && thumbnailSizePx > 0)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GalleryGridMetrics.Gap),
        modifier = modifier,
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
}

@Composable
private fun TimelineDayHeader(epochDay: Long) {
    val text = LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()),
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
) {
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
    if (loaded == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(GalleryColors.Muted.copy(alpha = 0.16f)),
        )
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
    }
}
