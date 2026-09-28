package com.librestatic.lightforge

import androidx.compose.runtime.key

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import kotlinx.coroutines.CancellationException
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.LoadState
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.editing.video.VideoExportPhase
import com.librestatic.lightforge.core.data.GalleryHighlightKind
import com.librestatic.lightforge.core.mediastore.MediaAction
import com.librestatic.lightforge.core.mediastore.MediaActionPhase
import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.mediastore.ScopedMediaOperations
import com.librestatic.lightforge.core.mediastore.LocalShareSanitizer
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GalleryAnimatedContent
import com.librestatic.lightforge.core.designsystem.GalleryAnimatedVisibility
import com.librestatic.lightforge.core.designsystem.GalleryNavigationType
import com.librestatic.lightforge.core.designsystem.GalleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.GalleryMotionEdge
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryStateContent
import com.librestatic.lightforge.core.designsystem.galleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.ml.LocalAnalysisOnboardingDecision
import com.librestatic.lightforge.core.ml.UserHardwareWorkload
import com.librestatic.lightforge.core.selection.SelectionSpec
import com.librestatic.lightforge.core.selection.SelectionReducer
import com.librestatic.lightforge.core.search.SearchConcept
import com.librestatic.lightforge.core.search.SearchVocabulary
import com.librestatic.lightforge.feature.album.AlbumContent
import com.librestatic.lightforge.feature.collections.CollectionsContent
import com.librestatic.lightforge.feature.collections.MomentUnavailableContent
import com.librestatic.lightforge.feature.collections.MomentContent
import com.librestatic.lightforge.feature.collections.PeopleContent
import com.librestatic.lightforge.feature.collections.PeopleUiState
import com.librestatic.lightforge.feature.collections.formatMomentDateRange
import com.librestatic.lightforge.feature.details.DetailsContent
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import com.librestatic.lightforge.feature.photos.TimelineFocusReturn
import com.librestatic.lightforge.feature.photos.LibraryPhotosRoute
import com.librestatic.lightforge.feature.photos.PhotoHighlightUi
import com.librestatic.lightforge.feature.photos.AdaptivePagedPhotosTimeline
import com.librestatic.lightforge.feature.photos.MediaCollectionGrid
import com.librestatic.lightforge.feature.trash.TrashContent
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.feature.viewer.ViewerContent
import com.librestatic.lightforge.feature.photoeditor.PhotoEditorContent
import com.librestatic.lightforge.feature.videoeditor.VideoEditorContent
import com.librestatic.lightforge.feature.search.SearchContent
import com.librestatic.lightforge.feature.settings.AnalysisStatus
import com.librestatic.lightforge.feature.settings.FaceAnalysisUiState
import com.librestatic.lightforge.feature.settings.RecognitionSettingsContent
import com.librestatic.lightforge.feature.settings.SemanticModelCompatibilityUi
import com.librestatic.lightforge.feature.settings.SemanticModelSettingsItemUi
import com.librestatic.lightforge.feature.settings.SemanticModelSettingsUiState
import com.librestatic.lightforge.feature.privatealbum.PrivateAlbumContent
import com.librestatic.lightforge.feature.privatealbum.PrivateAlbumRepository
import com.librestatic.lightforge.feature.privatealbum.PrivateAlbumDatabase
import com.librestatic.lightforge.feature.privatealbum.BiometricGate
import com.librestatic.lightforge.feature.places.OfflineGazetteer
import com.librestatic.lightforge.feature.places.BundledGazetteer

internal enum class RootTab { Photos, Collections, Search }
/** Maps only the exact public-image identity used by a saved Motion provider. */
internal fun publicationRecoveryMotionProviderKey(sourceIdentity: String?): String? {
    val fields = Regex("content://media/([A-Za-z0-9_-]{1,128})/images/media/(0|[1-9][0-9]*)@(0|[1-9][0-9]*)/(0|[1-9][0-9]*)")
        .matchEntire(sourceIdentity ?: return null)?.groupValues ?: return null
    return "motion:${fields[1]}:${fields[2]}:Image:${fields[3]}:${fields[4]}:false"
}

internal enum class SurfaceRoute {
    Root, Updates, DeviceFolders, Album, HighlightCollection, Viewer, PhotoEditor, VideoEditor,
    Archive, Trash, Settings, About, Moment, MomentParticipants, MemoryControls, MemoriesBrowser, ManualMoment, MemoryVideo, MotionPhoto, CreationGif, LocalBackup, LocalBackupTasks, RemoteBackup, OwnSync, OfflinePlaces, LocalSharing, PetIdentity, People, Cleanup, PrivateAlbum, PrivateAlbumPicker, Collage, PdfStudio, Documents, Stacks, SmartAlbums, PublicationRecoveries,
}
internal fun retainsPhotosViewerWindow(
    requested: Boolean, route: SurfaceRoute, fromPhotos: Boolean, external: Boolean,
): Boolean = requested && fromPhotos && !external &&
    route in setOf(SurfaceRoute.Viewer, SurfaceRoute.PhotoEditor, SurfaceRoute.VideoEditor, SurfaceRoute.MotionPhoto)
private data class PrivateImportProgress(val completed: Int, val total: Int)
private data class PrivateImportOutcome(val successful: List<TimelineMedia>, val total: Int)
internal data class ScreenMotionKey(
    val route: SurfaceRoute,
    val rootTab: RootTab,
    val saveableStateKey: String? = null,
) {
    // AnimatedContent folds contentKey.hashCode into nested rememberSaveable identities.
    // Enum.hashCode is process-local, even when wrapped in a data class. Use stable names
    // so the same restored route/draft consumes its saved child state in a new process.
    override fun hashCode(): Int {
        var result = route.name.hashCode()
        result = 31 * result + rootTab.name.hashCode()
        return 31 * result + (saveableStateKey?.hashCode() ?: 0)
    }
}

private sealed interface ViewerReturnDestination {
    data class Root(val tab: RootTab) : ViewerReturnDestination
    data class Album(val key: AlbumKey) : ViewerReturnDestination
    data object Places : ViewerReturnDestination
    data object Highlight : ViewerReturnDestination
    data object Archive : ViewerReturnDestination
    data object Trash : ViewerReturnDestination
    data object People : ViewerReturnDestination
    data object Cleanup : ViewerReturnDestination
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
            ViewerReturnDestination.Trash -> listOf("trash")
            ViewerReturnDestination.Archive -> listOf("archive")
            ViewerReturnDestination.Places -> listOf("places")
            ViewerReturnDestination.Highlight -> listOf("highlight")
            ViewerReturnDestination.People -> listOf("people")
            ViewerReturnDestination.Cleanup -> listOf("cleanup")
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
            "trash" -> ViewerReturnDestination.Trash
            "archive" -> ViewerReturnDestination.Archive
            "places" -> ViewerReturnDestination.Places
            "highlight" -> ViewerReturnDestination.Highlight
            "people" -> ViewerReturnDestination.People
            "cleanup" -> ViewerReturnDestination.Cleanup
            else -> null
        }
    },
)

private const val FavoritesSearchQuery = "favorites"

private fun rootStateKey(tab: RootTab) = when (tab) {
    RootTab.Photos -> "root:photos"
    RootTab.Search -> "root:search"
    RootTab.Collections -> "root:collections"
}

private fun albumStateKey(key: AlbumKey) = when (key) {
    is AlbumKey.Physical -> "album:physical:${key.volumeName}:${key.bucketId}"
    is AlbumKey.Virtual -> "album:virtual:${key.albumId}"
}

internal fun surfaceStateKey(
    route: SurfaceRoute,
    rootTab: RootTab,
    selectedAlbum: AlbumSummary?,
    selectedHighlightId: String? = null,
    creationGifSessionId: String? = null,
    creationCollageSessionId: String? = null,
    memoryVideoSessionId: String? = null,
    manualMomentSessionId: String? = null,
    viewerIdentity: String? = null,
    videoEditorSessionId: String? = null,
): String? = when (route) {
    SurfaceRoute.Root -> rootStateKey(rootTab)
    SurfaceRoute.Album -> selectedAlbum?.key?.let(::albumStateKey)
    SurfaceRoute.Documents -> "documents"
    SurfaceRoute.Archive -> "archive"
    SurfaceRoute.Trash -> "trash"
    SurfaceRoute.VideoEditor -> videoEditorSessionId?.let { "video-editor:$it" }
    SurfaceRoute.Viewer -> viewerIdentity?.let { "viewer:$it" }
    SurfaceRoute.MotionPhoto -> viewerIdentity?.let { "motion:$it" }
    SurfaceRoute.MemoryControls -> "memory-controls"
    SurfaceRoute.ManualMoment -> manualMomentSessionId?.let { "manual-moment:$it" }
    SurfaceRoute.MemoryVideo -> memoryVideoSessionId?.let { "memory-video:$it" }
    SurfaceRoute.MemoriesBrowser -> "memories-browser"
    SurfaceRoute.Collage -> creationCollageSessionId?.let { "creation-collage:$it" }
    SurfaceRoute.CreationGif -> creationGifSessionId?.let { "creation-gif:$it" }
    SurfaceRoute.PublicationRecoveries -> "publication-recoveries"
    SurfaceRoute.LocalBackupTasks -> "local-backup-tasks"
    SurfaceRoute.RemoteBackup -> "remote-backup"
    SurfaceRoute.LocalSharing -> "local-sharing"
    SurfaceRoute.PetIdentity -> "pet-identity"
    SurfaceRoute.OwnSync -> "own-sync"
    SurfaceRoute.OfflinePlaces -> "offline-places"
    SurfaceRoute.HighlightCollection -> selectedHighlightId?.let { "highlight:$it" }
    else -> null
}

internal data class SurfaceReturn(val route: SurfaceRoute, val rootTab: RootTab)

/**
 * Places is reachable from Settings and from the Search root tab. Back must return to the
 * surface the user actually came from instead of stranding them in Settings.
 */
internal fun placesReturnTarget(requestedRoute: SurfaceRoute, requestedTab: RootTab): SurfaceReturn =
    if (requestedRoute == SurfaceRoute.Root) SurfaceReturn(SurfaceRoute.Root, requestedTab)
    else SurfaceReturn(SurfaceRoute.Settings, requestedTab)

internal fun availableSurfaceRoute(
    requested: SurfaceRoute,
    hasCurrentMedia: Boolean,
    hasSelectedAlbum: Boolean,
    hasSelectedHighlight: Boolean,
    hasPhotoEditor: Boolean = hasCurrentMedia,
    hasVideoEditor: Boolean = hasCurrentMedia,
    hasCreationGifSources: Boolean = false,
    isPhotoEditorOpening: Boolean = false,
    hasCreationCollageSources: Boolean = false,
    hasCreationCollageDraft: Boolean = false,
    hasCreationGifDraft: Boolean = false,
    hasViewerDraft: Boolean = false,
    hasVideoEditorDraft: Boolean = false,
): SurfaceRoute = when (requested) {
    SurfaceRoute.Collage -> requested.takeIf { hasCreationCollageSources || hasCreationCollageDraft } ?: SurfaceRoute.Root
    SurfaceRoute.CreationGif -> requested.takeIf { hasCreationGifSources || hasCreationGifDraft } ?: SurfaceRoute.Root
    SurfaceRoute.Viewer, SurfaceRoute.MotionPhoto -> requested.takeIf { hasCurrentMedia || hasViewerDraft } ?: SurfaceRoute.Root
    SurfaceRoute.PhotoEditor -> requested.takeIf { hasCurrentMedia && (hasPhotoEditor || isPhotoEditorOpening) }
        ?: if (hasCurrentMedia) SurfaceRoute.Viewer else SurfaceRoute.Root
    SurfaceRoute.VideoEditor -> requested.takeIf { hasVideoEditorDraft || (hasCurrentMedia && hasVideoEditor) }
        ?: if (hasCurrentMedia) SurfaceRoute.Viewer else SurfaceRoute.Root
    SurfaceRoute.Album -> requested.takeIf { hasSelectedAlbum } ?: SurfaceRoute.Root
    SurfaceRoute.HighlightCollection -> requested.takeIf { hasSelectedHighlight } ?: SurfaceRoute.Root
    else -> requested
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductionGalleryApp(
    viewModel: GalleryViewModel,
    permissions: PermissionCoordinator,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val appScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val voiceSearchUnavailable = stringResource(com.librestatic.lightforge.feature.search.R.string.search_voice_unavailable)
    val privateBiometricUnavailable = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_auth_unavailable)
    val privateLockedTitle = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_locked)
    val privateUnlockSubtitle = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_key_unlock_body)
    val privateBiometricFailed = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_biometric_failed)
    val privateImportConfirmBody = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_import_confirm_body)
    val privateImportInterrupted = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_import_interrupted)
    val privateExportFailed = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_export_failed)
    val appLockTitle = stringResource(R.string.app_lock_title)
    val appLockBody = stringResource(R.string.app_lock_body)
    val destructiveAuthTitle = stringResource(R.string.destructive_auth_title)
    val destructiveAuthBody = stringResource(R.string.destructive_auth_body)
    val access by viewModel.access.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val thumbnails by viewModel.thumbnailLoader.collectAsState()
    val currentMedia by viewModel.currentMedia.collectAsState()
    val viewerRestoreSnapshot by viewModel.viewerRestoreSnapshot.collectAsState()
    val viewerSourceChecking by viewModel.viewerSourceChecking.collectAsState()
    val viewerState by viewModel.viewerState.collectAsState()
    val selectedAlbum by viewModel.selectedAlbum.collectAsState()
    val collectionLayoutWorking by viewModel.collectionLayoutWorking.collectAsState()
    val collectionLayoutFailed by viewModel.collectionLayoutFailed.collectAsState()
    val collectionLayoutRevision by viewModel.collectionLayoutRevision.collectAsState()
    val albumCoverWorking by viewModel.albumCoverWorking.collectAsState()
    val albumCoverFailed by viewModel.albumCoverFailed.collectAsState()
    val albumCoverRevision by viewModel.albumCoverRevision.collectAsState()
    val albumRename by viewModel.albumRename.collectAsState()
    val albumDelete by viewModel.albumDelete.collectAsState()
    val selection by viewModel.selection.collectAsState()
    // 1-based pick order for the timeline's "Create PDF" badges (Phase E): SelectionSpec.Explicit
    // is backed by a LinkedHashSet (see SelectionReducer.toggle/SelectionSpec.explicit), so its
    // iteration order IS tap order — this only surfaces it, it does not change the selection
    // model. Empty whenever the current selection is not eligible to become a PDF, so it never
    // shows misleading numbers on a selection headed somewhere else (album, trash, share, ...).
    val pdfSelectionOrder = remember(selection, viewModel) {
        val explicit = selection as? com.librestatic.lightforge.core.selection.SelectionSpec.Explicit
        if (explicit == null || viewModel.selectedPdfSources().isEmpty()) emptyMap()
        else explicit.keys.withIndex().associate { (index, key) -> key to index + 1 }
    }
    var pdfReturnToDocuments by rememberSaveable { mutableStateOf(false) }
    var pendingPdfSources by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var pendingPdfRequestId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val selectionCount by viewModel.selectionCount.collectAsState()
    val photoState by viewModel.photoState.collectAsState()
    val adjacentPhotoStates by viewModel.adjacentPhotoStates.collectAsState()
    val trashCount by viewModel.trashCount.collectAsState()
    var initialStackId by rememberSaveable { mutableStateOf<String?>(null) }
    val smartAlbumRepository by viewModel.smartAlbumRepository.collectAsState()
    val memoryExclusionRepository by viewModel.memoryExclusionRepository.collectAsState()
    val memoryVideoSessionId by viewModel.memoryVideoSessionId.collectAsState()
    val manualMomentSession by viewModel.manualMomentSession.collectAsState()
    val manualMomentSessionId by viewModel.manualMomentSessionId.collectAsState()
    val manualMomentRestoring by viewModel.manualMomentRestoring.collectAsState()
    val manualMomentBusy by viewModel.manualMomentBusy.collectAsState()
    val manualMomentError by viewModel.manualMomentError.collectAsState()
    val manualMomentRecovery by viewModel.manualMomentRecovery.collectAsState()
    val manualMomentPendingCreate by viewModel.manualMomentPendingCreate.collectAsState()
    val manualMomentAcknowledgementToken by viewModel.manualMomentAcknowledgementToken.collectAsState()
    val memoryVideoSources by viewModel.memoryVideoSources.collectAsState()
    val memoryVideoRestoring by viewModel.memoryVideoRestoring.collectAsState()
    val memoryVideoTitle by viewModel.memoryVideoTitle.collectAsState()
    val memoryVideoError = stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_failure)
    val manualCreationErrorText by rememberUpdatedState(stringResource(com.librestatic.lightforge.feature.collections.R.string.manual_moment_error))
    val manualSelectionHint by rememberUpdatedState(stringResource(com.librestatic.lightforge.feature.collections.R.string.manual_moment_select_photos))
    val videoSelectionHint by rememberUpdatedState(stringResource(R.string.selection_video_photos))
    val gifCreationErrorText by rememberUpdatedState(stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_error))
    val gifSelectionHint by rememberUpdatedState(stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_no_selection))
    val resultActionUnavailable by rememberUpdatedState(stringResource(R.string.viewer_action_unavailable))

    var memoryControlsReturnToMoment by rememberSaveable { mutableStateOf(false) }
    val photoStackRepository by viewModel.photoStackRepository.collectAsState()
    val stackError = stringResource(com.librestatic.lightforge.feature.collections.R.string.stacks_error)
    val documentRepository by viewModel.documentRepository.collectAsState()
    val documentCount by viewModel.documentCount.collectAsState()
    val documentError = stringResource(com.librestatic.lightforge.feature.collections.R.string.documents_error)
    val pdfSelectionHint = stringResource(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_selection_pdf_hint)
    val archiveCount by viewModel.archiveCount.collectAsState()
    val activityEvents by viewModel.activity.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val selectedHighlight by viewModel.selectedHighlight.collectAsState()
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val search by viewModel.search.collectAsState()
    val searchIndexReady by viewModel.searchIndexReady.collectAsState()
    val semanticModels by viewModel.semanticModels.collectAsState()
    val detectedContentEnabled by viewModel.detectedContentEnabled.collectAsState()
    val peopleAnalysis by viewModel.peopleAnalysis.collectAsState()
    val petCollectionsEnabled by viewModel.petCollectionsEnabled.collectAsState()
    // Settings and the Search sheet read the same persisted master/child switches.
    val localAnalysisSwitches by viewModel.localAnalysisSwitches.collectAsState()
    val petAnalysis by viewModel.petAnalysis.collectAsState()
    val localAnalysisOnboarding by viewModel.localAnalysisOnboarding.collectAsState()
    val gallerySettings by viewModel.gallerySettings.collectAsState()
    val galleryFolderOptions by viewModel.galleryFolderOptions.collectAsState()
    val petSummary by viewModel.petSummary.collectAsState()
    val selectedMoment by viewModel.selectedMoment.collectAsState()
    val momentSummaries by viewModel.momentSummaries.collectAsState()
    val momentRepository by viewModel.momentRepository.collectAsState()
    val creationGifSession by viewModel.creationGifSession.collectAsState()
    val creationGifSources = creationGifSession?.sources.orEmpty()
    val creationGifSessionId by viewModel.creationGifSessionId.collectAsState()
    val creationGifRestoring by viewModel.creationGifRestoring.collectAsState()
    val creationCollageSession by viewModel.creationCollageSession.collectAsState()
    val creationCollageSessionId by viewModel.creationCollageSessionId.collectAsState()
    val creationCollageRestoring by viewModel.creationCollageRestoring.collectAsState()
    val creationCollageSources = creationCollageSession?.sources.orEmpty()
    val momentPlaceLabels = remember(viewModel) { viewModel::momentPlaceLabel }
    val momentMembers by viewModel.momentMembers.collectAsState(initial = emptyList())
    val people by viewModel.peopleSummaries.collectAsState()
    val hiddenPeople by viewModel.hiddenPeopleSummaries.collectAsState()
    val cleanupAnalysisEnabled by viewModel.cleanupAnalysisEnabled.collectAsState()
    val selectedPerson by viewModel.selectedPerson.collectAsState()
    val selectedPersonMembers by viewModel.selectedPersonMembers.collectAsState()
    val me by viewModel.me.collectAsState()
    val actionState by viewModel.systemAction.collectAsState()
    val external by viewModel.externalMedia.collectAsState()
    val externalPhoto by viewModel.externalPhotoState.collectAsState()
    val photoEditor by viewModel.photoEditor.collectAsState()
    val photoEditorOpening by viewModel.photoEditorOpening.collectAsState()
    val videoEditor by viewModel.videoEditor.collectAsState()
    val videoEditorOpening by viewModel.videoEditorOpening.collectAsState()
    val videoEditorSessionId by viewModel.videoEditorSessionId.collectAsState()
    val videoExports by viewModel.videoExports.collectAsState()
    val activeVideoExports = remember(videoExports) { activeVideoExportQueue(videoExports) }
    val globalExportProgress = remember(activeVideoExports) {
        activeVideoExportProgress(activeVideoExports)
    }
    val activeExportDescription = activeVideoExports.takeIf { it.isNotEmpty() }?.let {
        pluralStringResource(R.plurals.video_exports_active, it.size, it.size)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectionClearedMessage = stringResource(R.string.selection_count, 0L)
    var previousSelectionCount by remember { mutableStateOf(selectionCount) }
    LaunchedEffect(selectionCount) {
        val previous = previousSelectionCount
        previousSelectionCount = selectionCount
        // The live count leaves composition at zero. A visible, polite Snackbar covers that
        // terminal transition without a hidden focus target or an initial "0 selected" announcement.
        // A new selection cancels a queued clear message through this effect's count key.
        if (previous > 0L && selectionCount == 0L) {
            snackbarHostState.showSnackbar(selectionClearedMessage)
        }
    }
    val timeline = viewModel.timeline.collectAsLazyPagingItems()
    val physicalAlbums = viewModel.physicalAlbums.collectAsLazyPagingItems()
    val virtualAlbums = viewModel.virtualAlbums.collectAsLazyPagingItems()
    val albumItems = viewModel.albumMedia.collectAsLazyPagingItems()
    val trashItems = viewModel.trash.collectAsLazyPagingItems()
    val archiveItems = viewModel.archive.collectAsLazyPagingItems()
    val highlightItems = viewModel.highlightMedia.collectAsLazyPagingItems()
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
    var route by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    val privateAlbumRepo = remember {
        PrivateAlbumRepository.sessionBacked(context.applicationContext)
    }
    val privateFlowVisible = route == SurfaceRoute.PrivateAlbum || route == SurfaceRoute.PrivateAlbumPicker
    val privateSession = com.librestatic.lightforge.feature.privatealbum.rememberPrivateAlbumSession(
        active = privateFlowVisible,
        lifecycleOwner = lifecycleOwner,
        onRevoke = { privateAlbumRepo.revokeSession() },
    )
    DisposableEffect(privateAlbumRepo) { onDispose { privateAlbumRepo.disposeSession() } }
    val privateAlbumUnlocked = privateSession.isUnlocked
    DisposableEffect(privateFlowVisible) {
        if (!privateFlowVisible) return@DisposableEffect onDispose { }
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Keep only the headless-capable portable host stable when the global lock removes gallery UI.
    var showPrivatePortable by remember { mutableStateOf(false) }
    var privatePortableHasItems by remember { mutableStateOf(false) }
    LaunchedEffect(privateFlowVisible) {
        if (!privateFlowVisible) showPrivatePortable = false
    }
    LaunchedEffect(gallerySettings.security.appLockEnabled, appUnlocked) {
        if (gallerySettings.security.appLockEnabled && !appUnlocked) privateSession.revoke()
    }
    if (showPrivatePortable && privateFlowVisible) {
        com.librestatic.lightforge.feature.privatealbum.PrivatePortableContent(
            repository = privateAlbumRepo,
            hasItems = privatePortableHasItems,
            onClose = { showPrivatePortable = false },
            isUnlocked = privateAlbumUnlocked && (!gallerySettings.security.appLockEnabled || appUnlocked),
            onAuthenticationRequired = { privateSession.revoke() },
        )
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
    val photosInputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val photosAccessibility = remember(context) {
        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    }
    // Input-focus return is transient; it is not persisted authorization or a TalkBack receipt.
    var photosViewerOrigin by remember { mutableStateOf<MediaKey?>(null) }
    var photosViewerUsesWindow by remember { mutableStateOf(false) }
    var photosFocusReturn by remember { mutableStateOf<TimelineFocusReturn?>(null) }
    var photosFocusSequence by remember { mutableStateOf(0L) }
    LaunchedEffect(route, rootTab) {
        if (route != SurfaceRoute.Root || rootTab != RootTab.Photos) photosFocusReturn = null
    }
    LaunchedEffect(route, external) {
        if (external != null || route !in setOf(SurfaceRoute.Root, SurfaceRoute.Viewer,
                SurfaceRoute.PhotoEditor, SurfaceRoute.VideoEditor, SurfaceRoute.MotionPhoto)) {
            photosViewerUsesWindow = false
            photosViewerOrigin = null
        }
    }
    val surfaceStateHolder = rememberSaveableStateHolder()
    var viewerReturnDestination by rememberSaveable(stateSaver = ViewerReturnDestinationSaver) {
        mutableStateOf<ViewerReturnDestination?>(null)
    }
    var filter by rememberSaveable { mutableStateOf(AlbumMediaFilter.All) }
    var sort by rememberSaveable { mutableStateOf(AlbumSort.NewestFirst) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showCreateAlbum by rememberSaveable { mutableStateOf(false) }
    var showCreateMenu by rememberSaveable { mutableStateOf(false) }
    var pendingCreation by rememberSaveable { mutableStateOf<PendingCreation?>(null) }
    // A pending creation belongs to the Photos grid: leaving it, or clearing a selection that was
    // started for it, cancels the request instead of resurfacing it later out of context.
    var pendingCreationSawSelection by remember { mutableStateOf(false) }
    LaunchedEffect(pendingCreation, selectionCount) {
        when {
            pendingCreation == null -> pendingCreationSawSelection = false
            selectionCount > 0 -> pendingCreationSawSelection = true
            pendingCreationSawSelection -> pendingCreation = null
        }
    }
    LaunchedEffect(route, rootTab) {
        if (route != SurfaceRoute.Root || rootTab != RootTab.Photos) pendingCreation = null
    }
    var trashSelectionMode by rememberSaveable { mutableStateOf(false) }
    var archiveSelectionMode by rememberSaveable { mutableStateOf(false) }
    var trashMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAddToAlbum by rememberSaveable { mutableStateOf(false) }
    var reopenAddToAlbum by rememberSaveable { mutableStateOf(false) }
    var showEmptyTrashConfirmation by rememberSaveable { mutableStateOf(false) }
    var showDiscardEditorConfirmation by rememberSaveable { mutableStateOf(false) }
    var showVideoExportQueue by rememberSaveable { mutableStateOf(false) }
    var dismissedVideoExportIds by rememberSaveable { mutableStateOf("") }
    var newAlbumName by rememberSaveable { mutableStateOf("") }
    var pendingRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    var gifReturnRoute by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    var gifReturnRootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var gifPreparing by remember { mutableStateOf(false) }
    var backupReviewTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var backupTasksReturnRemote by rememberSaveable { mutableStateOf(false) }
    var momentReturnToBrowser by rememberSaveable { mutableStateOf(false) }
    var placesReturnRoute by rememberSaveable { mutableStateOf(SurfaceRoute.Settings) }
    var placesReturnRootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    fun openOfflinePlaces() {
        val target = placesReturnTarget(route, rootTab)
        placesReturnRoute = target.route
        placesReturnRootTab = target.rootTab
        route = SurfaceRoute.OfflinePlaces
    }
    fun leaveOfflinePlaces() { rootTab = placesReturnRootTab; route = placesReturnRoute }
    var collageReturnRoute by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    var collageReturnRootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var collagePreparing by remember { mutableStateOf(false) }
    var videoReturnRoute by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    var videoReturnRootTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var videoPreparing by remember { mutableStateOf(false) }
    LaunchedEffect(route, rootTab) { viewModel.cancelPendingMemoryVideoPreparation() }
    fun closeMemoryVideo() { viewModel.clearMemoryVideo(); rootTab = videoReturnRootTab; route = videoReturnRoute }
    var manualReturnRoute by rememberSaveable { mutableStateOf(SurfaceRoute.Root) }
    var manualReturnTab by rememberSaveable { mutableStateOf(RootTab.Photos) }
    var manualPreparing by remember { mutableStateOf(false) }
    LaunchedEffect(route, rootTab) { viewModel.cancelPendingManualMomentPreparation() }
    fun closeManualMoment() {
        if (!manualMomentBusy) {
            val closedId = viewModel.manualMomentSessionId.value
            viewModel.clearManualMoment()
            closedId?.let { surfaceStateHolder.removeState("manual-moment:$it") }
            rootTab = manualReturnTab; route = manualReturnRoute
        }
    }
    fun openManualMoment(keys: List<com.librestatic.lightforge.core.model.MediaKey>? = null) {
        if (manualPreparing) return
        manualPreparing = true
        val requestedRoute = route
        val requestedTab = rootTab
        val hasSelection = keys != null || viewModel.canCreateSelectionVideo()
        appScope.launch {
            try {
                val ready = try { viewModel.prepareManualMoment(keys) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (route != requestedRoute || rootTab != requestedTab) return@launch
                if (ready) {
                    pendingCreation = null
                    manualReturnRoute = requestedRoute; manualReturnTab = requestedTab
                    route = SurfaceRoute.ManualMoment
                } else if (!hasSelection) {
                    rootTab = RootTab.Photos; route = SurfaceRoute.Root
                    pendingCreation = PendingCreation.Memory
                } else {
                    snackbarHostState.showSnackbar(manualCreationErrorText)
                }
            } finally { manualPreparing = false }
        }
    }
    fun openSelectionVideo() {
        if (videoPreparing) return
        videoPreparing = true
        val requestedRoute = route
        val requestedTab = rootTab
        val hadSelection = viewModel.canCreateSelectionVideo()
        appScope.launch {
            try {
                val ready = try { viewModel.prepareSelectionVideo() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (route != requestedRoute || rootTab != requestedTab) return@launch
                if (ready) {
                    pendingCreation = null
                    videoReturnRoute = requestedRoute; videoReturnRootTab = requestedTab
                    route = SurfaceRoute.MemoryVideo
                } else if (!hadSelection) {
                    rootTab = RootTab.Photos; route = SurfaceRoute.Root
                    pendingCreation = PendingCreation.MemoryVideo
                } else {
                    snackbarHostState.showSnackbar(memoryVideoError)
                }
            } finally { videoPreparing = false }
        }
    }
    fun backKeepingCreationGif() {
        rootTab = gifReturnRootTab
        route = gifReturnRoute
    }
    fun closeCreationGif() {
        creationGifSessionId?.let { surfaceStateHolder.removeState("creation-gif:$it") }
        viewModel.clearCreationGif()
        rootTab = gifReturnRootTab
        route = gifReturnRoute
    }
    fun openCreationGif() {
        if (gifPreparing) return
        gifPreparing = true
        val requestedRoute = route
        val requestedTab = rootTab
        val hadSelection = viewModel.canCreateGif()
        val retainedSessionId = viewModel.creationGifSessionId.value
        appScope.launch {
            try {
                val ready = try { viewModel.prepareCreationGif() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (route != requestedRoute || rootTab != requestedTab) {
                    if (viewModel.creationGifSessionId.value != retainedSessionId) viewModel.clearCreationGif()
                    return@launch
                }
                if (ready) {
                    pendingCreation = null
                    gifReturnRoute = requestedRoute
                    gifReturnRootTab = requestedTab
                    route = SurfaceRoute.CreationGif
                } else if (!hadSelection) {
                    rootTab = RootTab.Photos; route = SurfaceRoute.Root
                    pendingCreation = PendingCreation.Gif
                } else {
                    snackbarHostState.showSnackbar(gifCreationErrorText)
                }
            } finally { gifPreparing = false }
        }
    }

    fun openCreationCollage() {
        if (collagePreparing) return
        collagePreparing = true
        val requestedRoute = route
        val requestedTab = rootTab
        val retainedSessionId = viewModel.creationCollageSessionId.value
        appScope.launch {
            try {
                val outcome = try { viewModel.prepareCreationCollage() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        CollagePreparation.Rejected(CollageRejection.SourceUnavailable)
                    }
                if (route != requestedRoute || rootTab != requestedTab) {
                    if (viewModel.creationCollageSessionId.value != retainedSessionId) viewModel.clearCreationCollage()
                    return@launch
                }
                when (outcome) {
                    CollagePreparation.Ready -> {
                        pendingCreation = null
                        collageReturnRoute = requestedRoute
                        collageReturnRootTab = requestedTab
                        route = SurfaceRoute.Collage
                    }
                    is CollagePreparation.Rejected -> {
                        // Nothing selected is the only case that still needs the user taken to the
                        // grid; every other rejection is explained where the user already is.
                        if (outcome.reason == CollageRejection.NothingSelected) {
                            rootTab = RootTab.Photos
                            route = SurfaceRoute.Root
                            pendingCreation = PendingCreation.Collage
                            return@launch
                        }
                        val message = collageRejectionMessage(outcome.reason, outcome.selectedCount)
                        snackbarHostState.showSnackbar(
                            message.formatArg?.let { resources.getString(message.stringRes, it) }
                                ?: resources.getString(message.stringRes),
                        )
                    }
                }
            } finally { collagePreparing = false }
        }
    }

    val privateImportSelection = remember { mutableStateMapOf<MediaKey, TimelineMedia>() }
    var privateImportProgress by remember { mutableStateOf<PrivateImportProgress?>(null) }
    var privateImportOutcome by remember { mutableStateOf<PrivateImportOutcome?>(null) }
    // Viewer "Move to private album": the picker needs an unlocked session, so the item waits here.
    var privatePendingImport by remember { mutableStateOf<TimelineMedia?>(null) }

    LaunchedEffect(privateAlbumUnlocked) {
        if (!privateAlbumUnlocked) {
            privateImportSelection.clear()
            privateImportOutcome = null
            if (route == SurfaceRoute.PrivateAlbumPicker) route = SurfaceRoute.PrivateAlbum
        }
    }
    LaunchedEffect(privateAlbumUnlocked, route, privatePendingImport) {
        val pending = privatePendingImport ?: return@LaunchedEffect
        if (route != SurfaceRoute.PrivateAlbum) {
            privatePendingImport = null
        } else if (privateAlbumUnlocked && privateSession.accessToken() != null) {
            privatePendingImport = null
            privateImportSelection.clear()
            privateImportSelection[pending.key] = pending
            route = SurfaceRoute.PrivateAlbumPicker
        }
    }

    val renderedRoute = availableSurfaceRoute(
        requested = if (route == SurfaceRoute.PrivateAlbumPicker && !privateAlbumUnlocked) SurfaceRoute.PrivateAlbum else route,
        hasCurrentMedia = currentMedia != null || external != null,
        hasSelectedAlbum = selectedAlbum != null,
        hasSelectedHighlight = selectedHighlight != null,
        hasPhotoEditor = photoEditor != null,
        isPhotoEditorOpening = photoEditorOpening,
        hasVideoEditor = videoEditor != null || videoEditorOpening,
        hasVideoEditorDraft = videoEditorSessionId != null,
        hasCreationGifSources = creationGifSources.size in 2..60,
        hasCreationGifDraft = creationGifSessionId != null,
        hasViewerDraft = viewerRestoreSnapshot != null,
        hasCreationCollageSources = creationCollageSources.size in 2..4,
        hasCreationCollageDraft = creationCollageSessionId != null,
    )
    val userHardwareWorkload = when {
        renderedRoute == SurfaceRoute.VideoEditor && videoEditor?.externalAccessBlocked != true -> UserHardwareWorkload.VideoEditor
        renderedRoute == SurfaceRoute.PhotoEditor -> UserHardwareWorkload.PhotoEditor
        renderedRoute == SurfaceRoute.Viewer && external?.kind == MediaKind.Video -> UserHardwareWorkload.VideoViewer
        renderedRoute == SurfaceRoute.Viewer && currentMedia?.kind == MediaKind.Video ->
            UserHardwareWorkload.VideoViewer
        else -> null
    }
    LaunchedEffect(userHardwareWorkload) {
        viewModel.setUserHardwareWorkload(userHardwareWorkload)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.setUserHardwareWorkload(null) }
    }
    // "Retry confirmation" after a denied system dialog is scoped to the surface that started it:
    // navigating elsewhere dismisses it instead of floating it over every tab (V-07).
    var systemActionSurface by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(renderedRoute, rootTab) {
        val surface = "${renderedRoute.name}:${rootTab.name}"
        if (systemActionSurface != null && systemActionSurface != surface) viewModel.dismissCancelledSystemAction()
        systemActionSurface = surface
    }
    LaunchedEffect(route, renderedRoute) {
        if (route != renderedRoute) {
            viewerReturnDestination = null
            showDetails = false
            route = renderedRoute
        }
    }
    LaunchedEffect(external?.uri, external?.metadataReady) {
        val item = external ?: return@LaunchedEffect
        if (!item.available && !(item.kind == MediaKind.Video && videoEditorSessionId != null)) {
            route = SurfaceRoute.Viewer
            return@LaunchedEffect
        }
        if (!item.metadataReady) return@LaunchedEffect
        if (item.editMode) {
            viewModel.openExternalEditor()
            route = if (item.kind == MediaKind.Image) SurfaceRoute.PhotoEditor else SurfaceRoute.VideoEditor
        } else if (route == SurfaceRoute.Root) {
            route = SurfaceRoute.Viewer
        }
    }

    fun leavePrivateAlbum() {
        showPrivatePortable = false
        privateImportSelection.clear()
        privateSession.revoke()
        route = SurfaceRoute.Root
    }

    /** Renews the 30 s authenticated-key window without touching the album's UI authorization. */
    suspend fun reauthenticatePrivateImport(): Boolean {
        val fragmentActivity = context as? FragmentActivity ?: return false
        if (!BiometricGate.canAuthenticate(context)) return false
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            BiometricGate.authenticate(
                activity = fragmentActivity,
                title = privateLockedTitle,
                subtitle = privateImportConfirmBody,
                onSuccess = { if (continuation.isActive) continuation.resumeWith(Result.success(true)) },
                onError = { if (continuation.isActive) continuation.resumeWith(Result.success(false)) },
                // A failed sample is not terminal; the prompt stays open for another attempt.
                onFail = {},
            )
        }
    }

    fun startPrivateImport() {
        val batch = privateImportSelection.values.toList()
        val access = privateSession.accessToken() ?: return
        if (route != SurfaceRoute.PrivateAlbumPicker || batch.isEmpty() || privateImportProgress != null) return
        privateImportProgress = PrivateImportProgress(0, batch.size)
        appScope.launch {
            val result = try {
                com.librestatic.lightforge.feature.privatealbum.importPrivateBatch(
                    items = batch,
                    resolveKey = { privateAlbumRepo.requireMasterKeyBinding() },
                    importOne = { media, key -> privateAlbumRepo.importFromMedia(media, key).success },
                    reauthenticate = ::reauthenticatePrivateImport,
                    hasAccess = { privateSession.hasAccess(access) && route == SurfaceRoute.PrivateAlbumPicker },
                    onProgress = { privateImportProgress = PrivateImportProgress(it, batch.size) },
                )
            } catch (cancelled: CancellationException) { privateImportProgress = null; throw cancelled }
            privateImportSelection.clear()
            privateImportProgress = null
            // The album may already be locked, which hides the outcome dialog; a toast is still seen.
            if (result.interrupted) Toast.makeText(context, privateImportInterrupted, Toast.LENGTH_LONG).show()
            // A previously authorized import may finish, but must not reopen a route after exit/lock.
            if (privateSession.hasAccess(access) && route == SurfaceRoute.PrivateAlbumPicker) {
                privateImportOutcome = PrivateImportOutcome(result.successful, batch.size)
                route = SurfaceRoute.PrivateAlbum
            }
        }
    }

    val gazetteer = remember { OfflineGazetteer(BundledGazetteer.load()) }
    val exportSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportGallerySettings) }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::importGallerySettings) }

    fun openViewer(destination: ViewerReturnDestination, openMedia: () -> Unit) {
        photosFocusReturn = null
        photosViewerOrigin = null
        photosViewerUsesWindow = false
        viewerReturnDestination = destination
        openMedia()
        route = SurfaceRoute.Viewer
    }

    fun backKeepingMotionPhoto() {
        route = SurfaceRoute.Viewer
    }

    fun publicationTrackingRemoved(family: String, id: String, sourceIdentity: String?) {
        // Only an explicit, durably resolved global action closes the matching feature draft.
        when (family) {
            "collage" -> if (viewModel.creationCollageSessionId.value == id) {
                surfaceStateHolder.removeState("creation-collage:$id")
                viewModel.clearCreationCollage()
            }
            "gif" -> if (viewModel.creationGifSessionId.value == id) {
                surfaceStateHolder.removeState("creation-gif:$id")
                viewModel.clearCreationGif()
            }
            "motion" -> publicationRecoveryMotionProviderKey(sourceIdentity)?.let(surfaceStateHolder::removeState)
        }
    }

    fun closeMotionPhoto() {
        viewerRestoreSnapshot?.identity?.let { surfaceStateHolder.removeState("motion:$it") }
        route = SurfaceRoute.Viewer
    }

    fun restoreViewerReturnDestination() {
        viewerRestoreSnapshot?.identity?.let {
            // A Viewer exit does not acknowledge an unresolved Motion publication.
            // Its source-keyed provider is retired only by the feature's explicit close.
            surfaceStateHolder.removeState("viewer:$it")
        }
        viewModel.clearViewerRecovery()
        val destination = viewerReturnDestination
        viewerReturnDestination = null
        when (destination) {
            is ViewerReturnDestination.Root -> {
                rootTab = destination.tab
                route = SurfaceRoute.Root
                if (destination.tab == RootTab.Photos) {
                    photosViewerOrigin?.takeUnless { photosViewerUsesWindow }?.let { origin ->
                        photosFocusSequence += 1
                        photosFocusReturn = TimelineFocusReturn(origin, photosFocusSequence)
                    }
                }
                photosViewerOrigin = null
                photosViewerUsesWindow = false
            }
            is ViewerReturnDestination.Album -> route = SurfaceRoute.Album
            ViewerReturnDestination.Archive -> route = SurfaceRoute.Archive
            ViewerReturnDestination.Trash -> route = SurfaceRoute.Trash
            ViewerReturnDestination.Places -> route = SurfaceRoute.OfflinePlaces
            ViewerReturnDestination.Highlight -> route = SurfaceRoute.HighlightCollection
            ViewerReturnDestination.People -> route = SurfaceRoute.People
            ViewerReturnDestination.Cleanup -> route = SurfaceRoute.Cleanup
            null -> route = SurfaceRoute.Root
        }
    }

    fun closeVideoEditor() {
        val closedId = videoEditorSessionId
        viewModel.closeVideoEditor()
        closedId?.let { surfaceStateHolder.removeState("video-editor:$it") }
    }

    fun handleBack() {
        when {
            showDetails -> showDetails = false
            route == SurfaceRoute.PhotoEditor -> {
                if (photoEditor?.content?.isDirty == true) showDiscardEditorConfirmation = true
                else {
                    viewModel.closePhotoEditor()
                    if (external?.editMode == true) {
                        viewModel.clearExternal()
                        activity?.setResult(Activity.RESULT_CANCELED)
                        activity?.finish()
                    } else route = SurfaceRoute.Viewer
                }
            }
            route == SurfaceRoute.VideoEditor -> {
                if (videoEditor?.content?.isDirty == true) showDiscardEditorConfirmation = true
                else {
                    closeVideoEditor()
                    if (external?.editMode == true) {
                        viewModel.clearExternal()
                        activity?.setResult(Activity.RESULT_CANCELED)
                        activity?.finish()
                    } else route = SurfaceRoute.Viewer
                }
            }
            route == SurfaceRoute.People && selectedPerson != null -> viewModel.closePerson()
            route == SurfaceRoute.PrivateAlbumPicker -> {
                privateImportSelection.clear()
                route = SurfaceRoute.PrivateAlbum
            }
            route == SurfaceRoute.PrivateAlbum -> leavePrivateAlbum()
            route == SurfaceRoute.Viewer && external != null -> {
                viewModel.clearExternal()
                activity?.finish()
            }
            route == SurfaceRoute.Viewer -> restoreViewerReturnDestination()
            route == SurfaceRoute.Moment && momentReturnToBrowser -> route = SurfaceRoute.MemoriesBrowser
            route == SurfaceRoute.MemoriesBrowser -> { rootTab = RootTab.Collections; route = SurfaceRoute.Root }
            route == SurfaceRoute.PublicationRecoveries -> { route = SurfaceRoute.Root }
            route == SurfaceRoute.LocalBackupTasks -> { backupReviewTaskId = null; route = if (backupTasksReturnRemote) SurfaceRoute.RemoteBackup else SurfaceRoute.LocalBackup }
            route == SurfaceRoute.OfflinePlaces -> leaveOfflinePlaces()
            route == SurfaceRoute.OwnSync || route == SurfaceRoute.LocalSharing || route == SurfaceRoute.PetIdentity -> { route = SurfaceRoute.Settings }
            route == SurfaceRoute.RemoteBackup -> { route = SurfaceRoute.Settings }
            route == SurfaceRoute.LocalBackup -> { backupReviewTaskId = null; route = if (backupTasksReturnRemote) SurfaceRoute.RemoteBackup else SurfaceRoute.Settings }
            // The feature owns confirmed disposal. A Back during restoration must retain
            // the exact session until its publication receipt has been inspected.
            route == SurfaceRoute.Collage -> { rootTab = collageReturnRootTab; route = collageReturnRoute }
            route == SurfaceRoute.CreationGif -> backKeepingCreationGif()
            route == SurfaceRoute.MotionPhoto -> backKeepingMotionPhoto()
            route == SurfaceRoute.ManualMoment -> closeManualMoment()
            route == SurfaceRoute.MemoryVideo -> closeMemoryVideo()
            route == SurfaceRoute.MomentParticipants -> route = SurfaceRoute.Moment
            route == SurfaceRoute.About -> route = SurfaceRoute.Settings
            else -> route = SurfaceRoute.Root
        }
    }

    BackHandler(enabled = renderedRoute != SurfaceRoute.Root || showDetails || selectionCount > 0) {
        // Back from a root selection returns to browsing, not to the previous Android task.
        if (renderedRoute == SurfaceRoute.Root && !showDetails && selectionCount > 0) {
            viewModel.clearSelection()
        } else handleBack()
    }

    // Back from a secondary root tab returns to Photos first, per Material navigation guidance.
    BackHandler(
        enabled = renderedRoute == SurfaceRoute.Root && !showDetails && selectionCount == 0L && rootTab != RootTab.Photos,
    ) {
        rootTab = RootTab.Photos
    }

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
    val exportNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.saveVideoEditorCopy() }
    fun startVideoExport() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            exportNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.saveVideoEditorCopy()
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
    ) { viewModel.onPermissionRequestResult() }
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
    val treeCopyComplete = stringResource(R.string.viewer_copy_complete)
    val treeMoveCopyComplete = stringResource(R.string.viewer_move_copy_complete)
    val treeActionUnavailable = stringResource(R.string.viewer_action_unavailable)
    // Keep the result key outside viewer-specific SaveableStateProvider scopes.
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        val captured = viewModel.pendingTreeOperation
        viewModel.clearTreeOperation()
        if (treeUri != null && captured != null) viewModel.startTreeOperation(captured, treeUri)
    }
    LaunchedEffect(Unit) {
        viewModel.treeOperationOutcomes.collect { outcome ->
            val message = when (outcome) {
                GalleryViewModel.TreeOperationOutcome.Copied -> treeCopyComplete
                GalleryViewModel.TreeOperationOutcome.MoveCopied -> treeMoveCopyComplete
                GalleryViewModel.TreeOperationOutcome.Failed -> treeActionUnavailable
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    val moveState by viewModel.verifiedMove.state.collectAsState()
    var showEndCopyTracking by remember { mutableStateOf(false) }
    var pendingMoveRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    val moveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingMoveRequestId?.let { viewModel.verifiedMove.onSystemResult(it, result.resultCode == Activity.RESULT_OK) }
        pendingMoveRequestId = null
    }
    val moveTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { viewModel.verifiedMove.reauthorize(it) }
    }
    LaunchedEffect(viewModel) {
        viewModel.verifiedMove.launches.collect { launch ->
            try {
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    viewModel.verifiedMove.deferLaunch(launch.requestId)
                } else if (viewModel.verifiedMove.confirmLaunch(launch.requestId)) {
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        pendingMoveRequestId = launch.requestId
                        moveLauncher.launch(IntentSenderRequest.Builder(launch.intentSender).build())
                    } else viewModel.verifiedMove.deferLaunch(launch.requestId)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                viewModel.verifiedMove.deferLaunch(launch.requestId); throw cancelled
            } catch (_: Exception) {
                pendingMoveRequestId = null; viewModel.verifiedMove.onSystemResult(launch.requestId, false)
            }
        }
    }
    LaunchedEffect(moveState.authorization) {
        val token = moveState.authorization ?: return@LaunchedEffect
        if (viewModel.verifiedMove.claimAuthorization(token)) {
            if (!gallerySettings.security.destructiveActionLockEnabled) viewModel.verifiedMove.finishAuthorization(token, true)
            else {
                val activity = context as? FragmentActivity
                if (activity == null) viewModel.verifiedMove.finishAuthorization(token, false)
                else BiometricGate.authenticate(activity, destructiveAuthTitle, destructiveAuthBody,
                    onSuccess = { viewModel.verifiedMove.finishAuthorization(token, true) },
                    onError = { viewModel.verifiedMove.finishAuthorization(token, false) },
                    onFail = { /* A failed scan is non-terminal; the same prompt may still succeed. */ })
            }
        }
    }
    LaunchedEffect(Unit) { viewModel.resumePendingSystemAction(pendingRequestId) }
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
    LaunchedEffect(Unit) {
        viewModel.editorCopyOpened.collect {
            route = SurfaceRoute.Viewer
        }
    }
    LaunchedEffect(Unit) {
        viewModel.editorCopyNotice.collect { notice -> snackbarHostState.showSnackbar(notice) }
    }
    val exportFailedMessage = stringResource(R.string.video_export_failed)
    val exportCancelledMessage = stringResource(R.string.video_export_cancelled)
    LaunchedEffect(viewModel, exportFailedMessage, exportCancelledMessage) {
        var previous = viewModel.videoExports.value.associateBy(VideoExportJob::id)
        viewModel.videoExports.collect { jobs ->
            jobs.forEach { job ->
                val message = when (videoExportTerminalAnnouncement(previous[job.id], job)) {
                    VideoExportJobStatus.Failed -> exportFailedMessage
                    VideoExportJobStatus.Cancelled -> exportCancelledMessage
                    else -> null // Completion already has its own actionable Snackbar below.
                }
                if (message != null) {
                    val contextualMessage = "${job.displayName}: $message"
                    // Keep collecting other jobs while SnackbarHost serializes visible results.
                    appScope.launch { snackbarHostState.showSnackbar(contextualMessage) }
                }
            }
            previous = jobs.associateBy(VideoExportJob::id)
        }
    }
    val exportCompleteMessage = stringResource(R.string.video_export_complete)
    val exportSoftwareMessage = stringResource(R.string.video_export_complete_software)
    val exportFallbackMessage = stringResource(R.string.video_export_complete_fallback)
    val viewExportLabel = stringResource(R.string.video_export_view)
    LaunchedEffect(Unit) {
        viewModel.videoExportCompleted.collect { job ->
            val result = snackbarHostState.showSnackbar(
                message = when {
                    job.usedSoftwareCodec -> exportSoftwareMessage
                    job.usedEncoderFallback -> exportFallbackMessage
                    else -> exportCompleteMessage
                },
                actionLabel = viewExportLabel,
                // An action label would otherwise make it Indefinite and pin it over every surface (V-07).
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val uri = android.net.Uri.parse(job.outputUri)
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }
            }
        }
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
                        focusReturn = photosFocusReturn.takeIf {
                            route == SurfaceRoute.Root && rootTab == RootTab.Photos && LocalSurfaceFocusReady.current
                        },
                        onFocusReturnConsumed = { request ->
                            if (photosFocusReturn == request) photosFocusReturn = null
                        },
                        selectionMode = selectionCount > 0,
                        access = access,
                        engineState = engineState.toUiState(),
                        entries = timeline,
                        thumbnailLoader = thumbnails,
                        onRequestAccess = ::requestAccess,
                        onOpenSettings = { route = SurfaceRoute.Settings },
                        onOpenDeviceFolders = { route = SurfaceRoute.DeviceFolders },
                        onCreate = { showCreateMenu = true },
                        onOpenUpdates = { route = SurfaceRoute.Updates },
                        // The rail already has Create, Updates (with export progress) and Settings.
                        showNavigationActions = adaptiveInfo.navigationType != GalleryNavigationType.Rail,
                        activeExportCount = activeVideoExports.size,
                        activeExportProgress = globalExportProgress,
                        activeExportDescription = activeExportDescription,
                        onOpenExportQueue = { showVideoExportQueue = true },
                        highlights = highlights.map { highlight ->
                            PhotoHighlightUi(
                                id = highlight.id,
                                title = when (highlight.kind) {
                                    GalleryHighlightKind.YearsAgo -> pluralStringResource(
                                        R.plurals.highlight_years_ago,
                                        highlight.yearsAgo ?: 1,
                                        highlight.yearsAgo ?: 1,
                                    )
                                    GalleryHighlightKind.Selfies -> stringResource(R.string.highlight_selfies)
                                },
                                cover = highlight.cover,
                                onClick = {
                                    viewModel.openHighlight(highlight)
                                    route = SurfaceRoute.HighlightCollection
                                },
                            )
                        },
                        onMediaClick = { media ->
                            if (selectionCount > 0) viewModel.toggleSelection(media)
                            else if (media.stack != null) {
                                initialStackId = requireNotNull(media.stack).id
                                route = SurfaceRoute.Stacks
                            } else openViewer(ViewerReturnDestination.Root(RootTab.Photos)) {
                                photosViewerUsesWindow = photosAccessibility?.isTouchExplorationEnabled == true
                                photosViewerOrigin = media.key.takeIf {
                                    photosInputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard ||
                                        photosAccessibility?.isTouchExplorationEnabled == true
                                }
                                viewModel.openTimelineMedia(media)
                            }
                        },
                        isMediaSelected = { media ->
                            SelectionReducer.isSelected(selection, media.key)
                        },
                        selectionOrder = { media -> pdfSelectionOrder[media.key] },
                        onMediaSelectionChange = viewModel::setMediaSelected,
                        preferredColumns = gallerySettings.thumbnails.gridColumns.takeIf { it != com.librestatic.lightforge.core.preferences.AutoGridColumns },
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
                        archiveCount,
                        momentSummaries,
                        onMomentClick = { momentReturnToBrowser = false; viewModel.openMoment(it.momentId); route = SurfaceRoute.Moment },
                        onAllMemoriesClick = { route = SurfaceRoute.MemoriesBrowser },
                        onAlbumClick = { album ->
                            viewModel.selectAlbum(album, filter, sort)
                            route = SurfaceRoute.Album
                        },
                        layoutOrder = gallerySettings.library.collectionOrder,
                        hiddenCollections = gallerySettings.library.hiddenCollections,
                        layoutWorking = collectionLayoutWorking,
                        layoutFailed = collectionLayoutFailed,
                        layoutRevision = collectionLayoutRevision,
                        onSaveLayout = viewModel::saveCollectionLayout,
                        onCreateAlbum = { showCreateAlbum = true },
                        onTrashClick = { route = SurfaceRoute.Trash },
                        onArchiveClick = { route = SurfaceRoute.Archive },
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
                        pdfStudioLabel = stringResource(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_studio),
                        pdfStudioBody = stringResource(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_local),
                        documentCount = documentCount,
                        onSmartAlbumsClick = { route = SurfaceRoute.SmartAlbums },
                        onMemoryControlsClick = { memoryControlsReturnToMoment = false; route = SurfaceRoute.MemoryControls },
                        onDocumentsClick = { route = SurfaceRoute.Documents },
                        onStacksClick = { initialStackId = null; route = SurfaceRoute.Stacks },
                        onPdfStudioClick = { pdfReturnToDocuments = false; route = SurfaceRoute.PdfStudio },
                        collageLabel = stringResource(R.string.m6_collage),
                        onCollageClick = ::openCreationCollage,
                        // Reuses the Search favorites filter ("favorites" sets favoriteOnly).
                        onCleanupClick = { route = SurfaceRoute.Cleanup },
                        onFavoritesClick = {
                            viewModel.setSearchQuery(FavoritesSearchQuery)
                            viewModel.search(FavoritesSearchQuery)
                            rootTab = RootTab.Search
                        },
                        momentPlaceLabels = momentPlaceLabels,
                    )
                    RootTab.Search -> {
                        val archivedKeys by viewModel.archivedMediaKeys.collectAsState()
                        SearchContent(
                            query = search.query,
                            hits = search.hits,
                            loading = search.loading,
                            terminal = search.terminal,
                            partialIndex = !searchIndexReady,
                            onRetry = { viewModel.search() },
                            error = search.error,
                            semanticUnavailable = search.semanticUnavailable,
                            detectedContentEnabled = localAnalysisSwitches.isActive(com.librestatic.lightforge.core.ml.LocalAnalysisFeature.Content),
                            thumbnailLoader = thumbnails,
                            petCollection = when (SearchVocabulary.resolve(search.query.trim())) {
                                SearchConcept.Dog -> "dog" to petSummary.dogCount
                                SearchConcept.Cat -> "cat" to petSummary.catCount
                                else -> null
                            },
                            // Pet searches read the collection's own labels, so re-running the
                            // query here shows the collection's photos in place.
                            onOpenPetCollection = { label ->
                                viewModel.setSearchQuery(label)
                                viewModel.search(label)
                            },
                            onOpenPlaces = ::openOfflinePlaces,
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
                            onPresetSearch = { preset ->
                                if (SearchVocabulary.resolve(preset) == SearchConcept.Document) route = SurfaceRoute.Documents
                                else viewModel.search(preset)
                            },
                            onLoadMore = viewModel::loadMoreSearch,
                            onHit = { hit ->
                                openViewer(ViewerReturnDestination.Root(RootTab.Search)) {
                                    viewModel.openSearchHit(hit)
                                }
                            },
                            // Enable = master + photo content and text only; people stays a separate opt-in.
                            onEnableDetectedContent = { viewModel.setContentAnalysisEnabled(true) },
                            onPauseDetectedContent = { viewModel.setAllLocalAnalysisEnabled(false) },
                            peopleAnalysisEnabled = localAnalysisSwitches.isActive(com.librestatic.lightforge.core.ml.LocalAnalysisFeature.People),
                            onEnablePeopleAnalysis = { viewModel.setPeopleAnalysisEnabled(true) },
                            onDeleteDetectedContent = viewModel::deleteAllLocalAnalysisData,
                            isArchived = { hit -> "${hit.key.volumeName}:${hit.key.mediaStoreId}" in archivedKeys },
                        )
                    }
                }
                SurfaceRoute.Updates -> UpdatesContent(
                    activityEvents,
                    showTitle = adaptiveInfo.navigationType == GalleryNavigationType.Rail,
                )
                SurfaceRoute.DeviceFolders -> DeviceFoldersContent(
                    albums = physicalAlbums,
                    onAlbumClick = { album ->
                        viewModel.selectAlbum(album, filter, sort)
                        route = SurfaceRoute.Album
                    },
                )
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
                            onMediaSelectionChange = viewModel::setMediaSelected,
                            onRenameAlbum = { viewModel.beginAlbumRename(album) },
                            onDeleteAlbum = deletableVirtualAlbumId(album.key)?.let { { viewModel.beginAlbumDelete(album) } },
                            deleteAlbumLabel = stringResource(R.string.album_delete),
                            onSetCover = { viewModel.setAlbumCover(album, it) },
                            coverWorking = albumCoverWorking,
                            coverFailed = albumCoverFailed,
                            coverRevision = albumCoverRevision,
                            showHeader = false,
                        )
                    }
                }
                SurfaceRoute.Viewer -> external?.let { externalMedia ->
                    ExternalViewer(
                        media = externalMedia,
                        photo = externalPhoto,
                        onClose = ::handleBack,
                        onEdit = {
                            viewModel.openExternalEditor()
                            route = if (externalMedia.kind == MediaKind.Image) SurfaceRoute.PhotoEditor else SurfaceRoute.VideoEditor
                        },
                    )
                } ?: currentMedia?.let { media ->
                    ViewerRoute(
                        media,
                        viewerState.items,
                        photoState,
                        adjacentPhotoStates,
                        thumbnails,
                        viewModel,
                        showDetails,
                        adaptiveInfo,
                        onTreeOperation = { move ->
                            if (viewModel.prepareTreeOperation(media, move)) treeLauncher.launch(null)
                            else Toast.makeText(context, treeActionUnavailable, Toast.LENGTH_SHORT).show()
                        },
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
                        onMotionPhoto = { route = SurfaceRoute.MotionPhoto },
                        onShareSanitized = { viewModel.sanitizedShare(media) },
                        gazetteer = gazetteer,
                        sessionVideoMuted = sessionVideoMuted,
                        onSessionVideoMutedChange = { sessionVideoMuted = it },
                        trashContext = viewerReturnDestination == ViewerReturnDestination.Trash,
                        onMoveToPrivate = {
                            privatePendingImport = media
                            route = SurfaceRoute.PrivateAlbum
                        },
                    )
                } ?: PublicMediaRecoveryContent(
                    title = stringResource(R.string.external_unavailable),
                    body = stringResource(if (viewerSourceChecking) com.librestatic.lightforge.feature.photos.R.string.library_loading_body else R.string.external_unavailable),
                    loading = viewerSourceChecking,
                    onBack = ::restoreViewerReturnDestination,
                )
                SurfaceRoute.PhotoEditor -> {
                    if (photoEditor == null && photoEditorOpening) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            GalleryLoadingIndicator()
                        }
                    }
                    photoEditor?.let { session ->
                    PhotoEditorContent(
                        state = session.content,
                        onBack = ::handleBack,
                        onSaveCopy = viewModel::savePhotoEditorCopy,
                        onApply = viewModel::applyPhotoEdit,
                        onUndo = viewModel::undoPhotoEdit,
                        onRedo = viewModel::redoPhotoEdit,
                        onRawSettingsChange = viewModel::previewRawDevelopment,
                        onRawSettingsChangeFinished = viewModel::commitRawDevelopment,
                        onTonePreview = viewModel::previewPhotoTone,
                        onToneChangeFinished = viewModel::commitPhotoTone,
                        onApplyAutoSuggestion = viewModel::applyPhotoAutoSuggestion,
                        onRawOutputFormatChange = viewModel::setRawOutputFormat,
                        onApplyObjectErase = viewModel::applyObjectErase,
                        onClearObjectErase = viewModel::clearObjectErase,
                        onPreviewSubjectClip = viewModel::previewSubjectClip,
                        onSaveSubjectClip = viewModel::saveSubjectClip,
                    )
                    }
                }
                SurfaceRoute.VideoEditor -> {
                    if (videoEditor == null) {
                        PublicMediaRecoveryContent(
                            title = stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_title),
                            body = stringResource(if (videoEditorOpening) com.librestatic.lightforge.feature.photos.R.string.library_loading_body else R.string.external_unavailable),
                            loading = videoEditorOpening, onBack = ::handleBack,
                        )
                    }
                    videoEditor?.let { session ->
                        val editorState = rememberSaveableStateHolder()
                        if (session.externalAccessBlocked) {
                            ExternalVideoAccessContent(onBack = ::handleBack, onRetry = viewModel::retryExternalVideoAccess,
                                checking = session.externalAccessChecking, sourceChanged = session.externalSourceChanged)
                        } else editorState.SaveableStateProvider(session.id) {
                        val controller = remember(session.id) {
                        VideoViewerController(
                            context,
                            enableVideoEffects = true,
                            initialLooping = true,
                        ).also {
                            it.select(session.source.uri, autoplay = false)
                        }
                    }
                    DisposableEffect(controller) { onDispose { controller.close() } }
                    VideoEditorContent(
                        sessionId = session.id,
                        state = session.content,
                        onPositionCheckpoint = { viewModel.checkpointVideoPosition(session.id, it) },
                        controller = controller,
                        onBack = ::handleBack,
                        onSaveCopy = ::startVideoExport,
                        onSpeedChange = viewModel::setVideoSpeed,
                        onOriginalVolumeChange = viewModel::setVideoOriginalVolume,
                        onChooseMusic = { musicPicker.launch(arrayOf("audio/*")) },
                        onRemoveMusic = viewModel::removeVideoMusic,
                        onMusicVolumeChange = viewModel::setVideoMusicVolume,
                        onSeek = { position -> controller.seekTo(viewModel.seekVideo(position)) },
                        onTrimChange = viewModel::setVideoTrim,
                        onColorGradeChange = viewModel::setVideoColorGrade,
                        onOutputQualityChange = viewModel::setVideoOutputQuality,
                        onDynamicRangeChange = viewModel::setVideoDynamicRange,
                        onGeometryChange = viewModel::setVideoGeometry,
                        onImportLut = { lutPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
                        onMarkSlowMotionIn = viewModel::markVideoSlowMotionIn,
                        onMarkSlowMotionOut = viewModel::markVideoSlowMotionOut,
                        onSelectSlowMotionSegment = viewModel::selectVideoSlowMotionSegment,
                        onUpdateSlowMotionSegment = viewModel::updateVideoSlowMotionSegment,
                        onDeleteSlowMotionSegment = viewModel::deleteVideoSlowMotionSegment,
                        onAddAnnotation = viewModel::addVideoAnnotation,
                        onUpdateAnnotation = viewModel::updateVideoAnnotation,
                        onEraseAnnotations = viewModel::eraseVideoAnnotations,
                        onSelectAnnotation = viewModel::selectVideoAnnotation,
                        onDeleteAnnotation = viewModel::deleteVideoAnnotation,
                        onMoveAnnotation = viewModel::moveVideoAnnotation,
                        onClearAnnotations = viewModel::clearVideoAnnotations,
                        onUndoAnnotation = viewModel::undoVideoAnnotation,
                        onRedoAnnotation = viewModel::redoVideoAnnotation,
                        onAddAnnotationKeyframe = viewModel::addVideoAnnotationKeyframe,
                        onTrackAnnotation = viewModel::startVideoAnnotationTracking,
                        onCancelAnnotationTracking = viewModel::cancelVideoAnnotationTracking,
                        onCancelExport = viewModel::cancelVideoExport,
                        foldInfo = adaptiveInfo.foldInfo,
                    )
                        }
                    }
                }
                SurfaceRoute.MemoriesBrowser -> momentRepository?.let { repository ->
                    com.librestatic.lightforge.feature.collections.MemoriesBrowserContent(
                        repository = repository,
                        thumbnailLoader = thumbnails,
                        momentPlaceLabels = momentPlaceLabels,
                        onBack = { rootTab = RootTab.Collections; route = SurfaceRoute.Root },
                        onMomentClick = { momentReturnToBrowser = true; viewModel.openMoment(it.momentId); route = SurfaceRoute.Moment },
                    )
                }
                SurfaceRoute.CreationGif -> {
                    if (creationGifRestoring) Column(Modifier.fillMaxSize()) {
                        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                        GalleryTopAppBar(
                            title = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_title),
                            onBack = ::backKeepingCreationGif,
                            navigationContentDescription = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_back),
                        )
                        GalleryStateContent(
                            title = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                            body = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_body),
                            illustrationDescription = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else creationGifSession?.let { session ->
                        fun launchGifResult(intent: Intent) {
                            val chooser = Intent.createChooser(intent, null)
                            context.startActivity(chooser)
                            com.librestatic.lightforge.feature.collage.CreationGifCommitProbe.afterHandoff(context, session.id, intent, chooser)
                        }
                        com.librestatic.lightforge.feature.collage.CreationGifContent(
                            sessionId = session.id,
                            title = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_title),
                            sources = session.sources,
                            sourcesAvailable = session.sourcesAvailable,
                            onBackKeepingRecovery = ::backKeepingCreationGif,
                            onOpen = { uri -> launchGifResult(ScopedMediaOperations.viewIntent(uri, "image/gif").apply {
                                clipData = android.content.ClipData.newUri(context.contentResolver, "GIF", uri)
                            }) },
                            onShare = { uri -> launchGifResult(Intent(Intent.ACTION_SEND).apply {
                                type = "image/gif"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = android.content.ClipData.newUri(context.contentResolver, "GIF", uri)
                            }) },
                            onBack = ::closeCreationGif,
                            onExported = { viewModel.creationGifExported() },
                        )
                    } ?: Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                        Column(Modifier.fillMaxSize()) {
                            GalleryTopAppBar(
                                title = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_title),
                                onBack = ::closeCreationGif,
                                navigationContentDescription = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_back),
                            )
                            Text(stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_error), Modifier.padding(24.dp))
                        }
                    }
                }
                SurfaceRoute.PublicationRecoveries -> PublicationRecoveriesContent(
                    onBack = { route = SurfaceRoute.Root },
                    onTrackingRemoved = ::publicationTrackingRemoved,
                )
                SurfaceRoute.MotionPhoto -> {
                    val motionMedia = currentMedia
                    val snapshot = viewerRestoreSnapshot
                    if (!viewerSourceChecking && snapshot != null && snapshot.kind == MediaKind.Image && !snapshot.isTrashed) {
                        // The saved source DTO also identifies a committed result after its original disappears.
                        // Only a current, revalidated source enables rendering or Room key-frame changes.
                        val sourceAvailable = motionMedia != null && snapshot.key == motionMedia.key &&
                            snapshot.generationModified == motionMedia.generationModified &&
                            snapshot.kind == motionMedia.kind && snapshot.isTrashed == motionMedia.isTrashed
                        val coverFlow = remember(snapshot.identity, motionMedia, sourceAvailable) {
                            if (sourceAvailable) viewModel.motionKeyFrame(motionMedia)
                            else kotlinx.coroutines.flow.flowOf(null)
                        }
                        val cover by coverFlow.collectAsState(initial = null)
                        fun launchMotionResult(publicationId: String, intent: Intent) {
                            val chooser = Intent.createChooser(intent, null)
                            context.startActivity(chooser)
                            com.librestatic.lightforge.feature.motionphotos.MotionPhotoCommitProbe.afterHandoff(context, publicationId, intent, chooser)
                        }
                        com.librestatic.lightforge.feature.motionphotos.MotionPhotoContent(
                            input = com.librestatic.lightforge.feature.motionphotos.MotionPhotoInput(
                                android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Images.Media.getContentUri(snapshot.key.volumeName), snapshot.key.mediaStoreId),
                                snapshot.generationModified, snapshot.generationAdded),
                            sourceAvailable = sourceAvailable,
                            keyFrameTimeUs = cover?.timeUs,
                            onSetKeyFrame = { time, file ->
                                sourceAvailable && viewModel.setMotionKeyFrame(motionMedia, time, file, cover?.revision)
                            },
                            onResetKeyFrame = cover?.takeIf { sourceAvailable }?.let { row ->
                                { viewModel.resetMotionKeyFrame(checkNotNull(motionMedia), row.revision) }
                            },
                            onBackKeepingRecovery = ::backKeepingMotionPhoto,
                            onOpen = { publicationId, uri, kind ->
                                val mime = if (kind == com.librestatic.lightforge.feature.motionphotos.MotionPhotoPublicationKind.Clip) "video/mp4" else "image/jpeg"
                                launchMotionResult(publicationId, ScopedMediaOperations.viewIntent(uri, mime).apply {
                                    clipData = android.content.ClipData.newUri(context.contentResolver, "Motion Photo", uri)
                                })
                            },
                            onShare = { publicationId, uri, kind ->
                                launchMotionResult(publicationId, Intent(Intent.ACTION_SEND).apply {
                                    type = if (kind == com.librestatic.lightforge.feature.motionphotos.MotionPhotoPublicationKind.Clip) "video/mp4" else "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    clipData = android.content.ClipData.newUri(context.contentResolver, "Motion Photo", uri)
                                })
                            },
                            onBack = ::closeMotionPhoto,
                        )
                    } else PublicMediaRecoveryContent(
                        title = stringResource(com.librestatic.lightforge.feature.motionphotos.R.string.motion_title),
                        body = stringResource(if (viewerSourceChecking) com.librestatic.lightforge.feature.motionphotos.R.string.motion_loading
                            else com.librestatic.lightforge.feature.motionphotos.R.string.motion_error),
                        loading = viewerSourceChecking,
                        onBack = ::backKeepingMotionPhoto,
                    )
                }
                SurfaceRoute.ManualMoment -> {
                    val session = manualMomentSession
                    if (session != null) key(session.draft.id) {
                        com.librestatic.lightforge.feature.collections.ManualMomentContent(
                            draftId = session.draft.id, sources = session.sources,
                            busy = manualMomentBusy, error = manualMomentError,
                            onCreate = { title, keys, include -> appScope.launch {
                                if (viewModel.manualMomentSessionId.value != session.draft.id) return@launch
                                val savedId = viewModel.createManualMoment(title, keys, include)
                                if (savedId == session.draft.id) surfaceStateHolder.removeState("manual-moment:$savedId")
                                if (savedId == session.draft.id && route == SurfaceRoute.ManualMoment &&
                                    viewModel.manualMomentSessionId.value == null) {
                                    momentReturnToBrowser = true
                                    route = SurfaceRoute.Moment
                                }
                            } }, onCancel = ::closeManualMoment,
                        )
                    } else if (manualMomentRestoring) PublicMediaRecoveryContent(
                        title = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                        body = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_body),
                        loading = true, onBack = ::closeManualMoment,
                    ) else MomentUnavailableContent(onBack = ::closeManualMoment)
                }
                SurfaceRoute.MemoryVideo -> {
                    if (memoryVideoRestoring) Column(Modifier.fillMaxSize()) {
                        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                        GalleryStateContent(
                            title = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                            body = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_body),
                            illustrationDescription = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (memoryVideoSources.isNotEmpty()) com.librestatic.lightforge.feature.videoeditor.MemoryVideoContent(
                        title = memoryVideoTitle?.takeIf { it.isNotBlank() } ?: selectedMoment?.takeIf { videoReturnRoute == SurfaceRoute.Moment }?.let {
                            com.librestatic.lightforge.feature.collections.momentDisplayTitle(it, momentPlaceLabels)
                        } ?: stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_title),
                        sources = memoryVideoSources,
                        onBack = ::closeMemoryVideo,
                    ) else MomentUnavailableContent(onBack = ::closeMemoryVideo)
                }
                SurfaceRoute.MomentParticipants -> {
                    val momentParticipants by viewModel.momentParticipants.collectAsState()
                    val participantsLoadFailed by viewModel.participantsLoadFailed.collectAsState()
                    key(selectedMoment?.momentId) {
                        com.librestatic.lightforge.feature.collections.MomentParticipantsContent(
                            snapshot = momentParticipants?.takeIf { it.momentId == selectedMoment?.momentId },
                            onBack = { route = SurfaceRoute.Moment },
                            onApply = viewModel::applyMomentParticipants,
                            onApplied = { if (route == SurfaceRoute.MomentParticipants) route = SurfaceRoute.Moment },
                            onReload = { if (participantsLoadFailed || momentParticipants == null) viewModel.reloadMomentParticipants() },
                            loadFailed = participantsLoadFailed || selectedMoment == null,
                            thumbnailLoader = thumbnails,
                        )
                    }
                }
                SurfaceRoute.LocalSharing -> {
                    val controller by viewModel.localSharingController.collectAsState()
                    OwnStorageNetworkGate(onBack = { route = SurfaceRoute.Settings }) {
                        controller?.let { com.librestatic.lightforge.feature.localsharing.LocalSharingContent(it,
                            onBack = { route = SurfaceRoute.Settings },
                            onOpenLocalTasks = { rootTab = RootTab.Photos; route = SurfaceRoute.Root }) }
                            ?: MomentUnavailableContent(onBack = { route = SurfaceRoute.Settings })
                    }
                }
                SurfaceRoute.PetIdentity -> {
                    val repository by viewModel.petIdentityRepository.collectAsState()
                    repository?.let { com.librestatic.lightforge.feature.petrecognition.PetIdentityContent(it,
                        onBack = { route = SurfaceRoute.Settings }) }
                        ?: MomentUnavailableContent(onBack = { route = SurfaceRoute.Settings })
                }
                SurfaceRoute.OwnSync -> {
                    OwnStorageNetworkGate(onBack = { route = SurfaceRoute.Settings }) {
                        com.librestatic.lightforge.feature.ownsync.OwnSyncContent(viewModel.ownSyncController,
                            onBack = { route = SurfaceRoute.Settings },onManageServers = { route = SurfaceRoute.RemoteBackup })
                    }
                }
                SurfaceRoute.OfflinePlaces -> {
                    val source by viewModel.placesSource.collectAsState()
                    source?.let { OfflinePlacesScreen(viewModel.offlinePlacesController,it,
                        onBack = ::leaveOfflinePlaces,onPhotoClick = { key -> openViewer(ViewerReturnDestination.Places) { viewModel.openPlacePhoto(key) } },
                        thumbnail = viewModel::placeThumbnail) }
                }
                SurfaceRoute.RemoteBackup -> {
                    val controller by viewModel.remoteBackupController.collectAsState()
                    controller?.let { RemoteBackupScreen(it, onBack = { route = SurfaceRoute.Settings }, onOpenLocalTask = { backupTasksReturnRemote = true; backupReviewTaskId = null; route = SurfaceRoute.LocalBackupTasks }) }
                        ?: MomentUnavailableContent(onBack = { route = SurfaceRoute.Settings })
                }
                SurfaceRoute.LocalBackupTasks -> com.librestatic.lightforge.feature.settings.LocalBackupTasksContent(
                    controller = viewModel.backupTaskController,
                    onReviewTask = { backupReviewTaskId = it; route = SurfaceRoute.LocalBackup },
                    onBack = { backupReviewTaskId = null; route = if (backupTasksReturnRemote) SurfaceRoute.RemoteBackup else SurfaceRoute.LocalBackup },
                )
                SurfaceRoute.LocalBackup -> {
                    val organizationPort by viewModel.organizationBackup.collectAsState()
                    val recovery by viewModel.backupRecovery.collectAsState()
                    Column(Modifier.fillMaxSize()) {
                        com.librestatic.lightforge.feature.settings.LocalBackupRecoveryBanner(
                            working = recovery.working, removed = recovery.removed,
                            retained = recovery.retained, failed = recovery.failed,
                            onRetry = viewModel::recoverIncompleteBackups,
                        )
                        Box(Modifier.weight(1f)) {
                            com.librestatic.lightforge.feature.settings.LocalBackupContent(
                                organizationPort = organizationPort,
                                preferencesPort = remember(context) { com.librestatic.lightforge.core.preferences.GallerySettingsRepository(context) },
                                taskController = viewModel.backupTaskController,
                                onOpenTasks = { backupReviewTaskId = null; route = SurfaceRoute.LocalBackupTasks },
                                reviewTaskId = backupReviewTaskId,
                                onBack = { backupReviewTaskId = null; route = if (backupTasksReturnRemote) SurfaceRoute.RemoteBackup else SurfaceRoute.Settings },
                            )
                        }
                    }
                }
                SurfaceRoute.MemoryControls -> {
                    val rules = memoryExclusionRepository
                    val peopleRules = smartAlbumRepository
                    if (rules != null && peopleRules != null) com.librestatic.lightforge.feature.collections.MemoryControlsContent(
                        repository = rules, peopleRepository = peopleRules, thumbnailLoader = thumbnails,
                        onBack = { route = if (memoryControlsReturnToMoment) SurfaceRoute.Moment else SurfaceRoute.Root },
                        onAnalysis = { route = SurfaceRoute.Settings },
                    ) else MomentUnavailableContent(onBack = { route = SurfaceRoute.Root })
                }
                SurfaceRoute.Moment -> selectedMoment?.let { moment ->
                    LaunchedEffect(moment.momentId, manualMomentPendingCreate?.token, manualMomentAcknowledgementToken) {
                        if (manualMomentPendingCreate?.draft?.id == moment.momentId &&
                            manualMomentAcknowledgementToken == manualMomentPendingCreate?.token) {
                            // A loaded result must have a display frame before removing durable recovery.
                            androidx.compose.runtime.withFrameNanos { }
                            androidx.compose.runtime.withFrameNanos { }
                            if (route == SurfaceRoute.Moment && viewModel.selectedMoment.value?.momentId == moment.momentId) {
                                viewModel.acknowledgeManualMomentCreate(moment.momentId)
                            }
                        }
                    }
                    val scope = rememberCoroutineScope()
                    MomentContent(
                        moment = moment,
                        momentPlaceLabels = momentPlaceLabels,
                        onParticipants = { route = SurfaceRoute.MomentParticipants },
                        members = momentMembers,
                        thumbnailLoader = thumbnails,
                        dateLabel = formatMomentDateRange(moment.startMillis, moment.endMillis),
                        stateLabel = stringResource(R.string.moment_state_label),
                        onBack = { route = if (momentReturnToBrowser) SurfaceRoute.MemoriesBrowser else SurfaceRoute.Root },
                        onSave = { scope.launch { viewModel.saveMoment() } },
                        onDelete = { viewModel.deleteSelectedMoment(); route = if (momentReturnToBrowser) SurfaceRoute.MemoriesBrowser else SurfaceRoute.Root },
                        onRename = { scope.launch { viewModel.renameMoment(it) } },
                        onSetCover = { scope.launch { viewModel.setMomentCover(it) } },
                        onReorder = { scope.launch { viewModel.reorderMoment(it) } },
                        onMemoryControls = { memoryControlsReturnToMoment = true; route = SurfaceRoute.MemoryControls },
                        makeVideoLabel = stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_title),
                        onMakeVideo = { scope.launch {
                            val prepared = try { viewModel.prepareSelectedMemoryVideo() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            // A delayed repository result must not pull the user back from another route.
                            if (route != SurfaceRoute.Moment) return@launch
                            if (prepared) { videoReturnRoute = SurfaceRoute.Moment; videoReturnRootTab = rootTab; route = SurfaceRoute.MemoryVideo }
                            else snackbarHostState.showSnackbar(memoryVideoError)
                        } },
                    )
                } ?: MomentUnavailableContent(onBack = { route = if (momentReturnToBrowser) SurfaceRoute.MemoriesBrowser else SurfaceRoute.Root })
                SurfaceRoute.People -> PeopleContent(
                    state = PeopleUiState(
                        consentGranted = peopleAnalysis.consentGranted || people.isNotEmpty(),
                        paused = peopleAnalysis.paused,
                        running = peopleAnalysis.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running,
                        waiting = peopleAnalysis.requested && peopleAnalysis.status != com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running,
                        analysisStage = when {
                            peopleAnalysis.paused -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.Paused
                            peopleAnalysis.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.Complete
                            peopleAnalysis.activeTask == com.librestatic.lightforge.core.ml.MlTaskType.FaceDetection -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.FaceDetection
                            peopleAnalysis.activeTask == com.librestatic.lightforge.core.ml.MlTaskType.FaceEmbeddings -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.FaceEmbeddings
                            peopleAnalysis.activeTask == com.librestatic.lightforge.core.ml.MlTaskType.PersonClustering -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.PersonClustering
                            else -> com.librestatic.lightforge.feature.collections.PeopleAnalysisStage.Idle
                        },
                        completedItems = peopleAnalysis.completedItems,
                        people = people,
                        selectedPerson = selectedPerson,
                        selectedMembers = selectedPersonMembers,
                        me = me,
                        hiddenPeople = hiddenPeople,
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
                    onMemberClick = { key -> openViewer(ViewerReturnDestination.People) { viewModel.openMediaByKey(key) } },
                    onLoadMoreMembers = viewModel::loadMorePersonMembers,
                    onMergePerson = viewModel::mergePersonInto,
                    onSplitFaces = viewModel::splitPersonFaces,
                    onUnhidePerson = viewModel::unhidePerson,
                )
                SurfaceRoute.Trash -> TrashContent(
                    items = trashItems,
                    totalCount = trashCount,
                    thumbnailLoader = thumbnails,
                    selectionMode = trashSelectionMode || selectionCount > 0,
                    isSelected = { media -> SelectionReducer.isSelected(selection, media.key) },
                    onOpen = { media ->
                        openViewer(ViewerReturnDestination.Trash) { viewModel.openTrashMedia(media) }
                    },
                    onSelectionModeChange = { enabled ->
                        trashSelectionMode = enabled
                        if (!enabled) viewModel.clearSelection()
                    },
                    onSelectionChange = viewModel::setMediaSelected,
                )
                SurfaceRoute.Archive -> when {
                    archiveCount == 0L -> GalleryStateContent(
                        title = stringResource(R.string.archive_empty),
                        body = stringResource(R.string.archive_empty_body),
                        illustrationDescription = stringResource(R.string.archive_title),
                        modifier = Modifier.fillMaxSize(),
                    )
                    thumbnails != null -> MediaCollectionGrid(
                        items = archiveItems,
                        thumbnailLoader = requireNotNull(thumbnails),
                        selectionMode = archiveSelectionMode || selectionCount > 0,
                        isSelected = { media -> SelectionReducer.isSelected(selection, media.key) },
                        onOpen = { media ->
                            openViewer(ViewerReturnDestination.Archive) { viewModel.openArchiveMedia(media) }
                        },
                        onSelectionModeChange = { enabled ->
                            archiveSelectionMode = enabled
                            if (!enabled) viewModel.clearSelection()
                        },
                        onSelectionChange = viewModel::setMediaSelected,
                    )
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        GalleryLoadingIndicator()
                    }
                }
                SurfaceRoute.HighlightCollection -> thumbnails?.let { loader ->
                    MediaCollectionGrid(
                        items = highlightItems,
                        thumbnailLoader = loader,
                        selectionMode = selectionCount > 0,
                        isSelected = { media -> SelectionReducer.isSelected(selection, media.key) },
                        onOpen = { media ->
                            openViewer(ViewerReturnDestination.Highlight) { viewModel.openHighlightMedia(media) }
                        },
                        onSelectionModeChange = { enabled -> if (!enabled) viewModel.clearSelection() },
                        onSelectionChange = viewModel::setMediaSelected,
                    )
                }
                SurfaceRoute.Settings -> RecognitionSettingsContent(
                    state = FaceAnalysisUiState(
                        consentGranted = peopleAnalysis.consentGranted,
                        paused = peopleAnalysis.paused,
                        completedItems = peopleAnalysis.completedItems,
                        status = peopleAnalysis.status?.let {
                            when (it) {
                                com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Ready -> AnalysisStatus.Ready
                                com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running -> AnalysisStatus.Running
                                com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Paused -> AnalysisStatus.Paused
                                com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> AnalysisStatus.Complete
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
                                    com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Ready -> AnalysisStatus.Ready
                                    com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running -> AnalysisStatus.Running
                                    com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Paused -> AnalysisStatus.Paused
                                    com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> AnalysisStatus.Complete
                                }
                            }
                        },
                    ),
                    onPetCollectionsEnabledChange = {
                        if (it) viewModel.enablePetCollections() else viewModel.disablePetCollections()
                    },
                    onHideDogResults = { viewModel.suppressPetType(com.librestatic.lightforge.core.ml.PetType.Dog) },
                    onHideCatResults = { viewModel.suppressPetType(com.librestatic.lightforge.core.ml.PetType.Cat) },
                    onRestorePetResults = {
                        viewModel.restorePetType(com.librestatic.lightforge.core.ml.PetType.Dog)
                        viewModel.restorePetType(com.librestatic.lightforge.core.ml.PetType.Cat)
                    },
                    settings = gallerySettings,
                    folderOptions = galleryFolderOptions,
                    onSettingsChange = viewModel::updateGallerySettings,
                    onExportSettings = { exportSettingsLauncher.launch("lightforge-backup.json") },
                    onLocalBackup = { backupTasksReturnRemote = false; route = SurfaceRoute.LocalBackup },
                    onRemoteBackup = { route = SurfaceRoute.RemoteBackup },
                    onLocalSharing = { route = SurfaceRoute.LocalSharing },
                    onPetIdentity = { route = SurfaceRoute.PetIdentity },
                    onOwnSync = { route = SurfaceRoute.OwnSync },
                    onOfflinePlaces = ::openOfflinePlaces,
                    onImportSettings = { importSettingsLauncher.launch("application/json") },
                    onResetSettings = viewModel::resetGallerySettings,
                    onBack = { route = SurfaceRoute.Root },
                    peopleAnalysisEnabled = localAnalysisSwitches.isActive(com.librestatic.lightforge.core.ml.LocalAnalysisFeature.People),
                    contentAnalysisEnabled = localAnalysisSwitches.isActive(com.librestatic.lightforge.core.ml.LocalAnalysisFeature.Content),
                    localAnalysisEnabled = localAnalysisSwitches.master,
                    onAllAnalysisEnabledChange = viewModel::setAllLocalAnalysisEnabled,
                    onPeopleAnalysisEnabledChange = viewModel::setPeopleAnalysisEnabled,
                    onContentAnalysisEnabledChange = viewModel::setContentAnalysisEnabled,
                    cleanupAnalysisEnabled = cleanupAnalysisEnabled,
                    onCleanupAnalysisEnabledChange = viewModel::setCleanupAnalysisEnabled,
                    semanticModels = SemanticModelSettingsUiState(
                        enabled = semanticModels.enabled,
                        automaticSelection = semanticModels.selectionMode == com.librestatic.lightforge.feature.semanticsearch.SemanticSelectionMode.Automatic,
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
                                    if (model.descriptor.id == "tinyclip-quality") com.librestatic.lightforge.feature.settings.R.string.semantic_quality_higher
                                    else com.librestatic.lightforge.feature.settings.R.string.semantic_quality_balanced,
                                ),
                                languages = stringResource(com.librestatic.lightforge.feature.settings.R.string.semantic_language_english_focused),
                                compatibility = when (model.compatibility) {
                                    com.librestatic.lightforge.feature.semanticsearch.SemanticModelCompatibility.Recommended -> SemanticModelCompatibilityUi.Recommended
                                    com.librestatic.lightforge.feature.semanticsearch.SemanticModelCompatibility.Supported -> SemanticModelCompatibilityUi.Supported
                                    com.librestatic.lightforge.feature.semanticsearch.SemanticModelCompatibility.TechnicallyUnsupported -> SemanticModelCompatibilityUi.Unsupported
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
                    onOpenAbout = { route = SurfaceRoute.About },
                    showHeader = false,
                )
                SurfaceRoute.About -> AboutContent(
                    versionName = BuildConfig.VERSION_NAME,
                    onBack = { route = SurfaceRoute.Settings },
                )
                SurfaceRoute.PrivateAlbum -> PrivateAlbumContent(
                    repository = privateAlbumRepo,
                    onBack = ::leavePrivateAlbum,
                    onUnlockRequest = { onSuccess, onError ->
                        val fragmentActivity = context as? FragmentActivity
                        if (fragmentActivity == null || !BiometricGate.canAuthenticate(context)) {
                            onError(privateBiometricUnavailable)
                        } else if (route == SurfaceRoute.PrivateAlbum) {
                            val token = privateSession.beginAuthentication()
                            if (token != null) BiometricGate.authenticate(
                                activity = fragmentActivity,
                                title = privateLockedTitle,
                                subtitle = privateUnlockSubtitle,
                                onSuccess = {
                                    appScope.launch {
                                        if (route != SurfaceRoute.PrivateAlbum || !privateSession.isPending(token)) return@launch
                                        try {
                                            privateAlbumRepo.openSession()
                                            if (route == SurfaceRoute.PrivateAlbum && privateSession.completeAuthentication(token)) onSuccess()
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            if (route == SurfaceRoute.PrivateAlbum && privateSession.cancelAuthentication(token)) onError(privateBiometricFailed)
                                        }
                                    }
                                },
                                onError = { message ->
                                    if (route == SurfaceRoute.PrivateAlbum && privateSession.cancelAuthentication(token)) onError(message)
                                },
                                onFail = {
                                    if (route == SurfaceRoute.PrivateAlbum && privateSession.isPending(token)) onError(privateBiometricFailed)
                                },
                            )
                        }
                    },
                    onExport = export@{ mediaId, onSuccess, onError ->
                        val access = privateSession.accessToken() ?: return@export
                        if (route != SurfaceRoute.PrivateAlbum) return@export
                        appScope.launch {
                            if (!privateSession.hasAccess(access) || route != SurfaceRoute.PrivateAlbum) return@launch
                            runCatching {
                                privateAlbumRepo.exportToMediaStore(
                                    mediaId,
                                    privateAlbumRepo.requireMasterKeyBinding(),
                                ) ?: error(privateExportFailed)
                            }.onSuccess {
                                if (route == SurfaceRoute.PrivateAlbum && privateSession.hasAccess(access)) onSuccess()
                            }.onFailure {
                                if (it is CancellationException) throw it
                                if (route == SurfaceRoute.PrivateAlbum && privateSession.hasAccess(access) && com.librestatic.lightforge.core.security.PrivateAlbumCrypto.requiresAuthentication(it)) privateSession.revoke()
                                else if (route == SurfaceRoute.PrivateAlbum && privateSession.hasAccess(access)) onError(privateExportFailed)
                            }
                        }
                    },
                    isUnlocked = privateAlbumUnlocked,
                    onPortableRequest = { hasItems ->
                        if (route == SurfaceRoute.PrivateAlbum && privateSession.accessToken() != null) {
                            privatePortableHasItems = hasItems
                            showPrivatePortable = true
                        }
                    },
                    // Authorization is granted only by the generation-checked platform callback above.
                    onUnlocked = {},
                    onAddRequest = {
                        if (privateSession.accessToken() != null && route == SurfaceRoute.PrivateAlbum) {
                            privateImportSelection.clear()
                            route = SurfaceRoute.PrivateAlbumPicker
                        }
                    },
                )
                SurfaceRoute.PrivateAlbumPicker -> {
                    val pickerLimitMessage = stringResource(
                        com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_limit,
                    )
                    fun updatePrivateImportSelection(media: TimelineMedia, selected: Boolean) {
                        if (privateSession.accessToken() == null) return
                        if (!selected) {
                            privateImportSelection.remove(media.key)
                        } else if (!privateImportSelection.containsKey(media.key)) {
                            if (privateImportSelection.size < 500) {
                                privateImportSelection[media.key] = media
                            } else {
                                Toast.makeText(context, pickerLimitMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                    Text(
                        stringResource(
                            com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_count,
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
                                title = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                                body = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_body),
                                illustrationDescription = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        timeline.itemCount == 0 -> GalleryStateContent(
                            title = stringResource(com.librestatic.lightforge.feature.photos.R.string.empty_library_title),
                            body = stringResource(com.librestatic.lightforge.feature.photos.R.string.empty_library_body),
                            illustrationDescription = stringResource(com.librestatic.lightforge.feature.photos.R.string.empty_library_title),
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AdaptivePagedPhotosTimeline(
                            entries = timeline,
                            thumbnailLoader = requireNotNull(thumbnails),
                            modifier = Modifier.fillMaxSize(),
                            preferredColumns = gallerySettings.thumbnails.gridColumns.takeIf { it != com.librestatic.lightforge.core.preferences.AutoGridColumns },
                            cropThumbnails = gallerySettings.thumbnails.cropToFill,
                            onMediaClick = { media ->
                                updatePrivateImportSelection(
                                    media,
                                    !privateImportSelection.containsKey(media.key),
                                )
                            },
                            onMediaSelectionChange = ::updatePrivateImportSelection,
                            isMediaSelected = { privateImportSelection.containsKey(it.key) },
                        )
                    }
                    }
                }
                SurfaceRoute.SmartAlbums -> smartAlbumRepository?.let { repository ->
                    com.librestatic.lightforge.feature.collections.SmartAlbumsContent(
                        repository = repository, thumbnailLoader = thumbnails,
                        onBack = { route = SurfaceRoute.Root }, onAnalysis = { route = SurfaceRoute.Settings },
                    )
                }
                SurfaceRoute.Cleanup -> {
                    // Collected only here so the cleanup queries run while the screen is shown.
                    val cleanup by viewModel.cleanup.collectAsState()
                    com.librestatic.lightforge.feature.collections.CleanupContent(
                        state = cleanup,
                        thumbnailLoader = thumbnails,
                        onBack = { route = SurfaceRoute.Root },
                        onEnableAnalysis = { viewModel.setCleanupAnalysisEnabled(true) },
                        onOpen = { key, list -> openViewer(ViewerReturnDestination.Cleanup) { viewModel.openCleanupMedia(key, list) } },
                        onTrashDuplicateCopies = viewModel::trashDuplicateCopies,
                        onTrashSection = viewModel::trashCleanupSection,
                    )
                }
                SurfaceRoute.Stacks -> photoStackRepository?.let { repository ->
                    com.librestatic.lightforge.feature.collections.PhotoStacksContent(
                        repository = repository, thumbnailLoader = thumbnails, initialStackId = initialStackId,
                        onBack = { initialStackId = null; route = SurfaceRoute.Root },
                        onAnalysis = { route = SurfaceRoute.Settings },
                    )
                }
                SurfaceRoute.Documents -> documentRepository?.let { repository ->
                    com.librestatic.lightforge.feature.collections.DocumentsContent(
                        repository = repository,
                        thumbnailLoader = thumbnails,
                        onBack = { route = SurfaceRoute.Root },
                        onCreateMemory = { keys -> openManualMoment(keys) },
                        onPdfStudio = {
                            pendingPdfSources = arrayListOf()
                            pdfReturnToDocuments = true
                            route = SurfaceRoute.PdfStudio
                        },
                        onPdf = { keys ->
                            pendingPdfRequestId = java.util.UUID.randomUUID().toString()
                            pendingPdfSources = ArrayList(keys.map { key ->
                                android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.getContentUri(key.volumeName), key.mediaStoreId).toString()
                            })
                            pdfReturnToDocuments = true
                            route = SurfaceRoute.PdfStudio
                        },
                    )
                }
                SurfaceRoute.PdfStudio -> {
                val pdfLimitedBody = stringResource(com.librestatic.lightforge.feature.photos.R.string.limited_access_body)
                val pdfManageLabel = stringResource(com.librestatic.lightforge.feature.photos.R.string.manage_access_action)
                val pdfDeniedBody = stringResource(com.librestatic.lightforge.feature.photos.R.string.permission_body)
                val pdfDeniedLabel = stringResource(com.librestatic.lightforge.feature.photos.R.string.grant_access_action)
                val pdfMediaSource = remember {
                    AppPdfMediaSource(
                        context = context,
                        documentRepository = viewModel.documentRepository,
                        queryMediaRepository = viewModel.queryMediaRepository,
                        librarySettings = viewModel.librarySettings,
                        access = viewModel.access,
                        wording = PdfMediaAccessWording(pdfLimitedBody, pdfManageLabel, pdfDeniedBody, pdfDeniedLabel),
                        onRequestAccess = ::requestAccess,
                    )
                }
                LaunchedEffect(access, pdfLimitedBody, pdfManageLabel, pdfDeniedBody, pdfDeniedLabel) {
                    pdfMediaSource.updateAccess(
                        access,
                        PdfMediaAccessWording(pdfLimitedBody, pdfManageLabel, pdfDeniedBody, pdfDeniedLabel),
                        ::requestAccess,
                    )
                }
                com.librestatic.lightforge.feature.pdfstudio.PdfStudioScreen(
                onExit = { route = if (pdfReturnToDocuments) SurfaceRoute.Documents else SurfaceRoute.Root },
                initialUris = pendingPdfSources.map(android.net.Uri::parse),
                initialRequestId = pendingPdfRequestId,
                onInitialUrisConsumed = { pendingPdfSources = arrayListOf() },
                // Phase F item 1: same GalleryFoldInfo the video editor receives, so PDF Studio
                // can lay out hinge-aware and tabletop postures instead of only reacting to width.
                foldInfo = adaptiveInfo.foldInfo,
                // Phase F item 3: the app's gallery + Documents data behind the Media panel.
                mediaSource = pdfMediaSource,
            )
                }
            SurfaceRoute.Collage -> {
                fun closeCollage() { viewModel.clearCreationCollage(); rootTab = collageReturnRootTab; route = collageReturnRoute }
                if (creationCollageRestoring) Column(Modifier.fillMaxSize()) {
                    GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                    GalleryStateContent(
                        title = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                        body = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_body),
                        illustrationDescription = stringResource(com.librestatic.lightforge.feature.photos.R.string.library_loading_title),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else creationCollageSession?.let { session ->
                fun launchResult(intent: Intent) {
                    runCatching {
                        val chooser = Intent.createChooser(intent, null)
                        context.startActivity(chooser)
                        com.librestatic.lightforge.feature.collage.CreationCollageCommitProbe.afterHandoff(context, session.id, intent, chooser)
                    }
                        .onFailure { Toast.makeText(context, resultActionUnavailable, Toast.LENGTH_SHORT).show() }
                }
                com.librestatic.lightforge.feature.collage.CreationCollageContent(
                    sessionId = session.id,
                    sources = session.sources,
                    sourcesAvailable = session.sourcesAvailable,
                    onBack = ::closeCollage,
                    onBackKeepingRecovery = { rootTab = collageReturnRootTab; route = collageReturnRoute },
                    onExported = { viewModel.creationCollageExported() },
                    onOpen = { uri -> launchResult(ScopedMediaOperations.viewIntent(uri, "image/png").apply {
                        clipData = android.content.ClipData.newUri(context.contentResolver, null, uri)
                    }) },
                    onShare = { uri -> launchResult(Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newUri(context.contentResolver, null, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }) },
                )
                } ?: Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize()) {
                        GalleryTopAppBar(
                            title = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_collage_title),
                            onBack = ::closeCollage,
                            navigationContentDescription = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_collage_back),
                        )
                        Text(stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_collage_error), Modifier.padding(24.dp))
                    }
                }
            }
            }
        }
        // Contextual selection actions float over the bottom of the surface, just above the
        // navigation dock, so they sit next to the media they act on instead of under the header.
        val bottomControls: @Composable (SurfaceRoute) -> Unit = { activeRoute ->
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                GalleryAnimatedVisibility(
                    // Moment keeps library selection for Back, but owns its chrome and top inset.
                    visible = selectionCount > 0 && activeRoute !in setOf(SurfaceRoute.PublicationRecoveries, SurfaceRoute.PdfStudio, SurfaceRoute.Documents, SurfaceRoute.Stacks, SurfaceRoute.SmartAlbums, SurfaceRoute.MemoryControls, SurfaceRoute.Moment, SurfaceRoute.MomentParticipants, SurfaceRoute.ManualMoment, SurfaceRoute.MemoryVideo, SurfaceRoute.MotionPhoto, SurfaceRoute.CreationGif, SurfaceRoute.Collage, SurfaceRoute.MemoriesBrowser, SurfaceRoute.LocalBackup, SurfaceRoute.LocalBackupTasks, SurfaceRoute.RemoteBackup, SurfaceRoute.OwnSync, SurfaceRoute.OfflinePlaces, SurfaceRoute.LocalSharing, SurfaceRoute.PetIdentity),
                    edge = GalleryMotionEdge.Bottom,
                ) {
                    when (activeRoute) {
                        SurfaceRoute.Trash -> ContextSelectionActions(
                            count = selectionCount,
                            primaryIcon = GalleryIcons.Download,
                            primaryLabel = stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_restore),
                            onPrimary = {
                                trashSelectionMode = false
                                viewModel.beginSelectionSystemAction(MediaAction.Trash(false))
                            },
                            secondaryIcon = GalleryIcons.Trash,
                            secondaryLabel = stringResource(R.string.selection_delete),
                            onSecondary = {
                                runDestructive {
                                    trashSelectionMode = false
                                    viewModel.beginSelectionSystemAction(MediaAction.Delete)
                                }
                            },
                            onSelectAll = viewModel::selectAllTrash,
                            onClear = { trashSelectionMode = false; viewModel.clearSelection() },
                        )
                        SurfaceRoute.Archive -> ContextSelectionActions(
                            count = selectionCount,
                            primaryIcon = GalleryIcons.Archive,
                            primaryLabel = stringResource(R.string.archive_unarchive),
                            onPrimary = { archiveSelectionMode = false; viewModel.setSelectionArchived(false) },
                            secondaryIcon = GalleryIcons.Trash,
                            secondaryLabel = stringResource(R.string.selection_trash),
                            onSecondary = {
                                runDestructive {
                                    archiveSelectionMode = false
                                    viewModel.beginSelectionSystemAction(MediaAction.Trash(true))
                                }
                            },
                            onSelectAll = viewModel::selectAllArchive,
                            onClear = { archiveSelectionMode = false; viewModel.clearSelection() },
                        )
                        else -> SelectionActions(
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
                            onArchive = { viewModel.setSelectionArchived(true) },
                            onShare = {
                                viewModel.selectionShareIntent()?.let {
                                    context.startActivity(Intent.createChooser(it, null))
                                }
                            },
                            onStack = {
                                appScope.launch {
                                    val id = viewModel.createSelectedPhotoStack()
                                    if (id == null) snackbarHostState.showSnackbar(stackError)
                                    else { initialStackId = id; route = SurfaceRoute.Stacks }
                                }
                            },
                            onDocuments = {
                                appScope.launch {
                                    if (viewModel.organizeSelectedDocuments()) route = SurfaceRoute.Documents
                                    else snackbarHostState.showSnackbar(documentError)
                                }
                            },
                            onPdfStudio = {
                                pdfReturnToDocuments = false
                                pendingPdfRequestId = java.util.UUID.randomUUID().toString()
                                pendingPdfSources = ArrayList(viewModel.selectedPdfSources().map { it.toString() })
                                route = SurfaceRoute.PdfStudio
                                // The gallery selection does not track tap order (SelectionSpec.Explicit
                                // is a plain Set): pages land in the gallery's own display order, so the
                                // handoff says so explicitly instead of implying a numbered pick order.
                                appScope.launch { snackbarHostState.showSnackbar(pdfSelectionHint) }
                            },
                            canCreatePdf = viewModel.selectedPdfSources().isNotEmpty(),
                            canCreateMemory = viewModel.canCreateSelectionVideo(),
                            onCreateMemory = { openManualMoment() },
                            onCreateMemoryVideo = ::openSelectionVideo,
                            canCreateGif = viewModel.canCreateGif(),
                            onCreateGif = ::openCreationGif,
                            onCreateCollage = ::openCreationCollage,
                            pendingCreation = pendingCreation?.takeIf { activeRoute == SurfaceRoute.Root },
                            onCancelPendingCreation = { pendingCreation = null },
                            onClear = viewModel::clearSelection,
                        )
                    }
                }
                GalleryAnimatedVisibility(
                    visible = selectionCount == 0L && pendingCreation != null &&
                        activeRoute == SurfaceRoute.Root && rootTab == RootTab.Photos,
                    edge = GalleryMotionEdge.Bottom,
                ) {
                    val pending = pendingCreation
                    if (pending != null) PendingCreationHint(
                        creation = pending,
                        hint = when (pending) {
                            PendingCreation.Memory -> manualSelectionHint
                            PendingCreation.MemoryVideo -> videoSelectionHint
                            PendingCreation.Gif -> gifSelectionHint
                            PendingCreation.Collage -> stringResource(
                                collageRejectionMessage(CollageRejection.NothingSelected, 0).stringRes,
                            )
                        },
                        onCancel = { pendingCreation = null },
                    )
                }
            }
        }
        val controls: @Composable (SurfaceRoute) -> Unit = { activeRoute ->
            // Controls are drawn above the route's own chrome, so on routes where the scaffold
            // withholds the top inset they have to apply it themselves or they collide with the
            // status bar. The surface tint still bleeds behind the bar; only the content moves.
            val controlsTopInset = if (surfaceControlsNeedTopInset(activeRoute)) {
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            } else Modifier
            moveState.copyDraft?.let { draft ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Column(Modifier.fillMaxWidth().then(controlsTopInset).padding(12.dp).testTag("move-copy-panel")) {
                        Text(stringResource(R.string.move_copy_title))
                        Text(draft.name)
                        Text(stringResource(if (draft.destinationUri == null) R.string.move_copy_unknown else R.string.move_copy_body))
                        if (moveState.failed) Text(stringResource(R.string.move_verified_failed))
                        if (moveState.busy) Text(stringResource(R.string.move_verified_working))
                        val colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = androidx.compose.material3.LocalContentColor.current,
                            disabledContentColor = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.38f))
                        TextButton(colors = colors, onClick = { viewModel.verifiedMove.reviewInterruptedCopy() },
                            enabled = !moveState.busy && draft.destinationUri != null, modifier = Modifier.testTag("move-copy-review")) {
                            Text(stringResource(R.string.move_copy_review))
                        }
                        TextButton(colors = colors, onClick = { moveTreeLauncher.launch(android.net.Uri.parse(draft.treeUri)) },
                            enabled = !moveState.busy) { Text(stringResource(R.string.move_verified_access)) }
                        TextButton(colors = colors, onClick = { showEndCopyTracking = true }, enabled = !moveState.busy,
                            modifier = Modifier.testTag("move-copy-end-tracking")) { Text(stringResource(R.string.move_verified_forget)) }
                    }
                }
                if (showEndCopyTracking) AlertDialog(
                    onDismissRequest = { if (!moveState.busy) showEndCopyTracking = false },
                    title = { Text(stringResource(R.string.move_verified_forget)) },
                    text = { Text(stringResource(R.string.move_copy_forget_body)) },
                    confirmButton = { TextButton(onClick = { viewModel.verifiedMove.forgetInterruptedCopy(); showEndCopyTracking = false },
                        enabled = !moveState.busy, modifier = Modifier.testTag("move-copy-confirm-end")) { Text(stringResource(R.string.move_verified_forget)) } },
                    dismissButton = { TextButton(onClick = { showEndCopyTracking = false }, enabled = !moveState.busy) { Text(stringResource(android.R.string.cancel)) } },
                )
                moveState.copyReview?.let { review ->
                    AlertDialog(
                        onDismissRequest = { viewModel.verifiedMove.dismissCopyReview() },
                        title = { Text(stringResource(R.string.move_copy_review)) },
                        text = { Column {
                            Text(stringResource(R.string.move_copy_review_body))
                            Text(review.displayName ?: draft.name)
                            Text(review.destinationUri)
                            Text(stringResource(R.string.move_verified_review_identity, review.bytes, review.sha256))
                        } },
                        confirmButton = { Column {
                            TextButton(onClick = { viewModel.verifiedMove.retryReviewedCopy() }, enabled = !moveState.busy,
                                modifier = Modifier.testTag("move-copy-retry")) { Text(stringResource(R.string.move_copy_retry)) }
                            TextButton(onClick = { viewModel.verifiedMove.discardReviewedCopy() }, enabled = !moveState.busy,
                                modifier = Modifier.testTag("move-copy-discard")) { Text(stringResource(R.string.move_copy_discard)) }
                        } },
                        dismissButton = { TextButton(onClick = { viewModel.verifiedMove.dismissCopyReview() }, enabled = !moveState.busy) { Text(stringResource(android.R.string.cancel)) } },
                    )
                }
            }
            if (moveState.entry != null || moveState.unreadable) {
                val banner = verifiedMoveBanner(moveState.entry?.phase, moveState.failed, moveState.unreadable)
                val success = banner.tone == VerifiedMoveTone.Success
                Surface(
                    color = if (success) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (success) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(Modifier.fillMaxWidth().then(controlsTopInset).padding(12.dp).testTag("verified-move-panel")) {
                        Text(stringResource(when (banner.tone) {
                            VerifiedMoveTone.Success -> R.string.move_verified_complete
                            VerifiedMoveTone.Attention -> R.string.move_verified_attention
                            VerifiedMoveTone.Progress -> R.string.move_verified_pending
                        }))
                        Text(stringResource(when (banner.tone) {
                            VerifiedMoveTone.Success -> R.string.move_verified_complete_body
                            VerifiedMoveTone.Attention -> R.string.move_verified_failed
                            VerifiedMoveTone.Progress -> R.string.move_verified_body
                        }))
                        if (moveState.busy) Text(stringResource(R.string.move_verified_working))
                        val moveButtonColors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = androidx.compose.material3.LocalContentColor.current,
                            disabledContentColor = androidx.compose.material3.LocalContentColor.current.copy(alpha = 0.38f),
                        )
                        Column {
                            if (banner.showReview) {
                                TextButton(colors = moveButtonColors, onClick = { viewModel.verifiedMove.reviewUnreadable() },
                                    enabled = !moveState.busy, modifier = Modifier.testTag("verified-move-review")) {
                                    Text(stringResource(R.string.move_verified_review))
                                }
                            }
                            if (banner.showRetry) TextButton(colors = moveButtonColors, onClick = { viewModel.verifiedMove.requestAttempt() },
                                enabled = !moveState.busy,
                                modifier = Modifier.testTag("verified-move-retry")) { Text(stringResource(R.string.action_retry)) }
                            if (banner.showAccess) TextButton(colors = moveButtonColors, onClick = { moveState.entry?.proof?.treeUri?.let { moveTreeLauncher.launch(android.net.Uri.parse(it)) } },
                                enabled = !moveState.busy,
                                modifier = Modifier.testTag("verified-move-access")) { Text(stringResource(R.string.move_verified_access)) }
                            if (banner.showForget) TextButton(colors = moveButtonColors, onClick = { viewModel.verifiedMove.forget() }, enabled = !moveState.busy,
                                modifier = Modifier.testTag("verified-move-forget")) { Text(stringResource(R.string.move_verified_forget)) }
                            // A clean move is finished: dismissing it is confirmation, not abandoning a repair.
                            if (banner.showDone) TextButton(colors = moveButtonColors, onClick = { viewModel.verifiedMove.forget() }, enabled = !moveState.busy,
                                modifier = Modifier.testTag("verified-move-done")) { Text(stringResource(R.string.move_verified_done)) }
                        }
                    }
                }
            }
            moveState.corruptReview?.let { review ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { viewModel.verifiedMove.dismissCorruptReview() },
                    title = { Text(stringResource(R.string.move_verified_review)) },
                    text = { Column {
                        Text(stringResource(R.string.move_verified_review_body))
                        Text(stringResource(R.string.move_verified_review_identity, review.bytes, review.sha256))
                    } },
                    confirmButton = {
                        TextButton(onClick = { viewModel.verifiedMove.preserveReviewedCorruption() },
                            enabled = !moveState.busy, modifier = Modifier.testTag("verified-move-preserve")) {
                            Text(stringResource(R.string.move_verified_preserve))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.verifiedMove.dismissCorruptReview() },
                            enabled = !moveState.busy, modifier = Modifier.testTag("verified-move-review-cancel")) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
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
        // Retain the originating Photos nodes in their real window. TalkBack owns
        // accessibility-focus restoration when the modal viewer window closes.
        val accessibleViewerWindow = retainsPhotosViewerWindow(
            photosViewerUsesWindow, renderedRoute,
            viewerReturnDestination == ViewerReturnDestination.Root(RootTab.Photos), external != null,
        )
        val scaffoldRoute = if (accessibleViewerWindow) SurfaceRoute.Root else renderedRoute
        val internalTopBarRoute = surfaceOwnsTopBar(scaffoldRoute)
        val contentInsets = when {
            surfaceIsFullBleed(scaffoldRoute) -> WindowInsets(0, 0, 0, 0)
            internalTopBarRoute -> ScaffoldDefaults.contentWindowInsets.only(
                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
            )
            else -> ScaffoldDefaults.contentWindowInsets
        }
        val showsLibraryNavigation = scaffoldRoute == SurfaceRoute.Root ||
            scaffoldRoute == SurfaceRoute.Updates ||
            scaffoldRoute == SurfaceRoute.DeviceFolders ||
            scaffoldRoute == SurfaceRoute.Archive ||
            scaffoldRoute == SurfaceRoute.Trash ||
            scaffoldRoute == SurfaceRoute.Album ||
            scaffoldRoute == SurfaceRoute.HighlightCollection
        fun selectRoot(destination: RootTab) {
            viewModel.clearSelection()
            archiveSelectionMode = false
            trashSelectionMode = false
            rootTab = destination
            route = SurfaceRoute.Root
        }
        val dismissedExportIds = remember(dismissedVideoExportIds) {
            dismissedVideoExportIds.split(',').filter(String::isNotBlank).toSet()
        }
        val hasUndismissedExport = activeVideoExports.any { it.id !in dismissedExportIds }
        LaunchedEffect(activeVideoExports.isEmpty()) {
            if (activeVideoExports.isEmpty()) dismissedVideoExportIds = ""
        }
        val globalStatus: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth().padding(
                        horizontal = GallerySpacing.Lg,
                        vertical = GallerySpacing.Sm,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
                ) {
                    SnackbarHost(snackbarHostState)
                    GalleryAnimatedVisibility(
                        visible = hasUndismissedExport &&
                            !(renderedRoute == SurfaceRoute.VideoEditor &&
                                videoEditor?.content?.isExporting == true),
                        edge = GalleryMotionEdge.Bottom,
                    ) {
                        activeVideoExports.firstOrNull()?.let { job ->
                            VideoExportGlobalStatusCard(
                                job = job,
                                activeCount = activeVideoExports.size,
                                onOpen = { showVideoExportQueue = true },
                                onDismiss = {
                                    dismissedVideoExportIds = (
                                        dismissedExportIds + activeVideoExports.map(VideoExportJob::id)
                                    ).joinToString(",")
                                },
                                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                            )
                        }
                    }
                }
        }
        val routeBarInPane = adaptiveInfo.navigationType == GalleryNavigationType.Rail && showsLibraryNavigation
        val routeTopBar: @Composable () -> Unit = {
                when (scaffoldRoute) {
                    SurfaceRoute.Root -> Unit
                    SurfaceRoute.Album -> GalleryTopAppBar(
                        title = selectedAlbum?.name ?: stringResource(com.librestatic.lightforge.feature.album.R.string.album_untitled),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    SurfaceRoute.Updates -> if (adaptiveInfo.navigationType != GalleryNavigationType.Rail) GalleryTopAppBar(
                        title = stringResource(R.string.updates_title),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    SurfaceRoute.DeviceFolders -> GalleryTopAppBar(
                        title = stringResource(R.string.device_folders_title),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    SurfaceRoute.Archive -> GalleryTopAppBar(
                        title = stringResource(R.string.archive_title),
                        onBack = {
                            archiveSelectionMode = false
                            viewModel.clearSelection()
                            route = SurfaceRoute.Root
                        },
                        navigationContentDescription = stringResource(R.string.nav_back),
                        actions = {
                            if (archiveCount > 0 && !archiveSelectionMode && selectionCount == 0L) {
                                TextButton(onClick = { archiveSelectionMode = true }) {
                                    Text(stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_select))
                                }
                            }
                        },
                    )
                    SurfaceRoute.HighlightCollection -> GalleryTopAppBar(
                        title = selectedHighlight?.let { highlight ->
                            when (highlight.kind) {
                                GalleryHighlightKind.YearsAgo -> pluralStringResource(
                                    R.plurals.highlight_years_ago,
                                    highlight.yearsAgo ?: 1,
                                    highlight.yearsAgo ?: 1,
                                )
                                GalleryHighlightKind.Selfies -> stringResource(R.string.highlight_selfies)
                            }
                        } ?: stringResource(R.string.nav_photos),
                        onBack = { route = SurfaceRoute.Root },
                        navigationContentDescription = stringResource(R.string.nav_back),
                    )
                    SurfaceRoute.Trash -> GalleryTopAppBar(
                        title = stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_title),
                        onBack = {
                            trashSelectionMode = false
                            viewModel.clearSelection()
                            route = SurfaceRoute.Root
                        },
                        navigationContentDescription = stringResource(R.string.nav_back),
                        actions = {
                            if (trashCount > 0 && !trashSelectionMode && selectionCount == 0L) {
                                TextButton(onClick = { trashSelectionMode = true }) {
                                    Text(stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_select))
                                }
                                Box {
                                    GalleryExpressiveIconButton(onClick = { trashMenuExpanded = true }) {
                                        Icon(
                                            GalleryIcons.More,
                                            contentDescription = stringResource(com.librestatic.lightforge.feature.viewer.R.string.viewer_more),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = trashMenuExpanded,
                                        onDismissRequest = { trashMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_empty)) },
                                            leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null) },
                                            onClick = {
                                                trashMenuExpanded = false
                                                if (gallerySettings.operations.skipAppDeleteConfirmation) {
                                                    runDestructive(viewModel::emptyTrash)
                                                } else {
                                                    showEmptyTrashConfirmation = true
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                    SurfaceRoute.Settings, SurfaceRoute.About -> Unit
                    SurfaceRoute.PrivateAlbumPicker -> GalleryTopAppBar(
                        title = stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_title),
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
                                Text(stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_add))
                            }
                        },
                    )
                    else -> Unit
                }
        }
        Scaffold(
            snackbarHost = { if (!accessibleViewerWindow) globalStatus() },
            contentWindowInsets = contentInsets,
            containerColor = if (scaffoldRoute == SurfaceRoute.Viewer) Color.Black
            else MaterialTheme.colorScheme.background,
            // In rail layouts the route's bar is drawn inside the content pane (see below) so the
            // rail never shifts down when a route with a top bar opens.
            topBar = { if (!routeBarInPane) routeTopBar() },
            bottomBar = {
                if (adaptiveInfo.navigationType == GalleryNavigationType.BottomBar && scaffoldRoute == SurfaceRoute.Root) {
                    GalleryBottomDock(
                        selected = rootTab,
                        onSelect = ::selectRoot,
                    )
                }
            },
        ) { padding ->
            if (adaptiveInfo.navigationType == GalleryNavigationType.Rail) {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (showsLibraryNavigation) {
                        GalleryExpandedRail(
                            route = scaffoldRoute,
                            selectedRoot = rootTab,
                            onRoot = ::selectRoot,
                            onCreate = { showCreateMenu = true },
                            activeExportCount = activeVideoExports.size,
                            activeExportProgress = globalExportProgress,
                            activeExportDescription = activeExportDescription,
                            onOpenExportQueue = { showVideoExportQueue = true },
                            onRoute = { destination ->
                                if (destination == SurfaceRoute.MemoryControls) memoryControlsReturnToMoment = false
                                if (destination == SurfaceRoute.Stacks) initialStackId = null
                                pdfReturnToDocuments = false
                                viewModel.clearSelection()
                                archiveSelectionMode = false
                                trashSelectionMode = false
                                route = destination
                            },
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        if (routeBarInPane) {
                            // The scaffold already applied the status bar inset to this Row.
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.librestatic.lightforge.core.designsystem.LocalGalleryTopBarWindowInsets provides WindowInsets(0, 0, 0, 0),
                            ) { routeTopBar() }
                        }
                        AnimatedSurfaceBody(
                            key = ScreenMotionKey(
                                scaffoldRoute,
                                rootTab,
                                surfaceStateKey(scaffoldRoute, rootTab, selectedAlbum, selectedHighlight?.id, creationGifSessionId, creationCollageSessionId, memoryVideoSessionId, manualMomentSessionId, viewerRestoreSnapshot?.identity, videoEditorSessionId),
                            ),
                            modifier = Modifier.weight(1f),
                            stateHolder = surfaceStateHolder,
                            controls = { activeRoute -> if (!accessibleViewerWindow) controls(activeRoute) },
                            bottomControls = { activeRoute -> if (!accessibleViewerWindow) bottomControls(activeRoute) },
                            content = content,
                        )
                    }
                }
            } else {
                AnimatedSurfaceBody(
                    key = ScreenMotionKey(
                        scaffoldRoute,
                        rootTab,
                        surfaceStateKey(scaffoldRoute, rootTab, selectedAlbum, selectedHighlight?.id, creationGifSessionId, creationCollageSessionId, memoryVideoSessionId, manualMomentSessionId, viewerRestoreSnapshot?.identity, videoEditorSessionId),
                    ),
                    modifier = Modifier.fillMaxSize().padding(padding).then(
                        if (scaffoldRoute == SurfaceRoute.Root) Modifier.rootTabSwipe(rootTab, ::selectRoot)
                        else Modifier,
                    ),
                    stateHolder = surfaceStateHolder,
                    controls = { activeRoute -> if (!accessibleViewerWindow) controls(activeRoute) },
                    bottomControls = { activeRoute -> if (!accessibleViewerWindow) bottomControls(activeRoute) },
                    content = content,
                )
            }
        }
        if (accessibleViewerWindow) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = ::handleBack,
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                    dismissOnClickOutside = false,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Box(Modifier.fillMaxSize()) {
                    AnimatedSurfaceBody(
                        key = ScreenMotionKey(
                            renderedRoute, rootTab,
                            surfaceStateKey(renderedRoute, rootTab, selectedAlbum, selectedHighlight?.id,
                                creationGifSessionId, creationCollageSessionId, memoryVideoSessionId,
                                manualMomentSessionId, viewerRestoreSnapshot?.identity, videoEditorSessionId),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        stateHolder = surfaceStateHolder,
                        controls = controls,
                        bottomControls = bottomControls,
                        content = content,
                    )
                    Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { globalStatus() }
                    }
                }
            }
        }
    }

    LaunchedEffect(activeVideoExports.isEmpty()) {
        if (activeVideoExports.isEmpty()) showVideoExportQueue = false
    }
    if (showVideoExportQueue && activeVideoExports.isNotEmpty()) {
        VideoExportQueueDialog(
            jobs = activeVideoExports,
            onCancel = viewModel::cancelVideoExportJob,
            onDismiss = { showVideoExportQueue = false },
        )
    }

    if (showCreateMenu) {
        ModalBottomSheet(
            onDismissRequest = { showCreateMenu = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.create_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                CreateSheetRow(
                    icon = GalleryIcons.PhotoLibrary,
                    title = stringResource(com.librestatic.lightforge.feature.collections.R.string.manual_moment_title),
                    description = stringResource(R.string.create_memory_desc),
                    testTag = "create-memory",
                ) { showCreateMenu = false; openManualMoment() }
                CreateSheetRow(
                    icon = GalleryIcons.Video,
                    title = stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_title),
                    description = stringResource(R.string.create_memory_video_desc),
                    testTag = "create-memory-video",
                ) { showCreateMenu = false; openSelectionVideo() }
                CreateSheetRow(
                    icon = GalleryIcons.Repeat,
                    title = stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_title),
                    description = stringResource(R.string.create_gif_desc),
                    testTag = "create-gif",
                ) { showCreateMenu = false; openCreationGif() }
                CreateSheetRow(
                    icon = GalleryIcons.GridView,
                    title = stringResource(R.string.m6_collage),
                    description = stringResource(R.string.create_collage_desc),
                    testTag = "create-collage",
                ) { showCreateMenu = false; openCreationCollage() }
                CreateSheetRow(
                    icon = GalleryIcons.PictureAsPdf,
                    title = stringResource(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_studio),
                    description = stringResource(R.string.create_pdf_desc),
                    testTag = "create-pdf",
                ) { showCreateMenu = false; pdfReturnToDocuments = false; route = SurfaceRoute.PdfStudio }
                CreateSheetRow(
                    icon = GalleryIcons.Album,
                    title = stringResource(R.string.album_create_title),
                    description = stringResource(R.string.create_album_desc),
                    testTag = "create-album",
                ) { showCreateMenu = false; showCreateAlbum = true }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.create_section_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                CreateSheetRow(
                    icon = GalleryIcons.History,
                    title = stringResource(R.string.publication_recoveries_title),
                    description = stringResource(R.string.create_recoveries_desc),
                    testTag = "publication-recoveries-entry",
                ) { showCreateMenu = false; route = SurfaceRoute.PublicationRecoveries }
            }
        }
    }
    BackHandler(enabled = albumCoverWorking) { /* The pending write owns its album until completion. */ }
    albumRename?.let { rename ->
        com.librestatic.lightforge.feature.album.RenameAlbumDialog(
            name = rename.name,
            onNameChange = viewModel::updateAlbumRename,
            working = rename.working,
            saveFailed = rename.saveFailed,
            onConfirm = viewModel::confirmAlbumRename,
            onDismiss = viewModel::dismissAlbumRename,
        )
    }
    albumDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!pending.working) viewModel.dismissAlbumDelete() },
            properties = DialogProperties(dismissOnBackPress = !pending.working, dismissOnClickOutside = !pending.working),
            title = { Text(stringResource(R.string.album_delete_title, pending.name.ifEmpty { stringResource(com.librestatic.lightforge.feature.album.R.string.album_untitled) })) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.album_delete_body))
                    if (pending.deleteFailed) Text(stringResource(R.string.album_delete_failed),
                        Modifier.testTag("album-delete-error"))
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmAlbumDelete() }, enabled = !pending.working,
                    modifier = Modifier.testTag("album-delete-confirm")) {
                    Text(stringResource(if (pending.working) R.string.album_delete_deleting else R.string.album_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissAlbumDelete() }, enabled = !pending.working,
                    modifier = Modifier.testTag("album-delete-cancel")) {
                    Text(stringResource(R.string.album_delete_cancel))
                }
            },
        )
    }
    if (showCreateAlbum) {
        AlbumNameDialog(
            value = newAlbumName,
            onValue = { newAlbumName = it },
            onDismiss = { showCreateAlbum = false; reopenAddToAlbum = false },
            onConfirm = {
                viewModel.createVirtualAlbum(newAlbumName)
                newAlbumName = ""
                showCreateAlbum = false
                if (reopenAddToAlbum) { reopenAddToAlbum = false; showAddToAlbum = true }
            },
        )
    }
    if (showAddToAlbum) {
        ChooseAlbumDialog(
            albums = virtualAlbums.itemSnapshotList.items,
            onCreateAlbum = {
                showAddToAlbum = false
                reopenAddToAlbum = true
                newAlbumName = ""
                showCreateAlbum = true
            },
            onDismiss = { showAddToAlbum = false },
            onSelect = { album ->
                (album.key as? com.librestatic.lightforge.core.model.AlbumKey.Virtual)?.let {
                    viewModel.addSelectionToVirtualAlbum(it.albumId)
                }
                showAddToAlbum = false
            },
        )
    }
    manualMomentRecovery?.let { recovery ->
        val status = recovery.status
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag(
                "manual-memory-recovery-${status.name.lowercase(java.util.Locale.ROOT)}"),
            onDismissRequest = viewModel::hideManualMomentRecovery,
            title = { Text(stringResource(R.string.manual_recovery_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (status) {
                        GalleryViewModel.ManualMomentRecoveryStatus.Committed -> R.string.manual_recovery_committed
                        GalleryViewModel.ManualMomentRecoveryStatus.Missing -> R.string.manual_recovery_missing
                        GalleryViewModel.ManualMomentRecoveryStatus.Conflict -> R.string.manual_recovery_conflict
                        GalleryViewModel.ManualMomentRecoveryStatus.Unavailable -> R.string.manual_recovery_unavailable
                    }))
                    if (status == GalleryViewModel.ManualMomentRecoveryStatus.Missing) recovery.request?.let { request ->
                        if (request.title.isNotBlank()) Text(request.title)
                        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.manual_recovery_photo_count,
                            request.orderedKeys.size, request.orderedKeys.size))
                    }
                    if (recovery.request != null) Text(stringResource(R.string.manual_recovery_dismiss_info))
                    if (manualMomentError) Text(manualCreationErrorText)
                    if (manualMomentBusy) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                if (status != GalleryViewModel.ManualMomentRecoveryStatus.Conflict) TextButton(
                    enabled = !manualMomentBusy,
                    modifier = Modifier.testTag(if (status == GalleryViewModel.ManualMomentRecoveryStatus.Committed)
                        "manual-memory-recovery-open" else "manual-memory-recovery-retry"),
                    onClick = {
                        if (status == GalleryViewModel.ManualMomentRecoveryStatus.Unavailable) viewModel.checkManualMomentRecovery()
                        else {
                            val fromRoute = route; val fromTab = rootTab
                            val closedId = viewModel.manualMomentSessionId.value
                            appScope.launch {
                                val id = viewModel.resumePendingManualMomentCreate()
                                if (id != null && id == recovery.request?.draft?.id) {
                                    closedId?.takeIf { it == recovery.request?.draft?.id }?.let { surfaceStateHolder.removeState("manual-moment:$it") }
                                    if (route == fromRoute && rootTab == fromTab &&
                                        viewModel.manualMomentPendingCreate.value?.token == recovery.request.token) {
                                        momentReturnToBrowser = true
                                        route = SurfaceRoute.Moment
                                    }
                                }
                            }
                        }
                    },
                ) { Text(stringResource(when (status) {
                    GalleryViewModel.ManualMomentRecoveryStatus.Committed -> R.string.manual_recovery_open
                    GalleryViewModel.ManualMomentRecoveryStatus.Missing -> R.string.manual_recovery_retry
                    else -> R.string.manual_recovery_check
                })) }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (recovery.request != null) TextButton(enabled = !manualMomentBusy, onClick = {
                        val closedId = viewModel.manualMomentSessionId.value
                        appScope.launch {
                            if (viewModel.dismissManualMomentRecovery()) {
                                closedId?.takeIf { it == recovery.request?.draft?.id }?.let { surfaceStateHolder.removeState("manual-moment:$it") }
                                if (route == SurfaceRoute.ManualMoment && closedId == recovery.request.draft.id) { rootTab = manualReturnTab; route = manualReturnRoute }
                            }
                        }
                    }) { Text(stringResource(R.string.manual_recovery_dismiss)) }
                    TextButton(enabled = !manualMomentBusy, onClick = viewModel::hideManualMomentRecovery) {
                        Text(stringResource(R.string.manual_recovery_later))
                    }
                }
            },
        )
    }
    if (showDiscardEditorConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardEditorConfirmation = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardEditorConfirmation = false
                    if (route == SurfaceRoute.PhotoEditor) viewModel.closePhotoEditor()
                    else if (route == SurfaceRoute.VideoEditor) closeVideoEditor()
                    if (external?.editMode == true) {
                        viewModel.clearExternal()
                        activity?.setResult(Activity.RESULT_CANCELED)
                        activity?.finish()
                    } else route = SurfaceRoute.Viewer
                }) { Text(stringResource(R.string.editor_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardEditorConfirmation = false }) {
                    Text(stringResource(R.string.editor_keep_editing))
                }
            },
        )
    }
    if (showEmptyTrashConfirmation) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirmation = false },
            title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.trash_empty_confirm_body, trashCount.toInt(), trashCount)) },
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
    privateImportProgress?.takeIf { privateAlbumUnlocked && privateFlowVisible }?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_picker_title)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    GalleryLoadingIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource(
                            com.librestatic.lightforge.feature.privatealbum.R.string.private_importing,
                            progress.completed,
                            progress.total,
                        ),
                    )
                }
            },
            confirmButton = {},
        )
    }
    privateImportOutcome?.takeIf { privateAlbumUnlocked && privateFlowVisible }?.let { outcome ->
        val importedCount = outcome.successful.size
        AlertDialog(
            onDismissRequest = { privateImportOutcome = null },
            title = {
                Text(
                    stringResource(
                        if (importedCount > 0) com.librestatic.lightforge.feature.privatealbum.R.string.private_import_result_title
                        else com.librestatic.lightforge.feature.privatealbum.R.string.private_error,
                    ),
                )
            },
            text = {
                Text(
                    if (importedCount > 0) {
                        pluralStringResource(
                            com.librestatic.lightforge.feature.privatealbum.R.plurals.private_import_result,
                            importedCount,
                            importedCount,
                            pluralStringResource(
                                com.librestatic.lightforge.feature.privatealbum.R.plurals.private_import_result_items,
                                outcome.total,
                                outcome.total,
                            ),
                        )
                    } else {
                        stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_import_result_failed)
                    },
                )
            },
            confirmButton = {
                if (importedCount > 0) {
                    TextButton(onClick = deleteOriginals@{
                        val access = privateSession.accessToken() ?: return@deleteOriginals
                        val imported = outcome.successful
                        privateImportOutcome = null
                        runDestructive {
                            if (route == SurfaceRoute.PrivateAlbum && privateSession.hasAccess(access)) {
                                viewModel.beginSystemAction(imported, MediaAction.Delete)
                            }
                        }
                    }) {
                        Text(stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_delete_originals))
                    }
                } else {
                    TextButton(onClick = { privateImportOutcome = null }) {
                        Text(stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_ok))
                    }
                }
            },
            dismissButton = if (importedCount > 0) {
                {
                    TextButton(onClick = { privateImportOutcome = null }) {
                        Text(stringResource(com.librestatic.lightforge.feature.privatealbum.R.string.private_keep_originals))
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
                Text(stringResource(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_title))
            },
            text = {
                Text(stringResource(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_body))
            },
            confirmButton = {
                GalleryExpressiveButton(onClick = viewModel::acceptLocalAnalysisDefaults) {
                    Text(stringResource(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::declineLocalAnalysisDefaults) {
                    Text(stringResource(com.librestatic.lightforge.feature.settings.R.string.local_analysis_opt_out_decline))
                }
            },
        )
    }
}

@Composable
internal fun VideoExportGlobalStatusCard(
    job: VideoExportJob,
    activeCount: Int,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val announcement = videoExportAccessibilityState(job.status, job.phase, job.progressPermille)
    val spokenProgress = announcement.percent?.let { stringResource(R.string.video_export_progress_percent, it) }
    val phaseLabel = stringResource(when (job.status) {
        VideoExportJobStatus.Queued -> R.string.video_export_waiting
        VideoExportJobStatus.Completed -> R.string.video_export_complete
        VideoExportJobStatus.Failed -> R.string.video_export_failed
        VideoExportJobStatus.Cancelled -> R.string.video_export_cancelled
        VideoExportJobStatus.Running -> job.phase.queueLabelResource()
    })
    val progress = job.takeIf { it.status == VideoExportJobStatus.Running }
        ?.progressPermille
        ?.coerceIn(0, 1000)
        ?.div(1000f)
    Surface(
        onClick = onOpen,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
            ) {
                Icon(
                    GalleryIcons.Video,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        pluralStringResource(
                            R.plurals.video_exports_active,
                            activeCount,
                            activeCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        phaseLabel,
                        // Own semantic node: do not make the clickable card (and its exact
                        // percentage/range updates) an endlessly changing live region.
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            if (spokenProgress != null) stateDescription = spokenProgress
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    Text(
                        stringResource(
                            R.string.video_export_progress_percent,
                            (progress * 100f).toInt(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        GalleryIcons.Close,
                        contentDescription = stringResource(R.string.video_export_dismiss),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = GallerySpacing.Xs)) {
                if (progress == null) {
                    GalleryIndeterminateProgressIndicator(
                        color = LocalContentColor.current,
                        trackColor = LocalContentColor.current.copy(alpha = 0.2f),
                    )
                } else {
                    GalleryProgressIndicator(
                        progress = { progress },
                        color = LocalContentColor.current,
                        trackColor = LocalContentColor.current.copy(alpha = 0.2f),
                    )
                }
            }
            TextButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    stringResource(R.string.video_export_details),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun VideoExportQueueDialog(
    jobs: List<VideoExportJob>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.video_export_queue_title))
                Text(
                    pluralStringResource(R.plurals.video_exports_active, jobs.size, jobs.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = VideoExportJob::id) { job ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(job.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            val phaseLabel = if (job.status == VideoExportJobStatus.Queued) {
                                stringResource(R.string.video_export_waiting)
                            } else {
                                stringResource(job.phase.queueLabelResource())
                            }
                            val spokenProgress = videoExportAccessibilityState(job.status, job.phase, job.progressPermille)
                                .percent?.let { stringResource(R.string.video_export_progress_percent, it) }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    phaseLabel,
                                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                                        liveRegion = LiveRegionMode.Polite
                                        if (spokenProgress != null) stateDescription = spokenProgress
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (job.status == VideoExportJobStatus.Running) {
                                    Text(
                                        stringResource(
                                            R.string.video_export_progress_percent,
                                            job.progressPermille / 10,
                                        ),
                                        modifier = Modifier.padding(start = 12.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            if (job.status == VideoExportJobStatus.Running) {
                                GalleryProgressIndicator(
                                    progress = { job.progressPermille.coerceIn(0, 1000) / 1000f },
                                )
                            } else {
                                GalleryIndeterminateProgressIndicator()
                            }
                            TextButton(
                                onClick = { onCancel(job.id) },
                                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                            ) {
                                Text(stringResource(R.string.video_export_cancel))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.video_export_close))
            }
        },
    )
}

private fun VideoExportPhase.queueLabelResource(): Int = when (this) {
    VideoExportPhase.Preparing -> R.string.video_export_preparing
    VideoExportPhase.GeneratingFrames -> R.string.video_export_generating_frames
    VideoExportPhase.Rendering -> R.string.video_export_rendering
    VideoExportPhase.Publishing -> R.string.video_export_publishing
    VideoExportPhase.Verifying -> R.string.video_export_verifying
    VideoExportPhase.Completed -> R.string.video_export_complete
}

private val LocalSurfaceFocusReady = androidx.compose.runtime.staticCompositionLocalOf { true }

@Composable
internal fun AnimatedSurfaceBody(
    key: ScreenMotionKey,
    modifier: Modifier,
    stateHolder: SaveableStateHolder,
    controls: @Composable (SurfaceRoute) -> Unit,
    bottomControls: @Composable (SurfaceRoute) -> Unit = {},
    content: @Composable (ScreenMotionKey) -> Unit,
) {
    GalleryAnimatedContent(
        targetState = key,
        modifier = modifier,
        contentKey = { it },
    ) { activeKey ->
        androidx.compose.runtime.CompositionLocalProvider(
            LocalSurfaceFocusReady provides (
                transition.currentState == androidx.compose.animation.EnterExitState.Visible &&
                    transition.targetState == androidx.compose.animation.EnterExitState.Visible
                ),
        ) {
        var bottomOverlayHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                controls(activeKey.route)
                androidx.compose.runtime.CompositionLocalProvider(
                    com.librestatic.lightforge.core.designsystem.LocalGalleryBottomOverlayPadding provides bottomOverlayHeight,
                ) {
                    if (activeKey.saveableStateKey != null) {
                        stateHolder.SaveableStateProvider(activeKey.saveableStateKey) {
                            content(activeKey)
                        }
                    } else {
                        content(activeKey)
                    }
                }
            }
            Box(
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomOverlayHeight = with(density) { it.height.toDp() } },
                contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
            ) {
                bottomControls(activeKey.route)
            }
        }
        }
    }
}

@Composable
private fun ContextSelectionActions(
    count: Long,
    primaryIcon: ImageVector,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryIcon: ImageVector,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
        leadingContent = {
            GalleryExpressiveIconButton(onClick = onClear) {
                Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.selection_clear))
            }
            Text(
                stringResource(R.string.selection_count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        trailingContent = {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.selection_select_all)) }
        },
    ) {
        GalleryExpressiveIconButton(onClick = onPrimary) {
            Icon(primaryIcon, contentDescription = primaryLabel)
        }
        GalleryExpressiveIconButton(onClick = onSecondary) {
            Icon(secondaryIcon, contentDescription = secondaryLabel)
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
    onArchive: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onDocuments: () -> Unit,
    onStack: () -> Unit,
    onPdfStudio: () -> Unit,
    canCreatePdf: Boolean,
    canCreateMemory: Boolean,
    onCreateMemory: () -> Unit,
    onCreateMemoryVideo: () -> Unit,
    canCreateGif: Boolean,
    onCreateGif: () -> Unit,
    onCreateCollage: () -> Unit,
    pendingCreation: PendingCreation? = null,
    onCancelPendingCreation: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The count and the creation the user came to make sit together above the toolbar, so
        // the toolbar keeps its full width for the five everyday actions on compact phones.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onClear,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { testTagsAsResourceId = true }.testTag("selection-count-clear"),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.selection_clear))
                    Text(
                        stringResource(R.string.selection_count, count),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            pendingCreation?.let { creation ->
                val enabled = when (creation) {
                    PendingCreation.Memory, PendingCreation.MemoryVideo -> canCreateMemory
                    PendingCreation.Gif -> canCreateGif
                    PendingCreation.Collage -> true
                }
                GalleryExpressiveButton(
                    onClick = when (creation) {
                        PendingCreation.Memory -> onCreateMemory
                        PendingCreation.MemoryVideo -> onCreateMemoryVideo
                        PendingCreation.Gif -> onCreateGif
                        PendingCreation.Collage -> onCreateCollage
                    },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { testTagsAsResourceId = true }.testTag("selection-pending-create"),
                ) {
                    Icon(pendingCreationIcon(creation), contentDescription = null)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(pendingCreationTitle(creation))
                }
            }
        }
        HorizontalFloatingToolbar(
            expanded = true,
            trailingContent = {
            Box {
                GalleryExpressiveIconButton(onClick = { menuExpanded = true }) {
                    Icon(GalleryIcons.More, contentDescription = stringResource(com.librestatic.lightforge.feature.viewer.R.string.viewer_more))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(com.librestatic.lightforge.feature.collections.R.string.manual_moment_title)) },
                        onClick = { menuExpanded = false; onCreateMemory() }, enabled = canCreateMemory,
                        leadingIcon = { Icon(GalleryIcons.PhotoLibrary, contentDescription = null) },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("selection-create-memory"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(com.librestatic.lightforge.feature.videoeditor.R.string.memory_video_title)) },
                        onClick = { menuExpanded = false; onCreateMemoryVideo() }, enabled = canCreateMemory,
                        leadingIcon = { Icon(GalleryIcons.Video, contentDescription = null) },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("selection-create-memory-video"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(com.librestatic.lightforge.feature.collections.R.string.stacks_create)) },
                        onClick = { menuExpanded = false; onStack() },
                        enabled = canCreatePdf && count >= 2,
                        leadingIcon = { Icon(GalleryIcons.Collections, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(com.librestatic.lightforge.feature.collections.R.string.documents_organize)) },
                        onClick = { menuExpanded = false; onDocuments() },
                        enabled = canCreatePdf,
                        leadingIcon = { Icon(GalleryIcons.Collections, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(com.librestatic.lightforge.feature.collage.R.string.creation_gif_title)) },
                        onClick = { menuExpanded = false; onCreateGif() },
                        enabled = canCreateGif,
                        leadingIcon = { Icon(GalleryIcons.Repeat, contentDescription = null) },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("selection-create-gif"),
                    )
                    // Always enabled: preparation explains a count or media-type mismatch in a snackbar.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.m6_collage)) },
                        onClick = { menuExpanded = false; onCreateCollage() },
                        leadingIcon = { Icon(GalleryIcons.GridView, contentDescription = null) },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("selection-create-collage"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.archive_move)) },
                        onClick = { menuExpanded = false; onArchive() },
                        leadingIcon = { Icon(GalleryIcons.Archive, contentDescription = null) },
                    )
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
                }
            }
            },
        ) {
            GalleryExpressiveIconButton(onClick = onAddToAlbum) {
                Icon(GalleryIcons.Album, contentDescription = stringResource(R.string.selection_add_album))
            }
            // Promoted from the overflow menu (Phase E): "Create PDF" is common enough from a
            // photo/receipt/document selection that it deserves a clear, always-visible action
            // rather than living one tap deeper.
            if (canCreatePdf) GalleryExpressiveIconButton(onClick = onPdfStudio) {
                Icon(
                    com.librestatic.lightforge.core.designsystem.GalleryIcons.PictureAsPdf,
                    contentDescription = stringResource(com.librestatic.lightforge.feature.pdfstudio.R.string.pdf_selection_create_pdf),
                )
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
            // Floats over media, so it needs its own container pair to stay legible.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    stringResource(R.string.selection_share_limit),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChooseAlbumDialog(
    albums: List<com.librestatic.lightforge.core.model.AlbumSummary>,
    onDismiss: () -> Unit,
    onSelect: (com.librestatic.lightforge.core.model.AlbumSummary) -> Unit,
    onCreateAlbum: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.selection_choose_album)) },
        text = {
            Column {
                if (albums.isEmpty()) Text(stringResource(R.string.selection_no_albums))
                albums.forEach { album ->
                    TextButton(onClick = { onSelect(album) }) {
                        Text(album.name ?: stringResource(com.librestatic.lightforge.feature.album.R.string.album_untitled))
                    }
                }
            }
        },
        // Creating a new album is reachable whatever the existing count: a user with albums should
        // not have to leave the selection to make one.
        confirmButton = {
            TextButton(onClick = onCreateAlbum) { Text(stringResource(R.string.album_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) } },
    )
}

@Composable
private fun ExternalViewer(
    media: GalleryViewModel.ExternalMedia,
    photo: com.librestatic.lightforge.feature.viewer.PhotoLoadState?,
    onClose: () -> Unit,
    onEdit: () -> Unit,
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
        VideoViewerController(context).also { it.select(media.uri, autoplay = true) }
    } else null
    DisposableEffect(video) { onDispose { video?.close() } }
    val scope = rememberCoroutineScope()
    val copyComplete = stringResource(R.string.viewer_copy_complete)
    val actionUnavailable = stringResource(R.string.viewer_action_unavailable)
    var showDetails by rememberSaveable(media.viewerId) { mutableStateOf(false) }
    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) scope.launch {
            runCatching {
                ScopedMediaOperations.copyToTree(
                    context.contentResolver,
                    media.uri,
                    treeUri,
                    media.displayName ?: "Lightforge-media",
                    media.mimeType ?: if (media.kind == MediaKind.Image) "image/*" else "video/*",
                )
            }.onSuccess { Toast.makeText(context, copyComplete, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_LONG).show() }
        }
    }
    fun launchExternal(intent: Intent) {
        runCatching { context.startActivity(Intent.createChooser(intent, null)) }
            .onFailure { Toast.makeText(context, actionUnavailable, Toast.LENGTH_SHORT).show() }
    }
    val mime = media.mimeType ?: if (media.kind == MediaKind.Image) "image/*" else "video/*"
    ViewerContent(
        media = media,
        mediaItems = listOf(media),
        photoState = photo,
        videoController = video,
        thumbnailLoader = null,
        isFavorite = false,
        onBack = onClose,
        onToggleFavorite = null,
        onShare = {
            launchExternal(Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, media.uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, media.displayName, media.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        },
        onShareSanitized = {
            scope.launch {
                runCatching { LocalShareSanitizer(context).prepare(media.uri, media.kind) }
                    .onSuccess { asset ->
                        launchExternal(Intent(Intent.ACTION_SEND).apply {
                            type = asset.mimeType
                            putExtra(Intent.EXTRA_STREAM, asset.uri)
                            clipData = android.content.ClipData.newUri(context.contentResolver, media.displayName, asset.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    }
                    .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_LONG).show() }
            }
        },
        onDetails = { showDetails = true },
        onEdit = onEdit,
        onRename = null,
        onCopy = { copyLauncher.launch(null) },
        onMove = null,
        onOpenWith = { launchExternal(ScopedMediaOperations.viewIntent(media.uri, mime)) },
        onSetAs = if (media.kind == MediaKind.Image) ({ launchExternal(ScopedMediaOperations.setAsIntent(media.uri, mime)) }) else null,
        onPrint = if (media.kind == MediaKind.Image) ({ ScopedMediaOperations.printImage(context, media.uri, media.displayName ?: "Lightforge") }) else null,
        onRepairDate = null,
        onTrash = null,
        onSelectMedia = {},
        textRecognizer = rememberViewerTextRecognizer(),
        modifier = Modifier.fillMaxSize(),
    )
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(media.displayName ?: stringResource(R.string.external_unavailable)) },
        text = {
            Text(buildString {
                append(mime)
                if (media.width > 0 && media.height > 0) append("\n${media.width} × ${media.height}")
                if (media.durationMillis > 0) {
                    append("\n${android.text.format.DateUtils.formatElapsedTime(media.durationMillis / 1_000)}")
                }
                if (media.sizeBytes > 0) {
                    append("\n${android.text.format.Formatter.formatFileSize(context, media.sizeBytes)}")
                }
            })
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.details_close)) } },
    )
}

@Composable
private fun PublicMediaRecoveryContent(title: String, body: String, loading: Boolean, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize()) {
            GalleryTopAppBar(title = title, onBack = onBack,
                navigationContentDescription = stringResource(com.librestatic.lightforge.feature.viewer.R.string.viewer_back))
            if (loading) GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
            Text(body, Modifier.padding(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerRoute(
    media: TimelineMedia,
    mediaItems: List<TimelineMedia>,
    photoState: com.librestatic.lightforge.feature.viewer.PhotoLoadState?,
    adjacentPhotoStates: Map<com.librestatic.lightforge.core.model.MediaKey, com.librestatic.lightforge.feature.viewer.PhotoLoadState.Ready>,
    thumbnailLoader: com.librestatic.lightforge.core.thumbnail.ThumbnailLoader?,
    viewModel: GalleryViewModel,
    showDetails: Boolean,
    adaptiveInfo: GalleryAdaptiveLayoutInfo,
    onTreeOperation: (Boolean) -> Unit,
    onShowDetails: () -> Unit,
    onHideDetails: () -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMotionPhoto: () -> Unit,
    onShareSanitized: () -> Unit,
    gazetteer: OfflineGazetteer? = null,
    sessionVideoMuted: Boolean? = null,
    onSessionVideoMutedChange: (Boolean?) -> Unit = {},
    trashContext: Boolean = false,
    onMoveToPrivate: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewerSnapshot by viewModel.viewerRestoreSnapshot.collectAsState()
    val recoveredViewer by viewModel.viewerRecovered.collectAsState()
    val currentSnapshot = viewerSnapshot?.takeIf { it.key == media.key && it.generationModified == media.generationModified }
    var hasMotion by remember(media.key, media.generationModified) { mutableStateOf(false) }
    LaunchedEffect(media.key, media.generationModified, trashContext, currentSnapshot?.generationAdded) {
        hasMotion = !trashContext && media.kind == MediaKind.Image && currentSnapshot != null &&
            com.librestatic.lightforge.feature.motionphotos.MotionPhotoSession.inspect(context,
                com.librestatic.lightforge.feature.motionphotos.MotionPhotoInput(viewModel.mediaUri(media), media.generationModified, currentSnapshot.generationAdded))
    }
    val coroutineScope = rememberCoroutineScope()
    val cheap by viewModel.cheapDetails.collectAsState()
    val exif by viewModel.exifDetails.collectAsState()
    val detectedText by viewModel.detectedText.collectAsState()
    val quickSlowMotionSave by viewModel.quickSlowMotionSave.collectAsState()
    val gallerySettings by viewModel.gallerySettings.collectAsState()
    val destructiveAuthTitle = stringResource(R.string.destructive_auth_title)
    val destructiveAuthBody = stringResource(R.string.destructive_auth_body)
    val chooserTitle = stringResource(R.string.viewer_choose_app)
    val actionUnavailable = stringResource(R.string.viewer_action_unavailable)
    var renameDialogVisible by rememberSaveable(media.key) { mutableStateOf(false) }
    var renameValue by rememberSaveable(media.key) { mutableStateOf(media.displayName.orEmpty()) }
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
    val viewerHostView = androidx.compose.ui.platform.LocalView.current
    val viewerWindow = (viewerHostView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        ?: (context as? Activity)?.window
    DisposableEffect(viewerWindow, gallerySettings.playback.maximumBrightness) {
        val window = viewerWindow
        val previous = window?.attributes?.screenBrightness
        if (gallerySettings.playback.maximumBrightness) {
            window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        }
        onDispose {
            if (previous != null) window.attributes = window.attributes.apply { screenBrightness = previous }
        }
    }
    val placeName = remember(exif) {
        val location = (exif as? com.librestatic.lightforge.core.model.ExifLoadResult.Ready)?.details?.location
        if (location != null && gazetteer != null) {
            gazetteer.reverseGeocode(location.latitude, location.longitude)?.city?.name
        } else null
    }
    val videoController = if (media.kind == MediaKind.Video) remember(media.key) {
        VideoViewerController(context, initialLooping = gallerySettings.playback.loopVideos).also {
            it.select(
                viewModel.mediaUri(media),
                autoplay = gallerySettings.playback.autoplayVideos && !recoveredViewer,
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
        com.librestatic.lightforge.feature.viewer.HoldSlowMotionSession(context, viewModel.mediaUri(media))
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
    val mediaArchived by remember(media.key) { viewModel.isArchived(media) }
        .collectAsState(initial = false)
    val archiveLabel = stringResource(if (mediaArchived) R.string.archive_unarchive else R.string.archive_move)
    // Restore/Delete from the trash viewer leaves the item stale in this list; close on completion.
    var trashActionPending by remember(media.key) { mutableStateOf(false) }
    // Moving to trash from the library leaves the photo stale too: advance to its neighbour,
    // captured before the list drops the row, or close when it was the last one (V-05).
    var advanceAfterTrash by remember(media.key) { mutableStateOf(false) }
    var trashNeighbour by remember(media.key) { mutableStateOf<TimelineMedia?>(null) }
    val systemActionState by viewModel.systemAction.collectAsState()
    LaunchedEffect(trashActionPending, advanceAfterTrash, systemActionState?.phase) {
        when (systemActionState?.phase) {
            com.librestatic.lightforge.core.mediastore.MediaActionPhase.Complete -> {
                if (trashActionPending) {
                    trashActionPending = false
                    onBack()
                } else if (advanceAfterTrash) {
                    advanceAfterTrash = false
                    trashNeighbour?.let(viewModel::selectViewerMedia) ?: onBack()
                }
            }
            is com.librestatic.lightforge.core.mediastore.MediaActionPhase.Cancelled,
            is com.librestatic.lightforge.core.mediastore.MediaActionPhase.RequestFailed -> {
                trashActionPending = false
                advanceAfterTrash = false
            }
            else -> Unit
        }
    }
    val viewer: @Composable () -> Unit = {
        ViewerContent(
            media = media,
            mediaItems = mediaItems,
            photoState = photoState,
            adjacentPhotoStates = adjacentPhotoStates,
            videoController = videoController,
            thumbnailLoader = thumbnailLoader,
            isFavorite = media.isFavorite,
            onBack = onBack,
            // A trashed item only offers Restore, Delete permanently and Details.
            onToggleFavorite = if (trashContext) null else ({ viewModel.beginSystemAction(media, MediaAction.Favorite(!media.isFavorite)) }),
            onShare = if (trashContext) null else ({
                if (gallerySettings.operations.shareWithoutLocationByDefault) {
                    onShareSanitized()
                } else {
                    context.startActivity(Intent.createChooser(viewModel.originalShareIntent(media), null))
                }
            }),
            onDetails = onShowDetails,
            onEdit = onEdit.takeUnless { trashContext },
            onMotionPhoto = onMotionPhoto.takeIf { hasMotion },
            motionPhotoLabel = stringResource(com.librestatic.lightforge.feature.motionphotos.R.string.motion_title),
            onRename = if (trashContext) null else ({
                renameValue = media.displayName.orEmpty()
                renameDialogVisible = true
            }),
            onCopy = if (trashContext) null else ({ onTreeOperation(false) }),
            onMove = if (trashContext) null else ({ onTreeOperation(true) }),
            onOpenWith = if (trashContext) null else ({ launchExternal(ScopedMediaOperations.viewIntent(target, wildcardMime)) }),
            onSetAs = if (trashContext) null else ({ launchExternal(ScopedMediaOperations.setAsIntent(target, wildcardMime)) }),
            onPrint = if (trashContext) null else ({
                runCatching { ScopedMediaOperations.printImage(context, target, media.displayName ?: "Lightforge") }
                    .onFailure { Toast.makeText(context, it.message ?: actionUnavailable, Toast.LENGTH_SHORT).show() }
            }),
            onRepairDate = if (trashContext) null else ::showDateRepairPicker,
            onShareSanitized = onShareSanitized.takeUnless { trashContext },
            onTrash = {
                runViewerDestructive {
                    trashActionPending = trashContext
                    if (!trashContext) {
                        val index = mediaItems.indexOfFirst { it.viewerId == media.viewerId }
                        trashNeighbour = if (index < 0) null
                            else mediaItems.getOrNull(index + 1) ?: mediaItems.getOrNull(index - 1)
                        advanceAfterTrash = true
                    }
                    viewModel.beginSystemAction(media, MediaAction.Trash(!trashContext))
                }
            },
            onDelete = if (trashContext) ({
                runViewerDestructive {
                    trashActionPending = true
                    viewModel.beginSystemAction(media, MediaAction.Delete)
                }
            }) else null,
            deleteActionLabel = if (trashContext) {
                stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_delete_permanently)
            } else null,
            onArchive = if (trashContext) null else ({
                viewModel.setMediaArchived(media, !mediaArchived)
                onBack()
            }),
            archiveActionLabel = if (trashContext) null else archiveLabel,
            onMoveToPrivate = onMoveToPrivate.takeUnless { trashContext },
            onSelectMedia = { selected ->
                mediaItems.firstOrNull { it.viewerId == selected.viewerId }?.let(viewModel::selectViewerMedia)
            },
            trashActionLabel = if (trashContext) {
                stringResource(com.librestatic.lightforge.feature.trash.R.string.trash_restore)
            } else null,
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
            textRecognizer = rememberViewerTextRecognizer(),
            modifier = Modifier.fillMaxSize(),
        )
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

/**
 * One entry of the Create sheet: a tonal icon badge (secondaryContainer/onSecondaryContainer),
 * a title and a one-line description, left-aligned and tappable as a whole (≥ 64dp tall).
 */
@Composable
private fun CreateSheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { testTagsAsResourceId = true }
            .testTag(testTag)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
