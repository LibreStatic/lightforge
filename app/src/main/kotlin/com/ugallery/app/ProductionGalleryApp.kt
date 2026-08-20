package com.ugallery.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.fragment.app.FragmentActivity
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.mediastore.MediaAction
import com.ugallery.core.mediastore.MediaActionPhase
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryActionButton
import com.ugallery.core.designsystem.GalleryAnimatedContent
import com.ugallery.core.designsystem.GalleryAnimatedVisibility
import com.ugallery.core.designsystem.GalleryNavigationType
import com.ugallery.core.designsystem.GalleryAdaptiveLayoutInfo
import com.ugallery.core.designsystem.GalleryFoldInfo
import com.ugallery.core.designsystem.GalleryFoldOrientation
import com.ugallery.core.designsystem.GalleryMotionEdge
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.designsystem.galleryAdaptiveLayoutInfo
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.feature.album.AlbumContent
import com.ugallery.feature.collections.CollectionsContent
import com.ugallery.feature.collections.MomentContent
import com.ugallery.feature.collections.PeopleContent
import com.ugallery.feature.collections.PeopleUiState
import com.ugallery.feature.collections.formatMomentDateRange
import com.ugallery.feature.details.DetailsContent
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.photos.LibraryPhotosRoute
import com.ugallery.feature.trash.TrashContent
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.feature.viewer.ViewerContent
import com.ugallery.feature.photoeditor.PhotoEditorContent
import com.ugallery.feature.videoeditor.VideoEditorContent
import com.ugallery.feature.search.SearchContent
import com.ugallery.feature.settings.AnalysisStatus
import com.ugallery.feature.settings.FaceAnalysisUiState
import com.ugallery.feature.settings.RecognitionSettingsContent
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
import com.ugallery.feature.semanticsearch.SemanticSearchEngine

private enum class RootTab { Photos, Collections, Search }
private enum class SurfaceRoute { Root, Album, Viewer, PhotoEditor, VideoEditor, Trash, Settings, Moment, People, PrivateAlbum, Collage }
private data class ScreenMotionKey(
    val route: SurfaceRoute,
    val rootTab: RootTab,
)

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
    val faceAnalysis by viewModel.faceAnalysis.collectAsState()
    val peopleAnalysis by viewModel.peopleAnalysis.collectAsState()
    val petCollectionsEnabled by viewModel.petCollectionsEnabled.collectAsState()
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
    val gazetteer = remember { OfflineGazetteer(BundledGazetteer.load()) }
    val semanticEngine = remember { SemanticSearchEngine() }
    val subjectClipper = remember { SubjectClipper() }
    val objectEraser = remember { ObjectEraser() }

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
        val content: @Composable (SurfaceRoute, RootTab) -> Unit = { activeRoute, activeRootTab ->
            when (activeRoute) {
                SurfaceRoute.Root -> when (activeRootTab) {
                    RootTab.Photos -> LibraryPhotosRoute(
                        access = access,
                        engineState = engineState.toUiState(),
                        entries = timeline,
                        thumbnailLoader = thumbnails,
                        onRequestAccess = ::requestAccess,
                        onOpenSettings = { route = SurfaceRoute.Settings },
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
                        onPeopleClick = { route = SurfaceRoute.People },
                        petCollectionsEnabled = petCollectionsEnabled,
                        dogCount = petSummary.dogCount,
                        catCount = petSummary.catCount,
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
                    RootTab.Search -> Column(Modifier.fillMaxSize()) {
                        if (!semanticEngine.isSemanticAvailable()) {
                            Text(
                                stringResource(R.string.m6_semantic_unavailable),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        SearchContent(
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
                            onHit = { hit -> viewModel.openSearchHit(hit); route = SurfaceRoute.Viewer },
                            onEnableDetectedContent = viewModel::enableDetectedContent,
                            onPauseDetectedContent = viewModel::pauseDetectedContent,
                            onDeleteDetectedContent = viewModel::deleteDetectedContent,
                        )
                    }
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
                            showHeader = false,
                        )
                    }
                }
                SurfaceRoute.Viewer -> currentMedia?.let { media ->
                    ViewerRoute(
                        media,
                        photoState,
                        viewModel,
                        showDetails,
                        adaptiveInfo,
                        onShowDetails = { showDetails = true; viewModel.loadDetails() },
                        onHideDetails = { showDetails = false },
                        onBack = { route = SurfaceRoute.Root },
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
                    )
                }
                SurfaceRoute.VideoEditor -> videoEditor?.let { session ->
                    val controller = remember(session.media.key) {
                        VideoViewerController(context).also { it.select(viewModel.mediaUri(session.media)) }
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
                        completedItems = peopleAnalysis.completedItems,
                        people = people,
                        selectedPerson = selectedPerson,
                        selectedMembers = selectedPersonMembers,
                        me = me,
                    ),
                    thumbnailLoader = thumbnails,
                    onBack = { route = SurfaceRoute.Root },
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
                    visibleItems = trashItems.itemSnapshotList.items,
                    totalCount = trashCount,
                    onRestore = { media -> viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Trash(false)) },
                    onDeletePermanently = { media -> viewModel.openMedia(media); viewModel.beginSystemAction(media, MediaAction.Delete) },
                    onEmptyTrash = { showEmptyTrashConfirmation = true },
                    showHeader = false,
                )
                SurfaceRoute.Settings -> RecognitionSettingsContent(
                    state = FaceAnalysisUiState(
                        consentGranted = faceAnalysis.consentGranted,
                        paused = faceAnalysis.paused,
                        completedItems = faceAnalysis.completedItems,
                        status = faceAnalysis.status?.let {
                            when (it) {
                                com.ugallery.core.ml.MlCheckpoint.Status.Ready -> AnalysisStatus.Ready
                                com.ugallery.core.ml.MlCheckpoint.Status.Running -> AnalysisStatus.Running
                                com.ugallery.core.ml.MlCheckpoint.Status.Paused -> AnalysisStatus.Paused
                                com.ugallery.core.ml.MlCheckpoint.Status.Complete -> AnalysisStatus.Complete
                            }
                        },
                    ),
                    onEnable = viewModel::enableFaceDetection,
                    onPause = viewModel::pauseFaceDetection,
                    onResume = viewModel::resumeFaceDetection,
                    onAnalyzeAll = viewModel::analyzeAllFaces,
                    onDelete = viewModel::deleteFaceDetectionData,
                    petCollectionsEnabled = petCollectionsEnabled,
                    onPetCollectionsEnabledChange = {
                        if (it) viewModel.enablePetCollections() else viewModel.disablePetCollections()
                    },
                    onHideDogResults = { viewModel.suppressPetType(com.ugallery.core.ml.PetType.Dog) },
                    onHideCatResults = { viewModel.suppressPetType(com.ugallery.core.ml.PetType.Cat) },
                    onRestorePetResults = {
                        viewModel.restorePetType(com.ugallery.core.ml.PetType.Dog)
                        viewModel.restorePetType(com.ugallery.core.ml.PetType.Cat)
                    },
                    showHeader = false,
                )
                SurfaceRoute.PrivateAlbum -> PrivateAlbumContent(
                    repository = privateAlbumRepo,
                    onBack = { route = SurfaceRoute.Root },
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
                )
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
                        Button(
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
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                )
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
                    onSelectAll = {
                        if (activeRoute == SurfaceRoute.Album && selectedAlbum != null) {
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
            contentWindowInsets = if (
                route == SurfaceRoute.PhotoEditor ||
                route == SurfaceRoute.VideoEditor ||
                route == SurfaceRoute.PrivateAlbum
            ) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                when (route) {
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
                            if (trashCount > 0) TextButton(onClick = { showEmptyTrashConfirmation = true }) {
                                Text(stringResource(com.ugallery.feature.trash.R.string.trash_empty))
                            }
                        },
                    )
                    SurfaceRoute.Settings -> GalleryTopAppBar(
                        title = stringResource(com.ugallery.feature.settings.R.string.face_analysis_title),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
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
                        key = ScreenMotionKey(route, rootTab),
                        modifier = Modifier.weight(1f),
                        controls = controls,
                        content = content,
                    )
                }
            } else {
                AnimatedSurfaceBody(
                    key = ScreenMotionKey(route, rootTab),
                    modifier = Modifier.fillMaxSize().padding(padding),
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
private fun AnimatedSurfaceBody(
    key: ScreenMotionKey,
    modifier: Modifier,
    controls: @Composable (SurfaceRoute) -> Unit,
    content: @Composable (SurfaceRoute, RootTab) -> Unit,
) {
    GalleryAnimatedContent(
        targetState = key,
        modifier = modifier,
        contentKey = { it },
    ) { activeKey ->
        Column(Modifier.fillMaxSize()) {
            controls(activeKey.route)
            content(activeKey.route, activeKey.rootTab)
        }
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
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
      Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
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
        }
        Row(Modifier.fillMaxWidth()) {
            GalleryActionButton(
                onClick = onAddToAlbum,
                icon = GalleryIcons.Album,
                label = stringResource(R.string.selection_add_album),
                modifier = Modifier.weight(1f),
            )
            GalleryActionButton(
                onClick = onShare,
                icon = GalleryIcons.Share,
                label = stringResource(R.string.selection_share),
                enabled = canShare,
                modifier = Modifier.weight(1f),
            )
            GalleryActionButton(
                onClick = onFavorite,
                icon = GalleryIcons.Heart,
                label = stringResource(R.string.selection_favorite),
                modifier = Modifier.weight(1f),
            )
            GalleryActionButton(
                onClick = onTrash,
                icon = GalleryIcons.Trash,
                label = stringResource(R.string.selection_trash),
                modifier = Modifier.weight(1f),
            )
        }
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
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Button(onClick = onClose) { Text(stringResource(R.string.details_close)) } }
            if (video != null) {
                val playing = (videoState?.value as? com.ugallery.feature.viewer.VideoViewerState.Ready)?.isPlaying == true
                item { Button(onClick = { if (playing) video.pause() else video.play() }) {
                    Text(stringResource(if (playing) R.string.external_pause else R.string.external_play))
                } }
            }
            if (media.editMode) item { Button(onClick = onSaveCopy) { Text(stringResource(R.string.external_save_copy)) } }
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
    photoState: com.ugallery.feature.viewer.PhotoLoadState?,
    viewModel: GalleryViewModel,
    showDetails: Boolean,
    adaptiveInfo: GalleryAdaptiveLayoutInfo,
    onShowDetails: () -> Unit,
    onHideDetails: () -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onShareSanitized: () -> Unit,
    gazetteer: OfflineGazetteer? = null,
) {
    val context = LocalContext.current
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val detectedText by viewModel.detectedText.collectAsState()
    val placeName = remember(exif) {
        val location = (exif as? com.ugallery.core.model.ExifLoadResult.Ready)?.details?.location
        if (location != null && gazetteer != null) {
            gazetteer.reverseGeocode(location.latitude, location.longitude)?.city?.name
        } else null
    }
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
            onBack = onBack,
            onToggleFavorite = { viewModel.beginSystemAction(media, MediaAction.Favorite(!media.isFavorite)) },
            onShare = {
                context.startActivity(Intent.createChooser(viewModel.originalShareIntent(media), null))
            },
            onDetails = onShowDetails,
            onEdit = onEdit,
            onShareSanitized = onShareSanitized,
            onTrash = { viewModel.beginSystemAction(media, MediaAction.Trash(true)) },
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
}

@Composable private fun RootNavigationBar(selected: RootTab, onSelect: (RootTab) -> Unit) {
    NavigationBar { RootTab.entries.forEach { tab ->
        NavigationBarItem(
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
    NavigationRail { RootTab.entries.forEach { tab ->
        NavigationRailItem(
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
