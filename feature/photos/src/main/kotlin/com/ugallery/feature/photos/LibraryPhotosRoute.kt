package com.ugallery.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryExpressiveIconButton
import com.ugallery.core.designsystem.GalleryIndeterminateProgressIndicator
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.model.LibraryAccess
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader

enum class LibraryUiState { Starting, Indexing, Ready, PermissionRequired, Error }

@Composable
fun LibraryPhotosRoute(
    access: LibraryAccess,
    engineState: LibraryUiState,
    entries: LazyPagingItems<TimelineEntry>,
    thumbnailLoader: ThumbnailLoader?,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onMediaClick: (TimelineMedia) -> Unit = {},
    onMediaLongClick: (TimelineMedia) -> Unit = {},
    preferredColumns: Int? = null,
    cropThumbnails: Boolean = true,
    onDensityChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val densityState = rememberTimelineDensityState()
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.photos_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        GalleryIcons.Lock,
                        contentDescription = stringResource(R.string.library_local),
                        modifier = Modifier.padding(end = GallerySpacing.Xs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.library_local),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            GalleryExpressiveIconButton(onClick = {
                densityState.cycleDensity(
                    anchorIndex = densityState.anchorIndex,
                    anchorOffset = densityState.anchorOffset,
                )
            }) {
                Icon(
                    GalleryIcons.Grid,
                    contentDescription = stringResource(R.string.change_grid_density),
                )
            }
            GalleryExpressiveIconButton(onClick = onOpenSettings) {
                Icon(
                    GalleryIcons.Settings,
                    contentDescription = stringResource(R.string.open_settings),
                )
            }
            if (access.isLimited) Text(
                stringResource(R.string.limited_access_label),
                style = MaterialTheme.typography.labelMedium,
            )
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

        val pagingError = entries.loadState.refresh as? LoadState.Error
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
                GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                GalleryStateContent(
                    title = stringResource(R.string.library_loading_title),
                    body = stringResource(R.string.library_loading_body),
                    illustrationDescription = stringResource(R.string.library_loading_title),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            entries.itemCount == 0 && engineState == LibraryUiState.Ready -> EmptyLibrary(Modifier.fillMaxSize())
            else -> {
                if (engineState == LibraryUiState.Indexing) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                AdaptivePagedPhotosTimeline(
                    entries = entries,
                    thumbnailLoader = thumbnailLoader,
                    onMediaClick = onMediaClick,
                    onMediaLongClick = onMediaLongClick,
                    modifier = Modifier.fillMaxSize(),
                    densityState = densityState,
                    preferredColumns = preferredColumns,
                    cropThumbnails = cropThumbnails,
                    onDensityChange = onDensityChange,
                )
            }
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
