package com.ugallery.feature.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

internal fun collectionGridColumns(availableWidth: Dp): Int = when {
    availableWidth < 292.dp -> 1
    availableWidth < 840.dp -> 2
    else -> 4
}

internal fun collectionRowCount(itemCount: Int, columns: Int): Int {
    require(itemCount >= 0)
    require(columns > 0)
    return (itemCount + columns - 1) / columns
}

private val CollectionCoverHeight = 88.dp

private data class CollectionCardSpec(
    val key: String,
    val title: String,
    val body: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val cover: MediaKey? = null,
    val circular: Boolean = false,
)

@Composable
fun CollectionsContent(
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    trashCount: Long,
    archiveCount: Long,
    momentSummaries: List<MomentSummaryRow>,
    onMomentClick: (MomentEntity) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onCreateAlbum: () -> Unit,
    onTrashClick: () -> Unit,
    onArchiveClick: () -> Unit,
    onLocalAnalysisClick: () -> Unit,
    peopleEnabled: Boolean,
    peopleCount: Long,
    peopleCover: MediaKey? = null,
    onPeopleClick: () -> Unit,
    petCollectionsEnabled: Boolean,
    dogCount: Long,
    catCount: Long,
    dogCover: MediaKey? = null,
    catCover: MediaKey? = null,
    onPetCollectionClick: (String) -> Unit,
    thumbnailLoader: ThumbnailLoader? = null,
    privateAlbumLabel: String? = null,
    onPrivateAlbumClick: (() -> Unit)? = null,
    collageLabel: String? = null,
    onCollageClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dogsTitle = stringResource(R.string.collections_dogs)
    val catsTitle = stringResource(R.string.collections_cats)
    val petCards = if (petCollectionsEnabled) listOf(
        CollectionCardSpec(
            key = "dogs",
            title = dogsTitle,
            body = stringResource(R.string.collections_item_count, dogCount),
            icon = GalleryIcons.Pet,
            cover = dogCover,
            circular = true,
            onClick = { onPetCollectionClick(dogsTitle) },
        ),
        CollectionCardSpec(
            key = "cats",
            title = catsTitle,
            body = stringResource(R.string.collections_item_count, catCount),
            icon = GalleryIcons.Pet,
            cover = catCover,
            circular = true,
            onClick = { onPetCollectionClick(catsTitle) },
        ),
    ) else emptyList()
    val momentCards = momentSummaries.map { summary ->
        CollectionCardSpec(
            key = "moment:${summary.moment.momentId}",
            title = summary.moment.title ?: stringResource(R.string.moment_untitled),
            body = stringResource(R.string.collections_item_count, summary.memberCount),
            icon = GalleryIcons.Image,
            onClick = { onMomentClick(summary.moment) },
        )
    }
    val libraryCards = buildList {
        if (peopleEnabled) add(CollectionCardSpec(
            key = "people",
            title = stringResource(R.string.collections_people),
            body = stringResource(R.string.collections_item_count, peopleCount),
            icon = GalleryIcons.User,
            cover = peopleCover,
            circular = true,
            onClick = onPeopleClick,
        ))
        add(CollectionCardSpec(
            key = "archive",
            title = stringResource(R.string.collections_archive),
            body = stringResource(R.string.collections_item_count, archiveCount),
            icon = GalleryIcons.Archive,
            onClick = onArchiveClick,
        ))
        add(CollectionCardSpec(
            key = "trash",
            title = stringResource(R.string.collections_trash),
            body = stringResource(R.string.collections_item_count, trashCount),
            icon = GalleryIcons.Trash,
            onClick = onTrashClick,
        ))
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      Column(Modifier.fillMaxSize().widthIn(max = 1_200.dp).padding(horizontal = GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.collections_title),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = GallerySpacing.Xl, bottom = GallerySpacing.Sm).semantics { heading() },
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = collectionGridColumns(maxWidth)
            LazyColumn(
                contentPadding = PaddingValues(bottom = GallerySpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "library-header") {
                    Text(
                        stringResource(R.string.collections_section_library),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                pagedLibraryCardRows(
                    cards = libraryCards,
                    virtualAlbums = virtualAlbums,
                    physicalAlbums = physicalAlbums,
                    columns = columns,
                    thumbnailLoader = thumbnailLoader,
                    onAlbumClick = onAlbumClick,
                )
                if (virtualAlbums.itemCount == 0 && physicalAlbums.itemCount == 0 &&
                    physicalAlbums.loadState.refresh !is LoadState.Loading &&
                    virtualAlbums.loadState.refresh !is LoadState.Loading
                ) {
                    item(key = "empty-albums") {
                        GalleryStateContent(
                            stringResource(R.string.collections_empty),
                            stringResource(R.string.collections_empty_body),
                            stringResource(R.string.collections_empty),
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "auto-header") {
                    Text(
                        stringResource(R.string.collections_section_auto),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                collectionCardRows(petCards + momentCards, columns, thumbnailLoader)
                item(key = "actions-header") {
                    Text(
                        stringResource(R.string.collections_section_actions),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                item(key = "create-album") {
                    CollectionCard(
                        title = stringResource(R.string.collections_create_album),
                        body = stringResource(R.string.collections_virtual_album_body),
                        icon = GalleryIcons.Plus,
                        onClick = onCreateAlbum,
                        wide = true,
                    )
                }
                if (privateAlbumLabel != null && onPrivateAlbumClick != null) item(key = "private-album") {
                    CollectionCard(
                        title = privateAlbumLabel,
                        body = stringResource(R.string.collections_private_album_body),
                        icon = GalleryIcons.Lock,
                        onClick = onPrivateAlbumClick,
                        wide = true,
                    )
                }
                if (collageLabel != null && onCollageClick != null) item(key = "collage") {
                    CollectionCard(
                        title = collageLabel,
                        body = stringResource(R.string.collections_collage_body),
                        icon = GalleryIcons.Collections,
                        onClick = onCollageClick,
                        wide = true,
                    )
                }
                item(key = "local-analysis") {
                    CollectionCard(
                        title = stringResource(R.string.collections_local_analysis),
                        body = stringResource(R.string.collections_local_analysis_body),
                        icon = GalleryIcons.Analyze,
                        onClick = onLocalAnalysisClick,
                        wide = true,
                    )
                }
            }
        }
      }
    }
}

private fun LazyListScope.pagedLibraryCardRows(
    cards: List<CollectionCardSpec>,
    virtualAlbums: LazyPagingItems<AlbumSummary>,
    physicalAlbums: LazyPagingItems<AlbumSummary>,
    columns: Int,
    thumbnailLoader: ThumbnailLoader?,
    onAlbumClick: (AlbumSummary) -> Unit,
) {
    val virtualAlbumCount = virtualAlbums.itemCount
    val totalItemCount = cards.size + virtualAlbumCount + physicalAlbums.itemCount
    // Access LazyPagingItems only from composed rows. Reading every index while building this
    // section defeats Paging, continuously invalidates rows, and restarts visible cover loads.
    repeat(collectionRowCount(totalItemCount, columns)) { rowIndex ->
        item(key = "library-row:$rowIndex") {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val rowStart = rowIndex * columns
                repeat(columns) { columnIndex ->
                    val itemIndex = rowStart + columnIndex
                    val card = when {
                        itemIndex >= totalItemCount -> null
                        itemIndex < cards.size -> cards[itemIndex]
                        itemIndex < cards.size + virtualAlbumCount -> {
                            virtualAlbums[itemIndex - cards.size]?.asCollectionCard(
                                onClick = onAlbumClick,
                            )
                        }
                        else -> {
                            physicalAlbums[itemIndex - cards.size - virtualAlbumCount]?.asCollectionCard(
                                onClick = onAlbumClick,
                            )
                        }
                    }
                    if (card == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        key(card.key) {
                            CollectionCard(
                                title = card.title,
                                body = card.body,
                                onClick = card.onClick,
                                icon = card.icon,
                                cover = card.cover,
                                circular = card.circular,
                                thumbnailLoader = thumbnailLoader,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumSummary.asCollectionCard(
    onClick: (AlbumSummary) -> Unit,
) = CollectionCardSpec(
    key = "album:$key",
    title = name ?: stringResource(R.string.collections_untitled),
    body = if (availability == AlbumAvailability.VolumeUnavailable) {
        stringResource(R.string.collections_volume_unavailable)
    } else {
        stringResource(R.string.collections_item_count, itemCount)
    },
    cover = cover,
    onClick = { onClick(this) },
)

private fun LazyListScope.collectionCardRows(
    cards: List<CollectionCardSpec>,
    columns: Int,
    thumbnailLoader: ThumbnailLoader?,
) {
    cards.chunked(columns).forEach { rowCards ->
        item(key = rowCards.joinToString("|") { it.key }) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowCards.forEach { card ->
                    CollectionCard(
                        title = card.title,
                        body = card.body,
                        onClick = card.onClick,
                        icon = card.icon,
                        cover = card.cover,
                        circular = card.circular,
                        thumbnailLoader = thumbnailLoader,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                repeat(columns - rowCards.size) {
                    Spacer(Modifier.weight(1f))
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
    circular: Boolean = false,
    thumbnailLoader: ThumbnailLoader? = null,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val thumbnailRequest = cover?.let { ThumbnailRequest(it, 0, 512, 320) }
    val cachedBitmap = remember(thumbnailRequest, thumbnailLoader) {
        thumbnailRequest?.let { request ->
            thumbnailLoader?.cached(request)
                ?: thumbnailLoader?.bestCached(request.mediaKey, request.generationModified)
        }
    }
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = cachedBitmap,
        thumbnailRequest,
        thumbnailLoader,
    ) {
        if (thumbnailRequest == null || thumbnailLoader == null) {
            value = null
        } else {
            runCatching { thumbnailLoader.load(thumbnailRequest) }
                .getOrNull()
                ?.let { value = it }
        }
    }
    Card(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
            contentDescription = "$title. $body"
        },
    ) {
        if (bitmap != null && circular) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = 16.dp, top = 16.dp)
                    .size(56.dp)
                    .clip(CircleShape),
            )
        } else if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(CollectionCoverHeight),
            )
        }
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (icon != null) CollectionCardIcon(icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CollectionCardText(title, body, bodyMaxLines = 3)
                }
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (bitmap == null && icon != null) CollectionCardIcon(icon)
                CollectionCardText(title, body, bodyMaxLines = if (bitmap == null) 4 else 2)
            }
        }
    }
}

@Composable
private fun CollectionCardIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
            .padding(10.dp),
    )
}

@Composable
private fun CollectionCardText(title: String, body: String, bodyMaxLines: Int) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = bodyMaxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
