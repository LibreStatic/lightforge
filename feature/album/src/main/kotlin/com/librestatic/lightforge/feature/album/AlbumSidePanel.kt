package com.librestatic.lightforge.feature.album

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySidePanelMetrics
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.sidePanelSwipe
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

/**
 * Album grid with a swipeable start-edge panel listing the other albums. The panel pushes the
 * grid rather than covering it, so the grid reflows to fewer columns while it is shown. It can
 * always be hidden, including on large screens.
 */
@Composable
fun AlbumWithSidePanel(
    selectedKey: AlbumKey,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    thumbnails: ThumbnailLoader,
    windowClass: GalleryWindowClass,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAlbumSelect: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val panelWidth = GallerySidePanelMetrics.width(windowClass)
    val panelWidthPx = with(LocalDensity.current) { panelWidth.toPx() }
    val progress = remember { Animatable(if (open) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    // The swipe handler outlives recompositions, so it must read the latest values.
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(open) { if (progress.targetValue != (if (open) 1f else 0f)) progress.animateTo(if (open) 1f else 0f) }
    Row(
        modifier.sidePanelSwipe(progress, panelWidthPx, scope, LocalLayoutDirection.current, enabled = swipeEnabled) { settled ->
            if (settled != currentOpen) currentOnOpenChange(settled)
        },
    ) {
        if (progress.value > 0f) {
            Box(Modifier.width(panelWidth * progress.value).fillMaxHeight().clipToBounds()) {
                AlbumSidePanel(
                    selectedKey = selectedKey,
                    virtualAlbums = virtualAlbums,
                    physicalAlbums = physicalAlbums,
                    thumbnails = thumbnails,
                    onAlbumSelect = onAlbumSelect,
                    modifier = Modifier.requiredWidth(panelWidth).fillMaxHeight().align(Alignment.CenterEnd),
                )
            }
        }
        content(Modifier.weight(1f))
    }
}

@Composable
private fun AlbumSidePanel(
    selectedKey: AlbumKey,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    thumbnails: ThumbnailLoader,
    onAlbumSelect: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val label = stringResource(R.string.album_side_panel_label)
    // Virtual albums come first, as in Collections; a divider row separates the two groups.
    val dividerRows = if (virtualAlbums.itemCount > 0 && physicalAlbums.itemCount > 0) 1 else 0
    LaunchedEffect(Unit) {
        val virtualIndex = virtualAlbums.itemSnapshotList.indexOfFirst { it?.key == selectedKey }
        val physicalIndex = physicalAlbums.itemSnapshotList.indexOfFirst { it?.key == selectedKey }
        val index = when {
            virtualIndex >= 0 -> virtualIndex
            physicalIndex >= 0 -> virtualAlbums.itemCount + dividerRows + physicalIndex
            else -> -1
        }
        if (index > 0) listState.scrollToItem(index)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.testTag("album-side-panel").semantics { paneTitle = label },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(virtualAlbums.itemCount, key = { "virtual:${virtualAlbums.itemSnapshotList.getOrNull(it)?.key ?: it}" }) { index ->
                virtualAlbums[index]?.let { SidePanelAlbum(it, it.key == selectedKey, thumbnails, onAlbumSelect) }
            }
            if (dividerRows > 0) item(key = "divider") { HorizontalDivider() }
            items(physicalAlbums.itemCount, key = { "physical:${physicalAlbums.itemSnapshotList.getOrNull(it)?.key ?: it}" }) { index ->
                physicalAlbums[index]?.let { SidePanelAlbum(it, it.key == selectedKey, thumbnails, onAlbumSelect) }
            }
        }
    }
}

@Composable
private fun SidePanelAlbum(
    album: AlbumSummary,
    selected: Boolean,
    loader: ThumbnailLoader,
    onSelect: (AlbumSummary) -> Unit,
) {
    val name = album.name ?: stringResource(R.string.album_untitled)
    val count = if (album.availability == AlbumAvailability.VolumeUnavailable) {
        stringResource(R.string.album_volume_unavailable)
    } else {
        pluralStringResource(R.plurals.album_item_count, album.itemCount.toInt(), album.itemCount)
    }
    val request = album.cover?.let { ThumbnailRequest(it, 0, 256, 256) }
    val bitmap by produceState(
        initialValue = request?.let { loader.cached(it) ?: loader.bestCached(it.mediaKey, it.generationModified) },
        request,
    ) {
        if (request != null) runCatching { loader.load(request) }.getOrNull()?.let { value = it }
    }
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .clickable { onSelect(album) }
            .padding(4.dp)
            .testTag("album-side-panel-item-${album.key}")
            .clearAndSetSemantics {
                contentDescription = "$name. $count"
                this.selected = selected
                onClick { onSelect(album); true }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize())
            } ?: Icon(GalleryIcons.Album, contentDescription = null, tint = colors.onSurfaceVariant)
            if (selected) Box(Modifier.matchParentSize().border(3.dp, colors.primary, shape))
        }
        val textColor = if (selected) colors.onSecondaryContainer else colors.onSurface
        Text(name, style = MaterialTheme.typography.labelLarge, color = textColor, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(count, style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
