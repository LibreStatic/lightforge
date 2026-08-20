package com.ugallery.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentSummaryRow
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest

@Composable
fun CollectionsContent(
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    trashCount: Long,
    momentSummaries: List<MomentSummaryRow>,
    onMomentClick: (MomentEntity) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onCreateAlbum: () -> Unit,
    onTrashClick: () -> Unit,
    onLocalAnalysisClick: () -> Unit,
    peopleEnabled: Boolean,
    peopleCount: Long,
    onPeopleClick: () -> Unit,
    petCollectionsEnabled: Boolean,
    dogCount: Long,
    catCount: Long,
    onPetCollectionClick: (String) -> Unit,
    thumbnailLoader: ThumbnailLoader? = null,
    privateAlbumLabel: String? = null,
    onPrivateAlbumClick: (() -> Unit)? = null,
    collageLabel: String? = null,
    onCollageClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val albums = buildList {
        repeat(virtualAlbums.itemCount) { virtualAlbums[it]?.let(::add) }
        repeat(physicalAlbums.itemCount) { physicalAlbums[it]?.let(::add) }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxSize().widthIn(max = 1_200.dp).padding(horizontal = GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.collections_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = GallerySpacing.Xl, bottom = GallerySpacing.Sm).semantics { heading() },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when { maxWidth < 600.dp -> 1; maxWidth < 840.dp -> 2; else -> 4 }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CollectionCard(
                        title = stringResource(R.string.collections_local_analysis),
                        body = stringResource(R.string.collections_local_analysis_body),
                        icon = GalleryIcons.Analyze,
                        onClick = onLocalAnalysisClick,
                    )
                }
                if (privateAlbumLabel != null && onPrivateAlbumClick != null) item {
                    CollectionCard(
                        title = privateAlbumLabel,
                        body = stringResource(R.string.collections_private_album_body),
                        icon = GalleryIcons.Lock,
                        onClick = onPrivateAlbumClick,
                    )
                }
                if (collageLabel != null && onCollageClick != null) item {
                    CollectionCard(
                        title = collageLabel,
                        body = stringResource(R.string.collections_collage_body),
                        icon = GalleryIcons.Collections,
                        onClick = onCollageClick,
                    )
                }
                if (peopleEnabled) {
                    item {
                        CollectionCard(
                            title = stringResource(R.string.collections_people),
                            body = stringResource(R.string.collections_item_count, peopleCount),
                            icon = GalleryIcons.User,
                            onClick = onPeopleClick,
                        )
                    }
                }
                if (petCollectionsEnabled) {
                    item {
                        CollectionCard(
                            title = stringResource(R.string.collections_dogs),
                            body = stringResource(R.string.collections_item_count, dogCount),
                            icon = GalleryIcons.Pet,
                            onClick = { onPetCollectionClick("dog") },
                        )
                    }
                    item {
                        CollectionCard(
                            title = stringResource(R.string.collections_cats),
                            body = stringResource(R.string.collections_item_count, catCount),
                            icon = GalleryIcons.Pet,
                            onClick = { onPetCollectionClick("cat") },
                        )
                    }
                }
                if (momentSummaries.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.collections_moments),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(momentSummaries.count(), key = { "moment_${it.hashCode()}" }) { index ->
                        val summary = momentSummaries[index]
                        CollectionCard(
                            title = summary.moment.title ?: stringResource(R.string.moment_untitled),
                            body = stringResource(R.string.collections_item_count, summary.memberCount),
                            icon = GalleryIcons.Image,
                            onClick = { onMomentClick(summary.moment) },
                        )
                    }
                }
                item {
                    CollectionCard(
                        title = stringResource(R.string.collections_create_album),
                        body = stringResource(R.string.collections_virtual_album_body),
                        icon = GalleryIcons.Plus,
                        onClick = onCreateAlbum,
                    )
                }
                item {
                    CollectionCard(
                        title = stringResource(R.string.collections_trash),
                        body = stringResource(R.string.collections_item_count, trashCount),
                        icon = GalleryIcons.Trash,
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
                        cover = album.cover,
                        thumbnailLoader = thumbnailLoader,
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
}

@Composable
private fun CollectionCard(
    title: String,
    body: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    cover: MediaKey? = null,
    thumbnailLoader: ThumbnailLoader? = null,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, cover, thumbnailLoader) {
        value = if (cover == null || thumbnailLoader == null) null else runCatching {
            thumbnailLoader.load(ThumbnailRequest(cover, 0, 512, 320))
        }.getOrNull()
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics {
            contentDescription = "$title. $body"
        },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (bitmap == null && icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
                        .padding(10.dp),
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
