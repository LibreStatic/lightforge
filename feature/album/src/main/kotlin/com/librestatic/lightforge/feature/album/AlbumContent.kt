package com.librestatic.lightforge.feature.album

import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.MediaSelectionOverlay
import com.librestatic.lightforge.core.designsystem.RetainGridThumbnailViewport
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.lazyGridDragSelection
import com.librestatic.lightforge.core.designsystem.videoDurationDescription
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.selection.SelectionReducer
import com.librestatic.lightforge.core.selection.SelectionSpec
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailPrefetchCandidate
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

@Composable
fun AlbumContent(
    album: AlbumSummary,
    items: LazyPagingItems<TimelineMedia>,
    thumbnails: ThumbnailLoader,
    filter: AlbumMediaFilter,
    sort: AlbumSort,
    selection: SelectionSpec,
    selectionQueryCount: Long?,
    onFilterChange: (AlbumMediaFilter) -> Unit,
    onSortChange: (AlbumSort) -> Unit,
    onMediaClick: (TimelineMedia) -> Unit,
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
    onRenameAlbum: (() -> Unit)? = null,
    onDeleteAlbum: (() -> Unit)? = null,
    deleteAlbumLabel: String? = null,
    onSetCover: ((TimelineMedia?) -> Unit)? = null,
    coverWorking: Boolean = false,
    coverFailed: Boolean = false,
    coverRevision: Int = 0,
) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    var sortExpanded by remember(album.key) { mutableStateOf(false) }
    var choosingCover by remember(album.key, coverRevision) { mutableStateOf(false) }
    var reviewedCover by remember(album.key, coverRevision) { mutableStateOf<TimelineMedia?>(null) }
    var confirmCover by remember(album.key, coverRevision) { mutableStateOf(false) }
    val canSetCover = album.key is AlbumKey.Virtual && onSetCover != null
    val picking = canSetCover && choosingCover
    fun cancelCover() {
        if (!coverWorking) {
            if (confirmCover) { confirmCover = false; reviewedCover = null }
            else choosingCover = false
        }
    }
    if (canSetCover && confirmCover) AlertDialog(
        onDismissRequest = ::cancelCover,
        properties = DialogProperties(dismissOnBackPress = !coverWorking, dismissOnClickOutside = !coverWorking),
        title = { Text(stringResource(R.string.album_cover_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (reviewedCover == null) stringResource(R.string.album_cover_auto_confirm)
                    else stringResource(R.string.album_cover_confirm, reviewedCover?.displayName
                        ?: stringResource(if (reviewedCover?.kind == MediaKind.Video) R.string.album_video else R.string.album_photo)))
                if (coverFailed) Text(stringResource(R.string.album_cover_failed), Modifier.testTag("album-cover-error"))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!coverWorking) onSetCover?.invoke(reviewedCover) }, enabled = !coverWorking,
                modifier = Modifier.testTag("album-cover-save")) {
                Text(stringResource(if (coverWorking) R.string.album_rename_saving else R.string.album_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = ::cancelCover, enabled = !coverWorking, modifier = Modifier.testTag("album-cover-confirm-cancel")) {
                Text(stringResource(R.string.album_rename_cancel))
            }
        },
    )
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showHeader && wide) WideAlbumHeader(album, thumbnails)
            else if (showHeader) Text(
                album.name ?: stringResource(R.string.album_untitled),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (album.key is AlbumKey.Virtual && onRenameAlbum != null) {
                TextButton(onClick = onRenameAlbum, enabled = !picking && !coverWorking, modifier = Modifier.testTag("album-rename")) {
                    Text(stringResource(R.string.album_rename))
                }
            }
            // Device folders are filesystem directories: only AlbumKey.Virtual albums may be deleted.
            if (album.key is AlbumKey.Virtual && onDeleteAlbum != null && deleteAlbumLabel != null) {
                TextButton(onClick = onDeleteAlbum, enabled = !picking && !coverWorking,
                    modifier = Modifier.testTag("album-delete")) {
                    Text(deleteAlbumLabel)
                }
            }
            if (canSetCover) {
                if (!picking) TextButton(onClick = { choosingCover = true }, enabled = !coverWorking,
                    modifier = Modifier.testTag("album-cover-choose")) {
                    Text(stringResource(R.string.album_cover_title))
                } else {
                    Text(stringResource(R.string.album_cover_hint), Modifier.testTag("album-cover-hint"))
                    TextButton(onClick = { reviewedCover = null; confirmCover = true }, enabled = !coverWorking,
                        modifier = Modifier.testTag("album-cover-automatic")) {
                        Text(stringResource(R.string.album_cover_automatic))
                    }
                    TextButton(onClick = { if (!coverWorking) { choosingCover = false; reviewedCover = null; confirmCover = false } },
                        enabled = !coverWorking, modifier = Modifier.testTag("album-cover-cancel")) {
                        Text(stringResource(R.string.album_rename_cancel))
                    }
                }
            }
            if (album.availability == AlbumAvailability.VolumeUnavailable) {
                Text(stringResource(R.string.album_volume_unavailable), color = MaterialTheme.colorScheme.error)
            }
            val filterChips: @Composable (Modifier) -> Unit = { chipsModifier ->
                LazyRow(chipsModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AlbumMediaFilter.entries) { value ->
                        FilterChip(
                            selected = filter == value,
                            enabled = !coverWorking,
                            onClick = { onFilterChange(value) },
                            label = { Text(stringResource(value.label())) },
                        )
                    }
                }
            }
            val sortButton: @Composable (Modifier) -> Unit = { sortModifier ->
                Box(sortModifier) {
                    TextButton(
                        onClick = { sortExpanded = true },
                        enabled = !coverWorking,
                        modifier = Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth()).testTag("album-sort"),
                    ) {
                        Text(stringResource(R.string.album_sort_current, stringResource(sort.label())))
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        AlbumSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.label())) },
                                onClick = {
                                    sortExpanded = false
                                    if (option != sort) onSortChange(option)
                                },
                                leadingIcon = {
                                    if (option == sort) Icon(GalleryIcons.Check, contentDescription = null)
                                },
                                modifier = Modifier.testTag("album-sort-${option.name}")
                                    .semantics { selected = option == sort },
                            )
                        }
                    }
                }
            }
            if (wide) {
                // Filters and sort share one toolbar row; the sort button no longer spans the width.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    filterChips(Modifier.weight(1f))
                    sortButton(Modifier)
                }
            } else {
                filterChips(Modifier)
                sortButton(Modifier.fillMaxWidth())
            }
            val count = runCatching { SelectionReducer.count(selection, selectionQueryCount) }.getOrDefault(0)
            if (count > 0 && !picking) Text(pluralStringResource(R.plurals.album_selected_count, count.toInt(), count))
        }
        when {
            album.availability == AlbumAvailability.VolumeUnavailable -> GalleryStateContent(
                stringResource(R.string.album_volume_unavailable),
                stringResource(R.string.album_volume_unavailable_body),
                stringResource(R.string.album_volume_unavailable),
                Modifier.fillMaxSize(),
            )
            items.itemCount == 0 -> GalleryStateContent(
                stringResource(R.string.album_empty),
                stringResource(R.string.album_empty_body),
                stringResource(R.string.album_empty),
                Modifier.fillMaxSize(),
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val gridState = rememberLazyGridState()
                val gap = 4.dp
                val columns = GalleryGridMetrics.adaptiveColumns(maxWidth)
                val thumbnailSizePx = with(LocalDensity.current) {
                    ((maxWidth - gap * (columns - 1)) / columns).roundToPx()
                }.coerceAtLeast(1)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = if (picking || coverWorking) Modifier else Modifier.lazyGridDragSelection(
                        state = gridState,
                        itemAtIndex = { index -> items.itemSnapshotList.getOrNull(index) },
                        itemKey = { it.key },
                        isSelected = { SelectionReducer.isSelected(selection, it.key) },
                        onSelectionChange = onMediaSelectionChange,
                    ),
                ) {
                    items(items.itemCount, key = { index ->
                        items.itemSnapshotList.getOrNull(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
                    }) { index ->
                        items[index]?.let { media ->
                            AlbumCell(
                                media,
                                thumbnails,
                                thumbnailSizePx,
                                selected = if (picking) reviewedCover?.key == media.key else SelectionReducer.isSelected(selection, media.key),
                                enabled = !coverWorking,
                                onClick = {
                                    if (!coverWorking) {
                                        if (picking) { reviewedCover = media; confirmCover = true }
                                        else onMediaClick(media)
                                    }
                                },
                                onLongClick = if (picking || coverWorking) null else { {
                                    onMediaSelectionChange(
                                        media,
                                        !SelectionReducer.isSelected(selection, media.key),
                                    )
                                } },
                            )
                        } ?: Box(Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                }
                RetainGridThumbnailViewport(
                    state = gridState,
                    loader = thumbnails,
                    columns = columns,
                    itemCount = items.itemCount,
                    contentKey = items.itemSnapshotList,
                    itemAtIndex = { index ->
                        val media = items.itemSnapshotList.getOrNull(index) ?: return@RetainGridThumbnailViewport null
                        ThumbnailPrefetchCandidate(
                            request = media.thumbnailRequest(thumbnailSizePx),
                            sourceWidth = media.width,
                            sourceHeight = media.height,
                            distanceFromViewportCenter = 0,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WideAlbumHeader(album: AlbumSummary, loader: ThumbnailLoader) {
    val colors = MaterialTheme.colorScheme
    val request = album.cover?.let { ThumbnailRequest(it, 0, 256, 256) }
    val bitmap by produceState(
        initialValue = request?.let { loader.cached(it) ?: loader.bestCached(it.mediaKey, it.generationModified) },
        request,
    ) {
        if (request != null) runCatching { loader.load(request) }.getOrNull()?.let { value = it }
    }
    Surface(color = colors.surfaceContainerLow, contentColor = colors.onSurface, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(GalleryIcons.Album, contentDescription = null, tint = colors.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(album.name ?: stringResource(R.string.album_untitled), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (album.availability != AlbumAvailability.VolumeUnavailable) {
                    Text(pluralStringResource(R.plurals.album_item_count, album.itemCount.toInt(), album.itemCount),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlbumCell(
    media: TimelineMedia,
    loader: ThumbnailLoader,
    sizePx: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    enabled: Boolean = true,
) {
    val request = media.thumbnailRequest(sizePx)
    val bitmap by produceState(loader.cached(request), request) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val isVideo = media.kind == MediaKind.Video
    val description = if (isVideo) {
        videoDurationDescription(media.durationMillis)
    } else {
        stringResource(R.string.album_photo)
    }.let { base ->
        com.librestatic.lightforge.core.designsystem.mediaTileDescription(base, media.isFavorite, media.displayName, isVideo)
    }
    val modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        .clearAndSetSemantics {
            contentDescription = description
            this.selected = selected
            if (enabled) this.onClick { onClick(); true }
            if (onLongClick != null && enabled) onLongClick {
                onLongClick()
                true
            }
        }
        .testTag("album-media-${media.key.volumeName}-${media.key.mediaStoreId}")
        .clickable(enabled = enabled, onClick = onClick)
    Box(modifier) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (!isVideo) {
            com.librestatic.lightforge.core.designsystem.AnimatedMediaTile(
                uri = com.librestatic.lightforge.core.designsystem.mediaStoreImageUri(media.key.volumeName, media.key.mediaStoreId),
                displayName = media.displayName,
                sizePx = sizePx,
            )
        }
        com.librestatic.lightforge.core.designsystem.MediaTileBadges(
            isFavorite = media.isFavorite,
            displayName = media.displayName,
            isVideo = isVideo,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        if (isVideo) {
            VideoDurationBadge(
                durationMillis = media.durationMillis,
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

private fun AlbumMediaFilter.label() = when (this) {
    AlbumMediaFilter.All -> R.string.album_all
    AlbumMediaFilter.Images -> R.string.album_photos
    AlbumMediaFilter.Videos -> R.string.album_videos
}

private fun AlbumSort.label() = when (this) {
    AlbumSort.NewestFirst -> R.string.album_newest
    AlbumSort.OldestFirst -> R.string.album_oldest
    AlbumSort.NameAscending -> R.string.album_sort_name_ascending
    AlbumSort.NameDescending -> R.string.album_sort_name_descending
    AlbumSort.SizeAscending -> R.string.album_sort_size_ascending
    AlbumSort.SizeDescending -> R.string.album_sort_size_descending
}
