package com.ugallery.feature.trash

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryGridMetrics
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.MediaSelectionOverlay
import com.ugallery.core.designsystem.RetainGridThumbnailViewport
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.lazyGridDragSelection
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import com.ugallery.core.thumbnail.ThumbnailRequest
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashContent(
    items: LazyPagingItems<TimelineMedia>,
    totalCount: Long,
    thumbnailLoader: ThumbnailLoader?,
    selectionMode: Boolean,
    isSelected: (TimelineMedia) -> Boolean,
    onOpen: (TimelineMedia) -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectionChange: (TimelineMedia, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        totalCount == 0L && items.loadState.refresh !is LoadState.Loading -> GalleryStateContent(
            title = stringResource(R.string.trash_empty_title),
            body = stringResource(R.string.trash_empty_state),
            illustrationDescription = stringResource(R.string.trash_empty_description),
            modifier = modifier.fillMaxSize(),
        )
        thumbnailLoader == null || items.loadState.refresh is LoadState.Loading && items.itemCount == 0 -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GalleryLoadingIndicator() }
        }
        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val gridState = rememberLazyGridState()
            val columns = if (maxWidth < 600.dp) 3 else ((maxWidth + GalleryGridMetrics.Gap) /
                (116.dp + GalleryGridMetrics.Gap)).toInt().coerceAtLeast(3)
            val thumbnailSizePx = with(LocalDensity.current) {
                ((maxWidth - GalleryGridMetrics.Gap * (columns - 1)) / columns).roundToPx()
            }.coerceAtLeast(1)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
                verticalArrangement = Arrangement.spacedBy(GalleryGridMetrics.Gap),
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxSize().testTag("trash_grid").lazyGridDragSelection(
                    state = gridState,
                    itemAtIndex = { index -> if (index <= 0) null else items.peek(index - 1) },
                    itemKey = { it.key },
                    isSelected = isSelected,
                    onSelectionChange = { media, selected ->
                        if (!selectionMode) onSelectionModeChange(true)
                        onSelectionChange(media, selected)
                    },
                    enabled = selectionMode,
                ),
            ) {
                item(key = "trash-info", span = { GridItemSpan(maxLineSpan) }) {
                    TrashInfoBanner(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
                items(
                    count = items.itemCount,
                    key = { index -> items.peek(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index" },
                    contentType = { "trash-media" },
                ) { index ->
                    val media = items[index]
                    if (media == null) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant))
                    } else TrashCell(
                        media = media,
                        loader = thumbnailLoader,
                        sizePx = thumbnailSizePx,
                        selected = isSelected(media),
                        onClick = {
                            if (selectionMode) onSelectionChange(media, !isSelected(media)) else onOpen(media)
                        },
                        onLongClick = {
                            if (!selectionMode) onSelectionModeChange(true)
                            onSelectionChange(media, !isSelected(media))
                        },
                    )
                }
                if (items.loadState.append is LoadState.Loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        GalleryLoadingIndicator()
                    }
                }
            }
            RetainGridThumbnailViewport(
                state = gridState,
                loader = thumbnailLoader,
                columns = columns,
                itemCount = items.itemCount + 1,
                contentKey = items.itemSnapshotList,
                itemAtIndex = { index ->
                    if (index <= 0) return@RetainGridThumbnailViewport null
                    val media = items.peek(index - 1) ?: return@RetainGridThumbnailViewport null
                    ThumbnailPrefetchCandidate(media.thumbnailRequest(thumbnailSizePx), media.width, media.height, 0)
                },
            )
        }
    }
}

@Composable
private fun TrashInfoBanner(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(GalleryIcons.Trash, contentDescription = null)
        Text(stringResource(R.string.trash_retention_info), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashCell(
    media: TimelineMedia,
    loader: ThumbnailLoader,
    sizePx: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val request = media.thumbnailRequest(sizePx)
    val bitmap by produceState(loader.cached(request), request, loader) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val context = LocalContext.current
    val kind = stringResource(if (media.kind == MediaKind.Video) R.string.trash_video_description else R.string.trash_photo_description)
    val expiry = media.dateExpiresMillis?.let {
        stringResource(R.string.trash_expires_on, DateFormat.getMediumDateFormat(context).format(Date(it)))
    } ?: stringResource(R.string.trash_expiry_unknown)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).testTag("trash_media_${media.key.volumeName}_${media.key.mediaStoreId}")
            .semantics {
                contentDescription = "$kind. $expiry"
                this.selected = selected
                onLongClick { onLongClick(); true }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val loaded = bitmap
        if (loaded == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        else Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (media.kind == MediaKind.Video) {
            VideoDurationBadge(media.durationMillis, Modifier.align(Alignment.TopEnd).padding(6.dp))
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
