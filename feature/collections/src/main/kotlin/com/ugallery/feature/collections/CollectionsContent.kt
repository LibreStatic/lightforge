package com.ugallery.feature.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentSummaryRow
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.model.AlbumAvailability
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.MediaKey
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest

internal fun collectionGridColumns(availableWidth: Dp): Int =
    when {
        availableWidth < 292.dp -> 1
        availableWidth < 840.dp -> 2
        else -> 4
    }

/** Which empty state a Collections album section may claim. */
enum class CollectionsSectionEmptyState { Hidden, NoAlbums, NoDeviceFolders }

/**
 * An empty section must only claim that *it* is empty. The surface keeps device folders, Moments and
 * library cards on the same scrollable page, so a still-loading or non-album section shows nothing.
 */
fun collectionsSectionEmptyState(
    sectionId: String,
    itemCount: Int,
    refreshing: Boolean,
): CollectionsSectionEmptyState = when {
    refreshing || itemCount > 0 -> CollectionsSectionEmptyState.Hidden
    sectionId == "virtual-albums" -> CollectionsSectionEmptyState.NoAlbums
    sectionId == "physical-albums" -> CollectionsSectionEmptyState.NoDeviceFolders
    else -> CollectionsSectionEmptyState.Hidden
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
    val wide: Boolean = false,
    val tag: String? = null,
)

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    documentCount: Long = 0,
    onDocumentsClick: (() -> Unit)? = null,
    onStacksClick: (() -> Unit)? = null,
    onSmartAlbumsClick: (() -> Unit)? = null,
    onMemoryControlsClick: (() -> Unit)? = null,
    onAllMemoriesClick: (() -> Unit)? = null,
    pdfStudioLabel: String? = null,
    pdfStudioBody: String? = null,
    onPdfStudioClick: (() -> Unit)? = null,
    collageLabel: String? = null,
    onCollageClick: (() -> Unit)? = null,
    onFavoritesClick: (() -> Unit)? = null,
    onCleanupClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    momentPlaceLabels: ((String) -> kotlinx.coroutines.flow.Flow<String?>)? = null,
    layoutOrder: List<String> = emptyList(),
    hiddenCollections: Set<String> = emptySet(),
    layoutWorking: Boolean = false,
    layoutFailed: Boolean = false,
    layoutRevision: Int = 0,
    onSaveLayout: ((List<String>, Set<String>) -> Unit)? = null,
) {
    val dogsTitle = stringResource(R.string.collections_dogs)
    val catsTitle = stringResource(R.string.collections_cats)
    val petCards =
        if (petCollectionsEnabled)
            listOf(
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
            )
        else emptyList()
    val momentCards =
        momentSummaries.map { summary ->
            CollectionCardSpec(
                key = "moment:${summary.moment.momentId}",
                title = momentDisplayTitle(summary.moment, momentPlaceLabels),
                body = stringResource(R.string.collections_item_count, summary.memberCount),
                icon = GalleryIcons.Image,
                onClick = { onMomentClick(summary.moment) },
            )
        }
    val libraryCards = buildList {
        onDocumentsClick?.let { action ->
            add(
                CollectionCardSpec(
                    key = "documents",
                    title = stringResource(R.string.documents_title),
                    body = stringResource(R.string.documents_collection_count, documentCount),
                    icon = GalleryIcons.Collections,
                    onClick = action,
                )
            )
        }

        if (peopleEnabled)
            add(
                CollectionCardSpec(
                    key = "people",
                    title = stringResource(R.string.collections_people),
                    body = stringResource(R.string.collections_item_count, peopleCount),
                    icon = GalleryIcons.User,
                    cover = peopleCover,
                    circular = true,
                    onClick = onPeopleClick,
                )
            )
        onFavoritesClick?.let { action ->
            add(
                CollectionCardSpec(
                    key = "favorites",
                    title = stringResource(R.string.collections_favorites),
                    body = stringResource(R.string.collections_favorites_body),
                    icon = GalleryIcons.Heart,
                    onClick = action,
                )
            )
        }
        add(
            CollectionCardSpec(
                key = "archive",
                title = stringResource(R.string.collections_archive),
                body = stringResource(R.string.collections_item_count, archiveCount),
                icon = GalleryIcons.Archive,
                onClick = onArchiveClick,
            )
        )
        add(
            CollectionCardSpec(
                key = "trash",
                title = stringResource(R.string.collections_trash),
                body = stringResource(R.string.collections_item_count, trashCount),
                icon = GalleryIcons.Trash,
                onClick = onTrashClick,
            )
        )
    }
    val cards = (libraryCards + petCards).associateBy { it.key }.toMutableMap()
    fun action(id: String, title: String, body: String, icon: ImageVector, callback: (() -> Unit)?, tag: String? = null) {
        if (callback != null) cards[id] = CollectionCardSpec(id, title, body, callback, icon = icon, wide = true, tag = tag)
    }
    action("all-memories", stringResource(R.string.memories_browser_open), stringResource(R.string.memories_browser_body),
        GalleryIcons.Image, onAllMemoriesClick, "collections-all-memories")
    action("create-album", stringResource(R.string.collections_create_album), stringResource(R.string.collections_virtual_album_body), GalleryIcons.Plus, onCreateAlbum)
    action("private-album", privateAlbumLabel ?: stringResource(R.string.collection_layout_private), stringResource(R.string.collections_private_album_body),
        GalleryIcons.Lock, onPrivateAlbumClick.takeIf { privateAlbumLabel != null })
    action("memory-controls", stringResource(R.string.memory_controls_title), stringResource(R.string.memory_controls_scope),
        GalleryIcons.Collections, onMemoryControlsClick, "collections-memory-controls")
    action("smart-albums", stringResource(R.string.smart_title), stringResource(R.string.smart_local),
        GalleryIcons.Collections, onSmartAlbumsClick, "collections-smart-albums")
    action("photo-stacks", stringResource(R.string.stacks_title), stringResource(R.string.stacks_collection_hint), GalleryIcons.Collections, onStacksClick)
    action("pdf-studio", pdfStudioLabel ?: stringResource(R.string.collection_layout_pdf), pdfStudioBody.orEmpty(),
        GalleryIcons.Collections, onPdfStudioClick.takeIf { pdfStudioLabel != null })
    action("collage", collageLabel ?: stringResource(R.string.collection_layout_collage), stringResource(R.string.collections_collage_body),
        GalleryIcons.Collections, onCollageClick.takeIf { collageLabel != null })
    action("cleanup", stringResource(R.string.cleanup_title), stringResource(R.string.cleanup_collection_body),
        GalleryIcons.Trash, onCleanupClick, "collections-cleanup")
    action("local-analysis", stringResource(R.string.collections_local_analysis), stringResource(R.string.collections_local_analysis_body), GalleryIcons.Analyze, onLocalAnalysisClick)
    val labels = mapOf(
        "documents" to stringResource(R.string.documents_title), "people" to stringResource(R.string.collections_people),
        "favorites" to stringResource(R.string.collections_favorites), "cleanup" to stringResource(R.string.cleanup_title),
        "archive" to stringResource(R.string.collections_archive), "trash" to stringResource(R.string.collections_trash),
        "virtual-albums" to stringResource(R.string.collection_layout_virtual), "physical-albums" to stringResource(R.string.collection_layout_physical),
        "dogs" to dogsTitle, "cats" to catsTitle, "memories" to stringResource(R.string.collections_moments),
        "all-memories" to stringResource(R.string.memories_browser_open), "create-album" to stringResource(R.string.collections_create_album),
        "private-album" to (privateAlbumLabel ?: stringResource(R.string.collection_layout_private)),
        "memory-controls" to stringResource(R.string.memory_controls_title), "smart-albums" to stringResource(R.string.smart_title),
        "photo-stacks" to stringResource(R.string.stacks_title), "pdf-studio" to (pdfStudioLabel ?: stringResource(R.string.collection_layout_pdf)),
        "collage" to (collageLabel ?: stringResource(R.string.collection_layout_collage)), "local-analysis" to stringResource(R.string.collections_local_analysis),
    )
    val available = cards.keys + setOf("virtual-albums", "physical-albums", "memories")
    val ordered = normalizedCollectionLayoutOrder(layoutOrder)
    var manageLayout by remember(layoutRevision) { mutableStateOf(false) }
    if (manageLayout && onSaveLayout != null) CollectionLayoutDialog(
        order = layoutOrder, hidden = hiddenCollections, labels = labels, available = available,
        working = layoutWorking, failed = layoutFailed,
        onSave = onSaveLayout, onDismiss = { if (!layoutWorking) manageLayout = false },
    )
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = 1_200.dp).padding(horizontal = GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.collections_title), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = GallerySpacing.Xl, bottom = GallerySpacing.Sm).semantics { heading() })
            if (onSaveLayout != null) TextButton(onClick = { manageLayout = true }, enabled = !layoutWorking,
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("collections-manage")) {
                Text(stringResource(R.string.collection_layout_manage))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = collectionGridColumns(maxWidth)
                LazyColumn(contentPadding = PaddingValues(bottom = GallerySpacing.Lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val pendingCards = mutableListOf<CollectionCardSpec>()
                    fun flushCards() {
                        if (pendingCards.isNotEmpty()) {
                            collectionCardRows(pendingCards.toList(), columns, thumbnailLoader)
                            pendingCards.clear()
                        }
                    }
                    for (id in ordered) {
                        if (id in hiddenCollections || id !in available) continue
                        when (id) {
                            "virtual-albums", "physical-albums" -> {
                                flushCards()
                                item(key = "$id-header") { Text(labels.getValue(id), style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() }) }
                                pagedAlbumCardRows(id, if (id == "virtual-albums") virtualAlbums else physicalAlbums,
                                    columns, thumbnailLoader, onAlbumClick)
                            }
                            "memories" -> {
                                flushCards()
                                item(key = "auto-header") { Text(labels.getValue(id), style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() }) }
                                collectionCardRows(momentCards, columns, thumbnailLoader)
                            }
                            else -> {
                                val card = cards.getValue(id)
                                if (card.wide) {
                                    flushCards()
                                    item(key = id) { CollectionCard(title = card.title, body = card.body,
                                        onClick = card.onClick, icon = card.icon, cover = card.cover, circular = card.circular,
                                        thumbnailLoader = thumbnailLoader, wide = true,
                                        modifier = Modifier.fillMaxWidth().then(card.tag?.let {
                                            Modifier.semantics { testTagsAsResourceId = true }.testTag(it)
                                        } ?: Modifier)) }
                                } else pendingCards += card
                            }
                        }
                    }
                    flushCards()
                }
            }
        }
    }
}

private fun LazyListScope.pagedAlbumCardRows(
    id: String,
    albums: LazyPagingItems<AlbumSummary>,
    columns: Int,
    thumbnailLoader: ThumbnailLoader?,
    onAlbumClick: (AlbumSummary) -> Unit,
) {
    val emptyState = collectionsSectionEmptyState(id, albums.itemCount, albums.loadState.refresh is LoadState.Loading)
    if (emptyState != CollectionsSectionEmptyState.Hidden) {
        val title = if (emptyState == CollectionsSectionEmptyState.NoAlbums) R.string.collections_albums_empty
        else R.string.collections_folders_empty
        val body = if (emptyState == CollectionsSectionEmptyState.NoAlbums) R.string.collections_albums_empty_body
        else R.string.collections_folders_empty_body
        item(key = "$id-empty") { GalleryStateContent(stringResource(title),
            stringResource(body), stringResource(title), Modifier.fillMaxWidth()) }
    }
    // Never access every Paging item to build/sort a block. Only composed rows request media.
    repeat(collectionRowCount(albums.itemCount, columns)) { rowIndex ->
        item(key = "$id-row:$rowIndex") {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) { columnIndex ->
                    val index = rowIndex * columns + columnIndex
                    val card = if (index < albums.itemCount) albums[index]?.asCollectionCard(onAlbumClick) else null
                    if (card == null) Spacer(Modifier.weight(1f)) else key(card.key) {
                        CollectionCard(card.title, card.body, card.onClick, icon = card.icon, cover = card.cover,
                            circular = card.circular, thumbnailLoader = thumbnailLoader,
                            modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumSummary.asCollectionCard(onClick: (AlbumSummary) -> Unit) =
    CollectionCardSpec(
        key = "album:$key",
        title = name ?: stringResource(R.string.collections_untitled),
        body =
            if (availability == AlbumAvailability.VolumeUnavailable) {
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
                repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
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
    val cachedBitmap =
        remember(thumbnailRequest, thumbnailLoader) {
            thumbnailRequest?.let { request ->
                thumbnailLoader?.cached(request)
                    ?: thumbnailLoader?.bestCached(request.mediaKey, request.generationModified)
            }
        }
    val bitmap by
        produceState<android.graphics.Bitmap?>(
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
        modifier.fillMaxWidth().clickable(onClick = onClick).semantics {
            contentDescription = "$title. $body"
        }
    ) {
        if (bitmap != null && circular) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.padding(start = 16.dp, top = 16.dp).size(56.dp).clip(CircleShape),
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
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier.background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.shapes.medium,
                )
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
