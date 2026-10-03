package com.librestatic.lightforge.feature.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.paging.compose.LazyPagingItems
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.AdaptiveMediaGrid
import com.librestatic.lightforge.core.designsystem.GalleryShapes
import com.librestatic.lightforge.core.designsystem.MediaSelectionAffordance
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.designsystem.MediaSelectionOverlay
import com.librestatic.lightforge.core.designsystem.MediaTileBadges
import com.librestatic.lightforge.core.designsystem.VideoDurationBadge
import com.librestatic.lightforge.core.designsystem.mediaTileDescription
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerScreen(
    viewModel: PickerViewModel,
    onCancel: () -> Unit,
    onPick: (List<PickerMedia>) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onUnlock: (title: String, subtitle: String) -> Unit,
) {
    val request = viewModel.request
    val settings by viewModel.settings.collectAsState()
    val access by viewModel.access.collectAsState()
    val unlocked by viewModel.unlocked.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val album by viewModel.album.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val multiple = request.allowMultiple
    val title = if (multiple && selection.isNotEmpty()) {
        pluralStringResource(R.plurals.picker_selected_count, selection.size, selection.size)
    } else {
        stringResource(request.titleRes())
    }
    val locked = settings?.security?.appLockEnabled == true && !unlocked
    val lockedTitle = stringResource(R.string.picker_locked_title)
    val lockedBody = stringResource(R.string.picker_locked_body)
    LaunchedEffect(locked) { if (locked) onUnlock(lockedTitle, lockedBody) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (multiple && selection.isNotEmpty()) {
                        GalleryExpressiveIconButton(onClick = viewModel::clearSelection) {
                            Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.picker_clear))
                        }
                    } else {
                        GalleryExpressiveIconButton(onClick = onCancel) {
                            Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.picker_cancel))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            if (multiple && selection.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(GallerySpacing.Lg),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        GalleryExpressiveButton(
                            onClick = { onPick(selection.values.toList()) },
                            modifier = Modifier.testTag("picker_confirm"),
                        ) {
                            Text(pluralStringResource(R.plurals.picker_add_count, selection.size, selection.size))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val fill = Modifier.fillMaxSize().padding(padding)
        val noAccess = access.images == GrantLevel.None && access.videos == GrantLevel.None
        when {
            request.isUnsatisfiable -> PickerMessage(
                title = stringResource(R.string.picker_unsupported_title),
                body = stringResource(R.string.picker_unsupported_body),
                modifier = fill,
            )
            settings == null -> PickerLoading(fill)
            locked -> PickerMessage(
                title = lockedTitle,
                body = lockedBody,
                modifier = fill,
                icon = { Icon(GalleryIcons.Lock, null, Modifier.size(36.dp)) },
            ) {
                GalleryExpressiveButton(onClick = { onUnlock(lockedTitle, lockedBody) }) {
                    Text(stringResource(R.string.picker_unlock))
                }
            }
            noAccess -> PickerMessage(
                title = stringResource(R.string.picker_permission_title),
                body = stringResource(R.string.picker_permission_body),
                modifier = fill,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GalleryExpressiveButton(onClick = onRequestAccess) {
                        Text(stringResource(R.string.picker_permission_allow))
                    }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.picker_permission_settings)) }
                }
            }
            else -> when (val state = catalog) {
                PickerCatalogState.Loading -> PickerLoading(fill)
                PickerCatalogState.Failed -> PickerMessage(
                    title = stringResource(R.string.picker_error_title),
                    body = stringResource(R.string.picker_error_body),
                    modifier = fill,
                    icon = { Icon(GalleryIcons.Warning, null, Modifier.size(36.dp)) },
                ) {
                    GalleryExpressiveButton(onClick = viewModel::refreshAccess) { Text(stringResource(R.string.picker_retry)) }
                }
                is PickerCatalogState.Ready -> Column(fill) {
                    if (access.isLimited) LimitedAccessBanner(onRequestAccess)
                    if (state.catalog.albums.size > 1) {
                        AlbumChips(state.catalog.albums, album, viewModel::selectAlbum)
                    }
                    PickerMediaGrid(
                        pages = viewModel.media.collectAsLazyPagingItems(),
                        loader = viewModel.thumbnails,
                        selection = selection.keys.toList(),
                        multiple = multiple,
                        onToggle = viewModel::toggle,
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

private fun PickRequest.titleRes(): Int = when {
    images.accepts && !videos.accepts -> if (allowMultiple) R.string.picker_title_photos else R.string.picker_title_photo
    videos.accepts && !images.accepts -> if (allowMultiple) R.string.picker_title_videos else R.string.picker_title_video
    else -> if (allowMultiple) R.string.picker_title_items else R.string.picker_title_item
}

/**
 * The pickable media, in the shared adaptive grid: edge margins, no clipped trailing column and
 * the same cell sizes as the gallery itself. Stateless so debug previews can feed it fake pages.
 */
@Composable
internal fun PickerMediaGrid(
    pages: LazyPagingItems<PickerMedia>,
    loader: ThumbnailLoader,
    selection: List<MediaKey>,
    multiple: Boolean,
    onToggle: (PickerMedia) -> Unit,
    onPick: (List<PickerMedia>) -> Unit,
) {
    val refresh = pages.loadState.refresh
    when {
        pages.itemCount == 0 && refresh is LoadState.Loading -> PickerLoading(Modifier.fillMaxSize())
        pages.itemCount == 0 && refresh is LoadState.Error -> PickerMessage(
            title = stringResource(R.string.picker_error_title),
            body = stringResource(R.string.picker_error_body),
            modifier = Modifier.fillMaxSize(),
        ) {
            GalleryExpressiveButton(onClick = pages::retry) { Text(stringResource(R.string.picker_retry)) }
        }
        pages.itemCount == 0 -> PickerMessage(
            title = stringResource(R.string.picker_empty_title),
            body = stringResource(R.string.picker_empty_body),
            modifier = Modifier.fillMaxSize(),
        )
        else -> {
            val density = LocalDensity.current.density
            AdaptiveMediaGrid(
                modifier = Modifier.fillMaxSize(),
                bottomPadding = GallerySpacing.Lg,
                gridModifier = Modifier.testTag("picker_grid"),
            ) { layout ->
                val sizePx = layout.cellSizePx(density)
                items(pages.itemCount, key = pages.itemKey { "${it.key.volumeName}:${it.key.mediaStoreId}" }) { index ->
                    val media = pages[index] ?: return@items
                    val order = if (multiple) selection.indexOf(media.key).takeIf { it >= 0 }?.plus(1) else null
                    PickerTile(
                        media = media,
                        loader = loader,
                        sizePx = sizePx,
                        multiple = multiple,
                        selected = order != null,
                        order = order,
                        onClick = { if (multiple) onToggle(media) else onPick(listOf(media)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerTile(
    media: PickerMedia,
    loader: ThumbnailLoader,
    sizePx: Int,
    multiple: Boolean,
    selected: Boolean,
    order: Int?,
    onClick: () -> Unit,
) {
    val request = ThumbnailRequest(media.key, media.generationModified, sizePx, sizePx)
    val bitmap by produceState(loader.cached(request), request, loader) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    val description = mediaTileDescription(
        stringResource(if (media.isVideo) R.string.picker_video else R.string.picker_photo),
        media.isFavorite,
        media.displayName,
        media.isVideo,
    )
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(GalleryShapes.Thumbnail)
            .testTag("picker_media_${media.key.volumeName}_${media.key.mediaStoreId}")
            .clearAndSetSemantics {
                contentDescription = description
                // Multiple selection is a set of toggles; a single pick is a plain button.
                if (multiple) this.selected = selected
                onClick { onClick(); true }
            }
            .clickable(onClick = onClick),
    ) {
        val loaded = bitmap
        if (loaded == null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        else Image(loaded.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        MediaTileBadges(
            isFavorite = media.isFavorite,
            displayName = media.displayName,
            isVideo = media.isVideo,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        if (media.isVideo) {
            VideoDurationBadge(media.durationMillis, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        // Multiple selection shows an empty check on every tile, so it reads as a checklist.
        MediaSelectionAffordance(visible = multiple && !selected)
        MediaSelectionOverlay(selected, order = order, shape = GalleryShapes.Thumbnail)
    }
}

@Composable
private fun AlbumChips(
    albums: List<PickerAlbum>,
    selected: PickerAlbumKey?,
    onSelect: (PickerAlbumKey?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        modifier = Modifier.testTag("picker_albums"),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.picker_all)) },
            )
        }
        items(albums, key = { "${it.volumeName}:${it.bucketId}" }) { album ->
            val key = PickerAlbumKey(album.volumeName, album.bucketId)
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(album.name ?: stringResource(R.string.picker_storage_root), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun LimitedAccessBanner(onManage: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
    ) {
        Row(
            Modifier.padding(start = GallerySpacing.Lg, end = GallerySpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.picker_limited_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = GallerySpacing.Md),
            )
            TextButton(
                onClick = onManage,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            ) { Text(stringResource(R.string.picker_limited_manage)) }
        }
    }
}

@Composable
private fun PickerLoading(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { GalleryLoadingIndicator() }
}

@Composable
private fun PickerMessage(
    title: String,
    body: String,
    modifier: Modifier,
    icon: @Composable () -> Unit = { Icon(GalleryIcons.PhotoLibrary, null, Modifier.size(36.dp)) },
    action: (@Composable () -> Unit)? = null,
) {
    GalleryStateContent(
        title = title,
        body = body,
        illustrationDescription = stringResource(R.string.picker_library),
        modifier = modifier,
        illustration = icon,
        action = action,
    )
}

