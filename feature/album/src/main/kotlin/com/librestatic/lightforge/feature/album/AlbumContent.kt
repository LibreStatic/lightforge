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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
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
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.MediaSelectionOverlay
import com.librestatic.lightforge.core.designsystem.RetainGridThumbnailViewport
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.galleryGridItemAnimation
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
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The pane's own width, not the window's: beside the albums panel the grid is narrower.
        val wide = maxWidth >= 600.dp
        val paneHeight = maxHeight
        val count = runCatching { SelectionReducer.count(selection, selectionQueryCount) }.getOrDefault(0)
        val header: @Composable () -> Unit = {
            AlbumHeader(
                album = album,
                thumbnails = thumbnails,
                showTitle = showHeader,
                wide = wide,
                filter = filter,
                sort = sort,
                picking = picking,
                coverWorking = coverWorking,
                selectedCount = if (picking) 0L else count,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                onRenameAlbum = onRenameAlbum.takeIf { album.key is AlbumKey.Virtual },
                onDeleteAlbum = onDeleteAlbum.takeIf { album.key is AlbumKey.Virtual && deleteAlbumLabel != null },
                deleteAlbumLabel = deleteAlbumLabel,
                onChooseCover = if (canSetCover) { { choosingCover = true } } else null,
                onAutomaticCover = { reviewedCover = null; confirmCover = true },
                onCancelCover = { if (!coverWorking) { choosingCover = false; reviewedCover = null; confirmCover = false } },
            )
        }
        val state = when {
            album.availability == AlbumAvailability.VolumeUnavailable -> Triple(
                R.string.album_volume_unavailable, R.string.album_volume_unavailable_body, R.string.album_volume_unavailable,
            )
            items.itemCount == 0 -> Triple(R.string.album_empty, R.string.album_empty_body, R.string.album_empty)
            else -> null
        }
        if (state != null) {
            // The toolbar scrolls with the message, so nothing is cut off in a short landscape window.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                header()
                GalleryStateContent(
                    stringResource(state.first),
                    stringResource(state.second),
                    stringResource(state.third),
                    Modifier.fillMaxWidth().heightIn(min = (paneHeight - 160.dp).coerceAtLeast(0.dp)),
                    heroIcon = if (album.availability == AlbumAvailability.VolumeUnavailable) GalleryIcons.Warning else GalleryIcons.PhotoLibrary,
                )
            }
        } else {
            val gridState = rememberLazyGridState()
            val gap = GalleryGridMetrics.Gap
            val columns = GalleryGridMetrics.adaptiveColumns(maxWidth)
            val thumbnailSizePx = with(LocalDensity.current) {
                ((maxWidth - gap * (columns - 1)) / columns).roundToPx()
            }.coerceAtLeast(1)
            // The header is the grid's first, full-width item: it scrolls away with the photos
            // instead of pinning them below a tall block (landscape phones showed a single row).
            val headerItems = 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize().testTag("album-grid").then(
                    if (picking || coverWorking) Modifier else Modifier.lazyGridDragSelection(
                        state = gridState,
                        itemAtIndex = { index -> items.itemSnapshotList.getOrNull(index - headerItems) },
                        itemKey = { it.key },
                        isSelected = { SelectionReducer.isSelected(selection, it.key) },
                        onSelectionChange = onMediaSelectionChange,
                    ),
                ),
            ) {
                item(key = "album-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
                items(items.itemCount, key = { index ->
                    items.itemSnapshotList.getOrNull(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
                }, contentType = { "media" }) { index ->
                    Box(galleryGridItemAnimation()) {
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
            }
            RetainGridThumbnailViewport(
                state = gridState,
                loader = thumbnails,
                columns = columns,
                itemCount = items.itemCount + headerItems,
                contentKey = items.itemSnapshotList,
                itemAtIndex = { index ->
                    val media = items.itemSnapshotList.getOrNull(index - headerItems) ?: return@RetainGridThumbnailViewport null
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

/**
 * Everything above the photos: one toolbar with the media filter on the start side and sort plus
 * album actions on the end side. Rename and Delete live in the overflow menu so the destructive
 * action is never one stray tap away; on wide panes Choose cover is a tonal button.
 */
@Composable
private fun AlbumHeader(
    album: AlbumSummary,
    thumbnails: ThumbnailLoader,
    showTitle: Boolean,
    wide: Boolean,
    filter: AlbumMediaFilter,
    sort: AlbumSort,
    picking: Boolean,
    coverWorking: Boolean,
    selectedCount: Long,
    onFilterChange: (AlbumMediaFilter) -> Unit,
    onSortChange: (AlbumSort) -> Unit,
    onRenameAlbum: (() -> Unit)?,
    onDeleteAlbum: (() -> Unit)?,
    deleteAlbumLabel: String?,
    onChooseCover: (() -> Unit)?,
    onAutomaticCover: () -> Unit,
    onCancelCover: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        if (showTitle && wide) WideAlbumHeader(album, thumbnails)
        else if (showTitle) Text(
            album.name ?: stringResource(R.string.album_untitled),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (album.availability == AlbumAvailability.VolumeUnavailable) {
            Text(stringResource(R.string.album_volume_unavailable), color = MaterialTheme.colorScheme.error)
        }
        if (picking) {
            Text(stringResource(R.string.album_cover_hint), Modifier.testTag("album-cover-hint"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm, Alignment.End)) {
                TextButton(onClick = onCancelCover, enabled = !coverWorking, modifier = Modifier.testTag("album-cover-cancel")) {
                    Text(stringResource(R.string.album_rename_cancel))
                }
                FilledTonalButton(onClick = onAutomaticCover, enabled = !coverWorking, modifier = Modifier.testTag("album-cover-automatic")) {
                    Text(stringResource(R.string.album_cover_automatic))
                }
            }
        } else {
            AlbumToolbar(
                wide = wide,
                filter = filter,
                sort = sort,
                enabled = !coverWorking,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
                onRenameAlbum = onRenameAlbum,
                onDeleteAlbum = onDeleteAlbum,
                deleteAlbumLabel = deleteAlbumLabel,
                onChooseCover = onChooseCover,
            )
        }
        if (selectedCount > 0) Text(
            pluralStringResource(R.plurals.album_selected_count, selectedCount.toInt(), selectedCount),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun AlbumToolbar(
    wide: Boolean,
    filter: AlbumMediaFilter,
    sort: AlbumSort,
    enabled: Boolean,
    onFilterChange: (AlbumMediaFilter) -> Unit,
    onSortChange: (AlbumSort) -> Unit,
    onRenameAlbum: (() -> Unit)?,
    onDeleteAlbum: (() -> Unit)?,
    deleteAlbumLabel: String?,
    onChooseCover: (() -> Unit)?,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    val coverInMenu = onChooseCover != null && !wide
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
            items(AlbumMediaFilter.entries) { value ->
                FilterChip(
                    selected = filter == value,
                    enabled = enabled,
                    onClick = { onFilterChange(value) },
                    label = { Text(stringResource(value.label())) },
                )
            }
        }
        if (wide && onChooseCover != null) {
            FilledTonalButton(
                onClick = onChooseCover,
                enabled = enabled,
                modifier = Modifier.padding(start = GallerySpacing.Sm).testTag("album-cover-choose"),
            ) {
                Text(stringResource(R.string.album_cover_action), maxLines = 1)
            }
        }
        val sortDescription = stringResource(R.string.album_sort_current, stringResource(sort.label()))
        Box {
            if (wide) {
                TextButton(onClick = { sortExpanded = true }, enabled = enabled, modifier = Modifier.testTag("album-sort")) {
                    Icon(GalleryIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(sort.label()), maxLines = 1, modifier = Modifier.padding(start = GallerySpacing.Sm)
                        .semantics { contentDescription = sortDescription })
                }
            } else {
                IconButton(onClick = { sortExpanded = true }, enabled = enabled, modifier = Modifier.testTag("album-sort")) {
                    Icon(GalleryIcons.Sort, contentDescription = sortDescription)
                }
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
        if (coverInMenu || onRenameAlbum != null || onDeleteAlbum != null) Box {
            IconButton(onClick = { moreExpanded = true }, enabled = enabled, modifier = Modifier.testTag("album-more")) {
                Icon(GalleryIcons.More, contentDescription = stringResource(R.string.album_more_options))
            }
            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                if (coverInMenu) DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_cover_action)) },
                    onClick = { moreExpanded = false; onChooseCover?.invoke() },
                    modifier = Modifier.testTag("album-cover-choose"),
                )
                onRenameAlbum?.let { rename ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.album_rename)) },
                        onClick = { moreExpanded = false; rename() },
                        modifier = Modifier.testTag("album-rename"),
                    )
                }
                // Device folders are filesystem directories: only AlbumKey.Virtual albums may be deleted.
                if (onDeleteAlbum != null && deleteAlbumLabel != null) DropdownMenuItem(
                    text = { Text(deleteAlbumLabel) },
                    onClick = { moreExpanded = false; onDeleteAlbum() },
                    modifier = Modifier.testTag("album-delete"),
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
