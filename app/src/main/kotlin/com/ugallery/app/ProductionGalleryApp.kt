package com.ugallery.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.paging.compose.collectAsLazyPagingItems
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.mediastore.MediaAction
import com.ugallery.core.mediastore.MediaActionPhase
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.feature.album.AlbumContent
import com.ugallery.feature.collections.CollectionsContent
import com.ugallery.feature.details.DetailsContent
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.photos.LibraryPhotosRoute
import com.ugallery.feature.trash.TrashContent
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.feature.viewer.ViewerContent
import com.ugallery.feature.search.SearchContent

private enum class RootTab { Photos, Collections, Search }
private enum class SurfaceRoute { Root, Album, Viewer, Trash }

@Composable
internal fun ProductionGalleryApp(
    viewModel: GalleryViewModel,
    permissions: PermissionCoordinator,
) {
    val context = LocalContext.current
    val access by viewModel.access.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val thumbnails by viewModel.thumbnailLoader.collectAsState()
    val currentMedia by viewModel.currentMedia.collectAsState()
    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionCount by viewModel.selectionCount.collectAsState()
    val photoState by viewModel.photoState.collectAsState()
    val trashCount by viewModel.trashCount.collectAsState()
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val search by viewModel.search.collectAsState()
    val searchIndexReady by viewModel.searchIndexReady.collectAsState()
    val detectedContentEnabled by viewModel.detectedContentEnabled.collectAsState()
    val actionState by viewModel.systemAction.collectAsState()
    val external by viewModel.externalMedia.collectAsState()
    val externalPhoto by viewModel.externalPhotoState.collectAsState()
    val timeline = viewModel.timeline.collectAsLazyPagingItems()
    val physicalAlbums = viewModel.physicalAlbums.collectAsLazyPagingItems()
    val virtualAlbums = viewModel.virtualAlbums.collectAsLazyPagingItems()
    val albumItems = viewModel.albumMedia.collectAsLazyPagingItems()
    val trashItems = viewModel.trash.collectAsLazyPagingItems()
    var rootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var route by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    var filter by rememberSaveable { mutableStateOf(AlbumMediaFilter.All) }
    var sort by rememberSaveable { mutableStateOf(AlbumSort.NewestFirst) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCreateAlbum by rememberSaveable { mutableStateOf(false) }
    var showAddToAlbum by rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashConfirmation by rememberSaveable { mutableStateOf(false) }
    var newAlbumName by rememberSaveable { mutableStateOf("") }
    var pendingRequestId by rememberSaveable { mutableStateOf<Long?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.onForeground() }
    val actionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pendingRequestId?.let { viewModel.onSystemActionResult(it, result.resultCode == Activity.RESULT_OK) }
        pendingRequestId = null
    }
    LaunchedEffect(Unit) {
        viewModel.actionLaunches.collect { launch ->
            pendingRequestId = launch.requestId
            actionLauncher.launch(IntentSenderRequest.Builder(launch.intentSender).build())
        }
    }
    LaunchedEffect(Unit) { viewModel.resumePendingSystemAction() }
    LaunchedEffect(Unit) {
        viewModel.externalSaved.collect { uri ->
            (context as? Activity)?.apply {
                setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                finish()
            }
        }
    }

    if (external != null) {
        ExternalViewer(
            requireNotNull(external),
            externalPhoto,
            onClose = {
                viewModel.clearExternal()
                (context as? Activity)?.finish()
            },
            onSaveCopy = viewModel::saveExternalCopy,
        )
        return
    }

    fun requestAccess() {
        val plan = if (Build.VERSION.SDK_INT >= 34 && access.isLimited) {
            permissions.reselectionRequest()
        } else permissions.initialMediaRequest()
        permissionLauncher.launch(plan.permissions.toTypedArray())
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val content: @Composable () -> Unit = {
            when (route) {
                SurfaceRoute.Root -> when (rootTab) {
                    RootTab.Photos -> LibraryPhotosRoute(
                        access = access,
                        engineState = engineState.toUiState(),
                        entries = timeline,
                        thumbnailLoader = thumbnails,
                        onRequestAccess = ::requestAccess,
                        onMediaClick = { media ->
                            if (selectionCount > 0) viewModel.toggleSelection(media)
                            else { viewModel.openMedia(media); route = SurfaceRoute.Viewer }
                        },
                        onMediaLongClick = viewModel::toggleSelection,
                    )
                    RootTab.Collections -> CollectionsContent(
                        physicalAlbums,
                        virtualAlbums,
                        trashCount,
                        onAlbumClick = { album ->
                            viewModel.selectAlbum(album, filter, sort)
                            route = SurfaceRoute.Album
                        },
                        onCreateAlbum = { showCreateAlbum = true },
                        onTrashClick = { route = SurfaceRoute.Trash },
                    )
                    RootTab.Search -> SearchContent(
                        query = search.query,
                        hits = search.hits,
                        loading = search.loading,
                        terminal = search.terminal,
                        partialIndex = !searchIndexReady,
                        error = search.error,
                        detectedContentEnabled = detectedContentEnabled,
                        thumbnailLoader = thumbnails,
                        onQueryChange = viewModel::setSearchQuery,
                        onSearch = { viewModel.search() },
                        onPresetSearch = viewModel::search,
                        onLoadMore = viewModel::loadMoreSearch,
                        onHit = { hit -> viewModel.openSearchHit(hit); route = SurfaceRoute.Viewer },
                        onEnableDetectedContent = viewModel::enableDetectedContent,
                        onPauseDetectedContent = viewModel::pauseDetectedContent,
                        onDeleteDetectedContent = viewModel::deleteDetectedContent,
                    )
                }
                SurfaceRoute.Album -> selectedAlbum?.let { album ->
                    thumbnails?.let { loader ->
                        AlbumContent(
                            album,
                            albumItems,
                            loader,
                            filter,
                            sort,
                            selection,
                            album.itemCount,
                            onFilterChange = { filter = it; viewModel.selectAlbum(album, filter, sort) },
                            onSortChange = { sort = it; viewModel.selectAlbum(album, filter, sort) },
                            onMediaClick = { media ->
                                if (selectionCount > 0) viewModel.toggleSelection(media)
                                else { viewModel.openMedia(media); route = SurfaceRoute.Viewer }
                            },
                            onMediaLongClick = viewModel::toggleSelection,
                        )
                    }
                }
                SurfaceRoute.Viewer -> currentMedia?.let { media ->
                    ViewerRoute(
                        media,
                        photoState,
                        viewModel,
                        showDetails,
                        expanded,
                        onShowDetails = { showDetails = true; viewModel.loadDetails() },
                        onHideDetails = { showDetails = false },
                        onBack = { route = SurfaceRoute.Root },
                    )
                }
                SurfaceRoute.Trash -> TrashContent(
                    visibleItems = trashItems.itemSnapshotList.items,
                    totalCount = trashCount,
                    onRestore = { media -> viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Trash(false)) },
                    onDeletePermanently = { media -> viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Delete) },
                    onEmptyTrash = { showEmptyTrashConfirmation = true },
                )
            }
        }
        val controls: @Composable () -> Unit = {
            if (selectionCount == 0L && (
                    route == SurfaceRoute.Album ||
                        route == SurfaceRoute.Root && rootTab == RootTab.Photos
                )
            ) {
                TextButton(onClick = {
                    if (route == SurfaceRoute.Album && selectedAlbum != null) {
                        viewModel.selectAllAlbum(requireNotNull(selectedAlbum), filter, sort)
                    } else viewModel.selectAllTimeline()
                }) { Text(stringResource(R.string.selection_select_all)) }
            }
            if (selectionCount > 0) {
                SelectionActions(
                    count = selectionCount,
                    canShare = selection is SelectionSpec.Explicit && selectionCount <= 500,
                    onSelectAll = {
                        if (route == SurfaceRoute.Album && selectedAlbum != null) {
                            viewModel.selectAllAlbum(requireNotNull(selectedAlbum), filter, sort)
                        } else viewModel.selectAllTimeline()
                    },
                    onFavorite = { viewModel.beginSelectionSystemAction(MediaAction.Favorite(true)) },
                    onTrash = { viewModel.beginSelectionSystemAction(MediaAction.Trash(true)) },
                    onDelete = { viewModel.beginSelectionSystemAction(MediaAction.Delete) },
                    onAddToAlbum = { showAddToAlbum = true },
                    onShare = {
                        viewModel.selectionShareIntent()?.let {
                            context.startActivity(Intent.createChooser(it, null))
                        }
                    },
                    onClear = viewModel::clearSelection,
                )
            }
            actionState?.let { state ->
                if (state.phase is MediaActionPhase.Cancelled) {
                    Button(onClick = viewModel::retrySystemAction) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        Scaffold(
            bottomBar = {
                if (!expanded && route == SurfaceRoute.Root) RootNavigationBar(rootTab) { rootTab = it }
            },
        ) { padding ->
            if (expanded && route == SurfaceRoute.Root) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    RootNavigationRail(rootTab) { rootTab = it }
                    Column(Modifier.weight(1f)) {
                        controls()
                        content()
                    }
                }
            } else Column(Modifier.fillMaxSize().padding(padding)) {
                if (route != SurfaceRoute.Root) Button(onClick = { route = SurfaceRoute.Root }) {
                    Text(stringResource(R.string.nav_back))
                }
                controls()
                content()
            }
        }
    }

    if (showCreateAlbum) {
        AlbumNameDialog(
            value = newAlbumName,
            onValue = { newAlbumName = it },
            onDismiss = { showCreateAlbum = false },
            onConfirm = {
                viewModel.createVirtualAlbum(newAlbumName)
                newAlbumName = ""
                showCreateAlbum = false
            },
        )
    }
    if (showAddToAlbum) {
        ChooseAlbumDialog(
            albums = virtualAlbums.itemSnapshotList.items,
            onDismiss = { showAddToAlbum = false },
            onSelect = { album ->
                (album.key as? com.ugallery.core.model.AlbumKey.Virtual)?.let {
                    viewModel.addSelectionToVirtualAlbum(it.albumId)
                }
                showAddToAlbum = false
            },
        )
    }
    if (showEmptyTrashConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirmation = false },
            title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
            text = { Text(stringResource(R.string.trash_empty_confirm_body, trashCount)) },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyTrashConfirmation = false
                    viewModel.emptyTrash()
                }) { Text(stringResource(R.string.trash_empty_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirmation = false }) {
                    Text(stringResource(R.string.album_cancel))
                }
            },
        )
    }
}

@Composable
private fun SelectionActions(
    count: Long,
    canShare: Boolean,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onAddToAlbum: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.selection_count, count), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.selection_select_all)) }
            TextButton(onClick = onFavorite) { Text(stringResource(R.string.selection_favorite)) }
            TextButton(onClick = onTrash) { Text(stringResource(R.string.selection_trash)) }
            TextButton(onClick = onAddToAlbum) { Text(stringResource(R.string.selection_add_album)) }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canShare) TextButton(onClick = onShare) { Text(stringResource(R.string.selection_share)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.selection_delete)) }
            TextButton(onClick = onClear) { Text(stringResource(R.string.selection_clear)) }
        }
    }
}

@Composable
private fun ChooseAlbumDialog(
    albums: List<com.ugallery.core.model.AlbumSummary>,
    onDismiss: () -> Unit,
    onSelect: (com.ugallery.core.model.AlbumSummary) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.selection_choose_album)) },
        text = {
            Column {
                if (albums.isEmpty()) Text(stringResource(R.string.selection_no_albums))
                albums.forEach { album ->
                    TextButton(onClick = { onSelect(album) }) {
                        Text(album.name ?: stringResource(com.ugallery.feature.album.R.string.album_untitled))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) } },
    )
}

@Composable
private fun ExternalViewer(
    media: GalleryViewModel.ExternalMedia,
    photo: com.ugallery.feature.viewer.PhotoLoadState?,
    onClose: () -> Unit,
    onSaveCopy: () -> Unit,
) {
    val context = LocalContext.current
    if (!media.available) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.external_grant_expired), color = MaterialTheme.colorScheme.error)
            Button(onClick = onClose) { Text(stringResource(R.string.details_close)) }
        }
        return
    }
    val video = if (media.kind == MediaKind.Video) remember(media.uri) {
        VideoViewerController(context).also { it.select(media.uri) }
    } else null
    val videoState = video?.state?.collectAsState()
    DisposableEffect(video) { onDispose { video?.close() } }
    Column(Modifier.fillMaxSize()) {
        Surface(Modifier.weight(1f).fillMaxSize()) {
            when {
                video != null -> AndroidView(
                    factory = { android.view.SurfaceView(it).also(video::attachSurface) },
                    modifier = Modifier.fillMaxSize(),
                )
                photo is com.ugallery.feature.viewer.PhotoLoadState.Ready -> AndroidView(
                    factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { image -> image.setImageDrawable(photo.drawable) },
                    modifier = Modifier.fillMaxSize(),
                )
                photo is com.ugallery.feature.viewer.PhotoLoadState.Error -> Text(
                    stringResource(R.string.external_unavailable),
                    Modifier.padding(24.dp),
                )
                else -> androidx.compose.material3.CircularProgressIndicator(Modifier.padding(24.dp))
            }
        }
        Row(Modifier.padding(12.dp)) {
            Button(onClick = onClose) { Text(stringResource(R.string.details_close)) }
            if (video != null) {
                val playing = (videoState?.value as? com.ugallery.feature.viewer.VideoViewerState.Ready)?.isPlaying == true
                Button(onClick = { if (playing) video.pause() else video.play() }) {
                    Text(stringResource(if (playing) R.string.external_pause else R.string.external_play))
                }
            }
            if (media.editMode) Button(onClick = onSaveCopy) { Text(stringResource(R.string.external_save_copy)) }
        }
    }
    DisposableEffect(photo) {
        val animated = (photo as? com.ugallery.feature.viewer.PhotoLoadState.Ready)?.drawable as? AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }
}

@Composable
private fun ViewerRoute(
    media: TimelineMedia,
    photoState: com.ugallery.feature.viewer.PhotoLoadState?,
    viewModel: GalleryViewModel,
    showDetails: Boolean,
    expanded: Boolean,
    onShowDetails: () -> Unit,
    onHideDetails: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val detectedText by viewModel.detectedText.collectAsState()
    val videoController = if (media.kind == MediaKind.Video) remember(media.key) {
        VideoViewerController(context).also { it.select(viewModel.mediaUri(media)) }
    } else null
    DisposableEffect(videoController) { onDispose { videoController?.close() } }
    val viewer: @Composable () -> Unit = {
        ViewerContent(
            media,
            photoState,
            videoController,
            media.isFavorite,
            onToggleFavorite = { viewModel.beginSystemAction(media, MediaAction.Favorite(!media.isFavorite)) },
            onShare = {
                context.startActivity(Intent.createChooser(viewModel.originalShareIntent(media), null))
            },
            onDetails = onShowDetails,
            onTrash = { viewModel.beginSystemAction(media, MediaAction.Trash(true)) },
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (expanded && showDetails && cheap != null) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.62f)) { viewer() }
            Surface(Modifier.weight(0.38f)) {
                Column {
                    TextButton(onClick = onHideDetails) { Text(stringResource(R.string.details_close)) }
                    DetailsContent(requireNotNull(cheap), exif, false, viewModel::loadDetails, detectedText)
                }
            }
        }
    } else {
        viewer()
        if (showDetails && cheap != null) Dialog(onDismissRequest = onHideDetails) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column {
                    TextButton(onClick = onHideDetails) { Text(stringResource(R.string.details_close)) }
                    DetailsContent(requireNotNull(cheap), exif, false, viewModel::loadDetails, detectedText)
                }
            }
        }
    }
}

@Composable private fun RootNavigationBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar { RootTab.entries.forEach { tab ->
        NavigationBarItem(selected == tab, { onSelect(tab) }, icon = { Text(if (tab == RootTab.Photos) "●" else "■") }, label = { Text(stringResource(tab.label())) })
    } }
}

@Composable private fun RootNavigationRail(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationRail { RootTab.entries.forEach { tab ->
        NavigationRailItem(selected == tab, { onSelect(tab) }, icon = { Text(if (tab == RootTab.Photos) "●" else "■") }, label = { Text(stringResource(tab.label())) })
    } }
}

private fun RootTab.label() = when (this) {
    RootTab.Photos -> R.string.nav_photos
    RootTab.Collections -> R.string.nav_collections
    RootTab.Search -> R.string.nav_search
}

@Composable
private fun AlbumNameDialog(value: String, onValue: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_create_title)) },
        text = { androidx.compose.material3.OutlinedTextField(value, onValue, label = { Text(stringResource(R.string.album_name)) }) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(stringResource(R.string.album_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) } },
    )
}
