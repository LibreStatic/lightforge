package com.librestatic.lightforge.feature.folders

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

private const val PrimaryVolume = "external_primary"

/**
 * The device folder explorer: volumes, then folders with a breadcrumb. Stateless; the host owns the
 * [FolderLocation] and feeds the matching [state] (see [buildFolderExplorerUiState]).
 */
@Composable
fun FolderExplorerContent(
    state: FolderExplorerUiState,
    thumbnails: ThumbnailLoader?,
    onEnter: (FolderLocation) -> Unit,
    onOpenFiles: (AlbumSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        FolderExplorerUiState.Loading -> {
            val description = stringResource(R.string.folders_loading)
            Box(modifier.fillMaxSize().semantics { contentDescription = description }, Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        FolderExplorerUiState.Empty -> GalleryStateContent(
            title = stringResource(R.string.folders_empty_title),
            body = stringResource(R.string.folders_empty_body),
            illustrationDescription = stringResource(R.string.folders_empty_title),
            modifier = modifier.fillMaxSize(),
            heroIcon = GalleryIcons.Folder,
        )
        is FolderExplorerUiState.Volumes -> FolderGrid(modifier) {
            items(state.volumes, key = { it.volumeName }) { volume ->
                VolumeCard(volume, thumbnails) { onEnter(volume.location) }
            }
        }
        is FolderExplorerUiState.Folder -> Column(modifier.fillMaxSize()) {
            Breadcrumb(state.crumbs, onEnter)
            FolderGrid(Modifier.weight(1f)) {
                state.files?.let { files ->
                    item(key = "files", span = { GridItemSpan(maxLineSpan) }) {
                        FilesCard(files) { (files.action as? FolderAction.OpenFiles)?.let { onOpenFiles(it.album) } }
                    }
                }
                items(state.folders, key = { it.key }) { folder ->
                    FolderCard(folder, thumbnails) {
                        when (val action = folder.action) {
                            is FolderAction.Enter -> onEnter(action.location)
                            is FolderAction.OpenFiles -> onOpenFiles(action.album)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit,
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

@Composable
private fun volumeName(volumeName: String, label: String?): String =
    if (volumeName == PrimaryVolume) stringResource(R.string.folders_internal_storage) else label ?: volumeName

@Composable
private fun Breadcrumb(crumbs: List<CrumbUi>, onEnter: (FolderLocation) -> Unit) {
    val scroll = rememberScrollState()
    // Deep paths overflow: keep the current folder in view.
    LaunchedEffect(crumbs) { scroll.scrollTo(scroll.maxValue) }
    val pathLabel = stringResource(R.string.folders_path_label)
    val currentLabel = stringResource(R.string.folders_current_folder)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("folder-breadcrumb")
            .semantics {
                contentDescription = pathLabel
                collectionInfo = CollectionInfo(rowCount = 1, columnCount = crumbs.size)
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
private fun countText(count: Long) =
    pluralStringResource(R.plurals.folders_item_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

@Composable
private fun summaryText(count: Long, bytes: Long): String =
    stringResource(R.string.folders_card_summary, countText(count), Formatter.formatShortFileSize(LocalContext.current, bytes))

@Composable
private fun FolderCard(folder: FolderCardUi, loader: ThumbnailLoader?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("folder-card-${folder.key}"),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        Cover(folder.cover, loader, Modifier.fillMaxWidth().aspectRatio(1.5f))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                summaryText(folder.itemCount, folder.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.subfolderCount > 0) {
                Text(
                    pluralStringResource(R.plurals.folders_subfolder_count, folder.subfolderCount, folder.subfolderCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
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
