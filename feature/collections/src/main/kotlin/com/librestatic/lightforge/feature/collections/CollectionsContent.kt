package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.librestatic.lightforge.core.designsystem.GalleryOverlayTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.res.pluralStringResource
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
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentSummaryRow
import com.librestatic.lightforge.core.designsystem.GalleryGridMetrics
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryShapeIllustration
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

/** Square collection tiles follow the Photos grid: as many as fit, never fewer than two. */
internal fun collectionGridColumns(availableWidth: Dp): Int =
    ((availableWidth + CollectionTileGap) / (CollectionTileTarget + CollectionTileGap)).toInt().coerceIn(2, 6)

private val CollectionTileGap = 8.dp
private val CollectionTileTarget = 160.dp
private val WideCollectionsWidth = 520.dp

/** Library entries shown as compact shortcuts instead of tiles, as in Google Photos. */
private val ShortcutKeys = setOf("documents", "people", "favorites", "archive", "trash")

private fun CollectionCardSpec.isShortcut() = wide || key in ShortcutKeys

internal fun collectionRowCount(itemCount: Int, columns: Int): Int {
    require(itemCount >= 0)
    require(columns > 0)
    return (itemCount + columns - 1) / columns
}

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
                    body = pluralStringResource(R.plurals.collections_item_count, dogCount.toInt(), dogCount),
                    icon = GalleryIcons.Pet,
                    cover = dogCover,
                    circular = true,
                    onClick = { onPetCollectionClick(dogsTitle) },
                ),
                CollectionCardSpec(
                    key = "cats",
                    title = catsTitle,
                    body = pluralStringResource(R.plurals.collections_item_count, catCount.toInt(), catCount),
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
                body = pluralStringResource(R.plurals.collections_item_count, summary.memberCount.toInt(), summary.memberCount),
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
                    body = pluralStringResource(R.plurals.documents_collection_count, documentCount.toInt(), documentCount),
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
                    body = pluralStringResource(R.plurals.collections_item_count, peopleCount.toInt(), peopleCount),
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
                body = pluralStringResource(R.plurals.collections_item_count, archiveCount.toInt(), archiveCount),
                icon = GalleryIcons.Archive,
                onClick = onArchiveClick,
            )
        )
        add(
            CollectionCardSpec(
                key = "trash",
                title = stringResource(R.string.collections_trash),
                body = pluralStringResource(R.plurals.collections_item_count, trashCount.toInt(), trashCount),
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
        GalleryIcons.Cleanup, onCleanupClick, "collections-cleanup")
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
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wideHeader = maxWidth >= WideCollectionsWidth
        val manageButton: @Composable () -> Unit = {
            val label: @Composable () -> Unit = { Text(stringResource(R.string.collection_layout_manage)) }
            val tagged = Modifier.semantics { testTagsAsResourceId = true }.testTag("collections-manage")
            if (wideHeader) FilledTonalButton(onClick = { manageLayout = true }, enabled = !layoutWorking, modifier = tagged) { label() }
            else TextButton(onClick = { manageLayout = true }, enabled = !layoutWorking, modifier = tagged) { label() }
        }
        Column(Modifier.fillMaxSize().widthIn(max = 1_200.dp).padding(horizontal = GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.collections_title), style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = GallerySpacing.Xl, bottom = GallerySpacing.Sm).semantics { heading() })
                if (onSaveLayout != null && wideHeader) manageButton()
            }
            if (onSaveLayout != null && !wideHeader) manageButton()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = collectionGridColumns(maxWidth)
                val visibleIds = ordered.filter { it !in hiddenCollections && it in available }
                val shortcuts = visibleIds.mapNotNull { id -> cards[id]?.takeIf { it.isShortcut() } }
                val denseColumns = GalleryGridMetrics.adaptiveColumns(maxWidth)
                val wideShortcuts = maxWidth >= WideCollectionsWidth
                LazyColumn(contentPadding = PaddingValues(bottom = GallerySpacing.Lg)) {
                    if (shortcuts.isNotEmpty()) item(key = "shortcuts") {
                        Box(Modifier.padding(bottom = CollectionTileGap)) { CollectionShortcuts(shortcuts, wrap = wideShortcuts) }
                    }
                    val pendingTiles = mutableListOf<CollectionCardSpec>()
                    fun flushTiles() {
                        if (pendingTiles.isNotEmpty()) {
                            collectionTileRows(pendingTiles.toList(), columns, thumbnailLoader, CollectionTileGap, dense = false)
                            pendingTiles.clear()
                        }
                    }
                    for (id in visibleIds) {
                        when (id) {
                            "virtual-albums", "physical-albums" -> {
                                flushTiles()
                                val albums = if (id == "virtual-albums") virtualAlbums else physicalAlbums
                                // An empty section has no action of its own ("Create album" is a
                                // shortcut), so it is omitted instead of showing a placeholder.
                                if (albums.itemCount > 0) {
                                    item(key = "$id-header") { CollectionSectionHeader(labels.getValue(id)) }
                                    // Device folders follow the Photos grid (same target cell and gap); albums stay larger.
                                    val dense = id == "physical-albums"
                                    pagedAlbumTileRows(
                                        id, albums, if (dense) denseColumns else columns, thumbnailLoader,
                                        if (dense) GalleryGridMetrics.Gap else CollectionTileGap, dense, onAlbumClick,
                                    )
                                }
                            }
                            "memories" -> {
                                flushTiles()
                                // Like the album sections, no moments means no section: never an orphan title.
                                if (momentCards.isNotEmpty()) {
                                    item(key = "auto-header") { CollectionSectionHeader(labels.getValue(id)) }
                                    collectionTileRows(momentCards, columns, thumbnailLoader, CollectionTileGap, dense = false)
                                }
                            }
                            else -> {
                                val card = cards.getValue(id)
                                if (!card.isShortcut()) pendingTiles += card
                            }
                        }
                    }
                    flushTiles()
                }
            }
        }
    }
}

private fun LazyListScope.pagedAlbumTileRows(
    id: String,
    albums: LazyPagingItems<AlbumSummary>,
    columns: Int,
    thumbnailLoader: ThumbnailLoader?,
    gap: Dp,
    dense: Boolean,
    onAlbumClick: (AlbumSummary) -> Unit,
) {
    // Never access every Paging item to build/sort a block. Only composed rows request media.
    repeat(collectionRowCount(albums.itemCount, columns)) { rowIndex ->
        item(key = "$id-row:$rowIndex") {
            Row(Modifier.fillMaxWidth().padding(bottom = gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { columnIndex ->
                    val index = rowIndex * columns + columnIndex
                    val card = if (index < albums.itemCount) albums[index]?.asCollectionCard(onAlbumClick) else null
                    if (card == null) Spacer(Modifier.weight(1f)) else key(card.key) {
                        CollectionTile(card, thumbnailLoader, Modifier.weight(1f), dense)
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
                pluralStringResource(R.plurals.collections_item_count, itemCount.toInt(), itemCount)
            },
        cover = cover,
        onClick = { onClick(this) },
    )
private fun LazyListScope.collectionTileRows(
    cards: List<CollectionCardSpec>,
    columns: Int,
    thumbnailLoader: ThumbnailLoader?,
    gap: Dp,
    dense: Boolean,
) {
    cards.chunked(columns).forEach { rowCards ->
        item(key = rowCards.joinToString("|") { it.key }) {
            Row(Modifier.fillMaxWidth().padding(bottom = gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                rowCards.forEach { card -> CollectionTile(card, thumbnailLoader, Modifier.weight(1f), dense) }
                // A short last row keeps square tiles instead of stretching them.
                repeat(columns - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CollectionSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = GallerySpacing.Sm, bottom = CollectionTileGap).semantics { heading() },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CollectionShortcuts(shortcuts: List<CollectionCardSpec>, wrap: Boolean) {
    val rowModifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("collections-shortcuts")
    val gap = Arrangement.spacedBy(CollectionTileGap)
    // Compact keeps one scrollable row; wide layouts wrap so every shortcut stays visible.
    if (wrap) FlowRow(rowModifier, horizontalArrangement = gap, verticalArrangement = gap) {
        shortcuts.forEach { key(it.key) { ShortcutChip(it) } }
    } else LazyRow(rowModifier, horizontalArrangement = gap) {
        items(shortcuts, key = { it.key }) { ShortcutChip(it) }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ShortcutChip(card: CollectionCardSpec) {
    Surface(
        onClick = card.onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "${card.title}. ${card.body}" }
            .then(card.tag?.let { Modifier.semantics { testTagsAsResourceId = true }.testTag(it) } ?: Modifier),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                card.icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                }
            }
            Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


/** Square tile: full-bleed cover with a bottom scrim, or an expressive shape when there is no cover. */
@Composable
private fun CollectionTile(
    card: CollectionCardSpec,
    thumbnailLoader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
) {
    val thumbnailRequest = card.cover?.let { ThumbnailRequest(it, 0, 512, 512) }
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
    val image = bitmap
    BoxWithConstraints(
        modifier.aspectRatio(1f)
            .clip(if (dense) MaterialTheme.shapes.medium else MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = card.onClick)
            .semantics { contentDescription = "${card.title}. ${card.body}" }
            .then(card.tag?.let { Modifier.semantics { testTagsAsResourceId = true }.testTag(it) } ?: Modifier),
    ) {
        val overMedia = image != null
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to GalleryOverlayTokens.ScrimBottom,
                    ),
                ),
            )
        } else if (card.icon != null) {
            GalleryShapeIllustration(
                card.icon,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                size = maxWidth * 0.46f,
                backdrop = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Column(Modifier.align(Alignment.BottomStart).padding(if (dense) 8.dp else 12.dp)) {
            Text(
                card.title,
                style = if (dense) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                color = if (overMedia) GalleryOverlayTokens.Content else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                card.body,
                style = if (dense) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                color = if (overMedia) GalleryOverlayTokens.Content else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
