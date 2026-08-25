package com.ugallery.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.mediastore.MediaAction
import com.ugallery.core.mediastore.MediaActionPhase
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.mediastore.ScopedMediaOperations
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryExpressiveIconButton
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryIndeterminateProgressIndicator
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryAnimatedContent
import com.ugallery.core.designsystem.GalleryAnimatedVisibility
import com.ugallery.core.designsystem.GalleryNavigationType
import com.ugallery.core.designsystem.GalleryAdaptiveLayoutInfo
import com.ugallery.core.designsystem.GalleryFoldInfo
import com.ugallery.core.designsystem.GalleryFoldOrientation
import com.ugallery.core.designsystem.GalleryMotionEdge
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.GalleryStateContent
import com.ugallery.core.designsystem.galleryAdaptiveLayoutInfo
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.GrantLevel
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.ml.LocalAnalysisOnboardingDecision
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.core.search.SearchConcept
import com.ugallery.core.search.SearchVocabulary
import com.ugallery.feature.album.AlbumContent
import com.ugallery.feature.collections.CollectionsContent
import com.ugallery.feature.collections.MomentContent
import com.ugallery.feature.collections.PeopleContent
import com.ugallery.feature.collections.PeopleUiState
import com.ugallery.feature.collections.formatMomentDateRange
import com.ugallery.feature.details.DetailsContent
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.photos.LibraryPhotosRoute
import com.ugallery.feature.photos.AdaptivePagedPhotosTimeline
import com.ugallery.feature.trash.TrashContent
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.feature.viewer.ViewerContent
import com.ugallery.feature.photoeditor.PhotoEditorContent
import com.ugallery.feature.videoeditor.VideoEditorContent
import com.ugallery.feature.search.SearchContent
import com.ugallery.feature.settings.AnalysisStatus
import com.ugallery.feature.settings.FaceAnalysisUiState
import com.ugallery.feature.settings.RecognitionSettingsContent
import com.ugallery.feature.settings.SemanticModelCompatibilityUi
import com.ugallery.feature.settings.SemanticModelSettingsItemUi
import com.ugallery.feature.settings.SemanticModelSettingsUiState
import com.ugallery.feature.privatealbum.PrivateAlbumContent
import com.ugallery.feature.privatealbum.PrivateAlbumRepository
import com.ugallery.feature.privatealbum.PrivateAlbumDatabase
import com.ugallery.feature.privatealbum.BiometricGate
import com.ugallery.feature.collage.CollageTemplate
import com.ugallery.feature.collage.CollageTemplates
import com.ugallery.feature.collage.CollageTemplatePicker
import com.ugallery.feature.motionphotos.MotionPhotoParser
import com.ugallery.feature.places.OfflineGazetteer
import com.ugallery.feature.places.BundledGazetteer
import com.ugallery.feature.subjectclip.SubjectClipper
import com.ugallery.feature.objecteraser.ObjectEraser

internal enum class RootTab { Photos, Collections, Search }
internal enum class SurfaceRoute { Root, Album, Viewer, PhotoEditor, VideoEditor, Trash, Settings, Moment, People, PrivateAlbum, PrivateAlbumPicker, Collage }
private data class PrivateImportProgress(val completed: Int, val total: Int)
private data class PrivateImportOutcome(val successful: List<TimelineMedia>, val total: Int)
internal data class ScreenMotionKey(
    val route: SurfaceRoute,
    val rootTab: RootTab,
    val saveableStateKey: String? = null,
)

private sealed interface ViewerReturnDestination {
    data class Root(val tab: RootTab) : ViewerReturnDestination
    data class Album(val key: AlbumKey) : ViewerReturnDestination
}

private val ViewerReturnDestinationSaver = listSaver<ViewerReturnDestination?, String>(
    save = { destination ->
        when (destination) {
            null -> emptyList()
            is ViewerReturnDestination.Root -> listOf("root", destination.tab.name)
            is ViewerReturnDestination.Album -> when (val key = destination.key) {
                is AlbumKey.Physical -> listOf("physical", key.volumeName, key.bucketId.toString())
                is AlbumKey.Virtual -> listOf("virtual", key.albumId.toString())
            }
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "root" -> saved.getOrNull(1)?.let { tabName ->
                runCatching { ViewerReturnDestination.Root(RootTab.valueOf(tabName)) }.getOrNull()
            }
            "physical" -> saved.getOrNull(1)?.let { volumeName ->
                saved.getOrNull(2)?.toLongOrNull()?.let { bucketId ->
                    ViewerReturnDestination.Album(AlbumKey.Physical(volumeName, bucketId))
                }
            }
            "virtual" -> saved.getOrNull(1)?.toLongOrNull()?.let { albumId ->
                ViewerReturnDestination.Album(AlbumKey.Virtual(albumId))
            }
            else -> null
        }
    },
)

private fun rootStateKey(tab: RootTab) = when (tab) {
    RootTab.Photos -> "root:photos"
    RootTab.Search -> "root:search"
    RootTab.Collections -> "root:collections"
}

private fun albumStateKey(key: AlbumKey) = when (key) {
    is AlbumKey.Physical -> "album:physical:${key.volumeName}:${key.bucketId}"
    is AlbumKey.Virtual -> "album:virtual:${key.albumId}"
}

private fun surfaceStateKey(
    route: SurfaceRoute,
    rootTab: RootTab,
    selectedAlbum: AlbumSummary?,
): String? = when (route) {
    SurfaceRoute.Root -> rootStateKey(rootTab)
    SurfaceRoute.Album -> selectedAlbum?.key?.let(::albumStateKey)
    else -> null
}

@Composable
internal fun ProductionGalleryApp(
    viewModel: GalleryViewModel,
    permissions: PermissionCoordinator,
) {
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val voiceSearchUnavailable = stringResource(com.ugallery.feature.search.R.string.search_voice_unavailable)
    val privateBiometricUnavailable = stringResource(com.ugallery.feature.privatealbum.R.string.private_biometric_unavailable)
    val privateLockedTitle = stringResource(com.ugallery.feature.privatealbum.R.string.private_locked)
    val privateUnlockSubtitle = stringResource(com.ugallery.feature.privatealbum.R.string.private_unlock_body)
    val privateBiometricFailed = stringResource(com.ugallery.feature.privatealbum.R.string.private_biometric_failed)
    val privateExportFailed = stringResource(com.ugallery.feature.privatealbum.R.string.private_export_failed)
    val appLockTitle = stringResource(R.string.app_lock_title)
    val appLockBody = stringResource(R.string.app_lock_body)
    val destructiveAuthTitle = stringResource(R.string.destructive_auth_title)
    val destructiveAuthBody = stringResource(R.string.destructive_auth_body)
    val access by viewModel.access.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val thumbnails by viewModel.thumbnailLoader.collectAsState()
    val currentMedia by viewModel.currentMedia.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionCount by viewModel.selectionCount.collectAsState()
    val photoState by viewModel.photoState.collectAsState()
    val trashCount by viewModel.trashCount.collectAsState()
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val search by viewModel.search.collectAsState()
    val searchIndexReady by viewModel.searchIndexReady.collectAsState()
    val semanticModels by viewModel.semanticModels.collectAsState()
    val detectedContentEnabled by viewModel.detectedContentEnabled.collectAsState()
    val peopleAnalysis by viewModel.peopleAnalysis.collectAsState()
    val petCollectionsEnabled by viewModel.petCollectionsEnabled.collectAsState()
    val petAnalysis by viewModel.petAnalysis.collectAsState()
    val localAnalysisOnboarding by viewModel.localAnalysisOnboarding.collectAsState()
    val gallerySettings by viewModel.gallerySettings.collectAsState()
    val galleryFolderOptions by viewModel.galleryFolderOptions.collectAsState()
    val petSummary by viewModel.petSummary.collectAsState()
    val selectedMoment by viewModel.selectedMoment.collectAsState()
    val momentSummaries by viewModel.momentSummaries.collectAsState()
    val momentMembers by viewModel.momentMembers.collectAsState(initial = emptyList())
    val people by viewModel.peopleSummaries.collectAsState()
    val selectedPerson by viewModel.selectedPerson.collectAsState()
    val selectedPersonMembers by viewModel.selectedPersonMembers.collectAsState()
    val me by viewModel.me.collectAsState()
    val actionState by viewModel.systemAction.collectAsState()
    val external by viewModel.externalMedia.collectAsState()
    val externalPhoto by viewModel.externalPhotoState.collectAsState()
    val photoEditor by viewModel.photoEditor.collectAsState()
    val videoEditor by viewModel.videoEditor.collectAsState()
    val timeline = viewModel.timeline.collectAsLazyPagingItems()
    val physicalAlbums = viewModel.physicalAlbums.collectAsLazyPagingItems()
    val virtualAlbums = viewModel.virtualAlbums.collectAsLazyPagingItems()
    val albumItems = viewModel.albumMedia.collectAsLazyPagingItems()
    val trashItems = viewModel.trash.collectAsLazyPagingItems()
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var appUnlocked by rememberSaveable { mutableStateOf(!gallerySettings.security.appLockEnabled) }
    var lockPromptActive by remember { mutableStateOf(false) }
    var backgroundedAt by rememberSaveable { mutableStateOf(0L) }
    var sessionVideoMuted by remember { mutableStateOf<Boolean?>(null) }
    fun requestAppUnlock() {
        val fragmentActivity = context as? FragmentActivity ?: return
        if (lockPromptActive || !BiometricGate.canAuthenticate(context)) return
        lockPromptActive = true
        BiometricGate.authenticate(
            activity = fragmentActivity,
            title = appLockTitle,
            subtitle = appLockBody,
            onSuccess = { lockPromptActive = false; appUnlocked = true },
            onError = { lockPromptActive = false },
            onFail = { lockPromptActive = false },
        )
    }
    fun runDestructive(block: () -> Unit) {
        if (!gallerySettings.security.destructiveActionLockEnabled) {
            block()
            return
        }
        val fragmentActivity = context as? FragmentActivity ?: return
        BiometricGate.authenticate(
            activity = fragmentActivity,
            title = destructiveAuthTitle,
            subtitle = destructiveAuthBody,
            onSuccess = block,
            onError = {},
            onFail = {},
        )
    }
    LaunchedEffect(gallerySettings.security.appLockEnabled) {
        if (!gallerySettings.security.appLockEnabled) appUnlocked = true
        else if (!appUnlocked) requestAppUnlock()
    }
    DisposableEffect(lifecycleOwner, gallerySettings.security.relockTimeoutMinutes) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { backgroundedAt = android.os.SystemClock.elapsedRealtime(); sessionVideoMuted = null }
                Lifecycle.Event.ON_START -> if (gallerySettings.security.appLockEnabled && backgroundedAt > 0L) {
                    val timeout = gallerySettings.security.relockTimeoutMinutes * 60_000L
                    if (timeout == 0L || android.os.SystemClock.elapsedRealtime() - backgroundedAt >= timeout) {
                        appUnlocked = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (gallerySettings.security.appLockEnabled && !appUnlocked) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(stringResource(R.string.app_lock_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.app_lock_body), Modifier.padding(16.dp))
                GalleryExpressiveButton(onClick = ::requestAppUnlock) { Text(stringResource(R.string.app_lock_unlock)) }
            }
        }
        return
    }
    val foldInfo by if (activity != null) {
        remember(activity, density) {
            WindowInfoTracker.getOrCreate(context).windowLayoutInfo(activity).map { info ->
                info.displayFeatures.filterIsInstance<FoldingFeature>()
                    .firstOrNull(FoldingFeature::isSeparating)
                    ?.let { feature ->
                        GalleryFoldInfo(
                            orientation = if (feature.orientation == FoldingFeature.Orientation.VERTICAL) {
                                GalleryFoldOrientation.Vertical
                            } else GalleryFoldOrientation.Horizontal,
                            isSeparating = feature.isSeparating,
                            left = with(density) { feature.bounds.left.toDp() },
                            top = with(density) { feature.bounds.top.toDp() },
                            right = with(density) { feature.bounds.right.toDp() },
                            bottom = with(density) { feature.bounds.bottom.toDp() },
                        )
                    }
            }
        }.collectAsState(initial = null)
    } else {
        remember { kotlinx.coroutines.flow.flowOf<GalleryFoldInfo?>(null) }.collectAsState(initial = null)
    }
    var rootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var route by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    val surfaceStateHolder = rememberSaveableStateHolder()
    var viewerReturnDestination by rememberSaveable(stateSaver = ViewerReturnDestinationSaver) {
        mutableStateOf<ViewerReturnDestination?>(null)
    }
    var filter by rememberSaveable { mutableStateOf(AlbumMediaFilter.All) }
    var sort by rememberSaveable { mutableStateOf(AlbumSort.NewestFirst) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCreateAlbum by rememberSaveable { mutableStateOf(false) }
    var showAddToAlbum by rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashConfirmation by rememberSaveable { mutableStateOf(false) }
    var newAlbumName by rememberSaveable { mutableStateOf("") }
    var pendingRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedCollageTemplateIndex by rememberSaveable { mutableStateOf(0) }
    var collageRendering by rememberSaveable { mutableStateOf(false) }
    var collageStatus by rememberSaveable { mutableStateOf<Int?>(null) }
    val collageTemplates = remember { CollageTemplate.entries.toList() }
    val privateAlbumRepo = remember {
        PrivateAlbumRepository(
            context.applicationContext,
            PrivateAlbumDatabase.open(context.applicationContext),
        )
    }
    var privateAlbumUnlocked by rememberSaveable { mutableStateOf(false) }
    val privateImportSelection = remember { mutableStateMapOf<MediaKey, TimelineMedia>() }
    var privateImportProgress by remember { mutableStateOf<PrivateImportProgress?>(null) }
    var privateImportOutcome by remember { mutableStateOf<PrivateImportOutcome?>(null) }

    fun leavePrivateAlbum() {
        privateImportSelection.clear()
        privateAlbumUnlocked = false
        route = SurfaceRoute.Root
    }

    fun startPrivateImport() {
        val batch = privateImportSelection.values.toList()
        if (batch.isEmpty() || privateImportProgress != null) return
        privateImportProgress = PrivateImportProgress(0, batch.size)
        appScope.launch {
            val successful = mutableListOf<TimelineMedia>()
            val masterKey = com.ugallery.core.security.PrivateAlbumCrypto.getOrCreateMasterKey()
            batch.forEachIndexed { index, media ->
                privateImportProgress = PrivateImportProgress(index + 1, batch.size)
                if (privateAlbumRepo.importFromMedia(media, masterKey).success) successful += media
            }
            privateImportSelection.clear()
            privateImportProgress = null
            privateImportOutcome = PrivateImportOutcome(successful, batch.size)
            route = SurfaceRoute.PrivateAlbum
        }
    }

    val privateFlowVisible = route == SurfaceRoute.PrivateAlbum || route == SurfaceRoute.PrivateAlbumPicker
    DisposableEffect(privateFlowVisible) {
        if (!privateFlowVisible) return@DisposableEffect onDispose { }
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val gazetteer = remember { OfflineGazetteer(BundledGazetteer.load()) }
    val subjectClipper = remember { SubjectClipper() }
    val objectEraser = remember { ObjectEraser() }
    val exportSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportGallerySettings) }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::importGallerySettings) }

    fun openViewer(destination: ViewerReturnDestination, openMedia: () -> Unit) {
        viewerReturnDestination = destination
        openMedia()
        route = SurfaceRoute.Viewer
    }

    fun restoreViewerReturnDestination() {
        val destination = viewerReturnDestination
        viewerReturnDestination = null
        when (destination) {
            is ViewerReturnDestination.Root -> {
                rootTab = destination.tab
                route = SurfaceRoute.Root
            }
            is ViewerReturnDestination.Album -> route = SurfaceRoute.Album
            null -> route = SurfaceRoute.Root
        }
    }

    fun handleBack() {
        when {
            showDetails -> showDetails = false
            route == SurfaceRoute.PhotoEditor -> {
                viewModel.closePhotoEditor()
                route = SurfaceRoute.Viewer
            }
            route == SurfaceRoute.VideoEditor -> {
                viewModel.closeVideoEditor()
                route = SurfaceRoute.Viewer
            }
            route == SurfaceRoute.People && selectedPerson != null -> viewModel.closePerson()
            route == SurfaceRoute.PrivateAlbumPicker -> {
                privateImportSelection.clear()
                route = SurfaceRoute.PrivateAlbum
            }
            route == SurfaceRoute.PrivateAlbum -> leavePrivateAlbum()
            route == SurfaceRoute.Viewer -> restoreViewerReturnDestination()
            else -> route = SurfaceRoute.Root
        }
    }

    BackHandler(enabled = route != SurfaceRoute.Root || showDetails, onBack = ::handleBack)

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.setVideoMusic(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Local track")
        }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importVideoLut(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Custom LUT")
        }
    }
    val voiceSearchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?.let { spoken ->
                    viewModel.setSearchQuery(spoken)
                    viewModel.search(spoken)
                }
        }
    }

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
    LaunchedEffect(Unit) {
        viewModel.sanitizedShare.collect { intent ->
            context.startActivity(Intent.createChooser(intent, null))
        }
    }
    LaunchedEffect(Unit) {
        viewModel.shareError.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
        val adaptiveInfo = galleryAdaptiveLayoutInfo(maxWidth, foldInfo)
        val content: @Composable (ScreenMotionKey) -> Unit = { activeKey ->
            when (activeKey.route) {
                SurfaceRoute.Root -> when (activeKey.rootTab) {
                    RootTab.Photos -> LibraryPhotosRoute(
                        access = access,
                        engineState = engineState.toUiState(),
                        entries = timeline,
                        thumbnailLoader = thumbnails,
                        onRequestAccess = ::requestAccess,
                        onOpenSettings = { route = SurfaceRoute.Settings },
                        onMediaClick = { media ->
                            if (selectionCount > 0) viewModel.toggleSelection(media)
                            else openViewer(ViewerReturnDestination.Root(RootTab.Photos)) {
                                viewModel.openTimelineMedia(media)
                            }
                        },
                        onMediaLongClick = viewModel::toggleSelection,
                        preferredColumns = gallerySettings.thumbnails.gridColumns,
                        cropThumbnails = gallerySettings.thumbnails.cropToFill,
                        onDensityChange = { columns ->
                            viewModel.updateGallerySettings { current ->
                                current.copy(thumbnails = current.thumbnails.copy(gridColumns = columns))
                            }
                        },
                    )
                    RootTab.Collections -> CollectionsContent(
                        physicalAlbums,
                        virtualAlbums,
                        trashCount,
                        momentSummaries,
                        onMomentClick = { viewModel.openMoment(it.momentId); route = SurfaceRoute.Moment },
                        onAlbumClick = { album ->
                            viewModel.selectAlbum(album, filter, sort)
                            route = SurfaceRoute.Album
                        },
                        onCreateAlbum = { showCreateAlbum = true },
                        onTrashClick = { route = SurfaceRoute.Trash },
                        onLocalAnalysisClick = { route = SurfaceRoute.Settings },
                        peopleEnabled = true,
                        peopleCount = people.size.toLong(),
                        peopleCover = people.firstNotNullOfOrNull { it.coverKey },
                        onPeopleClick = { route = SurfaceRoute.People },
                        petCollectionsEnabled = petCollectionsEnabled,
                        dogCount = petSummary.dogCount,
                        catCount = petSummary.catCount,
                        dogCover = petSummary.dogCover,
                        catCover = petSummary.catCover,
                        onPetCollectionClick = { label ->
                            viewModel.setSearchQuery(label)
                            viewModel.search(label)
                            rootTab = RootTab.Search
                        },
                        thumbnailLoader = thumbnails,
                        privateAlbumLabel = stringResource(R.string.m6_private_album),
                        onPrivateAlbumClick = { route = SurfaceRoute.PrivateAlbum },
                        collageLabel = stringResource(R.string.m6_collage),
                        onCollageClick = { route = SurfaceRoute.Collage },
                    )
                    RootTab.Search -> SearchContent(
                            query = search.query,
                            hits = search.hits,
                            loading = search.loading,
                            terminal = search.terminal,
                            partialIndex = !searchIndexReady,
                            onRetry = { viewModel.search() },
                            error = search.error,
                            detectedContentEnabled = detectedContentEnabled,
                            thumbnailLoader = thumbnails,
                            petCollection = when (SearchVocabulary.resolve(search.query.trim())) {
                                SearchConcept.Dog -> "dog" to petSummary.dogCount
                                SearchConcept.Cat -> "cat" to petSummary.catCount
                                else -> null
                            },
                            onOpenPetCollection = { label ->
                                viewModel.search(label)
                                rootTab = RootTab.Collections
                            },
                            onQueryChange = viewModel::setSearchQuery,
                            onSearch = { viewModel.search() },
                            onVoiceSearch = {
                                runCatching {
                                    voiceSearchLauncher.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                            putExtra(
                                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                            )
                                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                                        },
                                    )
                                }.onFailure {
                                    Toast.makeText(
                                        context,
                                        voiceSearchUnavailable,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onPresetSearch = viewModel::search,
                            onLoadMore = viewModel::loadMoreSearch,
                            onHit = { hit ->
                                openViewer(ViewerReturnDestination.Root(RootTab.Search)) {
                                    viewModel.openSearchHit(hit)
                                }
                            },
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
                                else openViewer(ViewerReturnDestination.Album(album.key)) {
                                    viewModel.openAlbumMedia(media, album, filter, sort)
                                }
                            },
                            onMediaLongClick = viewModel::toggleSelection,
                            showHeader = false,
                        )
                    }
                }
                SurfaceRoute.Viewer -> currentMedia?.let { media ->
                    ViewerRoute(
                        media,
                        viewerState.items,
                        photoState,
                        thumbnails,
                        viewModel,
                        showDetails,
                        adaptiveInfo,
                        onShowDetails = { showDetails = true; viewModel.loadDetails() },
                        onHideDetails = { showDetails = false },
                        onBack = ::handleBack,
                        onEdit = {
                            if (media.kind == MediaKind.Image) {
                                viewModel.openPhotoEditor(media)
                                route = SurfaceRoute.PhotoEditor
                            } else {
                                viewModel.openVideoEditor(media)
                                route = SurfaceRoute.VideoEditor
                            }
                        },
                        onShareSanitized = { viewModel.sanitizedShare(media) },
                        gazetteer = gazetteer,
                        sessionVideoMuted = sessionVideoMuted,
                        onSessionVideoMutedChange = { sessionVideoMuted = it },
                    )
                }
                SurfaceRoute.PhotoEditor -> photoEditor?.let { session ->
                    PhotoEditorContent(
                        state = session.content,
                        onBack = { viewModel.closePhotoEditor(); route = SurfaceRoute.Viewer },
                        onSaveCopy = viewModel::savePhotoEditorCopy,
                        onApply = viewModel::applyPhotoEdit,
                        onUndo = viewModel::undoPhotoEdit,
                        onRedo = viewModel::redoPhotoEdit,
                        onRawSettingsChange = viewModel::setRawDevelopment,
                        onRawOutputFormatChange = viewModel::setRawOutputFormat,
                    )
                }
                SurfaceRoute.VideoEditor -> videoEditor?.let { session ->
                    val controller = remember(session.media.key) {
                        VideoViewerController(
                            context,
                            enableVideoEffects = true,
                            initialLooping = true,
                        ).also {
                            it.select(viewModel.mediaUri(session.media), autoplay = true)
                        }
                    }
                    DisposableEffect(controller) { onDispose { controller.close() } }
                    VideoEditorContent(
                        state = session.content,
                        controller = controller,
                        onBack = { viewModel.closeVideoEditor(); route = SurfaceRoute.Viewer },
                        onSaveCopy = viewModel::saveVideoEditorCopy,
                        onSpeedChange = viewModel::setVideoSpeed,
                        onOriginalVolumeChange = viewModel::setVideoOriginalVolume,
                        onChooseMusic = { musicPicker.launch(arrayOf("audio/*")) },
                        onRemoveMusic = viewModel::removeVideoMusic,
                        onSeek = { position -> viewModel.seekVideo(position); controller.seekTo(position) },
                        onTrimChange = viewModel::setVideoTrim,
                        onColorGradeChange = viewModel::setVideoColorGrade,
                        onOutputQualityChange = viewModel::setVideoOutputQuality,
                        onImportLut = { lutPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
                        onMarkSlowMotionIn = viewModel::markVideoSlowMotionIn,
                        onMarkSlowMotionOut = viewModel::markVideoSlowMotionOut,
                        onSelectSlowMotionSegment = viewModel::selectVideoSlowMotionSegment,
                        onUpdateSlowMotionSegment = viewModel::updateVideoSlowMotionSegment,
                        onDeleteSlowMotionSegment = viewModel::deleteVideoSlowMotionSegment,
                        onCancelExport = viewModel::cancelVideoExport,
                    )
                }
                SurfaceRoute.Moment -> selectedMoment?.let { moment ->
                    val scope = rememberCoroutineScope()
                    MomentContent(
                        moment = moment,
                        members = momentMembers,
                        thumbnailLoader = thumbnails,
                        dateLabel = formatMomentDateRange(moment.startMillis, moment.endMillis),
                        stateLabel = stringResource(R.string.moment_state_label),
                        onBack = { route = SurfaceRoute.Root },
                        onSave = { scope.launch { viewModel.saveMoment() } },
                        onDelete = { viewModel.deleteSelectedMoment(); route = SurfaceRoute.Root },
                        onRename = { scope.launch { viewModel.renameMoment(it) } },
                        onSetCover = { scope.launch { viewModel.setMomentCover(it) } },
                        onReorder = { scope.launch { viewModel.reorderMoment(it) } },
                    )
                }
                SurfaceRoute.People -> PeopleContent(
                    state = PeopleUiState(
                        consentGranted = peopleAnalysis.consentGranted || people.isNotEmpty(),
                        paused = peopleAnalysis.paused,
                        running = peopleAnalysis.status == com.ugallery.core.ml.MlCheckpoint.Status.Running,
                        waiting = peopleAnalysis.requested && peopleAnalysis.status != com.ugallery.core.ml.MlCheckpoint.Status.Running,
                        analysisStage = when {
                            peopleAnalysis.paused -> com.ugallery.feature.collections.PeopleAnalysisStage.Paused
                            peopleAnalysis.status == com.ugallery.core.ml.MlCheckpoint.Status.Complete -> com.ugallery.feature.collections.PeopleAnalysisStage.Complete
                            peopleAnalysis.activeTask == com.ugallery.core.ml.MlTaskType.FaceDetection -> com.ugallery.feature.collections.PeopleAnalysisStage.FaceDetection
                            peopleAnalysis.activeTask == com.ugallery.core.ml.MlTaskType.FaceEmbeddings -> com.ugallery.feature.collections.PeopleAnalysisStage.FaceEmbeddings
                            peopleAnalysis.activeTask == com.ugallery.core.ml.MlTaskType.PersonClustering -> com.ugallery.feature.collections.PeopleAnalysisStage.PersonClustering
                            else -> com.ugallery.feature.collections.PeopleAnalysisStage.Idle
                        },
                        completedItems = peopleAnalysis.completedItems,
                        people = people,
                        selectedPerson = selectedPerson,
                        selectedMembers = selectedPersonMembers,
                        me = me,
                    ),
                    thumbnailLoader = thumbnails,
                    onBack = {
                        if (selectedPerson != null) viewModel.closePerson()
                        else route = SurfaceRoute.Root
                    },
                    onEnable = viewModel::enablePeopleRecognition,
                    onPause = viewModel::pausePeopleRecognition,
                    onResume = viewModel::resumePeopleRecognition,
                    onAnalyzeAll = viewModel::analyzeAllPeople,
                    onDeleteAll = viewModel::deletePeopleRecognitionData,
                    onPersonClick = viewModel::openPerson,
                    onRenamePerson = viewModel::renamePerson,
                    onHidePerson = viewModel::hidePerson,
                    onSetSelectedAsMe = viewModel::setSelectedPersonAsMe,
                    onResetMe = viewModel::resetMe,
                )
                SurfaceRoute.Trash -> TrashContent(
                    thumbnailLoader = thumbnails,
                    visibleItems = trashItems.itemSnapshotList.items,
                    totalCount = trashCount,
                    onRestore = { media -> viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Trash(false)) },
                    onDeletePermanently = { media -> runDestructive { viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Delete) } },
                    onEmptyTrash = { showEmptyTrashConfirmation = true },
                    showHeader = false,
                )
                SurfaceRoute.Settings -> RecognitionSettingsContent(
                    state = FaceAnalysisUiState(
                        consentGranted = peopleAnalysis.consentGranted,
                        paused = peopleAnalysis.paused,
                        completedItems = peopleAnalysis.completedItems,
                        status = peopleAnalysis.status?.let {
                            when (it) {
                                com.ugallery.core.ml.MlCheckpoint.Status.Ready -> AnalysisStatus.Ready
                                com.ugallery.core.ml.MlCheckpoint.Status.Running -> AnalysisStatus.Running
                                com.ugallery.core.ml.MlCheckpoint.Status.Paused -> AnalysisStatus.Paused
                                com.ugallery.core.ml.MlCheckpoint.Status.Complete -> AnalysisStatus.Complete
                            }
                        },
                    ),
                    onEnable = { viewModel.setPeopleAnalysisEnabled(true) },
                    onPause = viewModel::pausePeopleRecognition,
                    onResume = viewModel::resumePeopleRecognition,
                    onAnalyzeAll = viewModel::analyzeAllPeople,
                    onDelete = viewModel::deleteAllLocalAnalysisData,
                    petCollectionsEnabled = petCollectionsEnabled,
                    petAnalysisState = FaceAnalysisUiState(
                        consentGranted = petAnalysis.consentGranted,
                        paused = petAnalysis.paused,
                        completedItems = petAnalysis.completedItems,
                        status = when {
                            petAnalysis.requested -> AnalysisStatus.Running
                            else -> petAnalysis.status?.let {
                                when (it) {
                                    com.ugallery.core.ml.MlCheckpoint.Status.Ready -> AnalysisStatus.Ready
                                    com.ugallery.core.ml.MlCheckpoint.Status.Running -> AnalysisStatus.Running
                                    com.ugallery.core.ml.MlCheckpoint.Status.Paused -> AnalysisStatus.Paused
                                    com.ugallery.core.ml.MlCheckpoint.Status.Complete -> AnalysisStatus.Complete
                                }
                            }
                        },
                    ),
                    onPetCollectionsEnabledChange = {
                        if (it) viewModel.enablePetCollections() else viewModel.disablePetCollections()
                    },
                    onHideDogResults = { viewModel.suppressPetType(com.ugallery.core.ml.PetType.Dog) },
                    onHideCatResults = { viewModel.suppressPetType(com.ugallery.core.ml.PetType.Cat) },
                    onRestorePetResults = {
                        viewModel.restorePetType(com.ugallery.core.ml.PetType.Dog)
                        viewModel.restorePetType(com.ugallery.core.ml.PetType.Cat)
                    },
                    settings = gallerySettings,
                    folderOptions = galleryFolderOptions,
                    onSettingsChange = viewModel::updateGallerySettings,
                    onExportSettings = { exportSettingsLauncher.launch("ugallery-backup.json") },
                    onImportSettings = { importSettingsLauncher.launch("application/json") },
                    onResetSettings = viewModel::resetGallerySettings,
                    onBack = { route = SurfaceRoute.Root },
                    peopleAnalysisEnabled = peopleAnalysis.consentGranted,
                    contentAnalysisEnabled = detectedContentEnabled,
                    onAllAnalysisEnabledChange = viewModel::setAllLocalAnalysisEnabled,
                    onPeopleAnalysisEnabledChange = viewModel::setPeopleAnalysisEnabled,
                    onContentAnalysisEnabledChange = viewModel::setContentAnalysisEnabled,
                    semanticModels = SemanticModelSettingsUiState(
                        enabled = semanticModels.enabled,
                        automaticSelection = semanticModels.selectionMode == com.ugallery.feature.semanticsearch.SemanticSelectionMode.Automatic,
                        activeModelId = semanticModels.activeModelId,
                        buildingModelId = semanticModels.buildingModelId,
                        indexError = semanticModels.indexError,
                        models = semanticModels.models.map { model ->
                            SemanticModelSettingsItemUi(
                                id = model.descriptor.id,
                                name = model.descriptor.displayName,
                                version = model.descriptor.version,
                                sizeBytes = model.descriptor.packageBytes,
                                quality = stringResource(
                                    if (model.descriptor.id == "tinyclip-quality") com.ugallery.feature.settings.R.string.semantic_quality_higher
                                    else com.ugallery.feature.settings.R.string.semantic_quality_balanced,
                                ),
                                languages = stringResource(com.ugallery.feature.settings.R.string.semantic_language_english_focused),
                                compatibility = when (model.compatibility) {
                                    com.ugallery.feature.semanticsearch.SemanticModelCompatibility.Recommended -> SemanticModelCompatibilityUi.Recommended
                                    com.ugallery.feature.semanticsearch.SemanticModelCompatibility.Supported -> SemanticModelCompatibilityUi.Supported
                                    com.ugallery.feature.semanticsearch.SemanticModelCompatibility.TechnicallyUnsupported -> SemanticModelCompatibilityUi.Unsupported
                                },
                                installed = model.installed,
                                active = model.active,
                                downloading = model.downloading,
                                downloadedBytes = model.downloadedBytes,
                                error = model.error,
                            )
                        },
                    ),
                    onSemanticEnabledChange = viewModel::setSemanticSearchEnabled,
                    onSemanticDownload = viewModel::downloadSemanticModel,
                    onSemanticCancelDownload = viewModel::cancelSemanticModelDownload,
                    onSemanticActivate = viewModel::activateSemanticModel,
                    onSemanticDelete = viewModel::deleteSemanticModel,
                    onSemanticDeleteAll = viewModel::deleteAllSemanticModels,
                    onSemanticAutomaticSelection = viewModel::useAutomaticSemanticModel,
                    showHeader = false,
                )
                SurfaceRoute.PrivateAlbum -> PrivateAlbumContent(
                    repository = privateAlbumRepo,
                    onBack = ::leavePrivateAlbum,
                    onUnlockRequest = { onSuccess, onError ->
                        val fragmentActivity = context as? FragmentActivity
                        if (fragmentActivity == null || !BiometricGate.canAuthenticate(context)) {
                            onError(privateBiometricUnavailable)
                        } else {
                            BiometricGate.authenticate(
                                activity = fragmentActivity,
                                title = privateLockedTitle,
                                subtitle = privateUnlockSubtitle,
                                onSuccess = onSuccess,
                                onError = onError,
                                onFail = {
                                    onError(privateBiometricFailed)
                                },
                            )
                        }
                    },
                    onExport = { mediaId, onSuccess, onError ->
                        appScope.launch {
                            runCatching {
                                privateAlbumRepo.exportToMediaStore(
                                    mediaId,
                                    com.ugallery.core.security.PrivateAlbumCrypto.getOrCreateMasterKey(),
                                ) ?: error(privateExportFailed)
                            }.onSuccess { onSuccess() }
                                .onFailure {
                                    onError(it.message ?: privateExportFailed)
                                }
                        }
                    },
                    isUnlocked = privateAlbumUnlocked,
                    onUnlocked = { privateAlbumUnlocked = true },
                    onAddRequest = {
                        privateImportSelection.clear()
                        route = SurfaceRoute.PrivateAlbumPicker
                    },
                )
                SurfaceRoute.PrivateAlbumPicker -> {
                    val pickerLimitMessage = stringResource(
                        com.ugallery.feature.privatealbum.R.string.private_picker_limit,
                    )
                    Column(Modifier.fillMaxSize()) {
                    Text(
                        stringResource(
                            com.ugallery.feature.privatealbum.R.string.private_picker_count,
                            privateImportSelection.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    when {
                        thumbnails == null || timeline.loadState.refresh is LoadState.Loading -> {
                            GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                            GalleryStateContent(
                                title = stringResource(com.ugallery.feature.photos.R.string.library_loading_title),
                                body = stringResource(com.ugallery.feature.photos.R.string.library_loading_body),
                                illustrationDescription = stringResource(com.ugallery.feature.photos.R.string.library_loading_title),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        timeline.itemCount == 0 -> GalleryStateContent(
                            title = stringResource(com.ugallery.feature.photos.R.string.empty_library_title),
                            body = stringResource(com.ugallery.feature.photos.R.string.empty_library_body),
                            illustrationDescription = stringResource(com.ugallery.feature.photos.R.string.empty_library_title),
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AdaptivePagedPhotosTimeline(
                            entries = timeline,
                            thumbnailLoader = requireNotNull(thumbnails),
                            modifier = Modifier.fillMaxSize(),
                            preferredColumns = gallerySettings.thumbnails.gridColumns,
                            cropThumbnails = gallerySettings.thumbnails.cropToFill,
                            onMediaClick = { media ->
                                if (privateImportSelection.containsKey(media.key)) {
                                    privateImportSelection.remove(media.key)
                                } else if (privateImportSelection.size < 500) {
                                    privateImportSelection[media.key] = media
                                } else {
                                    Toast.makeText(
                                        context,
                                        pickerLimitMessage,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onMediaLongClick = { media ->
                                if (privateImportSelection.containsKey(media.key)) {
                                    privateImportSelection.remove(media.key)
                                } else if (privateImportSelection.size < 500) {
                                    privateImportSelection[media.key] = media
                                }
                            },
                            isMediaSelected = { privateImportSelection.containsKey(it.key) },
                        )
                    }
                    }
                }
                SurfaceRoute.Collage -> {
                    val template = collageTemplates.getOrElse(selectedCollageTemplateIndex) { collageTemplates[0] }
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            stringResource(R.string.m6_collage_select_template),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        CollageTemplatePicker(
                            selectedTemplate = template,
                            onTemplateSelected = { selectedCollageTemplateIndex = collageTemplates.indexOf(it) },
                            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                        )
                        Text(
                            stringResource(R.string.m6_collage_select_photos, template.slotCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.m6_collage_description),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        collageStatus?.let {
                            Text(
                                stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it == R.string.m6_collage_failed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        GalleryExpressiveButton(
                            onClick = {
                                collageRendering = true
                                collageStatus = null
                                appScope.launch {
                                    runCatching { viewModel.createCollage(template) }
                                        .onSuccess {
                                            collageRendering = false
                                            collageStatus = R.string.m6_collage_saved
                                        }
                                        .onFailure {
                                            collageRendering = false
                                            collageStatus = R.string.m6_collage_failed
                                        }
                                }
                            },
                            enabled = !collageRendering && viewModel.canCreateCollage(template),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            if (collageRendering) {
                                GalleryLoadingIndicator(modifier = Modifier.size(24.dp))
                            }
                            else Text(stringResource(R.string.m6_collage_render))
                        }
                    }
                }
            }
        }
        val controls: @Composable (SurfaceRoute) -> Unit = { activeRoute ->
            GalleryAnimatedVisibility(
                visible = selectionCount > 0,
                edge = GalleryMotionEdge.Top,
            ) {
                SelectionActions(
                    count = selectionCount,
                    canShare = selection is SelectionSpec.Explicit && selectionCount <= 500,
                    showShareLimitNote = selectionCount > 500,
                    onSelectAll = {
                        if (activeRoute == SurfaceRoute.Album && selectedAlbum != null) {
                            viewModel.selectAllAlbum(requireNotNull(selectedAlbum), filter, sort)
                        } else viewModel.selectAllTimeline()
                    },
                    onFavorite = { viewModel.beginSelectionSystemAction(MediaAction.Favorite(true)) },
                    onTrash = { runDestructive { viewModel.beginSelectionSystemAction(MediaAction.Trash(true)) } },
                    onDelete = { runDestructive { viewModel.beginSelectionSystemAction(MediaAction.Delete) } },
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
                    GalleryExpressiveButton(onClick = viewModel::retrySystemAction) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
        val internalTopBarRoute = route == SurfaceRoute.Settings ||
            route == SurfaceRoute.People ||
            route == SurfaceRoute.Moment
        val contentInsets = when {
            route == SurfaceRoute.Viewer ||
                route == SurfaceRoute.PhotoEditor ||
                route == SurfaceRoute.VideoEditor ||
                route == SurfaceRoute.PrivateAlbum ||
                route == SurfaceRoute.PrivateAlbumPicker -> WindowInsets(0, 0, 0, 0)
            internalTopBarRoute -> ScaffoldDefaults.contentWindowInsets.only(
                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
            )
            else -> ScaffoldDefaults.contentWindowInsets
        }
        Scaffold(
            contentWindowInsets = contentInsets,
            containerColor = if (route == SurfaceRoute.Viewer) Color.Black
            else MaterialTheme.colorScheme.background,
            topBar = {
                when (route) {
                    SurfaceRoute.Root -> if (engineState == LibraryEngineState.Indexing) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                stringResource(R.string.library_index_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    SurfaceRoute.Album -> GalleryTopAppBar(
                        title = selectedAlbum?.name ?: stringResource(com.ugallery.feature.album.R.string.album_untitled),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    SurfaceRoute.Trash -> GalleryTopAppBar(
                        title = stringResource(com.ugallery.feature.trash.R.string.trash_title),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                        actions = {
                            if (trashCount > 0) TextButton(onClick = {
                                if (gallerySettings.operations.skipAppDeleteConfirmation) {
                                    runDestructive(viewModel::emptyTrash)
                                } else {
                                    showEmptyTrashConfirmation = true
                                }
                            }) {
                                Text(stringResource(com.ugallery.feature.trash.R.string.trash_empty))
                            }
                        },
                    )
                    SurfaceRoute.Settings -> Unit
                    SurfaceRoute.PrivateAlbumPicker -> GalleryTopAppBar(
                        title = stringResource(com.ugallery.feature.privatealbum.R.string.private_picker_title),
                        onBack = {
                            privateImportSelection.clear()
                            route = SurfaceRoute.PrivateAlbum
                        },
                        navigationContentDescription = stringResource(R.string.nav_back),
                        actions = {
                            TextButton(
                                onClick = ::startPrivateImport,
                                enabled = privateImportSelection.isNotEmpty() && privateImportProgress == null,
                            ) {
                                Text(stringResource(com.ugallery.feature.privatealbum.R.string.private_picker_add))
                            }
                        },
                    )
                    SurfaceRoute.Collage -> GalleryTopAppBar(
                        title = stringResource(R.string.m6_collage),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    else -> Unit
                }
            },
            bottomBar = {
                if (adaptiveInfo.navigationType == GalleryNavigationType.BottomBar && route == SurfaceRoute.Root) {
                    RootNavigationBar(rootTab) { rootTab = it }
                }
            },
        ) { padding ->
            if (adaptiveInfo.navigationType == GalleryNavigationType.Rail) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (route == SurfaceRoute.Root) {
                        RootNavigationRail(rootTab) { rootTab = it }
                    }
                    AnimatedSurfaceBody(
                        key = ScreenMotionKey(route, rootTab, surfaceStateKey(route, rootTab, selectedAlbum)),
                        modifier = Modifier.weight(1f),
                        stateHolder = surfaceStateHolder,
                        controls = controls,
                        content = content,
                    )
                }
            } else {
                AnimatedSurfaceBody(
                    key = ScreenMotionKey(route, rootTab, surfaceStateKey(route, rootTab, selectedAlbum)),
                    modifier = Modifier.fillMaxSize().padding(padding),
                    stateHolder = surfaceStateHolder,
                    controls = controls,
                    content = content,
                )
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
                    runDestructive(viewModel::emptyTrash)
                }) { Text(stringResource(R.string.trash_empty_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirmation = false }) {
                    Text(stringResource(R.string.album_cancel))
                }
            },
        )
    }
    privateImportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(com.ugallery.feature.privatealbum.R.string.private_picker_title)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    GalleryLoadingIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource(
                            com.ugallery.feature.privatealbum.R.string.private_importing,
                            progress.completed,
                            progress.total,
                        ),
                    )
                }
            },
            confirmButton = {},
        )
    }
    privateImportOutcome?.let { outcome ->
        val importedCount = outcome.successful.size
        AlertDialog(
            onDismissRequest = { privateImportOutcome = null },
            title = {
                Text(
                    stringResource(
                        if (importedCount > 0) com.ugallery.feature.privatealbum.R.string.private_import_result_title
                        else com.ugallery.feature.privatealbum.R.string.private_error,
                    ),
                )
            },
            text = {
                Text(
                    if (importedCount > 0) {
                        stringResource(
                            com.ugallery.feature.privatealbum.R.string.private_import_result,
                            importedCount,
                            outcome.total,
                        )
                    } else {
                        stringResource(com.ugallery.feature.privatealbum.R.string.private_import_result_failed)
                    },
                )
            },
            confirmButton = {
                if (importedCount > 0) {
                    TextButton(onClick = {
                        val imported = outcome.successful
                        privateImportOutcome = null
                        runDestructive { viewModel.beginSystemAction(imported, MediaAction.Delete) }
                    }) {
                        Text(stringResource(com.ugallery.feature.privatealbum.R.string.private_delete_originals))
                    }
                } else {
                    TextButton(onClick = { privateImportOutcome = null }) {
                        Text(stringResource(com.ugallery.feature.privatealbum.R.string.private_ok))
                    }
                }
            },
            dismissButton = if (importedCount > 0) {
                {
                    TextButton(onClick = { privateImportOutcome = null }) {
                        Text(stringResource(com.ugallery.feature.privatealbum.R.string.private_keep_originals))
                    }
                }
            } else null,
        )
    }
    if (
        localAnalysisOnboarding == LocalAnalysisOnboardingDecision.Pending &&
        access.images != GrantLevel.None &&
        external == null
    ) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(stringResource(com.ugallery.feature.settings.R.string.local_analysis_opt_out_title))
            },
            text = {
                Text(stringResource(com.ugallery.feature.settings.R.string.local_analysis_opt_out_body))
            },
            confirmButton = {
                GalleryExpressiveButton(onClick = viewModel::acceptLocalAnalysisDefaults) {
                    Text(stringResource(com.ugallery.feature.settings.R.string.local_analysis_opt_out_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineLocalAnalysisDefaults) {
                    Text(stringResource(com.ugallery.feature.settings.R.string.local_analysis_opt_out_decline))
                }
            },
        )
    }
}

@Composable
internal fun AnimatedSurfaceBody(
    key: ScreenMotionKey,
    modifier: Modifier,
    stateHolder: SaveableStateHolder,
    controls: @Composable (SurfaceRoute) -> Unit,
    content: @Composable (ScreenMotionKey) -> Unit,
) {
    GalleryAnimatedContent(
        targetState = key,
        modifier = modifier,
        contentKey = { it },
    ) { activeKey ->
        Column(Modifier.fillMaxSize()) {
            controls(activeKey.route)
            if (activeKey.saveableStateKey != null) {
                stateHolder.SaveableStateProvider(activeKey.saveableStateKey) {
                    content(activeKey)
                }
            } else {
                content(activeKey)
            }
        }
    }
}

@Composable
private fun SelectionActions(
    count: Long,
    canShare: Boolean,
    showShareLimitNote: Boolean = false,
    onSelectAll: () -> Unit,
    onFavorite: () -> Unit,
    onTrash: () -> Unit,
    onDelete: () -> Unit,
    onAddToAlbum: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            leadingContent = {
            Text(
                stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            },
            trailingContent = {
            Box {
                GalleryExpressiveIconButton(onClick = { menuExpanded = true }) {
                    Icon(GalleryIcons.More, contentDescription = stringResource(com.ugallery.feature.viewer.R.string.viewer_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.selection_select_all)) },
                        onClick = { menuExpanded = false; onSelectAll() },
                        leadingIcon = { Icon(GalleryIcons.Check, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.selection_delete)) },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.selection_clear)) },
                        onClick = { menuExpanded = false; onClear() },
                        leadingIcon = { Icon(GalleryIcons.Close, contentDescription = null) },
                    )
                }
            }
            },
        ) {
            GalleryExpressiveIconButton(onClick = onAddToAlbum) {
                Icon(GalleryIcons.Album, contentDescription = stringResource(R.string.selection_add_album))
            }
            GalleryExpressiveIconButton(onClick = onShare, enabled = canShare) {
                Icon(GalleryIcons.Share, contentDescription = stringResource(R.string.selection_share))
            }
            GalleryExpressiveIconButton(onClick = onFavorite) {
                Icon(GalleryIcons.Heart, contentDescription = stringResource(R.string.selection_favorite))
            }
            GalleryExpressiveIconButton(onClick = onTrash) {
                Icon(GalleryIcons.Trash, contentDescription = stringResource(R.string.selection_trash))
            }
        }
        if (showShareLimitNote) {
            Text(
                stringResource(R.string.selection_share_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
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
            GalleryExpressiveButton(onClick = onClose) { Text(stringResource(R.string.details_close)) }
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
                else -> GalleryLoadingIndicator(Modifier.padding(24.dp))
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { GalleryExpressiveButton(onClick = onClose) { Text(stringResource(R.string.details_close)) } }
            if (video != null) {
                val playing = (videoState?.value as? com.ugallery.feature.viewer.VideoViewerState.Ready)?.isPlaying == true
                item { GalleryExpressiveButton(onClick = { if (playing) video.pause() else video.play() }) {
                    Text(stringResource(if (playing) R.string.external_pause else R.string.external_play))
                } }
            }
            if (media.editMode) item { GalleryExpressiveButton(onClick = onSaveCopy) { Text(stringResource(R.string.external_save_copy)) } }
        }
    }
    DisposableEffect(photo) {
        val animated = (photo as? com.ugallery.feature.viewer.PhotoLoadState.Ready)?.drawable as? AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerRoute(
    media: TimelineMedia,
    mediaItems: List<TimelineMedia>,
    photoState: com.ugallery.feature.viewer.PhotoLoadState?,
    thumbnailLoader: com.ugallery.core.thumbnail.ThumbnailLoader?,
    viewModel: GalleryViewModel,
    showDetails: Boolean,
    adaptiveInfo: GalleryAdaptiveLayoutInfo,
    onShowDetails: () -> Unit,
    onHideDetails: () -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onShareSanitized: () -> Unit,
    gazetteer: OfflineGazetteer? = null,
    sessionVideoMuted: Boolean? = null,
    onSessionVideoMutedChange: (Boolean?) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val detectedText by viewModel.detectedText.collectAsState()
    val quickSlowMotionSave by viewModel.quickSlowMotionSave.collectAsState()
    val gallerySettings by viewModel.gallerySettings.collectAsState()
    val destructiveAuthTitle = stringResource(R.string.destructive_auth_title)
    val destructiveAuthBody = stringResource(R.string.destructive_auth_body)
    val chooserTitle = stringResource(R.string.viewer_choose_app)
    val copyComplete = stringResource(R.string.viewer_copy_complete)
    val moveCopyComplete = stringResource(R.string.viewer_move_copy_complete)
    val actionUnavailable = stringResource(R.string.viewer_action_unavailable)
    var renameDialogVisible by rememberSaveable(media.key) { mutableStateOf(false) }
    var renameValue by rememberSaveable(media.key) { mutableStateOf(media.displayName.orEmpty()) }
    var treeMoveRequested by remember { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) coroutineScope.launch {
            viewModel.copyMediaToTree(media, treeUri, treeMoveRequested)
                .onSuccess {
                    Toast.makeText(context, if (treeMoveRequested) moveCopyComplete else copyComplete, Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_LONG).show() }
        }
    }
    val target = remember(media.key, media.kind) { MediaActionTarget(media.key, media.kind) }
    val wildcardMime = if (media.kind == MediaKind.Video) "video/*" else "image/*"
    fun launchExternal(intent: Intent) {
        runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
            .onFailure { Toast.makeText(context, actionUnavailable, Toast.LENGTH_SHORT).show() }
    }
    fun showDateRepairPicker() {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = media.timelineSortMillis }
        android.app.DatePickerDialog(
            context,
            { _, year, month, day ->
                calendar.set(java.util.Calendar.YEAR, year)
                calendar.set(java.util.Calendar.MONTH, month)
                calendar.set(java.util.Calendar.DAY_OF_MONTH, day)
                viewModel.requestDateRepair(media, calendar.timeInMillis)
            },
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH),
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
        ).show()
    }
    fun runViewerDestructive(block: () -> Unit) {
        if (!gallerySettings.security.destructiveActionLockEnabled) {
            block()
            return
        }
        val fragmentActivity = context as? FragmentActivity ?: return
        BiometricGate.authenticate(
            activity = fragmentActivity,
            title = destructiveAuthTitle,
            subtitle = destructiveAuthBody,
            onSuccess = block,
            onError = {},
            onFail = {},
        )
    }
    DisposableEffect(context, gallerySettings.playback.maximumBrightness) {
        val window = (context as? Activity)?.window
        val previous = window?.attributes?.screenBrightness
        if (gallerySettings.playback.maximumBrightness) {
            window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        }
        onDispose {
            if (previous != null) window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
    val placeName = remember(exif) {
        val location = (exif as? com.ugallery.core.model.ExifLoadResult.Ready)?.details?.location
        if (location != null && gazetteer != null) {
            gazetteer.reverseGeocode(location.latitude, location.longitude)?.city?.name
        } else null
    }
    val videoController = if (media.kind == MediaKind.Video) remember(media.key) {
        VideoViewerController(context, initialLooping = gallerySettings.playback.loopVideos).also {
            it.select(
                viewModel.mediaUri(media),
                autoplay = gallerySettings.playback.autoplayVideos,
                startMuted = sessionVideoMuted ?: gallerySettings.playback.startVideosMuted,
            )
        }
    } else null
    LaunchedEffect(videoController, gallerySettings.playback.loopVideos) {
        videoController?.setLooping(gallerySettings.playback.loopVideos)
    }
    var restoredVideoPosition by remember(media.key) { mutableStateOf(false) }
    LaunchedEffect(videoController, media.key, gallerySettings.playback.rememberVideoPosition) {
        val controller = videoController ?: return@LaunchedEffect
        if (gallerySettings.playback.rememberVideoPosition) {
            viewModel.restoredVideoPosition(media).takeIf { it > 0L }?.let(controller::seekTo)
        }
        restoredVideoPosition = true
    }
    LaunchedEffect(videoController, media.key, restoredVideoPosition) {
        val controller = videoController ?: return@LaunchedEffect
        if (!restoredVideoPosition) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            viewModel.saveVideoPosition(media, controller.currentPositionMillis())
        }
    }
    val slowMotionSession = if (media.kind == MediaKind.Video) remember(media.key) {
        com.ugallery.feature.viewer.HoldSlowMotionSession(context, viewModel.mediaUri(media))
    } else null
    LaunchedEffect(slowMotionSession, videoController) {
        val session = slowMotionSession ?: return@LaunchedEffect
        val controller = videoController ?: return@LaunchedEffect
        while (true) {
            session.prepare(controller.currentPositionMillis())
            delay(1_000L)
        }
    }
    DisposableEffect(videoController, slowMotionSession) {
        onDispose {
            slowMotionSession?.close()
            videoController?.let { viewModel.saveVideoPosition(media, it.currentPositionMillis()) }
            videoController?.close()
        }
    }
    LaunchedEffect(videoController) {
        viewModel.hardwareVolumeKeys.collect { videoController?.unmute(); onSessionVideoMutedChange(false) }
    }
    val viewer: @Composable () -> Unit = {
        ViewerContent(
            media = media,
            mediaItems = mediaItems,
            photoState = photoState,
            videoController = videoController,
            thumbnailLoader = thumbnailLoader,
            isFavorite = media.isFavorite,
            onBack = onBack,
            onToggleFavorite = { viewModel.beginSystemAction(media, MediaAction.Favorite(!media.isFavorite)) },
            onShare = {
                if (gallerySettings.operations.shareWithoutLocationByDefault) {
                    onShareSanitized()
                } else {
                    context.startActivity(Intent.createChooser(viewModel.originalShareIntent(media), null))
                }
            },
            onDetails = onShowDetails,
            onEdit = onEdit,
            onRename = {
                renameValue = media.displayName.orEmpty()
                renameDialogVisible = true
            },
            onCopy = {
                treeMoveRequested = false
                treeLauncher.launch(null)
            },
            onMove = {
                treeMoveRequested = true
                treeLauncher.launch(null)
            },
            onOpenWith = { launchExternal(ScopedMediaOperations.viewIntent(target, wildcardMime)) },
            onSetAs = { launchExternal(ScopedMediaOperations.setAsIntent(target, wildcardMime)) },
            onPrint = {
                runCatching { ScopedMediaOperations.printImage(context, target, media.displayName ?: "UGallery") }
                    .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_SHORT).show() }
            },
            onRepairDate = ::showDateRepairPicker,
            onShareSanitized = onShareSanitized,
            onTrash = { runViewerDestructive { viewModel.beginSystemAction(media, MediaAction.Trash(true)) } },
            onSelectMedia = viewModel::selectViewerMedia,
            onContentTap = { videoController?.unmute(); onSessionVideoMutedChange(false) },
            slowMotionSession = slowMotionSession,
            onSaveSlowMotionClip = { clip ->
                viewModel.saveQuickSlowMotionClip(media, clip.startMillis, clip.endMillis)
            },
            slowMotionSaveProgress = quickSlowMotionSave.progress,
            slowMotionSaveCompletionGeneration = quickSlowMotionSave.completionGeneration,
            gestureSettings = gallerySettings.gestures,
            onMuteToggle = { muted -> onSessionVideoMutedChange(muted) },
            videoScrubbingMode = gallerySettings.playback.videoScrubbingMode,
            modifier = Modifier.fillMaxSize(),
        )
        // Motion photo badge - detection requires file access, shown when available
        // MotionPhotoParser.parseXmp() is called from the viewer pipeline when XMP metadata is available
    }
    if (adaptiveInfo.supportsTwoPane && showDetails && cheap != null) {
        val verticalFold = adaptiveInfo.foldInfo?.takeIf { it.enablesSideBySide }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val containerWidth = maxWidth
            Row(Modifier.fillMaxSize()) {
                Column(
                    if (verticalFold == null) Modifier.weight(0.62f)
                    else Modifier.width(verticalFold.left.coerceIn(0.dp, containerWidth)),
                ) { viewer() }
                if (verticalFold != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(verticalFold.hingeWidth))
                }
                GalleryAnimatedVisibility(
                    visible = true,
                    edge = GalleryMotionEdge.End,
                    modifier = if (verticalFold == null) Modifier.weight(0.38f)
                    else Modifier.width((containerWidth - verticalFold.right).coerceAtLeast(0.dp)),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            TextButton(onClick = onHideDetails) { Text(stringResource(R.string.details_close)) }
                            DetailsContent(
                                requireNotNull(cheap),
                                exif,
                                false,
                                viewModel::loadDetails,
                                detectedText,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    } else {
        viewer()
        if (showDetails && cheap != null) ModalBottomSheet(onDismissRequest = onHideDetails) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailsContent(
                    requireNotNull(cheap),
                    exif,
                    false,
                    viewModel::loadDetails,
                    detectedText,
                    scrollable = false,
                )
                placeName?.let { name ->
                    Text(
                        stringResource(R.string.m6_places_nearby, name),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    if (renameDialogVisible) AlertDialog(
        onDismissRequest = { renameDialogVisible = false },
        title = { Text(stringResource(R.string.viewer_rename)) },
        text = {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it },
                singleLine = true,
                label = { Text(stringResource(R.string.viewer_file_name)) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = runCatching { ScopedMediaOperations.validateDisplayName(renameValue) }.isSuccess,
                onClick = {
                    runCatching { viewModel.requestRename(media, renameValue) }
                        .onSuccess { renameDialogVisible = false }
                        .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_SHORT).show() }
                },
            ) { Text(stringResource(R.string.viewer_rename)) }
        },
        dismissButton = {
            TextButton(onClick = { renameDialogVisible = false }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable private fun RootNavigationBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    ShortNavigationBar { RootTab.entries.forEach { tab ->
        ShortNavigationBarItem(
            selected = selected == tab,
            onClick = { onSelect(tab) },
            icon = {
                Icon(
                    imageVector = when (tab) {
                        RootTab.Photos -> GalleryIcons.Image
                        RootTab.Collections -> GalleryIcons.Collections
                        RootTab.Search -> GalleryIcons.Search
                    },
                    contentDescription = stringResource(tab.label()),
                )
            },
            label = { Text(stringResource(tab.label())) },
        )
    } }
}

@Composable private fun RootNavigationRail(selected: RootTab, onSelect: (RootTab) -> Unit) {
    val railState = rememberWideNavigationRailState(WideNavigationRailValue.Expanded)
    WideNavigationRail(state = railState) { RootTab.entries.forEach { tab ->
        WideNavigationRailItem(
            selected = selected == tab,
            onClick = { onSelect(tab) },
            icon = {
                Icon(
                    imageVector = when (tab) {
                        RootTab.Photos -> GalleryIcons.Image
                        RootTab.Collections -> GalleryIcons.Collections
                        RootTab.Search -> GalleryIcons.Search
                    },
                    contentDescription = stringResource(tab.label()),
                )
            },
            label = { Text(stringResource(tab.label())) },
            railExpanded = true,
        )
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
