package com.ugallery.feature.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.SelectionReducer
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.core.thumbnail.ThumbnailLoader
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
    onMediaLongClick: (TimelineMedia) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                album.name ?: stringResource(R.string.album_untitled),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (album.availability == AlbumAvailability.VolumeUnavailable) {
                Text(stringResource(R.string.album_volume_unavailable), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AlbumMediaFilter.entries.forEach { value ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { onFilterChange(value) },
                        label = { Text(stringResource(value.label())) },
                    )
                }
                FilterChip(
                    selected = sort == AlbumSort.OldestFirst,
                    onClick = {
                        onSortChange(
                            if (sort == AlbumSort.NewestFirst) AlbumSort.OldestFirst else AlbumSort.NewestFirst,
                        )
                    },
                    label = { Text(stringResource(if (sort == AlbumSort.NewestFirst) R.string.album_newest else R.string.album_oldest)) },
                )
            }
            val count = runCatching { SelectionReducer.count(selection, selectionQueryCount) }.getOrDefault(0)
            if (count > 0) Text(stringResource(R.string.album_selected_count, count))
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
                val columns = when { maxWidth < 600.dp -> 3; maxWidth < 840.dp -> 5; else -> 8 }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items.itemCount, key = { index ->
                        items.peek(index)?.key?.let { "${it.volumeName}:${it.mediaStoreId}" } ?: "pending:$index"
                    }) { index ->
                        items[index]?.let { media ->
                            AlbumCell(
                                media,
                                thumbnails,
                                selected = SelectionReducer.isSelected(selection, media.key),
                                onClick = { onMediaClick(media) },
                                onLongClick = { onMediaLongClick(media) },
                            )
                        } ?: Box(Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCell(
    media: TimelineMedia,
    loader: ThumbnailLoader,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val request = ThumbnailRequest(media.key, media.generationModified, 256, 256)
    val bitmap by produceState(loader.cached(request), request) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val description = stringResource(
        if (media.kind == MediaKind.Video) R.string.album_video else R.string.album_photo,
    )
    val modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .semantics { contentDescription = if (selected) "$description. selected" else description }
    bitmap?.let {
        Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } ?: Box(modifier)
}

private fun AlbumMediaFilter.label() = when (this) {
    AlbumMediaFilter.All -> R.string.album_all
    AlbumMediaFilter.Images -> R.string.album_photos
    AlbumMediaFilter.Videos -> R.string.album_videos
}
