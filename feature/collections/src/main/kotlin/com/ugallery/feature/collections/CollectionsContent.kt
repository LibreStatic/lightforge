package com.ugallery.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumSummary

@Composable
fun CollectionsContent(
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    trashCount: Long,
    onAlbumClick: (AlbumSummary) -> Unit,
    onCreateAlbum: () -> Unit,
    onTrashClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val albums = buildList {
        repeat(virtualAlbums.itemCount) { virtualAlbums[it]?.let(::add) }
        repeat(physicalAlbums.itemCount) { physicalAlbums[it]?.let(::add) }
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.collections_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when { maxWidth < 600.dp -> 2; maxWidth < 840.dp -> 3; else -> 5 }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CollectionCard(
                        title = stringResource(R.string.collections_create_album),
                        body = stringResource(R.string.collections_virtual_album_body),
                        onClick = onCreateAlbum,
                    )
                }
                item {
                    CollectionCard(
                        title = stringResource(R.string.collections_trash),
                        body = stringResource(R.string.collections_item_count, trashCount),
                        onClick = onTrashClick,
                    )
                }
                items(albums, key = { it.key.toString() }) { album ->
                    CollectionCard(
                        title = album.name ?: stringResource(R.string.collections_untitled),
                        body = if (album.availability == AlbumAvailability.VolumeUnavailable) {
                            stringResource(R.string.collections_volume_unavailable)
                        } else {
                            stringResource(R.string.collections_item_count, album.itemCount)
                        },
                        onClick = { onAlbumClick(album) },
                    )
                }
                if (albums.isEmpty() && physicalAlbums.loadState.refresh !is LoadState.Loading &&
                    virtualAlbums.loadState.refresh !is LoadState.Loading
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        GalleryStateContent(
                            stringResource(R.string.collections_empty),
                            stringResource(R.string.collections_empty_body),
                            stringResource(R.string.collections_empty),
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(title: String, body: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics {
            contentDescription = "$title. $body"
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
