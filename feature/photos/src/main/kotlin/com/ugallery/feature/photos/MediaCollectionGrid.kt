package com.ugallery.feature.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.MediaSelectionOverlay
import com.ugallery.core.designsystem.RetainGridThumbnailViewport
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.lazyGridDragSelection
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import com.ugallery.core.thumbnail.ThumbnailRequest

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCollectionGrid(
    items: LazyPagingItems<TimelineMedia>,
    thumbnailLoader: ThumbnailLoader,
    selectionMode: Boolean,
    isSelected: (TimelineMedia) -> Boolean,
    onOpen: (TimelineMedia) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectionChange: (TimelineMedia, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val state = rememberLazyGridState()
        val columns = if (maxWidth < 600.dp) 3 else ((maxWidth + GalleryGridMetrics.Gap) /
            (116.dp + GalleryGridMetrics.Gap)).toInt().coerceAtLeast(3)
        val sizePx = with(LocalDensity.current) {
            ((maxWidth - GalleryGridMetrics.Gap * (columns - 1)) / columns).roundToPx()
        }.coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
            verticalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.fillMaxSize().lazyGridDragSelection(
                state = state,
                itemAtIndex = items::peek,
                itemKey = { it.key },
                isSelected = isSelected,
                enabled = selectionMode,
                onSelectionChange = { media, selected ->
                    if (!selectionMode) onSelectionModeChange(true)
                    onSelectionChange(media, selected)
                },
            ),
        ) {
            items(items.itemCount, key = { index ->
                items.peek(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
            }) { index ->
                items[index]?.let { media ->
                    val request = media.request(sizePx)
                    val bitmap by produceState(thumbnailLoader.cached(request), request, thumbnailLoader) {
                        if (value == null) value = runCatching { thumbnailLoader.load(request) }.getOrNull()
                    }
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).semantics {
                            selected = isSelected(media)
                            onLongClick {
                                if (!selectionMode) onSelectionModeChange(true)
                                onSelectionChange(media, !isSelected(media))
                                true
                            }
                        }.combinedClickable(
                            onClick = {
                                if (selectionMode) onSelectionChange(media, !isSelected(media)) else onOpen(media)
                            },
                            onLongClick = {
                                if (!selectionMode) onSelectionModeChange(true)
                                onSelectionChange(media, !isSelected(media))
                            },
                        ),
                    ) {
                        bitmap?.let {
                            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } ?: Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                        if (media.kind == MediaKind.Video) {
                            VideoDurationBadge(media.durationMillis, Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                        MediaSelectionOverlay(isSelected(media))
                    }
                } ?: Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        RetainGridThumbnailViewport(
            state = state,
            loader = thumbnailLoader,
            columns = columns,
            itemCount = items.itemCount,
            contentKey = items.itemSnapshotList,
            itemAtIndex = { index -> items.peek(index)?.let { media ->
                ThumbnailPrefetchCandidate(media.request(sizePx), media.width, media.height, 0)
            } },
        )
    }
}

private fun TimelineMedia.request(sizePx: Int) = ThumbnailRequest(key, generationModified, sizePx, sizePx)
