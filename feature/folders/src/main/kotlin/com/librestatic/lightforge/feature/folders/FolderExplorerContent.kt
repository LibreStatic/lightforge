package com.librestatic.lightforge.feature.folders

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.designsystem.AnimatedMediaTile
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.designsystem.MediaTileBadges
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.mediaStoreImageUri
import com.librestatic.lightforge.core.designsystem.mediaTileDescription
import com.librestatic.lightforge.core.designsystem.videoDurationDescription
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

private const val PrimaryVolume = "external_primary"

/** Folder cards span this many columns of the folder grid; file tiles span one. */
private const val CardSpan = 2

private const val TileThumbnailPx = 256

/**
 * What a folder card can do in the advanced storage mode (ADR-046). The explorer receives null when
 * the mode is off, and then renders no menu, badge or advanced entry at all.
 */
class FolderExplorerActions(
    val onHide: (FolderCardUi) -> Unit,
    val onUnhide: (FolderCardUi) -> Unit,
    val onRename: (FolderCardUi, String) -> Unit,
    val onDelete: (FolderCardUi) -> Unit,
    val onOpenHiddenItem: (HiddenItemUi) -> Unit,
    /** Opens the destination picker to move the folder. */
    val onMove: (FolderCardUi) -> Unit = {},
)

private sealed interface FolderDialog {
    val folder: FolderCardUi

    data class Rename(override val folder: FolderCardUi) : FolderDialog
    data class Delete(override val folder: FolderCardUi) : FolderDialog
    data class Properties(override val folder: FolderCardUi) : FolderDialog
}

/**
 * The device folder explorer: volumes, then folders with a breadcrumb and the folder's own files in an
 * inline grid. Stateless; the host owns the [FolderLocation] and feeds the matching [state] (see
 * [buildFolderExplorerUiState]). [actions] is null unless the advanced storage mode is on.
 */
@Composable
fun FolderExplorerContent(
    state: FolderExplorerUiState,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onOpenFiles: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
    actions: FolderExplorerActions? = null,
    media: LazyPagingItems<TimelineMedia>? = null,
    onMediaClick: (TimelineMedia, FolderMediaScope) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    revokedWithHidden: Int = 0,
    onUnhideFolders: () -> Unit = {},
    mode: ExplorerMode = ExplorerMode.Browse,
) {
    val picking = mode as? ExplorerMode.PickDestination
    Column(modifier.fillMaxSize()) {
        if (revokedWithHidden > 0 && picking == null) RevokedNotice(revokedWithHidden, onUnhideFolders)
        // Placeholders sit in this weighted box, never in a list item, so they centre in what is left.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                FolderExplorerUiState.Loading -> {
                    val description = stringResource(R.string.folders_loading)
                    Box(Modifier.fillMaxSize().semantics { contentDescription = description }, Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                FolderExplorerUiState.Empty -> GalleryStateContent(
                    title = stringResource(R.string.folders_empty_title),
                    body = stringResource(R.string.folders_empty_body),
                    illustrationDescription = stringResource(R.string.folders_empty_title),
                    modifier = Modifier.fillMaxSize().testTag("folder-explorer-empty"),
                    heroIcon = GalleryIcons.Folder,
                )
                FolderExplorerUiState.Error -> GalleryStateContent(
                    title = stringResource(R.string.folders_error_title),
                    body = stringResource(R.string.folders_error_body),
                    illustrationDescription = stringResource(R.string.folders_error_title),
                    modifier = Modifier.fillMaxSize().testTag("folder-explorer-error"),
                    heroIcon = GalleryIcons.Warning,
                    action = {
                        Button(onClick = onRetry, modifier = Modifier.testTag("folder-explorer-retry")) {
                            Text(stringResource(R.string.folders_retry))
                        }
                    },
                )
                is FolderExplorerUiState.Volumes -> FolderGrid(Modifier) {
                    items(state.volumes, key = { it.volumeName }) { volume ->
                        VolumeCard(volume, thumbnails) { onEnter(volume.location) }
                    }
                }
                is FolderExplorerUiState.Folder -> FolderLevel(
                    state = state,
                    thumbnails = thumbnails,
                    onEnter = onEnter,
                    onOpenFiles = onOpenFiles,
                    actions = actions,
                    media = media,
                    onMediaClick = onMediaClick,
                    picking = picking,
                )
            }
        }
    }
}

@Composable
private fun FolderLevel(
    state: FolderExplorerUiState.Folder,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onOpenFiles: (AlbumSummary) -> Unit,
    actions: FolderExplorerActions?,
    media: LazyPagingItems<TimelineMedia>?,
    onMediaClick: (TimelineMedia, FolderMediaScope) -> Unit,
    picking: ExplorerMode.PickDestination?,
) {
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    val up = state.crumbs.getOrNull(state.crumbs.lastIndex - 1)?.location
    val upLabel = stringResource(R.string.folders_up_one_level)
    val upActions = if (up == null) emptyList() else listOf(CustomAccessibilityAction(upLabel) { onEnter(up); true })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Breadcrumb(state.crumbs, onEnter, upActions, Modifier.weight(1f))
            state.rootSpace?.let { space ->
                val free = space.freeBytes
                val total = space.totalBytes
                if (free != null && total != null) {
                    val context = LocalContext.current
                    Text(
                        stringResource(
                            R.string.folders_storage_space,
                            Formatter.formatShortFileSize(context, free),
                            Formatter.formatShortFileSize(context, total),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 16.dp).testTag("folder-volume-space"),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (picking != null && state.folders.none { it.location != picking.sourceFolder }) {
                // Nothing to open below this level; the destination can still be chosen or created here.
                GalleryStateContent(
                    title = stringResource(R.string.folders_picker_no_subfolders_title),
                    body = stringResource(R.string.folders_picker_no_subfolders_body),
                    illustrationDescription = stringResource(R.string.folders_picker_no_subfolders_title),
                    modifier = Modifier.fillMaxSize().testTag("folder-picker-no-subfolders"),
                    heroIcon = GalleryIcons.Folder,
                )
            } else if (picking == null && state.isEmpty) {
                GalleryStateContent(
                    title = stringResource(R.string.folders_folder_empty_title),
                    body = stringResource(R.string.folders_folder_empty_body),
                    illustrationDescription = stringResource(R.string.folders_folder_empty_title),
                    modifier = Modifier.fillMaxSize().testTag("folder-explorer-folder-empty"),
                    heroIcon = GalleryIcons.Folder,
                )
            } else {
                FolderLevelGrid(
                    state = state,
                    thumbnails = thumbnails,
                    onEnter = onEnter,
                    onOpenFiles = onOpenFiles,
                    actions = actions,
                    media = media,
                    onMediaClick = onMediaClick,
                    onDialog = { dialog = it },
                    upActions = upActions,
                    picking = picking,
                )
            }
        }
    }
    dialog?.let { current ->
        val close = { dialog = null }
        when (current) {
            is FolderDialog.Rename -> RenameDialog(
                folder = current.folder,
                siblings = state.folders.filter { it.key != current.folder.key }.map { it.name },
                onDismiss = close,
                onConfirm = { name -> close(); actions?.onRename?.invoke(current.folder, name) },
            )
            is FolderDialog.Delete -> DeleteDialog(
                folder = current.folder,
                onDismiss = close,
                onConfirm = { close(); actions?.onDelete?.invoke(current.folder) },
            )
            is FolderDialog.Properties -> PropertiesDialog(current.folder, state.crumbs, close)
        }
    }
}

@Composable
private fun FolderLevelGrid(
    state: FolderExplorerUiState.Folder,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onOpenFiles: (AlbumSummary) -> Unit,
    actions: FolderExplorerActions?,
    media: LazyPagingItems<TimelineMedia>?,
    onMediaClick: (TimelineMedia, FolderMediaScope) -> Unit,
    onDialog: (FolderDialog) -> Unit,
    upActions: List<CustomAccessibilityAction>,
    picking: ExplorerMode.PickDestination?,
) {
    // Each folder keeps its own scroll position: entering one starts at its top, going back restores the parent.
    val locationKey = state.crumbs.last().location.let { "${it.volumeName}|${it.relativePath}" }
    val positions = remember { HashMap<String, Pair<Int, Int>>() }
    val gridState = remember(locationKey) {
        positions[locationKey]?.let { (index, offset) -> LazyGridState(index, offset) } ?: LazyGridState()
    }
    LaunchedEffect(gridState, locationKey) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { positions[locationKey] = it }
    }
    LazyVerticalGrid(
        columns = FolderLevelCells,
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("folder-explorer-grid")
            .semantics { customActions = upActions },
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val cards = if (picking == null) state.folders else state.folders.filter { it.location != picking.sourceFolder }
        items(cards, key = { it.key }, span = { GridItemSpan(CardSpan) }) { folder ->
            FolderCard(folder, thumbnails, if (picking == null) actions else null, onDialog, picking != null) {
                when (val action = folder.action) {
                    is FolderAction.Enter -> onEnter(action.location)
                    // A picker only navigates: a folder without sub-folders is entered, not opened as an album.
                    is FolderAction.OpenFiles -> if (picking != null) onEnter(folder.location) else onOpenFiles(action.album)
                }
            }
        }
        if (picking != null) return@LazyVerticalGrid
        if (state.hiddenItems.isNotEmpty()) {
            sectionHeader("hidden-header", R.string.folders_hidden_items, state.hiddenItems.size.toLong())
            items(state.hiddenItems, key = { "hidden:${it.volumeName}:${it.mediaStoreId}" }) { item ->
                HiddenTile(item) { actions?.onOpenHiddenItem?.invoke(item) }
            }
        }
        val scope = state.media
        if (scope != null && media != null) {
            sectionHeader("files-header", R.string.folders_files_in_folder, null)
            items(
                count = media.itemCount,
                key = media.itemKey { "media:${it.key.volumeName}:${it.key.mediaStoreId}" },
            ) { index ->
                media[index]?.let { item -> MediaTile(item, thumbnails) { onMediaClick(item, scope) } }
            }
        } else {
            state.files?.let { files ->
                item(key = "files", span = { GridItemSpan(maxLineSpan) }) {
                    FilesCard(files) { (files.action as? FolderAction.OpenFiles)?.let { onOpenFiles(it.album) } }
                }
            }
        }
    }
}

private fun LazyGridScope.sectionHeader(key: String, title: Int, count: Long?) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        Text(
            if (count == null) stringResource(title) else "${stringResource(title)} · ${countText(count)}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() }.testTag("folder-$key"),
        )
    }
}

@Composable
private fun FolderGrid(
    modifier: Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(168.dp),
        modifier = modifier.fillMaxSize().testTag("folder-explorer-grid"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Cards are at least 168 dp wide, as in the volume grid; file tiles are half a card, so a card spans
 * two columns and its edges line up with the tiles around it.
 */
private object FolderLevelCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val cardColumns = ((availableSize + spacing) / (168.dp.roundToPx() + spacing)).coerceAtLeast(1)
        val columns = cardColumns * CardSpan
        val usable = availableSize - spacing * (columns - 1)
        val base = usable / columns
        val remainder = usable % columns
        return List(columns) { base + if (it < remainder) 1 else 0 }
    }
}

@Composable
private fun volumeName(volumeName: String, label: String?): String =
    if (volumeName == PrimaryVolume) stringResource(R.string.folders_internal_storage) else label ?: volumeName

@Composable
private fun Breadcrumb(
    crumbs: List<CrumbUi>,
    onEnter: (FolderLocation) -> Unit,
    upActions: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    // Deep paths overflow: keep the current folder in view.
    LaunchedEffect(crumbs) { scroll.scrollTo(scroll.maxValue) }
    val pathLabel = stringResource(R.string.folders_path_label)
    val currentLabel = stringResource(R.string.folders_current_folder)
    Row(
        modifier
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("folder-breadcrumb")
            .semantics {
                contentDescription = pathLabel
                collectionInfo = CollectionInfo(rowCount = 1, columnCount = crumbs.size)
                customActions = upActions
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(GalleryIcons.ChevronForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val title = crumb.title ?: volumeName(crumb.volumeName, crumb.volumeLabel)
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(if (crumb.isCurrent) colors.secondaryContainer else colors.surface)
                    .clickable(role = Role.Button) { onEnter(crumb.location) }
                    .padding(horizontal = 12.dp)
                    .testTag("folder-crumb-$index")
                    .semantics {
                        collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
                        selected = crumb.isCurrent
                        if (crumb.isCurrent) stateDescription = currentLabel
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (crumb.isCurrent) colors.onSecondaryContainer else colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RevokedNotice(count: Int, onUnhide: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("folder-revoked-notice"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                pluralStringResource(R.plurals.folders_revoked_notice, count, count),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onUnhide,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer),
                modifier = Modifier.align(Alignment.End).testTag("folder-revoked-unhide"),
            ) { Text(stringResource(R.string.folders_revoked_action)) }
        }
    }
}

@Composable
private fun Cover(cover: MediaKey?, loader: ThumbnailLoader?, modifier: Modifier) {
    val request = cover?.let { ThumbnailRequest(it, 0, 256, 256) }
    val bitmap by produceState(
        initialValue = request?.let { loader?.let { l -> l.cached(it) ?: l.bestCached(it.mediaKey, it.generationModified) } },
        request,
        loader,
    ) {
        if (request != null && loader != null) runCatching { loader.load(request) }.getOrNull()?.let { value = it }
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } ?: Icon(GalleryIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun countText(count: Long): String {
    val clamped = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.folders_item_count, clamped, clamped)
}

@Composable
private fun summaryText(count: Long, bytes: Long): String =
    stringResource(R.string.folders_card_summary, countText(count), Formatter.formatShortFileSize(LocalContext.current, bytes))

@Composable
private fun dateRangeText(min: Long?, max: Long?): String? {
    if (min == null || max == null) return null
    return DateUtils.formatDateRange(
        LocalContext.current,
        min,
        max,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: FolderCardUi,
    loader: ThumbnailLoader?,
    actions: FolderExplorerActions?,
    onDialog: (FolderDialog) -> Unit,
    picking: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = actions != null && folder.location.relativePath?.let { !isAndroidAppTree(it) } == true
    val moreDescription = stringResource(R.string.folders_more_options_for, folder.name)
    val body: @Composable ColumnScope.() -> Unit = {
        Box {
            Cover(folder.cover, loader, Modifier.fillMaxWidth().aspectRatio(1.5f))
            if (folder.isHidden && !picking) HiddenBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (hasMenu) 4.dp else 12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                CardSummary(folder)
            }
            if (actions != null && hasMenu) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("folder-card-menu-${folder.key}"),
                    ) {
                        Icon(GalleryIcons.More, contentDescription = moreDescription)
                    }
                    FolderMenu(folder, actions, menuOpen, { menuOpen = false }, onDialog)
                }
            }
        }
    }
    if (hasMenu) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("folder-card-${folder.key}")
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = moreDescription,
                    onLongClick = { menuOpen = true },
                    onClick = onClick,
                ),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
            content = body,
        )
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().testTag("folder-card-${folder.key}"),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
            content = body,
        )
    }
}

@Composable
private fun CardSummary(folder: FolderCardUi) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    if (folder.isEmptyDirectory) {
        Text(stringResource(R.string.folders_empty_directory), style = style, color = colors.onSurfaceVariant, maxLines = 1)
        return
    }
    if (folder.subfolderCount > 0) {
        Text(
            stringResource(R.string.folders_here_total, folder.directCount.toIntClamped(), folder.itemCount.toIntClamped()),
            style = style,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            pluralStringResource(R.plurals.folders_subfolder_count, folder.subfolderCount, folder.subfolderCount) +
                " · " + Formatter.formatShortFileSize(LocalContext.current, folder.sizeBytes),
            style = style,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            summaryText(folder.itemCount, folder.sizeBytes),
            style = style,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    dateRangeText(folder.minSortMillis, folder.maxSortMillis)?.let {
        Text(it, style = style, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HiddenBadge(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("folder-hidden-badge"),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(GalleryIcons.VisibilityOff, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.folders_hidden_badge), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FolderMenu(
    folder: FolderCardUi,
    actions: FolderExplorerActions,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDialog: (FolderDialog) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!folder.isHidden && !folder.isEmptyDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_menu_hide)) },
                leadingIcon = { Icon(GalleryIcons.VisibilityOff, contentDescription = null) },
                onClick = { onDismiss(); actions.onHide(folder) },
                modifier = Modifier.testTag("folder-menu-hide"),
            )
        }
        if (folder.isHidden && folder.isHiddenByApp) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_menu_unhide)) },
                leadingIcon = { Icon(GalleryIcons.Visibility, contentDescription = null) },
                onClick = { onDismiss(); actions.onUnhide(folder) },
                modifier = Modifier.testTag("folder-menu-unhide"),
            )
        }
        if (!isStandardFolder(folder.location.relativePath)) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_menu_move)) },
                leadingIcon = { Icon(GalleryIcons.DriveFileMove, contentDescription = null) },
                onClick = { onDismiss(); actions.onMove(folder) },
                modifier = Modifier.testTag("folder-menu-move"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_menu_rename)) },
                leadingIcon = { Icon(GalleryIcons.Rename, contentDescription = null) },
                onClick = { onDismiss(); onDialog(FolderDialog.Rename(folder)) },
                modifier = Modifier.testTag("folder-menu-rename"),
            )
            if (folder.isEmptyDirectory && folder.subfolderCount == 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.folders_menu_delete)) },
                    leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                    onClick = { onDismiss(); onDialog(FolderDialog.Delete(folder)) },
                    modifier = Modifier.testTag("folder-menu-delete"),
                )
            }
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.folders_menu_properties)) },
            leadingIcon = { Icon(GalleryIcons.Info, contentDescription = null) },
            onClick = { onDismiss(); onDialog(FolderDialog.Properties(folder)) },
            modifier = Modifier.testTag("folder-menu-properties"),
        )
    }
}

@Composable
private fun RenameDialog(
    folder: FolderCardUi,
    siblings: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) = RenameFolderDialog(
    currentName = folder.location.leafName().orEmpty(),
    // A compacted card ("A/B") stands for the deepest folder, whose real siblings are not listed here.
    siblings = if ('/' in folder.name) emptyList() else siblings,
    onDismiss = onDismiss,
    onConfirm = onConfirm,
)

/** Asks for the new name of the folder called [currentName]; [siblings] are the names already next to it. */
@Composable
fun RenameFolderDialog(
    currentName: String,
    siblings: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val problem = validateFolderName(name, siblings)
    val unchanged = name.trim() == currentName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folders_rename_label)) },
                singleLine = true,
                isError = problem != null && !unchanged,
                supportingText = {
                    if (problem != null && !unchanged) Text(stringResource(problem.messageRes()))
                },
                modifier = Modifier.fillMaxWidth().testTag("folder-rename-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = problem == null && !unchanged,
                modifier = Modifier.testTag("folder-rename-confirm"),
            ) { Text(stringResource(R.string.folders_rename_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

/** Asks for the name of a new folder inside the current one; [siblings] are the names already there. */
@Composable
fun NewFolderDialog(siblings: List<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val problem = validateFolderName(name, siblings)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_new_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.folders_rename_label)) },
                singleLine = true,
                isError = problem != null && name.isNotEmpty(),
                supportingText = {
                    if (problem != null && name.isNotEmpty()) Text(stringResource(problem.messageRes()))
                },
                modifier = Modifier.fillMaxWidth().testTag("folder-new-field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = problem == null,
                modifier = Modifier.testTag("folder-new-confirm"),
            ) { Text(stringResource(R.string.folders_new_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

private fun FolderNameProblem.messageRes(): Int = when (this) {
    FolderNameProblem.Blank -> R.string.folders_name_blank
    FolderNameProblem.InvalidCharacters -> R.string.folders_name_invalid
    FolderNameProblem.Reserved -> R.string.folders_name_reserved
    FolderNameProblem.TooLong -> R.string.folders_name_too_long
    FolderNameProblem.AlreadyExists -> R.string.folders_name_exists
}

@Composable
private fun DeleteDialog(folder: FolderCardUi, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_delete_title)) },
        text = { Text(stringResource(R.string.folders_delete_body, folder.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("folder-delete-confirm")) {
                Text(stringResource(R.string.folders_delete_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}

@Composable
private fun PropertiesDialog(folder: FolderCardUi, crumbs: List<CrumbUi>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val volume = crumbs.firstOrNull()
    val path = buildString {
        append(volume?.let { volumeName(it.volumeName, it.volumeLabel) } ?: folder.location.volumeName.orEmpty())
        folder.location.relativePath?.trim('/')?.takeIf { it.isNotEmpty() }?.let { append('/').append(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(folder.name) },
        text = {
            Column(Modifier.testTag("folder-properties"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PropertyRow(stringResource(R.string.folders_prop_path), path)
                PropertyRow(
                    stringResource(R.string.folders_prop_items),
                    stringResource(R.string.folders_here_total, folder.directCount.toIntClamped(), folder.itemCount.toIntClamped()),
                )
                PropertyRow(stringResource(R.string.folders_prop_subfolders), folder.subfolderCount.toString())
                PropertyRow(stringResource(R.string.folders_prop_size), Formatter.formatFileSize(context, folder.sizeBytes))
                dateRangeText(folder.minSortMillis, folder.maxSortMillis)?.let {
                    PropertyRow(stringResource(R.string.folders_prop_dates), it)
                }
                PropertyRow(
                    stringResource(R.string.folders_prop_status),
                    stringResource(if (folder.isHidden) R.string.folders_prop_hidden else R.string.folders_prop_visible),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_close)) } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MediaTile(media: TimelineMedia, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val request = ThumbnailRequest(media.key, media.generationModified, TileThumbnailPx, TileThumbnailPx)
    val bitmap by produceState(loader?.cached(request), request, loader) {
        if (value == null && loader != null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val isVideo = media.kind == MediaKind.Video
    val description = mediaTileDescription(
        if (isVideo) videoDurationDescription(media.durationMillis) else stringResource(R.string.folders_photo),
        media.isFavorite,
        media.displayName,
        isVideo,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clearAndSetSemantics {
                contentDescription = description
                this.onClick { onClick(); true }
            }
            .testTag("folder-media-${media.key.volumeName}-${media.key.mediaStoreId}")
            .clickable(onClick = onClick),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (!isVideo) {
            AnimatedMediaTile(
                uri = mediaStoreImageUri(media.key.volumeName, media.key.mediaStoreId),
                displayName = media.displayName,
                sizePx = TileThumbnailPx,
            )
        }
        MediaTileBadges(
            isFavorite = media.isFavorite,
            displayName = media.displayName,
            isVideo = isVideo,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        if (isVideo) {
            VideoDurationBadge(media.durationMillis, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
    }
}

/** A hidden item: the system gallery excludes it, so the tile shows its name and opens it elsewhere. */
@Composable
private fun HiddenTile(item: HiddenItemUi, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = item.name
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("folder-hidden-item-${item.mediaStoreId}")
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (item.isVideo) GalleryIcons.Video else GalleryIcons.Image,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            item.name,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilesCard(files: FolderCardUi, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("folder-files-card"),
        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(GalleryIcons.Image, contentDescription = null, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.folders_files_in_folder), style = MaterialTheme.typography.titleMedium)
                Text(summaryText(files.itemCount, files.sizeBytes), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(GalleryIcons.ChevronForward, contentDescription = null)
        }
    }
}

@Composable
private fun VolumeCard(volume: VolumeCardUi, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("folder-volume-${volume.volumeName}"),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Cover(volume.cover, loader, Modifier.fillMaxWidth().aspectRatio(1.5f))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                volumeName(volume.volumeName, volume.label),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summaryText(volume.itemCount, volume.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val free = volume.freeBytes
            val total = volume.totalBytes
            if (free != null && total != null) {
                Text(
                    stringResource(
                        R.string.folders_storage_space,
                        Formatter.formatShortFileSize(context, free),
                        Formatter.formatShortFileSize(context, total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
