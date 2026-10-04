package com.librestatic.lightforge.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.galleryBottomContentPadding
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import com.librestatic.lightforge.core.designsystem.GalleryCircularProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryColorRoles
import com.librestatic.lightforge.core.designsystem.GalleryEmptyState
import com.librestatic.lightforge.core.designsystem.GalleryScrims
import com.librestatic.lightforge.core.designsystem.GalleryShapes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

data class PhotoHighlightUi(
    val id: String,
    val title: String,
    val cover: TimelineMedia,
    val onClick: () -> Unit,
)

enum class LibraryUiState { Starting, Indexing, Ready, PermissionRequired, Error }

@Composable
fun LibraryPhotosRoute(
    access: LibraryAccess,
    engineState: LibraryUiState,
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader?,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenDeviceFolders: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCreate: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    activeExportCount: Int = 0,
    activeExportProgress: Float? = null,
    activeExportDescription: String? = null,
    onOpenExportQueue: () -> Unit = {},
    highlights: List<PhotoHighlightUi> = emptyList(),
    onMediaClick: (TimelineMedia) -> Unit = {},
    isMediaSelected: (TimelineMedia) -> Boolean = { false },
    selectionOrder: (TimelineMedia) -> Int? = { null },
    onMediaSelectionChange: (TimelineMedia, Boolean) -> Unit = { _, _ -> },
    preferredColumns: Int? = null,
    cropThumbnails: Boolean = true,
    onDensityChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    focusReturn: TimelineFocusReturn? = null,
    onFocusReturnConsumed: (TimelineFocusReturn) -> Unit = {},
    /** False when a navigation rail already offers Updates and Settings. */
    showNavigationActions: Boolean = true,
    scrubberIndex: TimelineIndex? = null,
    onScrubberJump: (TimelineAnchor?) -> Unit = {},
    backgroundStatus: LibraryBackgroundStatus? = null,
    filter: PhotosFilter = PhotosFilter.All,
    /** Null hides the filter row. */
    onFilterChange: ((PhotosFilter) -> Unit)? = null,
    sort: PhotosSort = PhotosSort.Newest,
    /** Null hides the sort menu. */
    onSortChange: ((PhotosSort) -> Unit)? = null,
    /** Ctrl+A and the tile context menu's "Select all"; null hides both. */
    onSelectAll: (() -> Unit)? = null,
    /** Esc while selecting. */
    onClearSelection: (() -> Unit)? = null,
) {
    val densityState = rememberTimelineDensityState()
    val pagingError = entries.loadState.refresh as? LoadState.Error
    // Announce the state actually displayed, not Ready while paging/thumbnail startup fails.
    val presentationState = when {
        engineState == LibraryUiState.PermissionRequired -> LibraryUiState.PermissionRequired
        engineState == LibraryUiState.Error || pagingError != null -> LibraryUiState.Error
        thumbnailLoader == null || engineState == LibraryUiState.Starting ||
            entries.loadState.refresh is LoadState.Loading && entries.itemCount == 0 -> LibraryUiState.Starting
        else -> engineState
    }
    // The route's own width, not the window's: a rail or a side panel narrows it.
    BoxWithConstraints(modifier.fillMaxSize()) {
    val wideToolbar = maxWidth >= 600.dp
    val gridEdge = com.librestatic.lightforge.core.designsystem.AdaptiveMediaGridDefaults.edgePadding(maxWidth)
    val page = GalleryColorRoles.current.page
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides page.content,
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(page.container),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = onOpenDeviceFolders,
                label = { Text(stringResource(R.string.library_local), maxLines = 1) },
                leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null, Modifier.size(18.dp)) },
                modifier = Modifier.semantics { heading() },
            )
            if (wideToolbar) {
                // Wide windows fold the index status into the toolbar row instead of a second banner.
                // The Box keeps the weight even while the status is silent, so the actions stay at the end.
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).padding(horizontal = GallerySpacing.Md)) {
                    LibraryIndexStatus(state = presentationState, background = backgroundStatus)
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            if (showNavigationActions) {
                // Create lives in the navigation, so the header keeps only Updates and an overflow.
                UpdatesAction(
                    onClick = if (activeExportCount > 0) onOpenExportQueue else onOpenUpdates,
                    activeExportCount = activeExportCount,
                    activeExportProgress = activeExportProgress,
                    contentDescription = activeExportDescription
                        ?: stringResource(R.string.photos_updates),
                )
                PhotosOverflowMenu(onOpenSettings = onOpenSettings, onOpenDeviceFolders = onOpenDeviceFolders)
            }
            if (access.isLimited) Text(
                stringResource(R.string.limited_access_label),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (!wideToolbar) LibraryIndexStatus(
            state = presentationState,
            background = backgroundStatus,
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        )
        if (highlights.isNotEmpty() && thumbnailLoader != null) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = GallerySpacing.Lg),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(GallerySpacing.Sm),
                modifier = Modifier.fillMaxWidth().padding(bottom = GallerySpacing.Md),
            ) {
                items(highlights, key = PhotoHighlightUi::id) { highlight ->
                    HighlightCard(highlight, thumbnailLoader)
                }
            }
        }
        if (access.isLimited) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.limited_access_body), Modifier.weight(1f))
                GalleryExpressiveButton(onClick = onRequestAccess) { Text(stringResource(R.string.manage_access_action)) }
            }
        }

        // The stack hint shows once per session when a selection starts, then gets out of the way.
        var selectionHintShown by rememberSaveable { mutableStateOf(false) }
        var selectionHintVisible by remember { mutableStateOf(false) }
        LaunchedEffect(selectionMode) {
            if (selectionMode && !selectionHintShown) {
                selectionHintShown = true
                selectionHintVisible = true
                delay(SelectionHintMillis)
            }
            selectionHintVisible = false
        }
        AnimatedVisibility(selectionHintVisible) {
            val active = GalleryColorRoles.current.active
            androidx.compose.material3.Surface(
                color = active.container,
                contentColor = active.content,
                shape = GalleryShapes.Plate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Xs),
            ) {
                Text(stringResource(R.string.timeline_stack_selection_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm))
            }
        }
        val showFilterBar = onFilterChange != null && thumbnailLoader != null &&
            presentationState != LibraryUiState.PermissionRequired &&
            presentationState != LibraryUiState.Error &&
            presentationState != LibraryUiState.Starting
        if (showFilterBar) {
            PhotosFilterBar(
                filter = filter,
                onFilterChange = requireNotNull(onFilterChange),
                sort = sort,
                onSortChange = onSortChange,
                totalCount = scrubberIndex?.total,
                wide = wideToolbar,
                // Wide windows line the row up with the grid's edge (the shell gutter).
                modifier = Modifier.padding(
                    start = if (wideToolbar) gridEdge else GallerySpacing.Lg,
                    end = if (wideToolbar) gridEdge else GallerySpacing.Xs,
                    bottom = GallerySpacing.Xs,
                ),
            )
        }
        when {
            engineState == LibraryUiState.PermissionRequired -> PermissionRequired(onRequestAccess)
            engineState == LibraryUiState.Error || pagingError != null -> GalleryStateContent(
                title = stringResource(R.string.library_error_title),
                body = stringResource(R.string.library_error_body),
                illustrationDescription = stringResource(R.string.library_error_title),
                modifier = Modifier.fillMaxSize(),
            )
            thumbnailLoader == null ||
                engineState == LibraryUiState.Starting ||
                entries.loadState.refresh is LoadState.Loading && entries.itemCount == 0 -> {
                GalleryStateContent(
                    title = stringResource(R.string.library_loading_title),
                    body = stringResource(R.string.library_loading_body),
                    illustrationDescription = stringResource(R.string.library_loading_title),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            entries.itemCount == 0 && engineState == LibraryUiState.Ready && filter != PhotosFilter.All &&
                onFilterChange != null -> GalleryEmptyState(
                title = stringResource(R.string.photos_filter_empty_title),
                body = stringResource(R.string.photos_filter_empty_body),
                actionLabel = stringResource(R.string.photos_filter_empty_action),
                onAction = { onFilterChange(PhotosFilter.All) },
                modifier = Modifier.fillMaxSize(),
            )
            entries.itemCount == 0 && engineState == LibraryUiState.Ready -> EmptyLibrary(Modifier.fillMaxSize())
            else -> {
                AdaptivePagedPhotosTimeline(
                    entries = entries,
                    focusReturn = focusReturn,
                    onFocusReturnConsumed = onFocusReturnConsumed,
                    thumbnailLoader = thumbnailLoader,
                    onMediaClick = onMediaClick,
                    isMediaSelected = isMediaSelected,
                    selectionOrder = selectionOrder,
                    onMediaSelectionChange = onMediaSelectionChange,
                    modifier = Modifier.fillMaxSize(),
                    densityState = densityState,
                    preferredColumns = preferredColumns,
                    cropThumbnails = cropThumbnails,
                    onDensityChange = onDensityChange,
                    scrubberIndex = scrubberIndex,
                    onScrubberJump = onScrubberJump,
                    selectionMode = selectionMode,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                )
            }
        }
    }
    }
    }
}

private const val SelectionHintMillis = 5_000L

@Composable
private fun UpdatesAction(
    onClick: () -> Unit,
    activeExportCount: Int,
    activeExportProgress: Float?,
    contentDescription: String,
) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        GalleryExpressiveIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
        ) {
            if (activeExportCount <= 0) {
                Icon(GalleryIcons.Notifications, contentDescription = contentDescription)
            } else {
                UpdatesIndicator(
                    activeExportProgress = activeExportProgress,
                    contentDescription = contentDescription,
                )
            }
        }
        if (activeExportCount > 0) {
            Badge(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 2.dp),
            ) {
                Text(if (activeExportCount > 99) "99+" else activeExportCount.toString())
            }
        }
    }
}

@Composable
private fun UpdatesIndicator(
    activeExportProgress: Float?,
    contentDescription: String,
) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (activeExportProgress == null) {
            GalleryCircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
            )
        } else {
            GalleryCircularProgressIndicator(
                progress = { activeExportProgress.coerceIn(0f, 1f) },
                modifier = Modifier.size(34.dp),
                strokeWidth = 3.dp,
            )
        }
        Icon(
            GalleryIcons.Notifications,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun HighlightCard(highlight: PhotoHighlightUi, loader: ThumbnailLoader) {
    val request = remember(highlight.cover) {
        ThumbnailRequest(highlight.cover.key, highlight.cover.generationModified, 360, 420)
    }
    val bitmap by produceState(loader.cached(request), request, loader) {
        if (value == null) value = runCatching { loader.load(request) }.getOrNull()
    }
    Card(
        Modifier.width(156.dp).height(176.dp).clickable(onClick = highlight.onClick),
        shape = GalleryShapes.Card,
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clip(GalleryShapes.Card)) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(GalleryScrims.bottom()),
            )
            Text(
                highlight.title,
                style = MaterialTheme.typography.titleMedium,
                color = GalleryScrims.Content,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

@Composable
private fun PermissionRequired(onRequestAccess: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        GalleryStateContent(
            title = stringResource(R.string.permission_title),
            body = stringResource(R.string.permission_body),
            illustrationDescription = stringResource(R.string.permission_title),
            modifier = Modifier.weight(1f),
        )
        GalleryExpressiveButton(
            onClick = onRequestAccess,
            // The floating navigation overlays the content: clear it or the button is unreachable.
            modifier = Modifier.padding(
                start = GallerySpacing.Xl,
                top = GallerySpacing.Xl,
                end = GallerySpacing.Xl,
                bottom = galleryBottomContentPadding(GallerySpacing.Xl),
            ),
        ) { Text(stringResource(R.string.grant_access_action)) }
    }
}

@Composable
private fun PhotosOverflowMenu(onOpenSettings: () -> Unit, onOpenDeviceFolders: () -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        GalleryExpressiveIconButton(onClick = { expanded = true }) {
            Icon(GalleryIcons.More, contentDescription = stringResource(R.string.photos_more_options))
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.photos_menu_device_folders)) },
                leadingIcon = { Icon(GalleryIcons.Folder, contentDescription = null) },
                onClick = { expanded = false; onOpenDeviceFolders() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.photos_menu_settings)) },
                leadingIcon = { Icon(GalleryIcons.Settings, contentDescription = null) },
                onClick = { expanded = false; onOpenSettings() },
            )
        }
    }
}
