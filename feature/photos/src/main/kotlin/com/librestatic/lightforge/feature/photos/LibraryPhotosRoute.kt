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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    onCreate: () -> Unit = {},
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
    /** False when a navigation rail already offers Create, Updates and Settings. */
    showNavigationActions: Boolean = true,
    scrubberIndex: TimelineIndex? = null,
    onScrubberJump: (TimelineAnchor?) -> Unit = {},
    backgroundStatus: LibraryBackgroundStatus? = null,
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
    val wideToolbar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                GalleryExpressiveIconButton(onClick = onCreate) {
                    Icon(GalleryIcons.Plus, contentDescription = stringResource(R.string.photos_create))
                }
                UpdatesAction(
                    onClick = if (activeExportCount > 0) onOpenExportQueue else onOpenUpdates,
                    activeExportCount = activeExportCount,
                    activeExportProgress = activeExportProgress,
                    contentDescription = activeExportDescription
                        ?: stringResource(R.string.photos_updates),
                )
                GalleryExpressiveIconButton(onClick = onOpenSettings) {
                    Icon(
                        GalleryIcons.User,
                        contentDescription = stringResource(R.string.open_settings),
                    )
                }
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

        if (selectionMode) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.timeline_stack_selection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm))
            }
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
                )
            }
        }
    }
    }
}

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
        shape = RoundedCornerShape(20.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                ),
            )
            Text(
                highlight.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
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
            modifier = Modifier.padding(GallerySpacing.Xl),
        ) { Text(stringResource(R.string.grant_access_action)) }
    }
}
