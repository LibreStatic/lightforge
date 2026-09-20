package com.ugallery.feature.album

import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.MediaSelectionOverlay
import com.ugallery.core.designsystem.RetainGridThumbnailViewport
import com.ugallery.core.designsystem.VideoDurationBadge
import com.ugallery.core.designsystem.lazyGridDragSelection
import com.ugallery.core.designsystem.videoDurationDescription
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.SelectionReducer
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailPrefetchCandidate
import com.ugallery.core.thumbnail.ThumbnailRequest

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
            if (showHeader) Text(
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AlbumMediaFilter.entries) { value ->
                    FilterChip(
                        selected = filter == value,
                        enabled = !coverWorking,
                        onClick = { onFilterChange(value) },
                        label = { Text(stringResource(value.label())) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { sortExpanded = true },
                    enabled = !coverWorking,
                    modifier = Modifier.fillMaxWidth().testTag("album-sort"),
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
            val count = runCatching { SelectionReducer.count(selection, selectionQueryCount) }.getOrDefault(0)
            if (count > 0 && !picking) Text(stringResource(R.string.album_selected_count, count))
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
                val columns = ((maxWidth + gap) / (104.dp + gap)).toInt().coerceAtLeast(1)
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
                        itemAtIndex = items::peek,
                        itemKey = { it.key },
                        isSelected = { SelectionReducer.isSelected(selection, it.key) },
                        onSelectionChange = onMediaSelectionChange,
                    ),
                ) {
                    items(items.itemCount, key = { index ->
                        items.peek(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
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
                        val media = items.peek(index) ?: return@RetainGridThumbnailViewport null
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
    }
    val modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        .semantics {
            contentDescription = description
            this.selected = selected
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
