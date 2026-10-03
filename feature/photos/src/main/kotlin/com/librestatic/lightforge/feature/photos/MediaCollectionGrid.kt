package com.librestatic.lightforge.feature.photos

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import com.librestatic.lightforge.core.designsystem.MediaSelectionOverlay
import com.librestatic.lightforge.core.designsystem.RetainGridThumbnailViewport
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.GalleryShapes
import androidx.compose.ui.draw.clip
import com.librestatic.lightforge.core.designsystem.lazyGridDragSelection
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchCandidate
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

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
        val layout = com.librestatic.lightforge.core.designsystem.mediaGridLayout(maxWidth)
        val columns = layout.columns
        val sizePx = layout.cellSizePx(LocalDensity.current.density)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
            verticalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
            contentPadding = layout.contentPadding(
                bottom = com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding(96.dp),
            ),
            modifier = Modifier.fillMaxSize().lazyGridDragSelection(
                state = state,
                itemAtIndex = { index -> items.itemSnapshotList.getOrNull(index) },
                itemKey = { it.key },
                isSelected = isSelected,
                onSelectionChange = { media, selected ->
                    if (!selectionMode) onSelectionModeChange(true)
                    onSelectionChange(media, selected)
                },
            ),
        ) {
            items(items.itemCount, key = { index ->
                items.itemSnapshotList.getOrNull(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
            }) { index ->
                items[index]?.let { media ->
                    val request = media.request(sizePx)
                    val bitmap by produceState(thumbnailLoader.cached(request), request, thumbnailLoader) {
                        if (value == null) value = runCatching { thumbnailLoader.load(request) }.getOrNull()
                    }
                    val isVideo = media.kind == MediaKind.Video
                    val description = com.librestatic.lightforge.core.designsystem.mediaTileDescription(
                        base = if (isVideo) {
                            com.librestatic.lightforge.core.designsystem.videoDurationDescription(media.durationMillis)
                        } else {
                            stringResource(R.string.photo_thumbnail_description)
                        },
                        isFavorite = media.isFavorite,
                        displayName = media.displayName,
                        isVideo = isVideo,
                    )
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(GalleryShapes.Thumbnail).clearAndSetSemantics {
                            contentDescription = description
                            selected = isSelected(media)
                            onClick {
                                if (selectionMode) onSelectionChange(media, !isSelected(media)) else onOpen(media)
                                true
                            }
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
                        if (media.kind == MediaKind.Image) {
                            com.librestatic.lightforge.core.designsystem.AnimatedMediaTile(
                                uri = com.librestatic.lightforge.core.designsystem.mediaStoreImageUri(media.key.volumeName, media.key.mediaStoreId),
                                displayName = media.displayName,
                                sizePx = sizePx,
                            )
                        }
                        com.librestatic.lightforge.core.designsystem.MediaTileBadges(
                            isFavorite = media.isFavorite,
                            displayName = media.displayName,
                            isVideo = media.kind == MediaKind.Video,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        )
                        if (media.kind == MediaKind.Video) {
                            VideoDurationBadge(media.durationMillis, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                        }
                        MediaSelectionOverlay(isSelected(media), shape = GalleryShapes.Thumbnail)
                        com.librestatic.lightforge.core.designsystem.MediaSelectionAffordance(
                            visible = selectionMode && !isSelected(media),
                        )
                    }
                } ?: Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(GalleryShapes.Thumbnail)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        RetainGridThumbnailViewport(
            state = state,
            loader = thumbnailLoader,
            columns = columns,
            itemCount = items.itemCount,
            contentKey = items.itemSnapshotList,
            itemAtIndex = { index -> items.itemSnapshotList.getOrNull(index)?.let { media ->
                ThumbnailPrefetchCandidate(media.request(sizePx), media.width, media.height, 0)
            } },
        )
    }
}

private fun TimelineMedia.request(sizePx: Int) = ThumbnailRequest(key, generationModified, sizePx, sizePx)
