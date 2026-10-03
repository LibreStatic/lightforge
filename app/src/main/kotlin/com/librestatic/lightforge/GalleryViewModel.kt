package com.librestatic.lightforge

import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.catch

import androidx.room.withTransaction
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import com.librestatic.lightforge.core.mediastore.VolumeGeneration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.librestatic.lightforge.core.data.MomentRepository
import com.librestatic.lightforge.core.database.MomentEntity
import com.librestatic.lightforge.core.database.MomentMemberRow
import com.librestatic.lightforge.core.database.MomentSummaryRow
import com.librestatic.lightforge.core.data.GalleryTimelineRepository
import com.librestatic.lightforge.core.data.GalleryAlbumRepository
import com.librestatic.lightforge.core.data.GalleryTrashRepository
import com.librestatic.lightforge.core.data.GalleryArchiveRepository
import com.librestatic.lightforge.core.data.GalleryActivityRepository
import com.librestatic.lightforge.core.data.GalleryActivityEvent
import com.librestatic.lightforge.core.data.GalleryActivityType
import com.librestatic.lightforge.core.data.GalleryHighlight
import com.librestatic.lightforge.core.data.GalleryHighlightsRepository
import com.librestatic.lightforge.core.data.GalleryQueryMediaRepository
import com.librestatic.lightforge.core.data.MediaMetadataRepository
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.data.IncrementalMediaSynchronizer
import com.librestatic.lightforge.core.data.IncrementalSyncResult
import com.librestatic.lightforge.core.data.InitialMediaScanner
import com.librestatic.lightforge.core.data.MediaStoreChangeMonitor
import com.librestatic.lightforge.core.data.RoomMediaIndexStore
import com.librestatic.lightforge.core.data.RoomSelectionTargetSource
import com.librestatic.lightforge.core.data.RoomViewerMediaSource
import com.librestatic.lightforge.core.data.GallerySearchIndexRepository
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.mediastore.MediaStoreGenerationProbe
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.core.mediastore.MediaAction
import com.librestatic.lightforge.core.mediastore.MediaActionLaunch
import com.librestatic.lightforge.core.mediastore.MediaActionReducer
import com.librestatic.lightforge.core.mediastore.MediaActionSnapshot
import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.mediastore.MediaStoreActionCoordinator
import com.librestatic.lightforge.core.mediastore.ShareCandidate
import com.librestatic.lightforge.core.mediastore.ShareCoordinator
import com.librestatic.lightforge.core.mediastore.PendingMediaWriter
import com.librestatic.lightforge.core.mediastore.MediaWriteSpec
import com.librestatic.lightforge.core.mediastore.PublishedCopy
import com.librestatic.lightforge.core.mediastore.LocalShareSanitizer
import com.librestatic.lightforge.core.mediastore.ScopedMediaOperations
import com.librestatic.lightforge.core.ml.DetectedContentRepository
import com.librestatic.lightforge.core.ml.CleanupRepository
import com.librestatic.lightforge.core.ml.CleanupSummary
import com.librestatic.lightforge.core.ml.ExactDuplicateGroup
import com.librestatic.lightforge.core.ml.ExactDuplicateRepository
import com.librestatic.lightforge.core.ml.FaceIdentityKey
import com.librestatic.lightforge.core.search.SearchQueryParser
import com.librestatic.lightforge.core.ml.LocalAnalysisOnboardingDecision
import com.librestatic.lightforge.core.ml.LocalAnalysisOnboardingStore
import com.librestatic.lightforge.core.ml.LocalAnalysisFeature
import com.librestatic.lightforge.core.ml.LocalAnalysisSwitchStore
import com.librestatic.lightforge.core.ml.LocalAnalysisSwitches
import com.librestatic.lightforge.core.ml.MlScheduler
import com.librestatic.lightforge.core.ml.MlTaskType
import com.librestatic.lightforge.core.ml.PetCollectionRepository
import com.librestatic.lightforge.core.ml.PetCollectionSettings
import com.librestatic.lightforge.core.ml.PetCollectionSummary
import com.librestatic.lightforge.core.ml.PetType
import com.librestatic.lightforge.core.ml.PeopleRepository
import com.librestatic.lightforge.core.ml.MlRunMode
import com.librestatic.lightforge.core.ml.MlControlState
import com.librestatic.lightforge.core.ml.UserHardwareLease
import com.librestatic.lightforge.core.ml.UserHardwareWorkload
import com.librestatic.lightforge.core.ml.UserHardwareWorkloadGate
import com.librestatic.lightforge.core.search.AppSearchMediaSearchRepository
import com.librestatic.lightforge.core.search.MediaSearchCursor
import com.librestatic.lightforge.core.search.MediaSearchHit
import com.librestatic.lightforge.core.search.SearchConcept
import com.librestatic.lightforge.core.search.SearchRankingDebug
import com.librestatic.lightforge.core.search.SearchVocabulary
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.CheapMediaDetails
import com.librestatic.lightforge.core.model.ExifLoadResult
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineAnchor
import com.librestatic.lightforge.core.model.TimelineEntry
import com.librestatic.lightforge.core.model.TimelineIndex
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.EditHistory
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawOutputFormat
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import com.librestatic.lightforge.core.preferences.VideoResumePolicy
import com.librestatic.lightforge.core.preferences.FavoriteBackupRecord
import com.librestatic.lightforge.core.preferences.GalleryBackupCodec
import com.librestatic.lightforge.core.editing.image.PhotoExportOutcome
import com.librestatic.lightforge.core.editing.image.PhotoAutoEnhancementAnalyzer
import com.librestatic.lightforge.core.editing.image.PhotoExportFailureKind
import com.librestatic.lightforge.core.editing.image.PhotoExportWarning
import com.librestatic.lightforge.core.editing.image.PhotoImageRenderer
import com.librestatic.lightforge.core.editing.video.Media3VideoExporter
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.core.editing.video.VideoGeometry
import com.librestatic.lightforge.core.editing.video.VideoExportRequest
import com.librestatic.lightforge.core.editing.video.CubeLut
import com.librestatic.lightforge.core.editing.video.CustomLutOption
import com.librestatic.lightforge.core.editing.video.LogProfileDetector
import com.librestatic.lightforge.core.editing.video.LutReference
import com.librestatic.lightforge.core.editing.video.VideoColorGrade
import com.librestatic.lightforge.core.editing.video.VideoEditRecipeCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputQuality
import com.librestatic.lightforge.core.editing.video.VideoDynamicRange
import com.librestatic.lightforge.core.editing.video.VideoOutputCapabilities
import com.librestatic.lightforge.core.editing.video.VideoSourceInfoReader
import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import com.librestatic.lightforge.core.editing.video.VideoAnnotationLayer
import com.librestatic.lightforge.core.editing.video.VideoAnnotationKeyframe
import com.librestatic.lightforge.core.editing.video.VideoAnnotationTrackingMode
import com.librestatic.lightforge.core.editing.video.VideoAnnotationTracker
import com.librestatic.lightforge.core.editing.video.VideoAnnotationTrackingResult
import com.librestatic.lightforge.core.editing.video.NormalizedPoint
import com.librestatic.lightforge.core.editing.video.VideoAnnotationShape
import com.librestatic.lightforge.core.editing.video.withTrimRange
import com.librestatic.lightforge.core.database.VideoEditRecipeEntity
import com.librestatic.lightforge.core.database.VideoPlaybackPositionEntity
import com.librestatic.lightforge.core.database.MediaItemEntity
import com.librestatic.lightforge.core.database.PhysicalAlbumRow
import com.librestatic.lightforge.core.raw.RawDeveloper
import com.librestatic.lightforge.core.raw.RawExportFailureKind
import com.librestatic.lightforge.core.raw.RawExportOutcome
import com.librestatic.lightforge.core.raw.RawPreviewSession
import com.librestatic.lightforge.core.raw.isRawMimeOrName
import com.librestatic.lightforge.core.selection.SelectionReducer
import com.librestatic.lightforge.core.selection.SelectionSpec
import com.librestatic.lightforge.core.selection.MediaQuery
import com.librestatic.lightforge.core.thumbnail.NativeImageDecoder
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.collections.CleanupDuplicateGroupUi
import com.librestatic.lightforge.feature.collections.CleanupList
import com.librestatic.lightforge.feature.collections.CleanupSection
import com.librestatic.lightforge.feature.collections.CleanupUiState
import com.librestatic.lightforge.feature.collections.LocalMeUiState
import com.librestatic.lightforge.feature.collections.MomentMemberUi
import com.librestatic.lightforge.feature.collections.PeopleUiState
import com.librestatic.lightforge.feature.collections.PersonCardUi
import com.librestatic.lightforge.feature.collections.PersonMemberCardUi
import com.librestatic.lightforge.feature.settings.GalleryFolderOption
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import com.librestatic.lightforge.feature.viewer.PhotoLoadState
import com.librestatic.lightforge.feature.viewer.PhotoPreviewTransition
import com.librestatic.lightforge.feature.viewer.PhotoViewerPipeline
import com.librestatic.lightforge.feature.viewer.ViewerAdjacentPreloadPlanner
import com.librestatic.lightforge.feature.viewer.ViewerUiState
import com.librestatic.lightforge.feature.photoeditor.PhotoEditorContentState
import com.librestatic.lightforge.feature.photoeditor.PhotoPoint
import com.librestatic.lightforge.feature.photoeditor.eraseRegionsFor
import com.librestatic.lightforge.feature.photoeditor.photoGeometryOperations
import com.librestatic.lightforge.feature.photoeditor.photoGeometryProjectable
import com.librestatic.lightforge.feature.photoeditor.projectToEdited
import com.librestatic.lightforge.feature.photoeditor.projectToSource
import com.librestatic.lightforge.feature.objecteraser.InpaintingSession
import com.librestatic.lightforge.feature.subjectclip.SubjectClipper
import com.librestatic.lightforge.feature.videoeditor.VideoEditorContentState
import com.librestatic.lightforge.feature.videoeditor.labelResource
import com.librestatic.lightforge.feature.collage.CollageConfig
import com.librestatic.lightforge.feature.collage.CollageTemplate
import com.librestatic.lightforge.feature.collage.CollageTemplates
import com.librestatic.lightforge.feature.semanticsearch.ReciprocalRankFusion
import com.librestatic.lightforge.feature.semanticsearch.RankedSemanticKey
import com.librestatic.lightforge.feature.semanticsearch.SemanticModelManager
import com.librestatic.lightforge.feature.semanticsearch.SemanticModelManagerState
import com.librestatic.lightforge.feature.semanticsearch.SemanticSearchEngine
import com.librestatic.lightforge.feature.semanticsearch.SemanticAnalysisPriority
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import javax.inject.Inject
import org.json.JSONObject
import com.librestatic.lightforge.core.model.ViewerMedia

enum class LibraryEngineState { Starting, Indexing, Ready, PermissionRequired, Error }

/** Post-sync library work that runs while the timeline is already browsable. */
enum class LibraryMaintenance { SearchIndex, Moments }

data class GallerySearchUiState(
    val query: String = "",
    val hits: List<MediaSearchHit> = emptyList(),
    val loading: Boolean = false,
    val terminal: Boolean = true,
    val error: Boolean = false,
    /** Semantic search threw; results are keyword-only. Distinct from "no results". */
    val semanticUnavailable: Boolean = false,
)

internal fun GallerySearchUiState.withEditedQuery(value: String): GallerySearchUiState {
    if (value == query) return this
    return GallerySearchUiState(query = value, terminal = value.isBlank())
}

data class PhotoEditorSession(
    val source: EditorMediaSource,
    val history: EditHistory,
    val content: PhotoEditorContentState,
    /** Cancel/save-copy discard only this session's draft, not a restored recipe on entry. */
    val entryRecipe: EditRecipe? = null,
    val entryRecipeUpdatedAtMillis: Long = 0,
    /**
     * Applied experimental eraser dabs, re-applied at full size on Save copy. Stored in upright
     * source coordinates and re-projected through the recipe, unless [eraseGeometry] is set: then
     * they were drawn under a straighten and are in the edited coordinates of that geometry.
     */
    val eraseMarks: List<PhotoPoint> = emptyList(),
    val eraseGeometry: List<EditOperation>? = null,
)

data class EditorMediaSource(
    val uriString: String,
    val kind: MediaKind,
    val libraryMedia: TimelineMedia? = null,
    val mimeType: String? = null,
    val displayName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long = 0,
) {
    val uri: Uri get() = Uri.parse(uriString)
    val stableId: String get() = libraryMedia?.viewerId ?: uriString
}

/** The experimental eraser and subject clip decode one full-size bitmap; larger photos are refused. */
private const val EXPERIMENTAL_PHOTO_MAX_PIXELS = 24_000_000L

private data class RawPreviewRequest(
    val generation: Long,
    val settings: RawDevelopmentSettings,
    val recipe: EditRecipe,
    val maxDimension: Int,
    val minimumIntervalMillis: Long,
    val final: Boolean,
)

data class VideoEditorSession(
    val source: EditorMediaSource,
    val baselineRecipe: VideoEditRecipe,
    val recipe: VideoEditRecipe,
    val content: VideoEditorContentState,
    val pendingExportRecipe: VideoEditRecipe? = null,
    val exportJobId: String? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val recoverySource: ViewerRestoreSnapshot? = null,
    val recovered: Boolean = false,
    val externalAccessBlocked: Boolean = false,
    val externalAccessChecking: Boolean = false,
    val externalSourceChanged: Boolean = false,
    val externalRecoverySource: ExternalVideoSourceSnapshot? = null,
) {
    fun isDirty(candidate: VideoEditRecipe = recipe): Boolean =
        candidate != (pendingExportRecipe ?: baselineRecipe)
}

data class QuickSlowMotionSaveState(
    val progress: Float? = null,
    val completionGeneration: Long = 0,
)

private const val LibraryLogTag = "LightforgeLibrary"

private data class GalleryRuntime(
    val database: GalleryDatabase,
    val timeline: GalleryTimelineRepository,
    val scanner: InitialMediaScanner,
    val synchronizer: IncrementalMediaSynchronizer,
    val generations: MediaStoreGenerationProbe,
    var thumbnails: ThumbnailLoader,
    val motionKeyFrames: com.librestatic.lightforge.core.data.MotionKeyFrameRepository,
    val thumbnailFactory: () -> ThumbnailLoader,
    val organizationBackup: GalleryOrganizationBackupAdapter,
    val decoder: NativeImageDecoder,
    val albums: GalleryAlbumRepository,
    val trash: GalleryTrashRepository,
    val archive: GalleryArchiveRepository,
    val activity: GalleryActivityRepository,
    val highlights: GalleryHighlightsRepository,
    val queryMedia: GalleryQueryMediaRepository,
    val metadata: MediaMetadataRepository,
    val selectionTargets: RoomSelectionTargetSource,
    val viewerMedia: RoomViewerMediaSource,
    val searchIndex: GallerySearchIndexRepository,
    val moments: MomentRepository,
    var monitor: MediaStoreChangeMonitor? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel @Inject constructor(
    application: Application,
    val permissions: PermissionCoordinator,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val runtime = MutableStateFlow<GalleryRuntime?>(null)
    private val mutableEngineState = MutableStateFlow(LibraryEngineState.Starting)
    private val mutableSearch = MutableStateFlow(GallerySearchUiState())
    val search = mutableSearch.asStateFlow()
    private val mutableSearchIndexReady = MutableStateFlow(false)
    private val mutableLibraryMaintenance = MutableStateFlow<LibraryMaintenance?>(null)
    /** Work that follows a sync while the timeline is already browsable; null when idle. */
    val libraryMaintenance = mutableLibraryMaintenance.asStateFlow()
    val searchIndexReady = mutableSearchIndexReady.asStateFlow()
    private val mlScheduler = MlScheduler(application)
    private val videoExportStore = VideoExportStore.get(application)
    val videoExports = videoExportStore.jobs
    private val mutableVideoExportCompleted = MutableSharedFlow<VideoExportJob>(extraBufferCapacity = 8)
    val videoExportCompleted = mutableVideoExportCompleted.asSharedFlow()
    private var userHardwareLease: UserHardwareLease? = null
    private var userHardwareWorkload: UserHardwareWorkload? = null
    private val localAnalysisOnboardingStore = LocalAnalysisOnboardingStore(application)
    private val peopleAnalysisTasks = listOf(
        MlTaskType.FaceDetection,
        MlTaskType.FaceEmbeddings,
        MlTaskType.PersonClustering,
    )
    private val contentAnalysisTasks = listOf(MlTaskType.ImageLabels, MlTaskType.Ocr)
    private val localAnalysisTasks = peopleAnalysisTasks + contentAnalysisTasks
    /** Opt-in "Free up space" analysis; both run under the full-library charging/foreground gate. */
    private val cleanupAnalysisTasks = listOf(MlTaskType.ExactDuplicates, MlTaskType.Similarity)
    private val gallerySettingsRepository = GallerySettingsRepository(application)
    /** PDF Studio's Media panel Photos scope (Phase F item 3): the same [LibrarySettings] the
     * Photos timeline queries with, so excluded folders / archive exclusion stay in sync live. */
    val librarySettings =
        gallerySettingsRepository.settings
            .map { it.library }
            .stateIn(viewModelScope, SharingStarted.Eagerly, com.librestatic.lightforge.core.preferences.LibrarySettings())
    val gallerySettings = gallerySettingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GallerySettings(),
    )

    /** First-run wizard flag; null while it is being settled, so nothing flashes before it. */
    val onboardingCompleted = kotlinx.coroutines.flow.flow {
        gallerySettingsRepository.resolveOnboarding()
        emitAll(gallerySettingsRepository.onboardingCompleted)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Marks the wizard done. [analysis] is null when it was skipped, which leaves the existing
     * local-analysis decision (and its later prompt) untouched.
     */
    fun completeOnboarding(analysis: Set<LocalAnalysisFeature>?) {
        viewModelScope.launch { gallerySettingsRepository.setOnboardingCompleted(true) }
        if (analysis == null) return
        val decision = if (analysis.isEmpty()) LocalAnalysisOnboardingDecision.Declined else LocalAnalysisOnboardingDecision.Accepted
        localAnalysisOnboardingStore.setDecision(decision)
        mutableLocalAnalysisOnboarding.value = decision
        // The wizard only offers these; any other remembered choice (Cleanup) is kept as is.
        val offered = setOf(
            LocalAnalysisFeature.People, LocalAnalysisFeature.Content,
            LocalAnalysisFeature.Pets, LocalAnalysisFeature.Semantic,
        )
        updateLocalAnalysisSwitches(fullLibrary = analysis.isNotEmpty()) { current ->
            val kept = current.remembered - offered
            LocalAnalysisSwitches(
                master = analysis.isNotEmpty() || (current.master && kept.isNotEmpty()),
                remembered = analysis + kept,
            )
        }
    }

    fun reopenOnboarding() {
        viewModelScope.launch { gallerySettingsRepository.setOnboardingCompleted(false) }
    }

    suspend fun restoredVideoPosition(media: TimelineMedia): Long = withContext(Dispatchers.IO) {
        if (!gallerySettings.value.playback.rememberVideoPosition || media.kind != MediaKind.Video) {
            return@withContext 0L
        }
        val dao = runtime.value?.database?.libraryDao() ?: return@withContext 0L
        val saved = dao.videoPlaybackPosition(media.key.volumeName, media.key.mediaStoreId)
            ?: return@withContext 0L
        val restored = VideoResumePolicy.restoredPosition(
            enabled = true,
            positionMillis = saved.positionMillis,
            savedDurationMillis = saved.durationMillis,
            currentDurationMillis = media.durationMillis,
        )
        if (restored == null) {
            dao.deleteVideoPlaybackPosition(media.key.volumeName, media.key.mediaStoreId)
            0L
        } else {
            restored
        }
    }

    fun saveVideoPosition(media: TimelineMedia, positionMillis: Long) {
        if (media.kind != MediaKind.Video) return
        viewModelScope.launch(Dispatchers.IO) {
            val dao = runtime.value?.database?.libraryDao() ?: return@launch
            val duration = media.durationMillis.coerceAtLeast(0L)
            val resumable = VideoResumePolicy.shouldPersist(
                enabled = gallerySettings.value.playback.rememberVideoPosition,
                positionMillis = positionMillis,
                durationMillis = duration,
            )
            if (resumable) {
                dao.upsertVideoPlaybackPosition(
                    VideoPlaybackPositionEntity(
                        volumeName = media.key.volumeName,
                        mediaStoreId = media.key.mediaStoreId,
                        positionMillis = positionMillis,
                        durationMillis = duration,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            } else {
                dao.deleteVideoPlaybackPosition(media.key.volumeName, media.key.mediaStoreId)
            }
        }
    }
    private val petSettings = PetCollectionSettings(application)
    /** Search keeps archived items (as the Archive copy promises); tiles badge them from this set. */
    val archivedMediaKeys = runtime.filterNotNull()
        .flatMapLatest { it.database.libraryDao().observeArchivedMediaKeys() }
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val localAnalysisSwitchStore = LocalAnalysisSwitchStore(application)
    /** Single source of truth for the "Use local analysis" master switch and its children. */
    private val mutableLocalAnalysisSwitches = MutableStateFlow(
        localAnalysisSwitchStore.load {
            buildSet {
                if (peopleAnalysisTasks.all(mlScheduler::hasConsent)) add(LocalAnalysisFeature.People)
                if (contentAnalysisTasks.all(mlScheduler::hasConsent)) add(LocalAnalysisFeature.Content)
                if (cleanupAnalysisTasks.all(mlScheduler::hasConsent)) add(LocalAnalysisFeature.Cleanup)
                if (petSettings.isEnabled()) add(LocalAnalysisFeature.Pets)
                if (SemanticModelManager.isEnabled(application)) add(LocalAnalysisFeature.Semantic)
            }
        },
    )
    val localAnalysisSwitches = mutableLocalAnalysisSwitches.asStateFlow()
    private val mutableLocalAnalysisOnboarding = MutableStateFlow(localAnalysisOnboardingStore.decision())
    val localAnalysisOnboarding = mutableLocalAnalysisOnboarding.asStateFlow()
    private val mutableDetectedContentEnabled = MutableStateFlow(
        contentAnalysisTasks.all(mlScheduler::hasConsent),
    )
    val detectedContentEnabled = mutableDetectedContentEnabled.asStateFlow()
    private val mutableCleanupAnalysisEnabled = MutableStateFlow(cleanupAnalysisTasks.all(mlScheduler::hasConsent))
    val cleanupAnalysisEnabled = mutableCleanupAnalysisEnabled.asStateFlow()
    @Volatile private var cleanupGroups: Map<String, ExactDuplicateGroup> = emptyMap()
    /** Re-read whenever Room reports a change to hashes, similarity features or media. */
    val cleanup = combine(runtime.filterNotNull(), mutableCleanupAnalysisEnabled) { active, enabled -> active to enabled }
        .flatMapLatest { (active, enabled) ->
            CleanupRepository(active.database).summary.map { summary -> loadCleanup(active, summary, enabled) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CleanupUiState())
    private val mutableFaceAnalysis = MutableStateFlow(mlScheduler.controlState(MlTaskType.FaceDetection))
    val faceAnalysis = mutableFaceAnalysis.asStateFlow()
    private val mutablePeopleAnalysis = MutableStateFlow(peopleControlState())
    val peopleAnalysis = mutablePeopleAnalysis.asStateFlow()
    private val mutablePetCollectionsEnabled = MutableStateFlow(petSettings.isEnabled())
    val petCollectionsEnabled = mutablePetCollectionsEnabled.asStateFlow()
    private val mutablePetAnalysis = MutableStateFlow(mlScheduler.controlState(MlTaskType.ImageLabels))
    val petAnalysis = mutablePetAnalysis.asStateFlow()
    private val petRefreshGeneration = MutableStateFlow(0L)
    val petSummary = combine(runtime.filterNotNull(), petRefreshGeneration) { current, _ -> current }
        .flatMapLatest { PetCollectionRepository(it.database).summary() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PetCollectionSummary())
    private val peopleRefreshGeneration = MutableStateFlow(0L)
    val peopleSummaries = combine(runtime.filterNotNull(), peopleRefreshGeneration) { current, _ -> current }
        .flatMapLatest { PeopleRepository(it.database).people() }
        .map { rows -> rows.map(::personCard) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hiddenPeopleSummaries = combine(runtime.filterNotNull(), peopleRefreshGeneration) { current, _ -> current }
        .flatMapLatest { PeopleRepository(it.database).hiddenPeople() }
        .map { rows -> rows.map(::personCard) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var personMemberLimit = PersonMemberPageSize
    private val mutableSelectedPerson = MutableStateFlow<PersonCardUi?>(null)
    val selectedPerson = mutableSelectedPerson.asStateFlow()
    private val mutableSelectedPersonMembers = MutableStateFlow<List<PersonMemberCardUi>>(emptyList())
    val selectedPersonMembers = mutableSelectedPersonMembers.asStateFlow()
    private val mutableMe = MutableStateFlow<LocalMeUiState?>(null)
    val me = mutableMe.asStateFlow()
    private var faceProgressJob: Job? = null
    private var peopleProgressJob: Job? = null
    private var petProgressJob: Job? = null
    private val mutableBenchmarkMlRunning = MutableStateFlow(false)
    val benchmarkMlRunning = mutableBenchmarkMlRunning.asStateFlow()
    private var benchmarkMlJob: Job? = null
    private var searchCursor: MediaSearchCursor? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private var semanticModelManager: SemanticModelManager? = null
    private var semanticSearchEngine: SemanticSearchEngine? = null
    private val mutableSemanticModels = MutableStateFlow(SemanticModelManagerState())
    val semanticModels = mutableSemanticModels.asStateFlow()
    val engineState = mutableEngineState.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LibraryEngineState.Starting,
    )
    val access = permissions.access
    // The counter makes a repeated jump to the same day (or to the newest photo) restart the pager.
    private data class TimelineJump(val anchor: TimelineAnchor?, val count: Long = 0)
    private val mutableTimelineJump = MutableStateFlow(TimelineJump(null))

    /** Restarts the timeline at [anchor]'s day, or at the newest photo when null. */
    fun jumpTimeline(anchor: TimelineAnchor?) {
        mutableTimelineJump.update { TimelineJump(anchor, it.count + 1) }
    }

    val timeline: Flow<PagingData<TimelineEntry>> by lazy {
        combine(gallerySettings.map { it.library }, selection.map {
            it !is SelectionSpec.Explicit || it.keys.isNotEmpty()
        }) { library, selecting -> library to selecting }
            .distinctUntilChanged()
            .flatMapLatest { (library, selecting) ->
                // A different library view has different rows, so an old jump target no longer applies.
                if (library != previousTimelineLibrary) {
                    previousTimelineLibrary = library
                    mutableTimelineJump.value = TimelineJump(null)
                }
                mutableTimelineJump.flatMapLatest { (anchor) ->
                    runtime.filterNotNull().flatMapLatest {
                        it.timeline.timeline(
                            ZoneId.systemDefault(),
                            library,
                            collapseStacks = !selecting,
                            anchor = anchor,
                        )
                    }
                }
            }.cachedIn(viewModelScope)
    }
    private var previousTimelineLibrary: com.librestatic.lightforge.core.preferences.LibrarySettings? = null

    /** Day histogram behind the timeline scrubber; null when the current sort has no dates. */
    val timelineIndex: kotlinx.coroutines.flow.StateFlow<TimelineIndex?> by lazy {
        combine(gallerySettings.map { it.library }, selection.map {
            it !is SelectionSpec.Explicit || it.keys.isNotEmpty()
        }) { library, selecting -> library to selecting }
            .distinctUntilChanged()
            .flatMapLatest { (library, selecting) ->
                runtime.filterNotNull().flatMapLatest {
                    it.timeline.timelineIndex(library, collapseStacks = !selecting)
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
    private val mutableBackupRecovery = MutableStateFlow(BackupRecoveryUiState())
    val backupRecovery = mutableBackupRecovery.asStateFlow()
    private var backupRecoveryJob: Job? = null

    fun recoverIncompleteBackups() {
        if (backupRecoveryJob?.isActive == true) return
        val active = runtime.value ?: return
        backupRecoveryJob = viewModelScope.launch {
            mutableBackupRecovery.value = BackupRecoveryUiState(working = true)
            try {
                val result = GalleryRestoreMediaSession.recover(
                    getApplication<Application>(),
                    com.librestatic.lightforge.feature.settings.LocalBackupTaskStore(getApplication<Application>()).retainedGalleryOperations() +
                        GalleryLocalSharingImportPort.retainedOperations(getApplication<Application>()),
                ) {
                    active.database.galleryRestoreReceiptDao().get(it) != null
                }
                mutableBackupRecovery.value = BackupRecoveryUiState(removed = result.removed, retained = result.retained)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableBackupRecovery.value = BackupRecoveryUiState(failed = true) }
        }
    }

    val organizationBackup = runtime.map { it?.organizationBackup }.stateIn(
        viewModelScope, SharingStarted.Eagerly, null,
    )
    private val thumbnailEpoch = MutableStateFlow(0L)
    val thumbnailLoader = combine(runtime, thumbnailEpoch) { active, _ -> active?.thumbnails }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null,
    )
    val physicalAlbums: Flow<PagingData<AlbumSummary>> = runtime.filterNotNull()
        .flatMapLatest { it.albums.physicalAlbums() }
        .cachedIn(viewModelScope)
    val galleryFolderOptions = runtime.filterNotNull()
        .flatMapLatest { it.database.libraryDao().physicalAlbumOptions() }
        .map { rows ->
            rows.filter(PhysicalAlbumRow::isAvailable).map { row ->
                GalleryFolderOption(
                    volumeName = row.volumeName,
                    bucketId = row.bucketId,
                    relativePath = row.relativePath,
                    displayName = row.displayName ?: row.bucketId.toString(),
                    itemCount = row.itemCount,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val virtualAlbums: Flow<PagingData<AlbumSummary>> = runtime.filterNotNull()
        .flatMapLatest { it.albums.virtualAlbums() }
        .cachedIn(viewModelScope)
    val trash: Flow<PagingData<TimelineMedia>> = runtime.filterNotNull()
        .flatMapLatest { it.trash.trash() }
        .cachedIn(viewModelScope)
    val trashCount = runtime.filterNotNull().flatMapLatest { it.trash.countFlow() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val archive: Flow<PagingData<TimelineMedia>> = runtime.filterNotNull()
        .flatMapLatest { it.archive.media() }
        .cachedIn(viewModelScope)
    val memoryExclusionRepository = runtime.map { active ->
        active?.let { com.librestatic.lightforge.core.data.MemoryExclusionRepository(it.database) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val smartAlbumRepository = runtime.map { active ->
        active?.let { com.librestatic.lightforge.core.data.GallerySmartAlbumRepository(it.database) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val photoStackRepository = runtime.map { active ->
        active?.let { com.librestatic.lightforge.core.data.GalleryPhotoStackRepository(it.database) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun createSelectedPhotoStack(): String? {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return null
        if (selected.keys.size < 2 || selectedPdfSources().isEmpty()) return null
        val repo = photoStackRepository.value ?: return null
        return try {
            val id = repo.create(selected.keys.toList())
            if (mutableSelection.value == selected) clearSelection()
            id
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    val documentRepository = runtime.map { active ->
        active?.let { com.librestatic.lightforge.core.data.GalleryDocumentRepository(it.database) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** PDF Studio's Media panel Photos scope (Phase F item 3), so it can query the SAME
     * isAccessible/isTrashed/archive/excluded-folder-filtered set the Photos timeline itself
     * uses, instead of unfiltered MediaStore. */
    val queryMediaRepository = runtime.map { active ->
        active?.let { com.librestatic.lightforge.core.data.GalleryQueryMediaRepository(it.database) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val documentCount = runtime.filterNotNull().flatMapLatest {
        com.librestatic.lightforge.core.data.GalleryDocumentRepository(it.database).count()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    suspend fun organizeSelectedDocuments(): Boolean {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        if (selectedPdfSources().isEmpty()) return false
        val repository = documentRepository.value ?: return false
        return try {
            repository.classify(selected.keys.toList(), com.librestatic.lightforge.core.data.DocumentCategory.Other)
            if (mutableSelection.value == selected) clearSelection()
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    val archiveCount = runtime.filterNotNull().flatMapLatest { it.archive.count() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val activity = runtime.filterNotNull().flatMapLatest { it.activity.latest() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<GalleryActivityEvent>())
    val highlights = runtime.filterNotNull().flatMapLatest { it.highlights.highlights(ZoneId.systemDefault()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<GalleryHighlight>())
    private val mutableSelectedHighlight = MutableStateFlow<GalleryHighlight?>(null)
    val selectedHighlight = mutableSelectedHighlight.asStateFlow()
    val highlightMedia: Flow<PagingData<TimelineMedia>> = runtime.filterNotNull()
        .flatMapLatest { active ->
            mutableSelectedHighlight.filterNotNull().flatMapLatest { active.queryMedia.media(it.query) }
        }
        .cachedIn(viewModelScope)
    val momentSummaries = runtime.filterNotNull().flatMapLatest { it.moments.summaries() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<MomentSummaryRow>())
    private val albumRequest = MutableStateFlow<AlbumRequest?>(null)
    val albumMedia: Flow<PagingData<TimelineMedia>> = runtime.filterNotNull()
        .flatMapLatest { active ->
            albumRequest.filterNotNull().flatMapLatest { request ->
                active.albums.media(request.key, request.filter, request.sort)
            }
        }.cachedIn(viewModelScope)
    private val mutableCurrentMedia = MutableStateFlow<TimelineMedia?>(null)
    val currentMedia = mutableCurrentMedia.asStateFlow()
    private val mutableViewerState = MutableStateFlow(ViewerUiState())
    val viewerState = mutableViewerState.asStateFlow()
    private var viewerQuery = MediaQuery()
    private var viewerWindowJob: Job? = null
    private val mutableSelectedAlbum = MutableStateFlow<AlbumSummary?>(null)
    val selectedAlbum = mutableSelectedAlbum.asStateFlow()
    private val restoredCreationState = CreationRestoreSnapshot.validatedOrNull(savedStateHandle.get<Any>(CreationStateKey))
    private val mutableSelection = MutableStateFlow<SelectionSpec>(restoredCreationState?.selection?.selection ?: SelectionSpec.explicit())
    val selection = mutableSelection.asStateFlow()
    private var selectionRevision = 0L
    private val mutableSelectionCount = MutableStateFlow(restoredCreationState?.selection?.count ?: 0L)
    val selectionCount = mutableSelectionCount.asStateFlow()
    private val explicitTargets = linkedMapOf<com.librestatic.lightforge.core.model.MediaKey, MediaActionTarget>().apply {
        restoredCreationState?.selection?.targets?.forEach { put(it.key, it) }
    }
    private var pendingRestoredViewer = restoredCreationState?.viewer
    private val mutableViewerRestoreSnapshot = MutableStateFlow(pendingRestoredViewer)
    val viewerRestoreSnapshot = mutableViewerRestoreSnapshot.asStateFlow()
    private val mutableViewerSourceChecking = MutableStateFlow(pendingRestoredViewer != null)
    val viewerSourceChecking = mutableViewerSourceChecking.asStateFlow()
    private val mutableViewerRecovered = MutableStateFlow(false)
    val viewerRecovered = mutableViewerRecovered.asStateFlow()
    private val viewerRestoreEpoch = MemoryVideoRequestEpoch()
    private var viewerSnapshotJob: Job? = null
    private val mutablePhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val photoState = mutablePhotoState.asStateFlow()
    private val mutableAdjacentPhotoStates =
        MutableStateFlow<Map<MediaKey, PhotoLoadState.Ready>>(emptyMap())
    val adjacentPhotoStates = mutableAdjacentPhotoStates.asStateFlow()
    private val mutableCheapDetails = MutableStateFlow<CheapMediaDetails?>(null)
    val cheapDetails = mutableCheapDetails.asStateFlow()
    private val mutableExifDetails = MutableStateFlow<ExifLoadResult?>(null)
    val exifDetails = mutableExifDetails.asStateFlow()
    private val mutableDetectedText = MutableStateFlow<String?>(null)
    val detectedText = mutableDetectedText.asStateFlow()
    private val mutableSystemAction = MutableStateFlow(savedStateHandle[ActionStateKey] as? MediaActionSnapshot)
    val systemAction = mutableSystemAction.asStateFlow()
    private val mutableActionLaunches = MutableSharedFlow<MediaActionLaunch>(extraBufferCapacity = 1)
    val actionLaunches = mutableActionLaunches.asSharedFlow()
    private val mutableHardwareVolumeKeys = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val hardwareVolumeKeys = mutableHardwareVolumeKeys.asSharedFlow()
    private var currentSystemCoordinator: MediaStoreActionCoordinator? = null
    private var handledSystemRequestId: Long? = null
    private var pendingWriteMutation: PendingWriteMutation? = savedStateHandle[WriteMutationStateKey]
    private var photoJob: Job? = null
    private var adjacentPhotoJob: Job? = null
    private var photoEditorJob: Job? = null
    private var photoExperimentalJob: Job? = null
    /** Keeps the downloaded inpainting model compiled while a photo editor is open. */
    private var photoInpainting: InpaintingSession? = null
    private var photoEditorOpenGeneration = 0L
    private var photoAutoEnhancementJob: Job? = null
    private var photoAutoEnhancementGeneration = 0L
    private var photoRecipeJob: Job? = null
    private var photoRecipeDiscardJob: Job? = null
    private var rawPreviewJob: Job? = null
    private var rawPreviewSession: RawPreviewSession? = null
    private var rawPreviewRequests: Channel<RawPreviewRequest>? = null
    private var rawPreviewGeneration = 0L
    private var rawPreviewProfile: RawPreviewProfile? = null
    private var videoEditorJob: Job? = null
    private var videoRecipeJob: Job? = null
    private var videoRecipeDiscardJob: Job? = null
    private var videoEditorOpenGeneration = 0L
    private var videoAnnotationTrackingJob: Job? = null
    private val videoAnnotationTrackingEpoch = MemoryVideoRequestEpoch()
    private val videoAnnotationUndo = ArrayDeque<List<VideoAnnotationLayer>>()
    private val videoAnnotationRedo = ArrayDeque<List<VideoAnnotationLayer>>()
    private val videoEditHistory = VideoEditHistory()
    private var videoHistoryApplying: VideoEditRecipe? = null
    private var slowMotionSaveJob: Job? = null
    private var bulkCursor: BulkCursor? = savedStateHandle[BulkStateKey]
    private var favoriteImportCursor: FavoriteImportCursor? = savedStateHandle[FavoriteImportStateKey]
    private var externalOpenGeneration = 0L
    private var pendingRestoredExternalVideoEditor = restoredCreationState?.externalVideoEditor
    private var externalVideoEditorSnapshot = pendingRestoredExternalVideoEditor
    private var externalVideoAccessJob: Job? = null
    private val mutableExternalMedia = MutableStateFlow<ExternalMedia?>(pendingRestoredExternalVideoEditor?.source?.toExternalMedia())
    val externalMedia = mutableExternalMedia.asStateFlow()
    private val mutableExternalPhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val externalPhotoState = mutableExternalPhotoState.asStateFlow()
    private val mutableExternalSaved = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val externalSaved = mutableExternalSaved.asSharedFlow()
    private val mutablePhotoEditor = MutableStateFlow<PhotoEditorSession?>(null)
    val photoEditor = mutablePhotoEditor.asStateFlow()
    private val mutablePhotoEditorOpening = MutableStateFlow(false)
    val photoEditorOpening = mutablePhotoEditorOpening.asStateFlow()
    private val mutableVideoEditor = MutableStateFlow<VideoEditorSession?>(null)
    // Records a history step whenever the editor's recipe changes, so every setter is undoable
    // without each one having to remember to snapshot itself.
    private val videoHistoryTracker = viewModelScope.launch {
        var previous: VideoEditorSession? = null
        mutableVideoEditor.collect { current ->
            trackVideoHistory(previous, current)
            previous = current
        }
    }
    val videoEditor = mutableVideoEditor.asStateFlow()
    private var pendingRestoredVideoEditor = restoredCreationState?.videoEditor
    private var videoEditorSnapshot = pendingRestoredVideoEditor
    private val mutableVideoEditorSessionId = MutableStateFlow(pendingRestoredVideoEditor?.id ?: pendingRestoredExternalVideoEditor?.id)
    val videoEditorSessionId = mutableVideoEditorSessionId.asStateFlow()
    private val mutableVideoEditorOpening = MutableStateFlow(pendingRestoredVideoEditor != null || pendingRestoredExternalVideoEditor != null)
    val videoEditorOpening = mutableVideoEditorOpening.asStateFlow()
    private val mutableEditorCopyOpened = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val editorCopyOpened = mutableEditorCopyOpened.asSharedFlow()
    private val mutableEditorCopyNotice = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Localized notices about lossy fallbacks in a copy that was just saved and opened. */
    val editorCopyNotice = mutableEditorCopyNotice.asSharedFlow()
    private val mutableQuickSlowMotionSave = MutableStateFlow(QuickSlowMotionSaveState())
    val quickSlowMotionSave = mutableQuickSlowMotionSave.asStateFlow()
    private val mutableSanitizedShare = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val sanitizedShare = mutableSanitizedShare.asSharedFlow()
    private val mutableShareError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shareError = mutableShareError.asSharedFlow()
    val backupTaskController = GalleryBackupTaskWorker.controller(application)
    private val mutableRemoteBackupController = MutableStateFlow<com.librestatic.lightforge.feature.remotebackup.RemoteBackupController?>(null)
    val remoteBackupController = mutableRemoteBackupController.asStateFlow()
    private val mutableLocalSharingController = MutableStateFlow<com.librestatic.lightforge.feature.localsharing.LocalSharingController?>(null)
    val localSharingController = mutableLocalSharingController.asStateFlow()
    private val mutablePetIdentityRepository = MutableStateFlow<com.librestatic.lightforge.feature.petrecognition.PetIdentityRepository?>(null)
    val petIdentityRepository = mutablePetIdentityRepository.asStateFlow()
    val ownSyncController = GalleryOwnSyncWorker.controller(application)
    val offlinePlacesController = GalleryOfflinePlacesWorker.controller(application)
    private val mutablePlacesSource = MutableStateFlow<GalleryPlacesSource?>(null)
    internal val placesSource = mutablePlacesSource.asStateFlow()

    fun openPlacePhoto(key: MediaKey) {
        viewModelScope.launch {
            val active=runtime.value ?: return@launch
            if (mutablePlacesSource.value?.hasLocationAccess()!=true) return@launch
            val row=active.database.libraryDao().media(key.volumeName,key.mediaStoreId) ?: return@launch
            if (!row.isAccessible || row.isTrashed || row.mediaType !in listOf(1,3)) return@launch
            openMedia(TimelineMedia(key,if(row.mediaType==3) MediaKind.Video else MediaKind.Image,
                row.generationModified,row.timelineSortMillis,row.width,row.height,row.durationMillis,
                row.dateExpiresSeconds?.times(1000),row.isFavorite,row.isTrashed))
        }
    }
    suspend fun placeThumbnail(key: MediaKey): android.graphics.Bitmap? {
        val active=runtime.value ?: return null
        if(mutablePlacesSource.value?.hasLocationAccess()!=true) return null
        val row=active.database.libraryDao().media(key.volumeName,key.mediaStoreId) ?: return null
        if(!row.isAccessible || row.isTrashed) return null
        return try { active.thumbnails.load(com.librestatic.lightforge.core.thumbnail.ThumbnailRequest(key,row.generationModified,160,160)) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { null }
    }


    val momentRepository = runtime.map { it?.moments }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val momentTitleGazetteer by lazy {
        com.librestatic.lightforge.feature.places.OfflineGazetteer(com.librestatic.lightforge.feature.places.BundledGazetteer.load())
    }

    /** No new GPS reads: only current, permission-authorized cached EXIF from visible members. */
    fun momentPlaceLabel(momentId: String): kotlinx.coroutines.flow.Flow<String?> =
        combine(runtime, access) { current, permission -> current to permission.unredactedLocation }
            .flatMapLatest { (current, allowed) ->
                if (current == null || !allowed) kotlinx.coroutines.flow.flowOf(null)
                else current.database.momentDao().observeLocation(momentId).map { location ->
                    location?.let { momentTitleGazetteer.reverseGeocode(it.latitude, it.longitude) }
                        // The bundled catalogue is sparse. A distant nearest city is not a place label.
                        ?.takeIf { it.distanceKm <= 25.0 }?.city?.name
                }
            }.distinctUntilChanged()

    private var pendingRestoredGif = restoredCreationState?.gif
    private val mutableCreationGifRestoring = MutableStateFlow(pendingRestoredGif != null)
    val creationGifRestoring = mutableCreationGifRestoring.asStateFlow()
    private val mutableCreationGifSessionId = MutableStateFlow(pendingRestoredGif?.id)
    val creationGifSessionId = mutableCreationGifSessionId.asStateFlow()
    private val gifRequestEpoch = MemoryVideoRequestEpoch()
    private val mutableCreationGifSession = MutableStateFlow<CreationGifPreparedSession?>(null)
    val creationGifSession = mutableCreationGifSession.asStateFlow()

    fun canCreateGif(): Boolean {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        return selected.keys.size in 2..60 && selected.keys.all { explicitTargets[it]?.kind == MediaKind.Image }
    }

    suspend fun prepareCreationGif(): Boolean {
        if (mutableCreationGifSession.value != null) return true
        if (pendingRestoredGif != null || mutableCreationGifRestoring.value) return false
        val epoch = gifRequestEpoch.begin()
        pendingRestoredGif = null
        mutableCreationGifRestoring.value = false
        saveCreationState()
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        if (!canCreateGif()) return false
        val targets = selected.keys.map { explicitTargets[it] ?: return false }
        val active = runtime.value ?: return false
        val permission = access.value
        val selectedRevision = selectionRevision
        val sources = withContext(Dispatchers.IO) {
            val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
            targets.map { target ->
                val row = active.database.libraryDao().media(target.key.volumeName, target.key.mediaStoreId)
                    ?: return@withContext null
                val actual = reader.readOne(target.key) ?: return@withContext null
                if (!row.isAccessible || row.isTrashed || actual.isTrashed || actual.kind != MediaKind.Image ||
                    actual.generationModified != row.generationModified || actual.generationAdded != row.generationAdded
                ) return@withContext null
                com.librestatic.lightforge.feature.collage.CreationGifSource(target.uri(), actual.generationModified, actual.generationAdded)
            }
        } ?: return false
        if (!gifRequestEpoch.isCurrent(epoch) || selectionRevision != selectedRevision || mutableSelection.value != selected || runtime.value !== active || access.value != permission) return false
        mutableCreationGifSession.value = CreationGifPreparedSession(java.util.UUID.randomUUID().toString(), sources)
        mutableCreationGifSessionId.value = mutableCreationGifSession.value?.id
        saveCreationState()
        return true
    }

    fun clearCreationGif() {
        gifRequestEpoch.cancel()
        pendingRestoredGif = null
        mutableCreationGifRestoring.value = false
        mutableCreationGifSession.value = null
        mutableCreationGifSessionId.value = null
        saveCreationState()
    }

    private suspend fun restorePendingCreationGif(active: GalleryRuntime) {
        val snapshot = pendingRestoredGif ?: return
        val permission = access.value
        // These DTOs are already bounded and validated by CreationRestoreSnapshot. Passing
        // them to the feature does not grant permission to prepare or render their sources.
        val detachedSources = snapshot.sources.map { source ->
            com.librestatic.lightforge.feature.collage.CreationGifSource(
                Uri.parse(source.uri), source.generationModified, source.generationAdded,
            )
        }
        try {
            val available = try {
                withContext(Dispatchers.IO) {
                    val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                    snapshot.sources.all { source ->
                        val uri = Uri.parse(source.uri)
                        val key = MediaKey(uri.pathSegments.first(), android.content.ContentUris.parseId(uri))
                        val row = active.database.libraryDao().media(key.volumeName, key.mediaStoreId)
                        val actual = reader.readOne(key)
                        row != null && actual != null &&
                            SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual) &&
                            actual.generationModified == source.generationModified && actual.generationAdded == source.generationAdded
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { false }
            if (pendingRestoredGif !== snapshot || runtime.value !== active || permission != access.value) return
            // A published GIF is independent of its originals. The feature checks its durable
            // receipt and bytes before Open/Share, while unavailable inputs prohibit a new render.
            mutableCreationGifSession.value = CreationGifPreparedSession(snapshot.id, detachedSources, available)
        } catch (cancelled: CancellationException) { throw cancelled
        } finally {
            if (pendingRestoredGif === snapshot) {
                pendingRestoredGif = null
                mutableCreationGifRestoring.value = false
                saveCreationState()
            }
        }
    }

    fun creationGifExported() {
        viewModelScope.launch { refreshLibrary() }
    }

    private var pendingRestoredCollage = restoredCreationState?.collage
    private val mutableCreationCollageRestoring = MutableStateFlow(pendingRestoredCollage != null)
    val creationCollageRestoring = mutableCreationCollageRestoring.asStateFlow()
    private val mutableCreationCollageSessionId = MutableStateFlow(pendingRestoredCollage?.id)
    val creationCollageSessionId = mutableCreationCollageSessionId.asStateFlow()
    private val collageRequestEpoch = MemoryVideoRequestEpoch()
    private val mutableCreationCollageSession = MutableStateFlow<CreationCollagePreparedSession?>(null)
    val creationCollageSession = mutableCreationCollageSession.asStateFlow()

    fun canCreateCollage(): Boolean = evaluateCollageSelection() is CollagePreparation.Ready

    /**
     * Reports exactly why a collage cannot be started so the caller can explain it to the user
     * instead of collapsing every cause into one generic snackbar.
     */
    internal fun evaluateCollageSelection(): CollagePreparation {
        if (mutableCreationCollageSession.value != null) return CollagePreparation.Ready
        if (pendingRestoredCollage != null) {
            return CollagePreparation.Rejected(CollageRejection.DraftPending, 0)
        }
        val selected = mutableSelection.value as? SelectionSpec.Explicit
            ?: return CollagePreparation.Rejected(CollageRejection.NothingSelected, 0)
        val count = selected.keys.size
        return when {
            count == 0 -> CollagePreparation.Rejected(CollageRejection.NothingSelected, 0)
            count == 1 -> CollagePreparation.Rejected(CollageRejection.NotEnoughSelection, count)
            count > 4 -> CollagePreparation.Rejected(CollageRejection.TooMany, count)
            !selected.keys.all { explicitTargets[it]?.kind == MediaKind.Image } ->
                CollagePreparation.Rejected(CollageRejection.NonImageSelected, count)
            else -> CollagePreparation.Ready
        }
    }

    internal suspend fun prepareCreationCollage(): CollagePreparation {
        // Back from an unresolved publication retains this exact session. The next Collage
        // action resumes it rather than silently replacing its receipt with a new request.
        if (mutableCreationCollageSession.value != null) return CollagePreparation.Ready
        val preflight = evaluateCollageSelection()
        if (preflight is CollagePreparation.Rejected) return preflight
        val epoch = collageRequestEpoch.begin()
        pendingRestoredCollage = null
        mutableCreationCollageRestoring.value = false
        saveCreationState()
        val selected = mutableSelection.value as? SelectionSpec.Explicit
            ?: return CollagePreparation.Rejected(CollageRejection.NothingSelected, 0)
        val unavailable = CollagePreparation.Rejected(CollageRejection.SourceUnavailable, selected.keys.size)
        val targets = selected.keys.map { explicitTargets[it] ?: return unavailable }
        val active = runtime.value ?: return unavailable
        val permission = access.value
        val selectedRevision = selectionRevision
        val sources = withContext(Dispatchers.IO) {
            val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
            targets.map { target ->
                val row = active.database.libraryDao().media(target.key.volumeName, target.key.mediaStoreId)
                    ?: return@withContext null
                val actual = reader.readOne(target.key) ?: return@withContext null
                if (!row.isAccessible || row.isTrashed || actual.isTrashed || actual.kind != MediaKind.Image ||
                    actual.generationModified != row.generationModified || actual.generationAdded != row.generationAdded
                ) {
                    return@withContext null
                }
                com.librestatic.lightforge.feature.collage.CreationCollageSource(target.uri(), actual.generationModified, actual.generationAdded)
            }
        } ?: return unavailable
        if (!collageRequestEpoch.isCurrent(epoch) || selectionRevision != selectedRevision || mutableSelection.value != selected || runtime.value !== active || access.value != permission) return unavailable
        mutableCreationCollageSession.value = CreationCollagePreparedSession(java.util.UUID.randomUUID().toString(), sources)
        mutableCreationCollageSessionId.value = mutableCreationCollageSession.value?.id
        saveCreationState()
        return CollagePreparation.Ready
    }

    fun clearCreationCollage() {
        collageRequestEpoch.cancel()
        pendingRestoredCollage = null
        mutableCreationCollageRestoring.value = false
        mutableCreationCollageSession.value = null
        mutableCreationCollageSessionId.value = null
        saveCreationState()
    }

    private suspend fun restorePendingCreationCollage(active: GalleryRuntime) {
        val snapshot = pendingRestoredCollage ?: return
        val permission = access.value
        // These DTOs are already bounded and validated by CreationRestoreSnapshot. Passing
        // them to the feature does not grant permission to prepare or render their sources.
        val detachedSources = snapshot.sources.map { source ->
            com.librestatic.lightforge.feature.collage.CreationCollageSource(
                Uri.parse(source.uri), source.generationModified, source.generationAdded,
            )
        }
        try {
            val available = try {
                withContext(Dispatchers.IO) {
                    val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                    snapshot.sources.all { source ->
                        val uri = Uri.parse(source.uri)
                        val key = MediaKey(uri.pathSegments.first(), android.content.ContentUris.parseId(uri))
                        val row = active.database.libraryDao().media(key.volumeName, key.mediaStoreId)
                        val actual = reader.readOne(key)
                        row != null && actual != null &&
                            SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual) &&
                            actual.generationModified == source.generationModified && actual.generationAdded == source.generationAdded
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { false }
            if (pendingRestoredCollage !== snapshot || runtime.value !== active || permission != access.value) return
            // A published PNG is independent of its originals. The feature checks its durable
            // receipt and bytes before Open/Share, while unavailable inputs prohibit a new render.
            mutableCreationCollageSession.value = CreationCollagePreparedSession(snapshot.id, detachedSources, available)
        } catch (cancelled: CancellationException) { throw cancelled
        } finally {
            if (pendingRestoredCollage === snapshot) {
                pendingRestoredCollage = null
                mutableCreationCollageRestoring.value = false
                saveCreationState()
            }
        }
    }

    fun creationCollageExported() {
        viewModelScope.launch { refreshLibrary() }
    }

    data class ManualMomentSession(
        val draft: com.librestatic.lightforge.core.data.ManualMomentDraft,
        val sources: List<TimelineMedia>,
        val originSelection: CreationSelectionSnapshot?,
        val originSelectionRevision: Long?,
    )
    private var pendingRestoredManualMoment = restoredCreationState?.manualMoment
    private val restoredManualOriginRevision = selectionRevision
    private val mutableManualMomentSessionId = MutableStateFlow(pendingRestoredManualMoment?.id)
    val manualMomentSessionId = mutableManualMomentSessionId.asStateFlow()
    private val mutableManualMomentRestoring = MutableStateFlow(pendingRestoredManualMoment != null)
    val manualMomentRestoring = mutableManualMomentRestoring.asStateFlow()
    private val mutableManualMomentSession = MutableStateFlow<ManualMomentSession?>(null)
    val manualMomentSession = mutableManualMomentSession.asStateFlow()
    private val mutableManualMomentBusy = MutableStateFlow(false)
    val manualMomentBusy = mutableManualMomentBusy.asStateFlow()
    private val mutableManualMomentError = MutableStateFlow(false)
    val manualMomentError = mutableManualMomentError.asStateFlow()
    enum class ManualMomentRecoveryStatus { Committed, Missing, Conflict, Unavailable }
    data class ManualMomentRecovery(val request: ManualMomentCreateRequest?, val status: ManualMomentRecoveryStatus)
    private val manualMomentPendingStore by lazy {
        ManualMomentPendingCreateStore(java.io.File(getApplication<Application>().noBackupFilesDir.canonicalFile, "manual-memory-pending"))
    }
    private val mutableManualMomentPendingCreate = MutableStateFlow<ManualMomentCreateRequest?>(null)
    val manualMomentPendingCreate = mutableManualMomentPendingCreate.asStateFlow()
    private val mutableManualMomentAcknowledgementToken = MutableStateFlow<String?>(null)
    val manualMomentAcknowledgementToken = mutableManualMomentAcknowledgementToken.asStateFlow()
    private val mutableManualMomentRecovery = MutableStateFlow<ManualMomentRecovery?>(null)
    val manualMomentRecovery = mutableManualMomentRecovery.asStateFlow()
    private var manualMomentRecoveryChecking = true
    private var manualMomentJournalUnavailable = false

    private fun clearManualDraftForPending(request: ManualMomentCreateRequest) {
        val id = request.draft.id
        if (pendingRestoredManualMoment?.id == id) pendingRestoredManualMoment = null
        if (mutableManualMomentSession.value?.draft?.id == id) mutableManualMomentSession.value = null
        if (mutableManualMomentSessionId.value == id) mutableManualMomentSessionId.value = null
        if (pendingRestoredManualMoment == null) mutableManualMomentRestoring.value = false
    }

    private suspend fun reconcilePendingManualMomentCreate(active: GalleryRuntime) {
        mutableManualMomentAcknowledgementToken.value = null
        manualMomentRecoveryChecking = true
        var request: ManualMomentCreateRequest? = null
        try {
            request = withContext(Dispatchers.IO) { manualMomentPendingStore.read() }
            val status = request?.let { value -> withContext(Dispatchers.IO) {
                com.librestatic.lightforge.core.data.ManualMomentRepository(active.database).committedStatus(
                    value.draft.toDraft(), value.orderedKeys, value.title, value.includeSpecialMedia,
                )
            } }
            if (runtime.value !== active) return
            mutableManualMomentPendingCreate.value = request
            manualMomentJournalUnavailable = false
            mutableManualMomentRecovery.value = request?.let { value -> ManualMomentRecovery(value, when (status) {
                com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Committed -> ManualMomentRecoveryStatus.Committed
                com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Missing -> ManualMomentRecoveryStatus.Missing
                else -> ManualMomentRecoveryStatus.Conflict
            }) }
            if (request != null) {
                // The durable reviewed request, not possibly older SavedState, owns pending Save recovery.
                if (pendingRestoredManualMoment?.id == request.draft.id) pendingRestoredManualMoment = null
                if (mutableManualMomentSession.value?.draft?.id == request.draft.id) mutableManualMomentSession.value = null
                if (pendingRestoredManualMoment == null) mutableManualMomentRestoring.value = false
                saveCreationState()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            manualMomentJournalUnavailable = true
            mutableManualMomentPendingCreate.value = request
            mutableManualMomentRecovery.value = ManualMomentRecovery(request, ManualMomentRecoveryStatus.Unavailable)
        } finally { manualMomentRecoveryChecking = false }
    }

    fun checkManualMomentRecovery() {
        if (mutableManualMomentBusy.value || manualMomentRecoveryChecking) return
        val active = runtime.value ?: return
        viewModelScope.launch {
            mutableManualMomentBusy.value = true
            try { reconcilePendingManualMomentCreate(active) } finally { mutableManualMomentBusy.value = false }
        }
    }

    /** Closing this notice does not acknowledge or discard the durable Save request. */
    fun hideManualMomentRecovery() { if (!mutableManualMomentBusy.value) mutableManualMomentRecovery.value = null }

    private fun verifyManualMomentProviderSources(request: ManualMomentCreateRequest) {
        val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
        val sources = request.draft.sources.associateBy { it.key }
        for (key in request.orderedKeys) {
            val expected = sources.getValue(key)
            val actual = checkNotNull(reader.readOne(key))
            check(actual.kind == MediaKind.Image && !actual.isTrashed &&
                actual.generationAdded == expected.generationAdded && actual.generationModified == expected.generationModified)
        }
    }

    /** User-triggered Retry/Open: recheck receipt before provider access; never replay on initialization. */
    suspend fun resumePendingManualMomentCreate(): String? {
        if (mutableManualMomentBusy.value || manualMomentRecoveryChecking) return null
        val request = mutableManualMomentPendingCreate.value ?: return null
        val active = runtime.value ?: return null
        val permission = access.value
        mutableManualMomentBusy.value = true
        mutableManualMomentError.value = false
        return try {
            val id = withContext(Dispatchers.IO) {
                check(manualMomentPendingStore.read() == request)
                val repo = com.librestatic.lightforge.core.data.ManualMomentRepository(active.database)
                when (repo.committedStatus(request.draft.toDraft(), request.orderedKeys, request.title, request.includeSpecialMedia)) {
                    com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Committed -> request.draft.id
                    com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Conflict -> error("Conflicting pending manual request")
                    com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Missing -> {
                        verifyManualMomentProviderSources(request)
                        check(runtime.value === active && permission == access.value)
                        manualMomentPendingStore.put(request) // Re-fsync a previous publication whose acknowledgement failed.
                        repo.create(request.draft.toDraft(), request.orderedKeys, request.title, request.includeSpecialMedia)
                            .also { ManualMomentCommitProbe.afterCommit(getApplication(), request) }
                    }
                }
            }
            if (runtime.value !== active || mutableManualMomentPendingCreate.value != request) return null
            openMoment(id)
            mutableManualMomentAcknowledgementToken.value = request.token
            // Recovery never consumes a selection that may belong to a newer task/user action.
            clearManualDraftForPending(request)
            mutableManualMomentRecovery.value = null
            saveCreationState()
            id
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableManualMomentError.value = true
            reconcilePendingManualMomentCreate(active)
            null
        } finally { mutableManualMomentBusy.value = false }
    }

    /** Called only after the expected loaded Moment has been displayed, never at Room commit time. */
    suspend fun acknowledgeManualMomentCreate(momentId: String) {
        val request = mutableManualMomentPendingCreate.value?.takeIf { it.draft.id == momentId } ?: return
        if (mutableManualMomentRecovery.value != null || selectedMoment.value?.momentId != momentId ||
            mutableManualMomentAcknowledgementToken.value != request.token) return
        val active = runtime.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val status = com.librestatic.lightforge.core.data.ManualMomentRepository(active.database).committedStatus(
                    request.draft.toDraft(), request.orderedKeys, request.title, request.includeSpecialMedia,
                )
                check(manualMomentMayAcknowledge(request, mutableManualMomentAcknowledgementToken.value,
                    selectedMoment.value?.momentId, status))
                val cleared = manualMomentPendingStore.clear(request)
                check(cleared || manualMomentPendingStore.read() == null)
            }
            if (mutableManualMomentPendingCreate.value == request) {
                mutableManualMomentPendingCreate.value = null
                mutableManualMomentAcknowledgementToken.value = null
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Keep the marker and an explicit retry instead of silently accepting a failed durable acknowledgement.
            mutableManualMomentAcknowledgementToken.value = null
            mutableManualMomentRecovery.value = ManualMomentRecovery(request, ManualMomentRecoveryStatus.Unavailable)
        }
    }

    /** Explicitly dismisses only this recovery record; never deletes a saved memory or any source. */
    suspend fun dismissManualMomentRecovery(): Boolean {
        if (mutableManualMomentBusy.value || manualMomentRecoveryChecking) return false
        val request = mutableManualMomentPendingCreate.value ?: return false
        mutableManualMomentBusy.value = true
        return try {
            withContext(Dispatchers.IO) { check(manualMomentPendingStore.clear(request)) }
            mutableManualMomentPendingCreate.value = null
            mutableManualMomentAcknowledgementToken.value = null
            mutableManualMomentRecovery.value = null
            manualMomentJournalUnavailable = false
            clearManualDraftForPending(request)
            saveCreationState()
            true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            mutableManualMomentRecovery.value = ManualMomentRecovery(request, ManualMomentRecoveryStatus.Unavailable)
            false
        } finally { mutableManualMomentBusy.value = false }
    }

    private val manualMomentEpoch = MemoryVideoRequestEpoch()
    fun cancelPendingManualMomentPreparation() { manualMomentEpoch.cancel() }
    fun clearManualMoment() {
        if (mutableManualMomentBusy.value) return
        manualMomentEpoch.cancel()
        pendingRestoredManualMoment = null
        mutableManualMomentRestoring.value = false
        mutableManualMomentSessionId.value = null
        mutableManualMomentSession.value = null
        mutableManualMomentError.value = false
        saveCreationState()
    }

    suspend fun prepareManualMoment(documentKeys: List<MediaKey>? = null): Boolean {
        if (mutableManualMomentBusy.value || manualMomentRecoveryChecking) return false
        if (mutableManualMomentPendingCreate.value != null || manualMomentJournalUnavailable) {
            checkManualMomentRecovery()
            return false
        }
        val epoch = manualMomentEpoch.begin()
        pendingRestoredManualMoment = null
        mutableManualMomentRestoring.value = false
        saveCreationState()
        val selected = mutableSelection.value
        val revision = selectionRevision
        val origin = if (documentKeys == null) CreationRestoreSnapshot.capture(
            selected, explicitTargets.values, mutableSelectionCount.value,
        )?.selection ?: return false else null
        val keys = documentKeys?.toList() ?: (selected as? SelectionSpec.Explicit)?.keys?.toList() ?: return false
        if (!SelectionMemoryVideoPreparation.acceptsSelection(keys)) return false
        val active = runtime.value ?: return false
        val permission = access.value
        val prepared = withContext(Dispatchers.IO) {
            val repository = com.librestatic.lightforge.core.data.ManualMomentRepository(active.database)
            val draft = repository.prepare(keys)
            val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
            val sources = draft.sources.map { snapshot ->
                val row = active.database.libraryDao().media(snapshot.key.volumeName, snapshot.key.mediaStoreId)
                    ?: return@withContext null
                val actual = reader.readOne(snapshot.key) ?: return@withContext null
                if (!SelectionMemoryVideoPreparation.matchesCurrentPhoto(snapshot.key, row, actual) ||
                    row.generationAdded != snapshot.generationAdded || row.generationModified != snapshot.generationModified)
                    return@withContext null
                TimelineMedia(snapshot.key, MediaKind.Image, row.generationModified, row.timelineSortMillis,
                    row.width, row.height, row.durationMillis, isFavorite = row.isFavorite,
                    displayName = row.displayName, sizeBytes = row.sizeBytes)
            }
            ManualMomentSession(draft, sources, origin, if (origin != null) revision else null)
        } ?: return false
        if (!manualMomentEpoch.isCurrent(epoch) || runtime.value !== active || permission != access.value ||
            (documentKeys == null && (selected != mutableSelection.value || revision != selectionRevision))) return false
        mutableManualMomentError.value = false
        mutableManualMomentSession.value = prepared
        mutableManualMomentSessionId.value = prepared.draft.id
        saveCreationState()
        return true
    }

    private fun ownsManualOrigin(origin: CreationSelectionSnapshot?, revision: Long?): Boolean =
        manualMomentOwnsSelection(origin, revision, mutableSelection.value,
            explicitTargets.values.toList(), mutableSelectionCount.value, selectionRevision)

    /** Rehydrate the original UUID only. Restoring a review never calls prepare/create. */
    private suspend fun restorePendingManualMoment(active: GalleryRuntime) {
        val snapshot = pendingRestoredManualMoment ?: return
        val permission = access.value
        try {
            val sources = withContext(Dispatchers.IO) {
                val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                snapshot.sources.map { source ->
                    val row = active.database.momentDao().manualSource(source.key.volumeName, source.key.mediaStoreId)
                        ?: return@withContext null
                    val actual = reader.readOne(source.key) ?: return@withContext null
                    if (!SelectionMemoryVideoPreparation.matchesCurrentPhoto(source.key, row, actual) ||
                        row.generationAdded != source.generationAdded || row.generationModified != source.generationModified)
                        return@withContext null
                    TimelineMedia(source.key, MediaKind.Image, row.generationModified, row.timelineSortMillis,
                        row.width, row.height, row.durationMillis, isFavorite = row.isFavorite,
                        displayName = row.displayName, sizeBytes = row.sizeBytes)
                }
            }
            if (pendingRestoredManualMoment !== snapshot || runtime.value !== active || permission != access.value) return
            if (sources != null) mutableManualMomentSession.value = ManualMomentSession(
                snapshot.toDraft(), sources, snapshot.originSelection,
                restoredManualOriginRevision.takeIf { ownsManualOrigin(snapshot.originSelection, it) },
            )
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Retain an explicit unavailable/Back route; never substitute fewer sources or save automatically.
        } finally {
            if (pendingRestoredManualMoment === snapshot) {
                pendingRestoredManualMoment = null
                mutableManualMomentRestoring.value = false
                saveCreationState()
            }
        }
    }

    suspend fun createManualMoment(title: String, orderedKeys: List<MediaKey>, includeSpecialMedia: Boolean): String? {
        if (mutableManualMomentBusy.value || manualMomentRecoveryChecking) return null
        if (mutableManualMomentPendingCreate.value != null || manualMomentJournalUnavailable) {
            checkManualMomentRecovery()
            return null
        }
        val session = mutableManualMomentSession.value ?: return null
        val active = runtime.value ?: return null
        val permission = access.value
        mutableManualMomentBusy.value = true
        mutableManualMomentError.value = false
        return try {
            val request = ManualMomentCreateRequest.capture(session.draft, title, orderedKeys, includeSpecialMedia)
            // Publish in-memory ownership before suspension: cancellation cannot hide a durable intent and admit a new draft.
            mutableManualMomentPendingCreate.value = request
            val id = withContext(Dispatchers.IO) {
                check(manualMomentPendingStore.read() == null) { "Resolve the existing pending Save first" }
                val repo = com.librestatic.lightforge.core.data.ManualMomentRepository(active.database)
                val status = repo.committedStatus(request.draft.toDraft(), request.orderedKeys, request.title, request.includeSpecialMedia)
                check(status != com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Conflict)
                if (status == com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Missing) verifyManualMomentProviderSources(request)
                check(runtime.value === active && permission == access.value && mutableManualMomentSession.value === session)
                manualMomentPendingStore.put(request) // Durably publish the reviewed intent BEFORE any Room write.
                if (status == com.librestatic.lightforge.core.data.ManualMomentCommitStatus.Committed) request.draft.id
                else repo.create(request.draft.toDraft(), request.orderedKeys, request.title, request.includeSpecialMedia)
                    .also { ManualMomentCommitProbe.afterCommit(getApplication(), request) }
            }
            mutableManualMomentPendingCreate.value = request
            if (runtime.value !== active || permission != access.value || mutableManualMomentSession.value !== session) {
                reconcilePendingManualMomentCreate(active)
                return null
            }
            openMoment(id)
            mutableManualMomentAcknowledgementToken.value = request.token
            val consumeSelection = ownsManualOrigin(session.originSelection, session.originSelectionRevision)
            mutableManualMomentSession.value = null
            mutableManualMomentSessionId.value = null
            pendingRestoredManualMoment = null
            if (consumeSelection) clearSelection() else saveCreationState()
            id
        } catch (cancelled: CancellationException) {
            mutableManualMomentPendingCreate.value?.let {
                mutableManualMomentRecovery.value = ManualMomentRecovery(it, ManualMomentRecoveryStatus.Unavailable)
            }
            throw cancelled
        } catch (_: Exception) {
            mutableManualMomentError.value = true
            reconcilePendingManualMomentCreate(active)
            null
        } finally { mutableManualMomentBusy.value = false }
    }

    private var pendingRestoredVideo = restoredCreationState?.video
    private val mutableMemoryVideoRestoring = MutableStateFlow(pendingRestoredVideo != null)
    val memoryVideoRestoring = mutableMemoryVideoRestoring.asStateFlow()
    private val mutableMemoryVideoSources = MutableStateFlow<List<com.librestatic.lightforge.core.editing.video.MemoryVideoSource>>(emptyList())
    val memoryVideoSources = mutableMemoryVideoSources.asStateFlow()
    private val mutableMemoryVideoTitle = MutableStateFlow(pendingRestoredVideo?.title)
    val memoryVideoTitle = mutableMemoryVideoTitle.asStateFlow()

    private val memoryVideoRequestEpoch = MemoryVideoRequestEpoch()
    fun cancelPendingMemoryVideoPreparation() { memoryVideoRequestEpoch.cancel() }

    private val mutableMemoryVideoSessionId = MutableStateFlow(pendingRestoredVideo?.id)
    val memoryVideoSessionId = mutableMemoryVideoSessionId.asStateFlow()

    fun canCreateSelectionVideo(): Boolean {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        return SelectionMemoryVideoPreparation.acceptsSelection(selected.keys.toList()) &&
            selected.keys.all { explicitTargets[it]?.kind == MediaKind.Image }
    }

    suspend fun prepareSelectionVideo(): Boolean {
        val requestEpoch = memoryVideoRequestEpoch.begin()
        // An explicit new request owns this route even when preparation later fails.
        pendingRestoredVideo = null
        mutableMemoryVideoRestoring.value = false
        saveCreationState()
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        if (!canCreateSelectionVideo()) return false
        val active = runtime.value ?: return false
        val permission = access.value
        val prepared = withContext(Dispatchers.IO) {
            val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
            SelectionMemoryVideoPreparation.prepare(selected.keys.toList(),
                indexed = { active.database.libraryDao().media(it.volumeName, it.mediaStoreId) },
                current = reader::readOne)
        } ?: return false
        if (!memoryVideoRequestEpoch.isCurrent(requestEpoch) || mutableSelection.value != selected || runtime.value !== active || access.value != permission) return false
        mutableMemoryVideoTitle.value = null
        mutableMemoryVideoSources.value = prepared.sources
        mutableMemoryVideoSessionId.value = prepared.id
        pendingRestoredVideo = null
        mutableMemoryVideoRestoring.value = false
        saveCreationState()
        return true
    }

    suspend fun prepareSelectedMemoryVideo(): Boolean {
        val requestEpoch = memoryVideoRequestEpoch.begin()
        // An explicit new request owns this route even when preparation later fails.
        pendingRestoredVideo = null
        mutableMemoryVideoRestoring.value = false
        saveCreationState()
        val id = selectedMoment.value?.momentId ?: return false
        val active = runtime.value ?: return false
        val permission = access.value
        val repository = active.moments
        val detail = repository.observeMoment(id).first() ?: return false
        val rows = repository.members(id)
        if (!memoryVideoRequestEpoch.isCurrent(requestEpoch) || rows.isEmpty() || rows.size > 120 || mutableSelectedMomentId.value != id || runtime.value !== active || access.value != permission) return false
        mutableMemoryVideoTitle.value = detail.title
        mutableMemoryVideoSources.value = rows.map { row ->
            com.librestatic.lightforge.core.editing.video.MemoryVideoSource(
                android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.getContentUri(row.media.volumeName), row.media.mediaStoreId
                ), row.media.generationModified, row.media.generationAdded
            )
        }
        mutableMemoryVideoSessionId.value = java.util.UUID.randomUUID().toString()
        pendingRestoredVideo = null
        mutableMemoryVideoRestoring.value = false
        saveCreationState()
        return true
    }

    fun clearMemoryVideo() {
        memoryVideoRequestEpoch.cancel()
        pendingRestoredVideo = null
        mutableMemoryVideoRestoring.value = false
        mutableMemoryVideoSessionId.value = null
        mutableMemoryVideoSources.value = emptyList()
        mutableMemoryVideoTitle.value = null
        saveCreationState()
    }

    /** Small task state only: no media bytes, cryptographic keys, passwords, exports or background replay. */
    private fun saveCreationState() {
        val video = pendingRestoredVideo ?: mutableMemoryVideoSessionId.value?.let { id ->
            val sources = mutableMemoryVideoSources.value
            if (sources.isEmpty() || sources.any { it.expectedGeneration == null || it.expectedGenerationAdded == null }) null
            else CreationVideoSnapshot(id, mutableMemoryVideoTitle.value, sources.map {
                CreationVideoSourceSnapshot(it.uri.toString(), requireNotNull(it.expectedGeneration), requireNotNull(it.expectedGenerationAdded))
            })
        }
        val collage = pendingRestoredCollage ?: mutableCreationCollageSession.value?.let { session ->
            if (session.sources.any { it.expectedGeneration == null || it.expectedGenerationAdded == null }) null
            else CreationCollageSnapshot(session.id, session.sources.map {
                CreationVideoSourceSnapshot(it.uri.toString(), requireNotNull(it.expectedGeneration), requireNotNull(it.expectedGenerationAdded))
            })
        }
        val gif = pendingRestoredGif ?: mutableCreationGifSession.value?.let { session ->
            if (session.sources.any { it.expectedGeneration == null || it.expectedGenerationAdded == null }) null
            else CreationGifSnapshot(session.id, session.sources.map {
                CreationVideoSourceSnapshot(it.uri.toString(), requireNotNull(it.expectedGeneration), requireNotNull(it.expectedGenerationAdded))
            })
        }
        savedStateHandle[CreationStateKey] = CreationRestoreSnapshot.capture(
            mutableSelection.value, explicitTargets.values, mutableSelectionCount.value, video, collage, gif,
            pendingRestoredViewer ?: mutableViewerRestoreSnapshot.value,
            pendingRestoredVideoEditor ?: videoEditorSnapshot,
            pendingRestoredManualMoment?.let { snapshot ->
                snapshot.copy(originSelection = snapshot.originSelection?.takeIf {
                    ownsManualOrigin(it, restoredManualOriginRevision)
                })
            } ?: mutableManualMomentSession.value?.let { session ->
                ManualMomentRestoreSnapshot.capture(session.draft, session.originSelection?.takeIf {
                    ownsManualOrigin(it, session.originSelectionRevision)
                })
            },
            pendingRestoredExternalVideoEditor ?: externalVideoEditorSnapshot,
        )
    }

    /** Never render a restored URI before its current access, identity and both generations agree. */
    private suspend fun restorePendingCreationVideo(active: GalleryRuntime) {
        val snapshot = pendingRestoredVideo ?: return
        val permission = access.value
        try {
            val restored = withContext(Dispatchers.IO) {
                val reader = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                snapshot.sources.map { source ->
                    val uri = Uri.parse(source.uri)
                    val key = MediaKey(uri.pathSegments.first(), android.content.ContentUris.parseId(uri))
                    val row = active.database.libraryDao().media(key.volumeName, key.mediaStoreId) ?: return@withContext null
                    val actual = reader.readOne(key) ?: return@withContext null
                    if (!SelectionMemoryVideoPreparation.matchesCurrentPhoto(key, row, actual) ||
                        actual.generationModified != source.generationModified || actual.generationAdded != source.generationAdded)
                        return@withContext null
                    com.librestatic.lightforge.core.editing.video.MemoryVideoSource(uri, source.generationModified, source.generationAdded)
                }
            }
            if (pendingRestoredVideo !== snapshot || runtime.value !== active || permission != access.value) return
            if (restored != null) {
                mutableMemoryVideoSources.value = restored
                mutableMemoryVideoTitle.value = snapshot.title
                mutableMemoryVideoSessionId.value = snapshot.id
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // The ordinary unavailable route offers Back; never silently substitute fewer sources.
        } finally {
            if (pendingRestoredVideo === snapshot) {
                pendingRestoredVideo = null
                mutableMemoryVideoRestoring.value = false
                saveCreationState()
            }
        }
    }

    private val mutableSelectedMomentId = MutableStateFlow(savedStateHandle.get<String>(SelectedMomentStateKey))
    val selectedMoment = combine(runtime, mutableSelectedMomentId) { current, id -> current to id }
        .flatMapLatest { (current, id) ->
            if (current == null || id == null) flowOf<MomentEntity?>(null)
            else current.moments.observeMoment(id).map { it?.takeUnless { row -> row.state == "DISMISSED" } }.onStart { emit(null) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val participantsReload = MutableStateFlow(0L)
    private val mutableParticipantsLoadFailed = MutableStateFlow(false)
    val participantsLoadFailed = mutableParticipantsLoadFailed.asStateFlow()
    val momentParticipants = combine(runtime, mutableSelectedMomentId, participantsReload) { current, id, _ -> current to id }
        .flatMapLatest { (current, id) ->
            mutableParticipantsLoadFailed.value = false
            if (current == null || id == null) flowOf<com.librestatic.lightforge.feature.collections.MomentParticipantsSnapshot?>(null)
            else com.librestatic.lightforge.core.data.MomentParticipantsRepository(current.database, com.librestatic.lightforge.core.ml.PersonClusteringMlEngine.AlgorithmVersion)
                .observe(id).map { state ->
                    if (state == null) { mutableParticipantsLoadFailed.value = true; null }
                    else com.librestatic.lightforge.feature.collections.MomentParticipantsSnapshot(state.momentId, state.revision,
                        if (state.manual) com.librestatic.lightforge.feature.collections.MomentParticipantsMode.Manual else com.librestatic.lightforge.feature.collections.MomentParticipantsMode.Automatic,
                        state.selectedIds, state.people.map { person -> PersonCardUi(person.clusterId, person.displayName, person.memberCount,
                            MediaKey(person.coverVolumeName, person.coverMediaStoreId)) }, state.automaticIds,
                        coverGenerations = state.people.associate { it.clusterId to it.coverGenerationModified })
                }.onStart { emit(null) }.catch { error ->
                    if (error is CancellationException) throw error
                    mutableParticipantsLoadFailed.value = true; emit(null)
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reloadMomentParticipants() { participantsReload.value++ }

    suspend fun applyMomentParticipants(request: com.librestatic.lightforge.feature.collections.MomentParticipantsApplyRequest): com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult {
        val active = runtime.value ?: return com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult.Unavailable
        if (mutableSelectedMomentId.value != request.momentId) return com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult.Unavailable
        val result = com.librestatic.lightforge.core.data.MomentParticipantsRepository(active.database, com.librestatic.lightforge.core.ml.PersonClusteringMlEngine.AlgorithmVersion)
            .apply(request.momentId, request.expectedRevision, request.mode == com.librestatic.lightforge.feature.collections.MomentParticipantsMode.Manual, request.selectedIds)
        return when (result) {
            com.librestatic.lightforge.core.data.MomentParticipantsWrite.Saved -> com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult.Saved
            com.librestatic.lightforge.core.data.MomentParticipantsWrite.Conflict -> com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult.Conflict
            com.librestatic.lightforge.core.data.MomentParticipantsWrite.Unavailable -> com.librestatic.lightforge.feature.collections.MomentParticipantsApplyResult.Unavailable
        }
    }

    private val refreshMutex = Mutex()

    fun onHardwareVolumeKey() {
        mutableHardwareVolumeKeys.tryEmit(Unit)
    }

    private val mutableCollectionLayoutWorking = MutableStateFlow(false)
    val collectionLayoutWorking = mutableCollectionLayoutWorking.asStateFlow()
    private val mutableCollectionLayoutFailed = MutableStateFlow(false)
    val collectionLayoutFailed = mutableCollectionLayoutFailed.asStateFlow()
    private val mutableCollectionLayoutRevision = MutableStateFlow(0)
    val collectionLayoutRevision = mutableCollectionLayoutRevision.asStateFlow()

    fun saveCollectionLayout(order: List<String>, hidden: Set<String>) {
        if (mutableCollectionLayoutWorking.value) return
        val ordered = order.toList()
        val invisible = hidden.toSet()
        mutableCollectionLayoutWorking.value = true
        mutableCollectionLayoutFailed.value = false
        viewModelScope.launch {
            try {
                gallerySettingsRepository.update { it.copy(library = it.library.copy(collectionOrder = ordered, hiddenCollections = invisible)) }
                mutableCollectionLayoutRevision.value += 1
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableCollectionLayoutFailed.value = true
            } finally { mutableCollectionLayoutWorking.value = false }
        }
    }

    fun updateGallerySettings(transform: (GallerySettings) -> GallerySettings) {
        viewModelScope.launch { gallerySettingsRepository.update(transform) }
    }

    fun resetGallerySettings() {
        viewModelScope.launch { gallerySettingsRepository.reset() }
    }

    fun exportGallerySettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val favorites = runtime.value?.database?.libraryDao()?.favoriteMediaForBackup().orEmpty()
                .map { it.toFavoriteBackupRecord() }
            val backup = GalleryBackupCodec.encode(gallerySettingsRepository.exportJson(), favorites)
            getApplication<Application>().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(backup.toString(2))
            }
        }
    }

    fun importGallerySettings(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val root = getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                JSONObject(it.readText())
            } ?: return@launch
            val payload = GalleryBackupCodec.decode(root)
            gallerySettingsRepository.importJson(payload.settings)
            val dao = runtime.value?.database?.libraryDao() ?: return@launch
            val targets = payload.favorites.mapNotNull { record ->
                val exact = dao.media(record.volumeName, record.mediaStoreId)
                    ?.takeIf { it.matchesBackupRecord(record, requireIdentityConfidence = true) }
                val resolved = exact ?: dao.mediaByBackupFingerprint(
                    record.displayName,
                    record.mimeType,
                    record.sizeBytes,
                ).filter { it.matchesBackupRecord(record, requireIdentityConfidence = false) }
                    .singleOrNull()
                resolved?.takeUnless(MediaItemEntity::isFavorite)?.toActionTarget()
            }.distinctBy(MediaActionTarget::key)
            if (targets.isNotEmpty()) withContext(Dispatchers.Main) { beginFavoriteImport(targets) }
        }
    }

    init {
        var knownExportStatuses = videoExportStore.jobs.value.associate { it.id to it.status }
        viewModelScope.launch {
            videoExportStore.jobs.collect { jobs ->
                jobs.forEach { job ->
                    if (job.status == VideoExportJobStatus.Completed &&
                        knownExportStatuses[job.id] != VideoExportJobStatus.Completed
                    ) {
                        mutableVideoExportCompleted.emit(job)
                        val editor = mutableVideoEditor.value
                        val external = mutableExternalMedia.value
                        if (external != null && editor?.exportJobId == job.id) {
                            val output = Uri.parse(job.outputUri)
                            if (external.editMode) {
                                mutableExternalSaved.emit(output)
                            } else {
                                mutableExternalMedia.value = null
                                mutableExternalPhotoState.value = null
                                mutableVideoEditor.value = null
                                publishedTimelineMedia(output, MediaKind.Video)?.let(::openMedia)
                                mutableEditorCopyOpened.emit(Unit)
                            }
                        }
                        viewModelScope.launch { refreshLibrary() }
                    }
                }
                knownExportStatuses = jobs.associate { it.id to it.status }
                val session = mutableVideoEditor.value ?: return@collect
                mutableVideoEditor.value = session.withVideoExportState(jobs)
                captureVideoEditorRecovery()
            }
        }
        if (mlScheduler.hasConsent(MlTaskType.FaceDetection)) monitorFaceProgress()
        if (mlScheduler.hasConsent(MlTaskType.PersonClustering)) monitorPeopleProgress()
        if (mlScheduler.hasConsent(MlTaskType.ImageLabels)) monitorPetProgress()
        viewModelScope.launch {
            val created = try {
                withContext(Dispatchers.IO) { createRuntime(application) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableEngineState.value = LibraryEngineState.Error
                pendingRestoredViewer = null
                mutableViewerSourceChecking.value = false
                pendingRestoredVideoEditor = null
                mutableVideoEditorOpening.value = false
                pendingRestoredGif = null
                mutableCreationGifRestoring.value = false
                pendingRestoredCollage = null
                mutableCreationCollageRestoring.value = false
                pendingRestoredVideo = null
                mutableMemoryVideoRestoring.value = false
                pendingRestoredManualMoment = null
                mutableManualMomentRestoring.value = false
                saveCreationState()
                return@launch
            }
            runtime.value = created
            mutablePetIdentityRepository.value = GalleryPetIdentityRepository(created.database,application.contentResolver)
            mutableLocalSharingController.value = GalleryLocalSharingWorker.controller(application,created.database)
            mutableLocalSharingController.value?.reconcile()
            mutablePlacesSource.value = GalleryPlacesSource(application,created.database)
            GalleryOfflinePlacesWorker.reconcile(application,offlinePlacesController)
            mutableRemoteBackupController.value = GalleryRemoteBackupWorker.controller(application, created.database)
            mutableRemoteBackupController.value?.reconcile()
            backupTaskController.reconcile()
            recoverIncompleteBackups()
            viewModelScope.launch(Dispatchers.IO) {
                created.database.momentDiscoveryDao().observeRevision().distinctUntilChanged()
                    .debounce(750L).collect {
                        // Database mutations also cover portable restores and local analysis;
                        // they need not wait for a later foreground MediaStore refresh.
                        try { created.moments.generateIfNeeded() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            // The durable checkpoint remains resumable; make the retry path visible.
                            mutableShareError.emit(getApplication<Application>().getString(R.string.memory_discovery_failed))
                        }
                    }
            }

            viewModelScope.launch(Dispatchers.IO) { runCatching { created.motionKeyFrames.pruneOrphans() } }
            viewModelScope.launch {
                thumbnailLoader.filterNotNull().flatMapLatest { it.memoryPressureGeneration }.collect { generation ->
                    if (generation > 0) {
                        adjacentPhotoJob?.cancel()
                        mutableAdjacentPhotoStates.value = emptyMap()
                    }
                }
            }
            viewModelScope.launch {
                created.motionKeyFrames.versions().distinctUntilChanged().collect {
                    val previous = created.thumbnails
                    created.thumbnails = created.thumbnailFactory()
                    thumbnailEpoch.value++
                    adjacentPhotoJob?.cancel()
                    mutableAdjacentPhotoStates.value = emptyMap()
                    mutableCurrentMedia.value?.takeIf { it.kind == MediaKind.Image }?.let { renderViewerMedia(it) }
                    previous.close()
                }
            }
            semanticModelManager = SemanticModelManager(application, created.database).also { manager ->
                manager.initializeEnabledDefault(
                    mutableLocalAnalysisOnboarding.value == LocalAnalysisOnboardingDecision.Accepted,
                )
                val semanticActive = mutableLocalAnalysisSwitches.value.isActive(LocalAnalysisFeature.Semantic)
                if (SemanticModelManager.isEnabled(application) != semanticActive) manager.setEnabled(semanticActive)
                semanticSearchEngine = SemanticSearchEngine(application, created.database, manager)
                viewModelScope.launch { manager.state.collect { mutableSemanticModels.value = it } }
                manager.ensureAutomaticDownload(
                    mutableLocalAnalysisOnboarding.value == LocalAnalysisOnboardingDecision.Accepted,
                )
            }
            created.monitor = MediaStoreChangeMonitor(application.contentResolver, viewModelScope) { batch ->
                batch.rowHints.forEach {
                    created.synchronizer.applyRowHint(it)
                    created.searchIndex.indexKey(it)
                }
                semanticModelManager?.scheduleActiveIndexUpdate()
                refreshLibrary(
                    indexedHintCount = batch.rowHints.size,
                    forceFullReconciliation = batch.requiresFullVolumeReconciliation,
                )
            }.also { it.start() }
            reconcilePendingManualMomentCreate(created)
            refreshLibrary(reconcileUnobservedChanges = true)
            restorePendingCreationVideo(created)
            restorePendingCreationCollage(created)
            restorePendingCreationGif(created)
            restorePendingManualMoment(created)
            restorePendingViewer(created)
            restorePendingVideoEditor(created)
            restorePendingExternalVideoEditor()
            if (mutableEngineState.value == LibraryEngineState.Ready) refreshSelectionCount()
            semanticModelManager?.scheduleActiveIndexUpdate()
        }
    }

    /** After a media permission prompt: refresh and reload the grids even if nothing changed. */
    fun onPermissionRequestResult() = onForeground(invalidateViews = true)

    fun onForeground(invalidateViews: Boolean = false) {
        mutablePlacesSource.value?.refreshPermission()
        ownSyncController.reconcile()
        mlScheduler.onAppForegrounded()
        semanticModelManager?.onAppForegrounded()
        if (mlScheduler.hasConsent(MlTaskType.FaceDetection)) monitorFaceProgress()
        if (mlScheduler.hasConsent(MlTaskType.PersonClustering)) monitorPeopleProgress()
        if (mlScheduler.hasConsent(MlTaskType.ImageLabels)) monitorPetProgress()
        val before = permissions.access.value.unredactedLocation
        val after = permissions.revalidate().unredactedLocation
        if (before && !after) viewModelScope.launch { runtime.value?.metadata?.onLocationPermissionRevoked() }
        viewModelScope.launch { refreshLibrary(invalidateViews = invalidateViews) }
        revalidateExternalGrant()
    }

    fun onBackground() {
        mlScheduler.onAppBackgrounded()
        semanticModelManager?.onAppBackgrounded()
        // WorkManager owns durable analysis. These jobs only refresh visible UI and would
        // otherwise poll SharedPreferences/Room while full-library work waits for power.
        faceProgressJob?.cancel()
        peopleProgressJob?.cancel()
        petProgressJob?.cancel()
    }

    fun setSearchQuery(value: String) {
        if (value == mutableSearch.value.query) return
        cancelSearchExecution()
        mutableSearch.value = mutableSearch.value.withEditedQuery(value)
    }

    fun search(value: String = mutableSearch.value.query) {
        val raw = value.trim()
        if (raw.isEmpty()) return
        val generation = cancelSearchExecution()
        mutableSearch.value = GallerySearchUiState(query = raw, loading = true, terminal = false)
        searchJob = viewModelScope.launch {
            try {
                (faceSearchHits(raw) ?: petSearchHits(raw))?.let { hits ->
                    if (isCurrentSearch(generation, raw)) {
                        mutableSearch.value = GallerySearchUiState(raw, hits, false, true, false)
                    }
                    return@launch
                }
                val cursor = AppSearchMediaSearchRepository(getApplication()).search(raw, 100)
                if (!isCurrentSearch(generation, raw)) {
                    cursor.close()
                    return@launch
                }
                searchCursor = cursor
                val page = cursor.nextPage()
                // Filter-only queries such as "favorites" have no text to embed; semantic hits
                // would add unrelated photos to an exact filter.
                var semanticUnavailable = false
                val semanticHits = if (SearchQueryParser().parse(raw).normalizedTerms.isEmpty()) emptyList()
                else try {
                    semanticSearchEngine?.search(raw).orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // Model, LiteRT or lease failures fall back to keyword results, visibly.
                    android.util.Log.w("GalleryViewModel", "Semantic search failed", failure)
                    semanticUnavailable = true
                    emptyList()
                }
                if (isCurrentSearch(generation, raw)) {
                    mutableSearch.value = GallerySearchUiState(
                        raw,
                        fuseSearchHits(page.hits, semanticHits),
                        false,
                        page.isTerminal,
                        false,
                        semanticUnavailable,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentSearch(generation, raw)) {
                    mutableSearch.value = GallerySearchUiState(raw, error = true)
                }
            }
        }
    }

    private fun fuseSearchHits(keyword: List<MediaSearchHit>, semantic: List<MediaSearchHit>): List<MediaSearchHit> {
        if (semantic.isEmpty()) return keyword
        fun MediaSearchHit.encodedKey() = "${key.volumeName}:${key.mediaStoreId}"
        val byKey = (keyword + semantic).associateBy { it.encodedKey() }
        return ReciprocalRankFusion.fuse(
            keyword.map { RankedSemanticKey(it.encodedKey(), it.debug.rankingSignal) },
            semantic.map { RankedSemanticKey(it.encodedKey(), it.debug.rankingSignal) },
        ).mapNotNull { fused ->
            byKey[fused.key]?.let { hit ->
                hit.copy(
                    debug = SearchRankingDebug(
                        mutableSearch.value.query,
                        "hybrid-rrf",
                        fused.score,
                        hit.debug.matchedProperties,
                    ),
                )
            }
        }
    }

    fun setSemanticSearchEnabled(enabled: Boolean) = updateLocalAnalysisSwitches {
        it.withFeature(LocalAnalysisFeature.Semantic, enabled)
    }

    private fun runSemanticSearch(enabled: Boolean) {
        semanticModelManager?.setEnabled(enabled)
        // Turning semantic search on is the user's consent to fetch the recommended model.
        if (enabled) semanticModelManager?.ensureAutomaticDownload(localAnalysisAccepted = true)
    }
    fun downloadSemanticModel(modelId: String) {
        semanticModelManager?.download(modelId, userInitiated = true)
    }
    fun cancelSemanticModelDownload(modelId: String) { semanticModelManager?.cancelDownload(modelId) }
    fun activateSemanticModel(modelId: String, allowUnsupported: Boolean) {
        semanticModelManager?.activate(modelId, allowUnsupported)
    }
    fun deleteSemanticModel(modelId: String) { semanticModelManager?.deleteModel(modelId) }
    fun deleteAllSemanticModels() { semanticModelManager?.deleteAllModels() }
    fun useAutomaticSemanticModel() { semanticModelManager?.useAutomaticSelection() }

    private fun cancelSearchExecution(): Long {
        searchJob?.cancel()
        searchJob = null
        searchCursor?.close()
        searchCursor = null
        return ++searchGeneration
    }

    private fun isCurrentSearch(generation: Long, raw: String): Boolean =
        searchGeneration == generation && mutableSearch.value.query.trim() == raw

    private suspend fun faceSearchHits(raw: String): List<MediaSearchHit>? {
        val concept = when (SearchVocabulary.resolve(raw)) {
            SearchConcept.Me -> SearchConcept.Me
            SearchConcept.People -> SearchConcept.People
            else -> return null
        }
        val repository = runtime.value?.database?.let(::PeopleRepository) ?: return emptyList()
        val rows = if (concept == SearchConcept.Me) {
            repository.meMatches().map { it.media to it.match.similarity.toDouble() }
        } else {
            repository.people().first().flatMap { person ->
                repository.members(person.cluster.clusterId).map {
                    it.media to it.membership.similarity.toDouble()
                }
            }
        }
        return rows
            .distinctBy { (media) -> MediaKey(media.volumeName, media.mediaStoreId) }
            .sortedByDescending { (media) -> media.timelineSortMillis }
            .take(200)
            .map { (media, similarity) ->
                MediaSearchHit(
                    key = MediaKey(media.volumeName, media.mediaStoreId),
                    kind = if (media.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaKind.Video else MediaKind.Image,
                    displayName = media.displayName,
                    timelineSortMillis = media.timelineSortMillis,
                    generationModified = media.generationModified,
                    favorite = media.isFavorite,
                    debug = SearchRankingDebug(
                        raw,
                        "face-${concept.name.lowercase(java.util.Locale.ROOT)}",
                        similarity,
                        listOf("faceEmbedding"),
                    ),
                    durationMillis = media.durationMillis,
                    width = media.width,
                    height = media.height,
                )
            }
    }

    /**
     * Dog and cat searches read the same Room labels as the pet collection counts, so the
     * Collections tile and Search always agree. Null falls back to keyword search.
     */
    private suspend fun petSearchHits(raw: String): List<MediaSearchHit>? {
        val type = when (SearchVocabulary.resolve(raw)) {
            SearchConcept.Dog -> com.librestatic.lightforge.core.ml.PetType.Dog
            SearchConcept.Cat -> com.librestatic.lightforge.core.ml.PetType.Cat
            else -> return null
        }
        val database = runtime.value?.database ?: return null
        val media = com.librestatic.lightforge.core.ml.PetCollectionRepository(database).media(type, 200)
        if (media.isEmpty()) return null
        return media.map { item ->
            MediaSearchHit(
                key = MediaKey(item.volumeName, item.mediaStoreId),
                kind = if (item.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaKind.Video else MediaKind.Image,
                displayName = item.displayName,
                timelineSortMillis = item.timelineSortMillis,
                generationModified = item.generationModified,
                favorite = item.isFavorite,
                debug = SearchRankingDebug(raw, "pet-${type.canonicalLabel}", 1.0, listOf("canonicalLabels")),
                durationMillis = item.durationMillis,
                width = item.width,
                height = item.height,
            )
        }
    }

    fun loadMoreSearch() {
        val cursor = searchCursor ?: return
        if (mutableSearch.value.loading || mutableSearch.value.terminal) return
        val generation = searchGeneration
        val raw = mutableSearch.value.query.trim()
        mutableSearch.value = mutableSearch.value.copy(loading = true)
        searchJob = viewModelScope.launch {
            runCatching { cursor.nextPage() }.onSuccess { page ->
                if (isCurrentSearch(generation, raw) && searchCursor === cursor) {
                    mutableSearch.value = mutableSearch.value.copy(
                        hits = (mutableSearch.value.hits + page.hits).distinctBy { hit ->
                            "${hit.key.volumeName}:${hit.key.mediaStoreId}"
                        },
                        loading = false,
                        terminal = page.isTerminal,
                    )
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (isCurrentSearch(generation, raw)) {
                    mutableSearch.value = mutableSearch.value.copy(loading = false, error = true)
                }
            }
        }
    }

    fun openSearchHit(hit: MediaSearchHit) {
        viewModelScope.launch {
            val row = runtime.value?.database?.libraryDao()?.media(hit.key.volumeName, hit.key.mediaStoreId) ?: return@launch
            openMedia(
                TimelineMedia(
                    hit.key, hit.kind, row.generationModified, row.timelineSortMillis, row.width, row.height,
                    row.durationMillis, row.dateExpiresSeconds?.times(1_000), row.isFavorite, row.isTrashed,
                ),
                MediaQuery(scope = MediaQuery.Scope.Search(mutableSearch.value.query.trim().lowercase())),
            )
        }
    }

    fun acceptLocalAnalysisDefaults() {
        localAnalysisOnboardingStore.setDecision(LocalAnalysisOnboardingDecision.Accepted)
        mutableLocalAnalysisOnboarding.value = LocalAnalysisOnboardingDecision.Accepted
        updateLocalAnalysisSwitches(fullLibrary = true) { LocalAnalysisSwitches.AllOn }
    }

    fun declineLocalAnalysisDefaults() {
        localAnalysisOnboardingStore.setDecision(LocalAnalysisOnboardingDecision.Declined)
        mutableLocalAnalysisOnboarding.value = LocalAnalysisOnboardingDecision.Declined
        updateLocalAnalysisSwitches { LocalAnalysisSwitches.AllOff }
        viewModelScope.launch {
            (localAnalysisTasks + cleanupAnalysisTasks).reversed().forEach { mlScheduler.deleteDerivedData(it) }
            refreshLocalAnalysisControls()
        }
    }

    /** "Use local analysis": off pauses every child, on restores each child's own last choice. */
    fun setAllLocalAnalysisEnabled(enabled: Boolean) =
        updateLocalAnalysisSwitches(fullLibrary = true) { it.withMaster(enabled) }

    fun setPeopleAnalysisEnabled(enabled: Boolean) = updateLocalAnalysisSwitches {
        it.withFeature(LocalAnalysisFeature.People, enabled)
    }

    fun setContentAnalysisEnabled(enabled: Boolean) = updateLocalAnalysisSwitches {
        it.withFeature(LocalAnalysisFeature.Content, enabled)
    }

    fun setCleanupAnalysisEnabled(enabled: Boolean) = updateLocalAnalysisSwitches {
        it.withFeature(LocalAnalysisFeature.Cleanup, enabled)
    }

    /**
     * Persists the new switch state and starts or stops only the features whose effective state
     * changed. [fullLibrary] keeps the master switch's historical full-library pass.
     */
    private fun updateLocalAnalysisSwitches(
        fullLibrary: Boolean = false,
        transform: (LocalAnalysisSwitches) -> LocalAnalysisSwitches,
    ) {
        val previous = mutableLocalAnalysisSwitches.value
        val next = transform(previous)
        localAnalysisSwitchStore.save(next)
        mutableLocalAnalysisSwitches.value = next
        val changes = previous.changedTo(next)
        changes.forEach { (feature, active) ->
            when (feature) {
                LocalAnalysisFeature.People -> runPeopleAnalysis(active)
                LocalAnalysisFeature.Content -> runContentAnalysis(active)
                LocalAnalysisFeature.Cleanup -> runCleanupAnalysis(active)
                LocalAnalysisFeature.Pets -> if (active) runPetCollections() else stopPetCollections()
                LocalAnalysisFeature.Semantic -> runSemanticSearch(active)
            }
        }
        // Pet collections also need image labels; stop them once neither feature wants them.
        if (!next.isActive(LocalAnalysisFeature.Content) && !next.isActive(LocalAnalysisFeature.Pets)) {
            contentAnalysisTasks.forEach { mlScheduler.setConsent(it, false) }
        }
        if (fullLibrary && changes.any { it.value }) viewModelScope.launch {
            if (changes[LocalAnalysisFeature.People] == true) mlScheduler.restart(MlTaskType.FaceDetection, MlRunMode.FullLibrary)
            if (changes[LocalAnalysisFeature.Content] == true) {
                contentAnalysisTasks.forEach { mlScheduler.restart(it, MlRunMode.FullLibrary) }
            }
            refreshLocalAnalysisControls()
        }
        refreshLocalAnalysisControls()
    }

    private fun runPeopleAnalysis(enabled: Boolean) {
        if (enabled) {
            peopleAnalysisTasks.forEach {
                mlScheduler.grantConsent(it)
                mlScheduler.unpause(it)
            }
            enqueueNextPeopleStage()
            monitorFaceProgress()
            monitorPeopleProgress()
        } else {
            peopleAnalysisTasks.forEach { mlScheduler.setConsent(it, false) }
            faceProgressJob?.cancel()
            peopleProgressJob?.cancel()
            mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
            mutablePeopleAnalysis.value = peopleControlState()
        }
    }

    private fun runContentAnalysis(enabled: Boolean) {
        if (enabled) {
            contentAnalysisTasks.forEach {
                mlScheduler.grantConsent(it)
                mlScheduler.unpause(it)
                mlScheduler.enqueue(it, MlRunMode.Recent)
            }
            mutableDetectedContentEnabled.value = true
            monitorPetProgress()
        } else {
            contentAnalysisTasks.forEach { mlScheduler.setConsent(it, false) }
            petProgressJob?.cancel()
            mutableDetectedContentEnabled.value = false
            mutablePetAnalysis.value = mlScheduler.controlState(MlTaskType.ImageLabels)
        }
    }

    fun deleteAllLocalAnalysisData() {
        updateLocalAnalysisSwitches { it.withMaster(false) }
        viewModelScope.launch {
            (localAnalysisTasks + cleanupAnalysisTasks).reversed().forEach { mlScheduler.deleteDerivedData(it) }
            refreshLocalAnalysisControls()
        }
    }

    private fun refreshLocalAnalysisControls() {
        mutableDetectedContentEnabled.value = contentAnalysisTasks.all(mlScheduler::hasConsent)
        mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
        mutablePeopleAnalysis.value = peopleControlState()
        mutablePetAnalysis.value = mlScheduler.controlState(MlTaskType.ImageLabels)
    }

    fun enableFaceDetection() {
        mlScheduler.grantConsent(MlTaskType.FaceDetection)
        mlScheduler.enqueue(MlTaskType.FaceDetection, MlRunMode.Recent)
        monitorFaceProgress()
    }

    fun pauseFaceDetection() {
        mlScheduler.pause(MlTaskType.FaceDetection)
        mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
        faceProgressJob?.cancel()
    }

    fun resumeFaceDetection() {
        mlScheduler.resume(MlTaskType.FaceDetection, MlRunMode.Recent)
        monitorFaceProgress()
    }

    fun analyzeAllFaces() {
        viewModelScope.launch {
            mlScheduler.restart(MlTaskType.FaceDetection, MlRunMode.FullLibrary)
            monitorFaceProgress()
        }
    }

    fun deleteFaceDetectionData() {
        viewModelScope.launch {
            mlScheduler.deleteDerivedData(MlTaskType.FaceDetection)
            faceProgressJob?.cancel()
            peopleProgressJob?.cancel()
            mutableSelectedPerson.value = null
            mutableSelectedPersonMembers.value = emptyList()
            mutableMe.value = null
            mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
            mutablePeopleAnalysis.value = peopleControlState()
        }
    }

    fun enablePeopleRecognition() = setPeopleAnalysisEnabled(true)

    fun pausePeopleRecognition() {
        listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach(mlScheduler::pause)
        mutablePeopleAnalysis.value = peopleControlState()
        peopleProgressJob?.cancel()
    }

    fun resumePeopleRecognition() {
        listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach {
            mlScheduler.grantConsent(it)
            mlScheduler.unpause(it)
        }
        enqueueNextPeopleStage()
        mutablePeopleAnalysis.value = peopleControlState()
        monitorFaceProgress()
        monitorPeopleProgress()
    }

    fun analyzeAllPeople() {
        viewModelScope.launch {
            listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach(mlScheduler::grantConsent)
            // All engines query missing/stale rows. Keep valid detections, embeddings and
            // memberships instead of rebuilding the complete people database.
            mlScheduler.restart(MlTaskType.FaceDetection, MlRunMode.FullLibrary)
            monitorFaceProgress()
            monitorPeopleProgress()
        }
    }

    fun deletePeopleRecognitionData() {
        viewModelScope.launch {
            listOf(MlTaskType.PersonClustering, MlTaskType.FaceEmbeddings, MlTaskType.FaceDetection).forEach {
                mlScheduler.deleteDerivedData(it)
            }
            peopleRefreshGeneration.value++
            peopleProgressJob?.cancel()
            faceProgressJob?.cancel()
            mutableSelectedPerson.value = null
            mutableSelectedPersonMembers.value = emptyList()
            mutableMe.value = null
            mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
            mutablePeopleAnalysis.value = peopleControlState()
        }
    }

    fun openPerson(clusterId: String) {
        val selected = peopleSummaries.value.firstOrNull { it.clusterId == clusterId } ?: return
        mutableSelectedPerson.value = selected
        personMemberLimit = PersonMemberPageSize
        loadPersonMembers(clusterId)
    }

    /** The person page is a lazy grid; it asks for the next page when it reaches the end. */
    fun loadMorePersonMembers() {
        val clusterId = mutableSelectedPerson.value?.clusterId ?: return
        if (mutableSelectedPersonMembers.value.size < personMemberLimit) return
        personMemberLimit += PersonMemberPageSize
        loadPersonMembers(clusterId)
    }

    private fun loadPersonMembers(clusterId: String) {
        viewModelScope.launch {
            val repo = runtime.value?.database?.let(::PeopleRepository) ?: return@launch
            val members = repo.members(clusterId, personMemberLimit).map {
                PersonMemberCardUi(MediaKey(it.media.volumeName, it.media.mediaStoreId), it.membership.faceOrdinal)
            }
            if (mutableSelectedPerson.value?.clusterId == clusterId) mutableSelectedPersonMembers.value = members
        }
    }

    fun openMediaByKey(key: MediaKey, sourceQuery: MediaQuery = MediaQuery()) {
        viewModelScope.launch {
            val row = runtime.value?.database?.libraryDao()?.media(key.volumeName, key.mediaStoreId) ?: return@launch
            if (!row.isAccessible || row.isTrashed) return@launch
            openMedia(TimelineMedia(key, if (row.mediaType == 3) MediaKind.Video else MediaKind.Image,
                row.generationModified, row.timelineSortMillis, row.width, row.height, row.durationMillis,
                row.dateExpiresSeconds?.times(1000), row.isFavorite, row.isTrashed), sourceQuery)
        }
    }

    fun mergePersonInto(sourceClusterId: String, targetClusterId: String) {
        viewModelScope.launch {
            val repo = runtime.value?.database?.let(::PeopleRepository) ?: return@launch
            try {
                repo.merge(targetClusterId, sourceClusterId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A concurrent clustering pass can remove either person; keep the page as is.
                android.util.Log.w("GalleryViewModel", "Merging people failed", failure)
                return@launch
            }
            peopleRefreshGeneration.value++
            // The merged person no longer exists; show the person it joined.
            peopleSummaries.first { people -> people.none { it.clusterId == sourceClusterId } }
            openPerson(targetClusterId)
        }
    }

    fun splitPersonFaces(clusterId: String, faces: List<PersonMemberCardUi>) {
        viewModelScope.launch {
            val repo = runtime.value?.database?.let(::PeopleRepository) ?: return@launch
            try {
                repo.split(clusterId, faces.map { FaceIdentityKey(it.key.volumeName, it.key.mediaStoreId, it.faceOrdinal) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("GalleryViewModel", "Splitting a person failed", failure)
                return@launch
            }
            peopleRefreshGeneration.value++
            loadPersonMembers(clusterId)
        }
    }

    private fun runCleanupAnalysis(enabled: Boolean) {
        cleanupAnalysisTasks.forEach {
            if (enabled) {
                mlScheduler.grantConsent(it)
                mlScheduler.unpause(it)
                mlScheduler.enqueue(it, MlRunMode.FullLibrary)
            } else mlScheduler.setConsent(it, false)
        }
        mutableCleanupAnalysisEnabled.value = enabled
    }

    private suspend fun loadCleanup(active: GalleryRuntime, summary: CleanupSummary, enabled: Boolean): CleanupUiState =
        withContext(Dispatchers.IO) {
            val repository = CleanupRepository(active.database)
            val groups = ExactDuplicateRepository(active.database).groups(null, CleanupGroupLimit)
            cleanupGroups = groups.associateBy { it.id }
            val largeVideos = repository.largeVideosQuery()
            val blurry = repository.blurryCandidatesQuery()
            val screenshots = repository.screenshotsQuery()
            CleanupUiState(
                loading = false,
                analysisEnabled = enabled,
                duplicateGroups = groups.map { group ->
                    CleanupDuplicateGroupUi(
                        id = group.id,
                        members = active.selectionTargets.page(repository.exactDuplicateGroupQuery(group), null, CleanupPreviewLimit)
                            .map { it.key },
                        keep = group.recommendedKeep,
                        memberCount = group.memberCount,
                        recoverableBytes = group.recoverableBytes,
                    )
                },
                duplicateGroupCount = summary.exactGroupCount,
                duplicateBytes = summary.exactRecoverableBytes,
                largeVideos = active.selectionTargets.page(largeVideos, null, CleanupPreviewLimit).map { it.key },
                largeVideoCount = active.selectionTargets.count(largeVideos),
                largeVideoBytes = summary.largeVideoBytes,
                screenshots = active.selectionTargets.page(screenshots, null, CleanupPreviewLimit).map { it.key },
                screenshotCount = active.selectionTargets.count(screenshots),
                blurry = active.selectionTargets.page(blurry, null, CleanupPreviewLimit).map { it.key },
                blurryCount = active.selectionTargets.count(blurry),
            )
        }

    /** Every copy except the recommended one goes through the shared select-all trash pipeline. */
    fun trashDuplicateCopies(groupId: String) {
        val group = cleanupGroups[groupId] ?: return
        val query = runtime.value?.database?.let(::CleanupRepository)?.exactDuplicateGroupQuery(group) ?: return
        beginQueryAction(SelectionSpec.queryAll(query, listOf(group.recommendedKeep)), MediaAction.Trash(true))
    }

    fun trashCleanupSection(section: CleanupSection) {
        val repository = runtime.value?.database?.let(::CleanupRepository) ?: return
        beginQueryAction(SelectionSpec.queryAll(cleanupSectionQuery(repository, section)), MediaAction.Trash(true))
    }

    private fun cleanupSectionQuery(repository: CleanupRepository, section: CleanupSection) = when (section) {
        CleanupSection.LargeVideos -> repository.largeVideosQuery()
        CleanupSection.Screenshots -> repository.screenshotsQuery()
        CleanupSection.Blurry -> repository.blurryCandidatesQuery()
    }

    /** Opens a cleanup item so the viewer pages through the list it was opened from. */
    fun openCleanupMedia(key: MediaKey, list: CleanupList) {
        val repository = runtime.value?.database?.let(::CleanupRepository) ?: return
        val query = when (list) {
            is CleanupList.DuplicateGroup -> cleanupGroups[list.groupId]?.let(repository::exactDuplicateGroupQuery)
            is CleanupList.Section -> cleanupSectionQuery(repository, list.section)
        } ?: return openMediaByKey(key)
        openMediaByKey(key, query)
    }

    fun unhidePerson(clusterId: String) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.unhide(clusterId)
            peopleRefreshGeneration.value++
        }
    }

    fun closePerson() {
        mutableSelectedPerson.value = null
        mutableSelectedPersonMembers.value = emptyList()
    }

    fun renamePerson(clusterId: String, name: String?) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.rename(clusterId, name)
            peopleRefreshGeneration.value++
            mutableSelectedPerson.value = mutableSelectedPerson.value?.takeIf { it.clusterId == clusterId }?.copy(displayName = name)
        }
    }

    fun hidePerson(clusterId: String) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.hide(clusterId)
            peopleRefreshGeneration.value++
            mutableSelectedPerson.value = null
            mutableSelectedPersonMembers.value = emptyList()
        }
    }

    fun setSelectedPersonAsMe(clusterId: String) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.setAsMeFromCluster(clusterId)
            refreshMeState()
        }
    }

    fun resetMe() {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.resetMe()
            mutableMe.value = null
        }
    }

    fun enablePetCollections() = updateLocalAnalysisSwitches { it.withFeature(LocalAnalysisFeature.Pets, true) }

    fun disablePetCollections() = updateLocalAnalysisSwitches { it.withFeature(LocalAnalysisFeature.Pets, false) }

    private fun runPetCollections() {
        petSettings.setEnabled(true)
        mutablePetCollectionsEnabled.value = true
        mlScheduler.grantConsent(MlTaskType.ImageLabels)
        mlScheduler.unpause(MlTaskType.ImageLabels)
        // Enabling a library collection is an explicit request to classify the whole
        // library. A recent-only pass silently misses older pet photos.
        viewModelScope.launch {
            mlScheduler.restart(MlTaskType.ImageLabels, MlRunMode.FullLibrary)
            mutablePetAnalysis.value = mlScheduler.controlState(MlTaskType.ImageLabels)
            monitorPetProgress()
        }
        mutableDetectedContentEnabled.value = true
    }

    private fun stopPetCollections() {
        petSettings.setEnabled(false)
        mutablePetCollectionsEnabled.value = false
    }

    fun suppressPetType(type: PetType) {
        viewModelScope.launch {
            runtime.value?.let { DetectedContentRepository(it.database).suppressLabel(type.canonicalLabel) }
        }
    }

    fun restorePetType(type: PetType) {
        viewModelScope.launch {
            runtime.value?.let { DetectedContentRepository(it.database).unsuppressLabel(type.canonicalLabel) }
            if (mlScheduler.hasConsent(MlTaskType.ImageLabels)) {
                mlScheduler.enqueue(MlTaskType.ImageLabels, MlRunMode.Recent)
            }
        }
    }

    private fun monitorPetProgress() {
        petProgressJob?.cancel()
        petProgressJob = viewModelScope.launch {
            while (isActive) {
                val current = mlScheduler.controlState(MlTaskType.ImageLabels)
                mutablePetAnalysis.value = current
                if (!current.requested &&
                    current.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete
                ) {
                    // The summary joins label and media tables. Re-subscribe after the final
                    // chunk so every device observes the complete counts immediately.
                    petRefreshGeneration.value++
                    break
                }
                // Nothing requested and nothing running means the worker gave up (or never ran).
                if (!current.consentGranted || current.paused || !current.isPending()) break
                delay(500)
            }
        }
    }

    private fun monitorFaceProgress() {
        faceProgressJob?.cancel()
        faceProgressJob = viewModelScope.launch {
            while (isActive) {
                val current = mlScheduler.controlState(MlTaskType.FaceDetection)
                mutableFaceAnalysis.value = current
                if (!current.consentGranted || current.paused ||
                    current.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete ||
                    !current.isPending()
                ) break
                delay(500)
            }
        }
    }

    private fun personCard(row: com.librestatic.lightforge.core.database.PersonClusterSummaryRow) = PersonCardUi(
        clusterId = row.cluster.clusterId,
        displayName = row.cluster.displayName,
        memberCount = row.visibleMemberCount,
        coverKey = row.coverVolumeName?.let { volume ->
            row.coverMediaStoreId?.let { id -> MediaKey(volume, id) }
        },
    )

    private fun MlControlState.isPending(): Boolean =
        requested || status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running

    private fun peopleControlState(): MlControlState {
        val tasks = listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering)
        val states = tasks.map(mlScheduler::controlState)
        val statuses = states.mapNotNull { it.status }
        val status = when {
            statuses.any { it == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running } -> com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running
            states.any { it.paused } -> com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Paused
            statuses.size == tasks.size && statuses.all { it == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete } -> com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete
            statuses.isNotEmpty() || states.any { it.requested } -> com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Ready
            else -> null
        }
        val active = states.firstOrNull { it.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running }
            ?: states.firstOrNull { it.requested }
            ?: states.firstOrNull { it.status != com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete }
        return MlControlState(
            consentGranted = states.all { it.consentGranted },
            paused = states.any { it.paused },
            completedItems = states.sumOf { it.completedItems },
            status = status,
            requested = states.any { it.requested },
            runMode = active?.runMode,
            activeTask = active?.activeTask,
            waitReason = states.firstNotNullOfOrNull { it.waitReason },
        )
    }

    private fun nextPeopleTask(): MlTaskType? {
        val face = mlScheduler.checkpoint(MlTaskType.FaceDetection)
        val embeddings = mlScheduler.checkpoint(MlTaskType.FaceEmbeddings)
        val clustering = mlScheduler.checkpoint(MlTaskType.PersonClustering)
        return when {
            face?.status != com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.FaceDetection
            embeddings?.status != com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.FaceEmbeddings
            clustering?.status != com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.PersonClustering
            else -> null
        }
    }

    /**
     * Starts the first unfinished People stage. Later stages are chained by MlChunkWorker, so
     * this is only needed for explicit user actions and to recover a chain stopped mid-way.
     */
    private fun enqueueNextPeopleStage() {
        val next = nextPeopleTask() ?: return
        if (!mlScheduler.hasConsent(next)) return
        // Face detection may be a bounded recent update. Embedding every detected face and
        // rebuilding clusters are library-wide passes and must retain the charging gate.
        mlScheduler.enqueue(next, if (next == MlTaskType.FaceDetection) MlRunMode.Recent else MlRunMode.FullLibrary)
    }

    /** UI observer only: MlChunkWorker schedules each People stage after the previous one. */
    private fun monitorPeopleProgress() {
        peopleProgressJob?.cancel()
        peopleProgressJob = viewModelScope.launch {
            val stages = listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering)
            // A chain stopped between stages (for example before this fix) is picked up once here.
            if (nextPeopleTask() != MlTaskType.FaceDetection && !peopleControlState().isPending()) enqueueNextPeopleStage()
            var idlePolls = 0
            while (isActive) {
                val next = nextPeopleTask()
                val current = peopleControlState()
                mutablePeopleAnalysis.value = current
                refreshMeState()
                if (next == null) {
                    // Person clustering writes several related tables in one background pass.
                    // Re-subscribing guarantees the UI observes the completed projection even
                    // on devices where Room's multi-table invalidation arrives late.
                    peopleRefreshGeneration.value++
                    break
                }
                if (stages.any { mlScheduler.controlState(it).paused }) break
                // The worker hands over between stages; two idle polls in a row mean the chain
                // stopped (gave up or lost consent) rather than being between stages.
                idlePolls = if (current.isPending()) 0 else idlePolls + 1
                if (idlePolls >= 2) break
                delay(750)
            }
        }
    }

    private suspend fun refreshMeState() {
        val repo = runtime.value?.database?.let(::PeopleRepository) ?: return
        val state = repo.meState() ?: run { mutableMe.value = null; return }
        val matches = repo.meMatches().map {
            PersonMemberCardUi(MediaKey(it.media.volumeName, it.media.mediaStoreId), it.match.faceOrdinal)
        }
        mutableMe.value = LocalMeUiState(state.referenceCount, state.isReady, state.matchCount, matches)
    }

    /** Benchmark-build hook that runs real bundled analysis against the indexed physical library. */
    internal fun startBenchmarkMlLoad() {
        if (!BuildConfig.BUILD_TYPE.contains("benchmark", ignoreCase = true)) return
        benchmarkMlJob?.cancel()
        benchmarkMlJob = viewModelScope.launch {
            mutableEngineState.first { it == LibraryEngineState.Ready }
            val tasks = listOf(MlTaskType.ImageLabels, MlTaskType.Ocr, MlTaskType.Similarity)
            tasks.forEach { mlScheduler.deleteDerivedData(it) }
            tasks.forEach {
                mlScheduler.grantConsent(it)
                check(mlScheduler.enqueue(it, MlRunMode.Recent))
            }
            var observedRunning = false
            while (isActive) {
                val checkpoints = tasks.mapNotNull(mlScheduler::checkpoint)
                observedRunning = observedRunning || checkpoints.any {
                    it.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Running
                }
                val complete = checkpoints.size == tasks.size && checkpoints.all {
                        it.status == com.librestatic.lightforge.core.ml.MlCheckpoint.Status.Complete
                    }
                mutableBenchmarkMlRunning.value = observedRunning && !complete
                if (complete) break
                delay(50)
            }
            mutableBenchmarkMlRunning.value = false
        }
    }

    fun openExternal(intent: Intent): Boolean {
        val action = intent.action
        val uri = intent.data ?: return false
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_EDIT) return false
        // Activity recreation replays its original Intent before runtime initialization.
        // Keep the restored draft authoritative until an explicit checked recovery.
        if (externalVideoDraftIsRestoring(pendingRestoredExternalVideoEditor ?: externalVideoEditorSnapshot,
                mutableVideoEditorSessionId.value, mutableVideoEditorOpening.value, uri.toString())) return true
        // A repeated edit request must not discard a draft whose grant has expired.
        val currentEditor = mutableVideoEditor.value
        if (currentEditor?.source?.libraryMedia == null && currentEditor?.source?.uri == uri &&
            mutableExternalMedia.value?.uri == uri) {
            revalidateExternalGrant()
            return true
        }
        val mime = runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull() ?: intent.type
        val kind = when {
            mime?.startsWith("image/") == true -> MediaKind.Image
            mime?.startsWith("video/") == true -> MediaKind.Video
            else -> return false
        }
        val available = canOpen(uri)
        closePhotoEditor()
        closeVideoEditor()
        val generation = ++externalOpenGeneration
        val request = ExternalMedia(
            uri = uri,
            mimeType = mime,
            kind = kind,
            editMode = action == Intent.ACTION_EDIT,
            available = available,
        )
        mutableExternalMedia.value = request
        if (available && kind == MediaKind.Image) loadExternalPhoto(uri)
        if (available) viewModelScope.launch(Dispatchers.IO) {
            val probed = probeExternalMedia(request)
            withContext(Dispatchers.Main) {
                if (externalOpenGeneration == generation && mutableExternalMedia.value?.uri == uri) {
                    mutableExternalMedia.value = probed
                    // Never let a stale successful metadata probe undo a foreground revocation.
                    revalidateExternalGrant()
                }
            }
        }
        return true
    }

    fun openExternalEditor() {
        if (externalVideoDraftIsRestoring(pendingRestoredExternalVideoEditor ?: externalVideoEditorSnapshot,
                mutableVideoEditorSessionId.value, mutableVideoEditorOpening.value)) return
        val external = mutableExternalMedia.value?.takeIf { it.available && it.metadataReady } ?: return
        val stableId = external.uri.toString()
        if (mutablePhotoEditor.value?.source?.uriString == stableId ||
            mutableVideoEditor.value?.source?.uriString == stableId
        ) return
        val source = EditorMediaSource(
            uriString = external.uri.toString(),
            kind = external.kind,
            mimeType = external.mimeType,
            displayName = external.displayName,
            width = external.width,
            height = external.height,
            durationMillis = external.durationMillis,
        )
        if (external.kind == MediaKind.Image) openPhotoEditor(source) else openVideoEditor(source)
    }

    fun clearExternal() {
        externalOpenGeneration++
        closePhotoEditor()
        closeVideoEditor()
        mutableExternalMedia.value = null
        mutableExternalPhotoState.value = null
    }

    fun saveExternalCopy() {
        val external = mutableExternalMedia.value?.takeIf { it.editMode && it.available } ?: return
        viewModelScope.launch {
            val concreteMime = external.mimeType?.takeUnless { '*' in it }
                ?: if (external.kind == MediaKind.Image) "image/jpeg" else "video/mp4"
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(concreteMime)
                ?: if (external.kind == MediaKind.Image) "jpg" else "mp4"
            val name = "Lightforge-copy-${System.currentTimeMillis()}.$extension"
            val copy = PendingMediaWriter(getApplication<Application>().contentResolver).copy(
                external.uri,
                MediaWriteSpec(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    external.kind,
                    name,
                    concreteMime,
                    if (external.kind == MediaKind.Image) "Pictures/Lightforge" else "Movies/Lightforge",
                ),
            )
            mutableExternalSaved.emit(copy.uri)
        }
    }

    fun selectAlbum(key: AlbumKey, filter: AlbumMediaFilter, sort: AlbumSort) {
        albumRequest.value = AlbumRequest(key, filter, sort)
    }

    fun selectAlbum(album: AlbumSummary, filter: AlbumMediaFilter, sort: AlbumSort) {
        mutableSelectedAlbum.value = album
        selectAlbum(album.key, filter, sort)
    }

    fun toggleSelection(media: TimelineMedia) {
        setMediaSelected(
            media = media,
            selected = !SelectionReducer.isSelected(mutableSelection.value, media.key),
        )
    }

    /** Idempotent selection mutation used by drag gestures and accessibility actions. */
    fun setMediaSelected(media: TimelineMedia, selected: Boolean) {
        if (media.stack != null && selected) {
            selectTimelineStack(media)
            return
        }
        val before = mutableSelection.value
        val wasSelected = SelectionReducer.isSelected(before, media.key)
        if (wasSelected == selected) return
        val updated = SelectionReducer.setSelected(before, media.key, selected)
        selectionRevision++
        mutableSelection.value = updated
        when (updated) {
            is SelectionSpec.Explicit -> {
                if (SelectionReducer.isSelected(updated, media.key)) {
                    explicitTargets[media.key] = MediaActionTarget(media.key, media.kind)
                } else explicitTargets.remove(media.key)
                mutableSelectionCount.value = explicitTargets.size.toLong()
            }
            is SelectionSpec.QueryAll -> {
                explicitTargets.clear()
                mutableSelectionCount.value = (
                    mutableSelectionCount.value + if (selected) 1 else -1
                ).coerceAtLeast(0)
            }
        }
        saveCreationState()
        if (updated is SelectionSpec.QueryAll) refreshSelectionCount()
    }

    /** A late count cannot revive Clear or overwrite a new selection of the same query (ABA). */
    private fun refreshSelectionCount() {
        val selected = mutableSelection.value as? SelectionSpec.QueryAll ?: return
        val revision = selectionRevision
        val active = runtime.value ?: return
        viewModelScope.launch {
            try {
                val count = active.selectionTargets.count(selected.querySnapshot)
                if (selectionRevision == revision && mutableSelection.value == selected && runtime.value === active) {
                    mutableSelectionCount.value = SelectionReducer.count(selected, count)
                    saveCreationState()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Retain query; unavailable storage must not crash task restoration. */ }
        }
    }

    private var timelineStackSelectionJob: Job? = null

    private fun selectTimelineStack(media: TimelineMedia) {
        val stack = media.stack ?: return
        val active = runtime.value ?: return
        val before = mutableSelection.value
        val library = gallerySettings.value.library
        timelineStackSelectionJob?.cancel()
        timelineStackSelectionJob = viewModelScope.launch {
            try {
                val members = active.timeline.selectStack(stack, library)
                // Never resurrect selection after Clear, a different selection, or changed filters.
                if (mutableSelection.value != before || gallerySettings.value.library != library) return@launch
                members.forEach { setMediaSelected(it, true) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableShareError.emit(getApplication<Application>().getString(
                    com.librestatic.lightforge.feature.photos.R.string.timeline_stack_changed))
            }
        }
    }

    fun clearSelection() {
        timelineStackSelectionJob?.cancel()
        selectionRevision++
        mutableSelection.value = SelectionReducer.clear()
        explicitTargets.clear()
        mutableSelectionCount.value = 0
        saveCreationState()
    }

    fun selectAllAlbum(album: AlbumSummary, filter: AlbumMediaFilter, sort: AlbumSort) {
        timelineStackSelectionJob?.cancel()
        val query = albumQuery(album.key, filter, sort)
        val selected = SelectionSpec.queryAll(query)
        selectionRevision++
        mutableSelection.value = selected
        explicitTargets.clear()
        mutableSelectionCount.value = 0
        saveCreationState()
        refreshSelectionCount()
    }

    fun selectAllTimeline() = selectAll(currentLibraryQuery())

    fun selectAllTrash() = selectAll(
        MediaQuery(trashedOnly = true, archiveMode = MediaQuery.ArchiveMode.Include),
    )

    fun selectAllArchive() = selectAll(
        MediaQuery(archiveMode = MediaQuery.ArchiveMode.Only),
    )

    private fun selectAll(query: MediaQuery) {
        timelineStackSelectionJob?.cancel()
        val selected = SelectionSpec.queryAll(query)
        selectionRevision++
        mutableSelection.value = selected
        explicitTargets.clear()
        mutableSelectionCount.value = 0
        saveCreationState()
        refreshSelectionCount()
    }

    fun beginSelectionSystemAction(action: MediaAction) {
        when (val selected = mutableSelection.value) {
            is SelectionSpec.Explicit -> {
                val targets = selected.keys.mapNotNull(explicitTargets::get)
                if (targets.isEmpty()) return
                beginTargetsAction(targets, action)
            }
            is SelectionSpec.QueryAll -> beginQueryAction(selected, action)
        }
    }

    fun emptyTrash() = beginQueryAction(
        SelectionSpec.queryAll(
            MediaQuery(trashedOnly = true, archiveMode = MediaQuery.ArchiveMode.Include),
        ),
        MediaAction.Delete,
    )

    fun openHighlight(highlight: GalleryHighlight) {
        mutableSelectedHighlight.value = highlight
    }

    fun openHighlightMedia(media: TimelineMedia) {
        val query = mutableSelectedHighlight.value?.query ?: return
        openMedia(media, query)
    }

    fun openArchiveMedia(media: TimelineMedia) = openMedia(
        media,
        MediaQuery(archiveMode = MediaQuery.ArchiveMode.Only, grouping = MediaQuery.Grouping.None),
    )

    fun openTrashMedia(media: TimelineMedia) = openMedia(
        media,
        MediaQuery(
            trashedOnly = true,
            archiveMode = MediaQuery.ArchiveMode.Include,
            grouping = MediaQuery.Grouping.None,
        ),
    )

    fun setSelectionArchived(archived: Boolean) {
        val selected = mutableSelection.value
        val count = mutableSelectionCount.value
        val revision = selectionRevision
        viewModelScope.launch {
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                when (selected) {
                    is SelectionSpec.Explicit -> active.archive.setArchived(selected.keys, archived)
                    is SelectionSpec.QueryAll -> {
                        var after: MediaKey? = null
                        while (true) {
                            val page = active.selectionTargets.page(selected.querySnapshot, after, 500)
                            if (page.isEmpty()) break
                            // Select-all records deselected items only as exclusions.
                            val keys = page.map { it.key }.filterNot { it in selected.exclusions }
                            if (keys.isNotEmpty()) active.archive.setArchived(keys, archived)
                            after = page.last().key
                            if (page.size < 500) break
                        }
                    }
                }
                active.activity.record(
                    if (archived) GalleryActivityType.Archived else GalleryActivityType.Unarchived,
                    count,
                )
            }
            // Selection state is main-thread only; keep a selection started while archiving ran.
            if (selectionRevision == revision) clearSelection()
            if (archived) refreshWidget()
        }
    }

    fun setMediaArchived(media: TimelineMedia, archived: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runtime.value?.let { active ->
                active.archive.setArchived(listOf(media.key), archived)
                active.activity.record(
                    if (archived) GalleryActivityType.Archived else GalleryActivityType.Unarchived,
                    1,
                )
            }
            if (archived) withContext(Dispatchers.Main) { refreshWidget() }
        }
    }

    fun isArchived(media: TimelineMedia): Flow<Boolean> = runtime.filterNotNull()
        .flatMapLatest { it.archive.isArchived(media.key) }

    private val mutableAlbumRename = MutableStateFlow<AlbumRenameState?>(null)
    val albumRename = mutableAlbumRename.asStateFlow()

    fun beginAlbumRename(album: AlbumSummary) {
        val key = album.key as? AlbumKey.Virtual ?: return
        if (mutableAlbumRename.value?.working == true) return
        mutableAlbumRename.value = AlbumRenameState(key.albumId, album.name.orEmpty())
    }

    fun updateAlbumRename(name: String) {
        val state = mutableAlbumRename.value?.takeUnless { it.working } ?: return
        mutableAlbumRename.value = state.copy(name = name, saveFailed = false)
    }

    fun dismissAlbumRename() {
        if (mutableAlbumRename.value?.working != true) mutableAlbumRename.value = null
    }

    fun confirmAlbumRename(name: String) {
        val state = mutableAlbumRename.value?.takeUnless { it.working } ?: return
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty() || normalized.length > com.librestatic.lightforge.core.data.GalleryAlbumRepository.MaxAlbumNameLength) return
        mutableAlbumRename.value = state.copy(name = name, working = true, saveFailed = false)
        viewModelScope.launch {
            try {
                val active = runtime.value
                val renamed = active != null && active.albums.renameVirtualAlbum(state.albumId, normalized)
                if (renamed) {
                    // Keep the route, paging request and selection intact; update only this title.
                    val selected = mutableSelectedAlbum.value
                    if (selected?.key == AlbumKey.Virtual(state.albumId)) {
                        mutableSelectedAlbum.value = selected.copy(name = normalized)
                    }
                    mutableAlbumRename.value = null
                } else {
                    mutableAlbumRename.value = state.copy(name = name, saveFailed = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableAlbumRename.value = state.copy(name = name, saveFailed = true)
            }
        }
    }

    private val mutableAlbumCoverWorking = MutableStateFlow(false)
    val albumCoverWorking = mutableAlbumCoverWorking.asStateFlow()
    private val mutableAlbumCoverFailed = MutableStateFlow(false)
    val albumCoverFailed = mutableAlbumCoverFailed.asStateFlow()
    private val mutableAlbumCoverRevision = MutableStateFlow(0)
    val albumCoverRevision = mutableAlbumCoverRevision.asStateFlow()

    fun setAlbumCover(album: AlbumSummary, media: TimelineMedia?) {
        val key = album.key as? AlbumKey.Virtual ?: return
        if (mutableAlbumCoverWorking.value) return
        mutableAlbumCoverWorking.value = true
        mutableAlbumCoverFailed.value = false
        viewModelScope.launch {
            try {
                val saved = runtime.value?.albums?.setVirtualAlbumCover(key.albumId, media?.key) == true
                mutableAlbumCoverFailed.value = !saved
                if (saved) mutableAlbumCoverRevision.value += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableAlbumCoverFailed.value = true
            } finally {
                mutableAlbumCoverWorking.value = false
            }
        }
    }

    fun createVirtualAlbum(name: String) {
        viewModelScope.launch { runtime.value?.albums?.createVirtualAlbum(name) }
    }

    private val mutableAlbumDelete = MutableStateFlow<AlbumDeleteState?>(null)
    val albumDelete = mutableAlbumDelete.asStateFlow()

    fun beginAlbumDelete(album: AlbumSummary) {
        val albumId = deletableVirtualAlbumId(album.key) ?: return
        if (mutableAlbumDelete.value?.working == true) return
        mutableAlbumDelete.value = AlbumDeleteState(albumId, album.name.orEmpty())
    }

    fun dismissAlbumDelete() {
        if (mutableAlbumDelete.value?.working != true) mutableAlbumDelete.value = null
    }

    fun confirmAlbumDelete() {
        val state = mutableAlbumDelete.value?.takeUnless { it.working } ?: return
        mutableAlbumDelete.value = state.copy(working = true, deleteFailed = false)
        viewModelScope.launch {
            try {
                val active = runtime.value
                val deleted = active != null && active.albums.deleteVirtualAlbum(state.albumId)
                if (deleted) {
                    mutableAlbumDelete.value = null
                    // Drop the surface the album owned; availableSurfaceRoute() returns to Collections.
                    if (mutableSelectedAlbum.value?.key == AlbumKey.Virtual(state.albumId)) {
                        mutableSelectedAlbum.value = null
                    }
                } else {
                    mutableAlbumDelete.value = state.copy(deleteFailed = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableAlbumDelete.value = state.copy(deleteFailed = true)
            }
        }
    }

    fun addSelectionToVirtualAlbum(albumId: Long) {
        require(albumId > 0)
        val selected = mutableSelection.value
        viewModelScope.launch(Dispatchers.IO) {
            val active = runtime.value ?: return@launch
            when (selected) {
                is SelectionSpec.Explicit -> selected.keys.chunked(500).forEach { chunk ->
                    active.albums.addToVirtualAlbum(albumId, chunk)
                }
                is SelectionSpec.QueryAll -> {
                    var after: MediaKey? = null
                    while (true) {
                        val page = active.selectionTargets.page(selected.querySnapshot, after, 500)
                        if (page.isEmpty()) break
                        after = page.last().key
                        val keys = page.map(MediaActionTarget::key).filterNot(selected.exclusions::contains)
                        if (keys.isNotEmpty()) active.albums.addToVirtualAlbum(albumId, keys)
                    }
                }
            }
            withContext(Dispatchers.Main) { clearSelection() }
        }
    }

    fun selectedPdfSources(): List<Uri> {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return emptyList()
        val targets = selected.keys.mapNotNull(explicitTargets::get)
        if (targets.size != selected.keys.size || targets.size !in 1..100 || targets.any { it.kind != MediaKind.Image }) return emptyList()
        return targets.map { it.uri() }
    }

    fun selectionShareIntent(): Intent? {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return null
        val targets = selected.keys.mapNotNull(explicitTargets::get)
        if (targets.isEmpty() || targets.size > 500) return null
        return ShareCoordinator(getApplication<Application>().contentResolver).original(
            targets.map { ShareCandidate(it, mediaMime(it.kind)) },
        ).intent
    }

    fun canCreateCollage(template: CollageTemplate): Boolean {
        val selected = mutableSelection.value as? SelectionSpec.Explicit ?: return false
        val targets = selected.keys.mapNotNull(explicitTargets::get)
        return targets.size == template.slotCount && targets.all { it.kind == MediaKind.Image }
    }

    suspend fun createCollage(template: CollageTemplate): Uri = withContext(Dispatchers.IO) {
        val selected = mutableSelection.value as? SelectionSpec.Explicit
            ?: error("Collages require an explicit selection")
        val targets = selected.keys.mapNotNull(explicitTargets::get)
        require(targets.size == template.slotCount && targets.all { it.kind == MediaKind.Image }) {
            "Select exactly ${template.slotCount} photos"
        }
        val resolver = getApplication<Application>().contentResolver
        val decoder = NativeImageDecoder(resolver)
        val sources = mutableListOf<Bitmap>()
        val output = try {
            targets.forEach { target ->
                sources += decoder.screenPreview(target.uri(), targetWidth = 1_600, targetHeight = 1_600)
            }
            CollageTemplates.render(
                sources,
                CollageConfig(template, outputWidth = 2_048, outputHeight = 2_048, spacing = 12f),
            )
        } finally {
            sources.forEach(Bitmap::recycle)
        }
        val temp = java.io.File(getApplication<Application>().cacheDir, "collage-${System.nanoTime()}.jpg")
        try {
            temp.outputStream().buffered().use { stream ->
                check(output.compress(Bitmap.CompressFormat.JPEG, 94, stream)) { "Collage encoding failed" }
            }
            PendingMediaWriter(resolver).publishFile(
                temp,
                MediaWriteSpec(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    MediaKind.Image,
                    "Lightforge-collage-${System.currentTimeMillis()}.jpg",
                    "image/jpeg",
                    "Pictures/Lightforge",
                ),
            ).uri
        } finally {
            output.recycle()
            temp.delete()
        }.also {
            withContext(Dispatchers.Main) { clearSelection() }
            refreshLibrary()
        }
    }

    fun motionKeyFrame(media: TimelineMedia) = runtime.filterNotNull().flatMapLatest {
        it.motionKeyFrames.observe(media.key)
    }.map { it?.takeIf { row -> row.generationModified == media.generationModified } }

    suspend fun setMotionKeyFrame(media: TimelineMedia, timeUs: Long, file: java.io.File, revision: String?): Boolean {
        val active = runtime.value ?: return false
        active.motionKeyFrames.set(media.key, media.generationModified, timeUs, file, revision)
        return true
    }

    suspend fun resetMotionKeyFrame(media: TimelineMedia, revision: String): Boolean =
        runtime.value?.motionKeyFrames?.reset(media.key, revision) ?: false

    private fun motionDisplayUri(active: GalleryRuntime, media: TimelineMedia): Uri =
        runCatching { active.motionKeyFrames.displayUri(media.key, media.generationModified) }.getOrNull() ?: media.uri()

    /** Closing the viewer invalidates pending reads; a late callback cannot resurrect its route. */
    fun clearViewerRecovery() {
        viewerRestoreEpoch.cancel()
        viewerSnapshotJob?.cancel()
        pendingRestoredViewer = null
        mutableViewerRestoreSnapshot.value = null
        mutableViewerSourceChecking.value = false
        mutableViewerRecovered.value = false
        saveCreationState()
    }

    private fun captureViewerRecovery(media: TimelineMedia) {
        val epoch = viewerRestoreEpoch.begin()
        viewerSnapshotJob?.cancel()
        pendingRestoredViewer = null
        mutableViewerRestoreSnapshot.value = null
        mutableViewerRecovered.value = false
        mutableViewerSourceChecking.value = true
        saveCreationState()
        val active = runtime.value
        val permission = access.value
        val query = viewerQuery
        if (active == null) { mutableViewerSourceChecking.value = false; return }
        viewerSnapshotJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val row = active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)
                        ?: return@withContext null
                    val actual = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                        .readOne(media.key) ?: return@withContext null
                    if (!row.isAccessible || row.generationModified != media.generationModified ||
                        row.generationModified != actual.generationModified || row.generationAdded != actual.generationAdded ||
                        actual.key != media.key || actual.kind != media.kind || row.mediaType != (if (media.kind == MediaKind.Image) 1 else 3) ||
                        row.isTrashed != media.isTrashed || actual.isTrashed != media.isTrashed) return@withContext null
                    ViewerRestoreSnapshot(media.key, media.kind, actual.generationModified, actual.generationAdded, actual.isTrashed, query)
                }
                if (!viewerRestoreEpoch.isCurrent(epoch) || runtime.value !== active || permission != access.value ||
                    mutableCurrentMedia.value?.let { it.key == media.key && it.generationModified == media.generationModified } != true) return@launch
                mutableViewerRestoreSnapshot.value = snapshot
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Viewing can report its ordinary source error; never persist an unvalidated identity.
            } finally {
                if (viewerRestoreEpoch.isCurrent(epoch)) {
                    mutableViewerSourceChecking.value = false
                    saveCreationState()
                }
            }
        }
    }

    private suspend fun restorePendingViewer(active: GalleryRuntime) {
        val snapshot = pendingRestoredViewer ?: return
        val permission = access.value
        try {
            val media = withContext(Dispatchers.IO) {
                val row = active.database.libraryDao().media(snapshot.key.volumeName, snapshot.key.mediaStoreId)
                    ?: return@withContext null
                val actual = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                    .readOne(snapshot.key) ?: return@withContext null
                if (!row.isAccessible || row.mediaType != (if (snapshot.kind == MediaKind.Image) 1 else 3) ||
                    row.generationModified != actual.generationModified || row.generationAdded != actual.generationAdded ||
                    row.isTrashed != actual.isTrashed ||
                    !snapshot.matchesSource(actual.key, actual.kind, actual.generationModified, actual.generationAdded, actual.isTrashed))
                    return@withContext null
                TimelineMedia(snapshot.key, snapshot.kind, actual.generationModified, row.timelineSortMillis,
                    row.width, row.height, row.durationMillis, row.dateExpiresSeconds?.times(1_000), row.isFavorite,
                    row.isTrashed, row.displayName, row.sizeBytes)
            }
            if (pendingRestoredViewer !== snapshot || runtime.value !== active || permission != access.value) return
            if (media != null) {
                viewerQuery = snapshot.query
                mutableCurrentMedia.value = media
                mutableViewerRestoreSnapshot.value = snapshot
                mutableViewerRecovered.value = true
                renderViewerMedia(media, forceWindowReload = true)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // Keep the source-bound unavailable route with Back, never substitute another item.
        } finally {
            if (pendingRestoredViewer === snapshot) {
                pendingRestoredViewer = null
                mutableViewerSourceChecking.value = false
                saveCreationState()
            }
        }
    }

    fun openMedia(media: TimelineMedia, sourceQuery: MediaQuery = MediaQuery()) {
        viewerQuery = sourceQuery
        selectViewerMedia(media, forceWindowReload = true)
    }

    fun openTimelineMedia(media: TimelineMedia) = openMedia(media, currentLibraryQuery())

    fun openAlbumMedia(
        media: TimelineMedia,
        album: AlbumSummary,
        filter: AlbumMediaFilter,
        sort: AlbumSort,
    ) = openMedia(media, albumQuery(album.key, filter, sort))

    fun selectViewerMedia(media: TimelineMedia, forceWindowReload: Boolean = false) {
        mutableCurrentMedia.value = media
        captureViewerRecovery(media)
        renderViewerMedia(media, forceWindowReload)
    }

    /** Rendering a new cover does not open a closed viewer or replace process recovery ownership. */
    private fun renderViewerMedia(media: TimelineMedia, forceWindowReload: Boolean = false) {
        val previousViewer = mutableViewerState.value
        val existingIndex = previousViewer.items.indexOfFirst { it.key == media.key }
        val nearPreviousEdge = existingIndex in 0 until ViewerWindowRefreshThreshold && previousViewer.hasPrevious
        val nearNextEdge = existingIndex >= previousViewer.items.size - ViewerWindowRefreshThreshold && previousViewer.hasNext
        val reloadWindow = forceWindowReload || existingIndex < 0 || nearPreviousEdge || nearNextEdge
        mutableViewerState.value = if (existingIndex >= 0) {
            previousViewer.copy(currentIndex = existingIndex, isLoading = reloadWindow)
        } else {
            ViewerUiState(listOf(media), 0, isLoading = true)
        }
        mutableSelectedMomentId.value = null
        savedStateHandle[SelectedMomentStateKey] = null
        mutableCheapDetails.value = null
        mutableExifDetails.value = null
        mutableDetectedText.value = null
        val adjacentPreview = mutableAdjacentPhotoStates.value[media.key]
        mutablePhotoState.value = adjacentPreview
        photoJob?.cancel()
        if (media.kind == MediaKind.Image) {
            val active = runtime.value ?: return
            val cachedThumbnail = (adjacentPreview?.drawable as? BitmapDrawable)?.bitmap
                ?: active.thumbnails.bestCached(media.key, media.generationModified)
            photoJob = viewModelScope.launch {
                PhotoViewerPipeline(active.decoder).load(
                    withContext(Dispatchers.IO) { motionDisplayUri(active, media) },
                    1_440,
                    3_120,
                    cachedThumbnail = cachedThumbnail,
                )
                    .collect { state ->
                        if (state !is PhotoLoadState.Thumbnail || adjacentPreview == null) {
                            mutablePhotoState.value = state
                        }
                    }
            }
        }
        preloadViewerNeighbors()
        if (!reloadWindow) return
        viewerWindowJob?.cancel()
        viewerWindowJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val items: List<TimelineMedia>
            val hasPrevious: Boolean
            val hasNext: Boolean
            if (viewerQuery.scope is MediaQuery.Scope.Search) {
                var hits = mutableSearch.value.hits
                var anchorIndex = hits.indexOfFirst { it.key == media.key }
                if (anchorIndex >= hits.size - ViewerWindowRefreshThreshold && !mutableSearch.value.terminal) {
                    val cursor = searchCursor
                    if (cursor != null) runCatching { cursor.nextPage() }.onSuccess { page ->
                        hits = hits + page.hits
                        mutableSearch.value = mutableSearch.value.copy(
                            hits = hits,
                            terminal = page.isTerminal,
                            loading = false,
                        )
                        anchorIndex = hits.indexOfFirst { it.key == media.key }
                    }
                }
                val from = (anchorIndex - ViewerWindowRadius).coerceAtLeast(0)
                val to = (anchorIndex + ViewerWindowRadius + 1).coerceAtMost(hits.size)
                items = if (anchorIndex < 0) listOf(media) else hits.subList(from, to).mapNotNull { hit ->
                    active.database.libraryDao().media(hit.key.volumeName, hit.key.mediaStoreId)?.let { row ->
                        TimelineMedia(
                            hit.key, hit.kind, row.generationModified, row.timelineSortMillis,
                            row.width, row.height, row.durationMillis,
                            row.dateExpiresSeconds?.times(1_000), row.isFavorite, row.isTrashed,
                        )
                    }
                }
                hasPrevious = anchorIndex > ViewerWindowRadius
                hasNext = anchorIndex >= 0 && (to < hits.size || !mutableSearch.value.terminal)
            } else {
                val window = active.viewerMedia.window(viewerQuery, media, ViewerWindowRadius)
                items = window.items
                hasPrevious = window.hasPrevious
                hasNext = window.hasNext
            }
            val currentIndex = items.indexOfFirst { it.key == media.key }.let { if (it < 0) 0 else it }
            mutableViewerState.value = ViewerUiState(
                items = items.ifEmpty { listOf(media) },
                currentIndex = currentIndex,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                isLoading = false,
            )
            preloadViewerNeighbors()
        }
    }

    private fun preloadViewerNeighbors() {
        adjacentPhotoJob?.cancel()
        val active = runtime.value ?: run {
            mutableAdjacentPhotoStates.value = emptyMap()
            return
        }
        val viewer = mutableViewerState.value
        val policy = active.thumbnails.prefetchPolicy ?: run {
            mutableAdjacentPhotoStates.value = emptyMap()
            return
        }
        val planned = ViewerAdjacentPreloadPlanner.plan(
            items = viewer.items,
            currentIndex = viewer.currentIndex,
            maxSourcePixels = policy.maxSourcePixels,
            safeBudgetBytes = policy.safeBudgetBytes(),
            backgroundPreloadEnabled = policy.extraRows > 0,
        )
        val plannedKeys = planned.mapTo(mutableSetOf()) { it.key }
        mutableAdjacentPhotoStates.value = mutableAdjacentPhotoStates.value.filterKeys(plannedKeys::contains)
        if (planned.isEmpty()) return

        adjacentPhotoJob = viewModelScope.launch {
            val previews = coroutineScope {
                planned.map { neighbor ->
                    async(Dispatchers.IO) {
                        try {
                            val drawable = active.decoder.screenDrawable(
                                motionDisplayUri(active, neighbor),
                                ViewerAdjacentPreloadPlanner.PreviewWidthPx,
                                ViewerAdjacentPreloadPlanner.PreviewHeightPx,
                            )
                            neighbor.key to PhotoLoadState.Ready(
                                drawable = drawable,
                                isAnimated = drawable is AnimatedImageDrawable,
                                supportsDeepZoom = false,
                                deepZoomUnavailableReason = null,
                                thumbnailTransition = PhotoPreviewTransition.Immediate,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }.toMap()
            mutableAdjacentPhotoStates.value = previews
        }
    }

    fun openPhotoEditor(media: TimelineMedia) {
        if (media.kind != MediaKind.Image) return
        mutableCurrentMedia.value = media
        captureViewerRecovery(media)
        openPhotoEditor(EditorMediaSource(
            uriString = mediaUri(media).toString(), kind = media.kind, libraryMedia = media,
            displayName = media.displayName, width = media.width, height = media.height,
        ))
    }

    private fun openPhotoEditor(source: EditorMediaSource) {
        photoEditorJob?.cancel()
        photoAutoEnhancementJob?.cancel()
        photoAutoEnhancementGeneration++
        closeRawPreviewSession()
        val generation = ++photoEditorOpenGeneration
        mutablePhotoEditorOpening.value = true
        photoEditorJob = viewModelScope.launch {
            try {
            val active = runtime.value ?: return@launch
            photoRecipeDiscardJob?.join()
            if (generation != photoEditorOpenGeneration) return@launch
            val libraryMedia = source.libraryMedia
            val entry = if (libraryMedia != null) withContext(Dispatchers.IO) {
                active.database.withTransaction {
                    val dao = active.database.editRecipeDao()
                    val id = EditRecipe.forSource(libraryMedia.key, libraryMedia.generationModified).recipeId
                    dao.load(id)?.let { it to requireNotNull(dao.recipe(id)).updatedAtMillis }
                }
            } else null
            val initial = entry?.first ?: if (libraryMedia != null)
                EditRecipe.forSource(libraryMedia.key, libraryMedia.generationModified)
            else EditRecipe.ephemeral(source.stableId.hashCode().toUInt().toString(16))
            val storedMedia = libraryMedia?.let { media -> withContext(Dispatchers.IO) {
                active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)
            } }
            val isRaw = isRawMimeOrName(storedMedia?.mimeType ?: source.mimeType, storedMedia?.displayName ?: source.displayName)
            val rawSettings = initial.operations.filterIsInstance<EditOperation.RawDevelop>()
                .lastOrNull()?.settings ?: RawDevelopmentSettings()
            val stagedRaw = if (isRaw) runCatching {
                RawDeveloper(
                    getApplication<Application>().contentResolver,
                    java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                ).openPreviewSession(source.uri)
            }.getOrNull() else null
            rawPreviewSession = stagedRaw
            val rawMetadata = stagedRaw?.let { runCatching { it.inspect() }.getOrNull() }
            mutablePhotoEditor.value = PhotoEditorSession(
                source = source,
                history = EditHistory.initial(initial),
                entryRecipe = entry?.first,
                entryRecipeUpdatedAtMillis = entry?.second ?: 0,
                content = PhotoEditorContentState(
                    isRendering = true,
                    isAutoEnhancementAnalyzing = !isRaw,
                    isDirty = initial.operations.isNotEmpty(),
                    selectedFilter = selectedFilter(initial),
                    tone = selectedTone(initial),
                    colorOperations = photoColorOperations(initial),
                    crop = selectedCrop(initial),
                    straightenDegrees = selectedStraighten(initial),
                    geometry = photoGeometryOperations(initial.operations),
                    isRaw = isRaw,
                    rawMetadata = rawMetadata,
                    rawSettings = rawSettings,
                ),
            )
            renderPhotoEditorPreview(active, source, initial)
            if (isRaw && stagedRaw != null) {
                startRawPreviewWorker(source, stagedRaw)
                mutablePhotoEditor.value?.content?.rawSettings?.takeIf { it != rawSettings }?.let {
                    enqueueRawPreview(it, initial, final = true)
                }
            }
            } finally {
                if (generation == photoEditorOpenGeneration) mutablePhotoEditorOpening.value = false
            }
        }
    }

    fun previewPhotoTone(tone: EditOperation.Tone) {
        val session = mutablePhotoEditor.value ?: return
        if (tone == session.content.tone) return
        mutablePhotoEditor.value = session.copy(
            content = session.content.copy(
                tone = tone,
                colorOperations = replacePhotoColorOperation(session.history.present.operations, tone),
                isDirty = true,
                statusMessage = null,
            ),
        )
    }

    fun commitPhotoTone() {
        val session = mutablePhotoEditor.value ?: return
        commitPhotoEdit(session.content.tone, renderRequired = false)
    }

    fun previewRawDevelopment(settings: RawDevelopmentSettings) {
        val session = mutablePhotoEditor.value ?: return
        if (!session.content.isRaw) return
        if (settings == session.content.rawSettings) return
        mutablePhotoEditor.value = session.copy(
            content = session.content.copy(
                rawSettings = settings,
                isRendering = true,
                isDirty = true,
                statusMessage = null,
            ),
        )
        enqueueRawPreview(settings, session.history.present, final = false)
    }

    fun commitRawDevelopment() {
        val session = mutablePhotoEditor.value ?: return
        if (!session.content.isRaw) return
        if (selectedRawSettings(session.history.present) == session.content.rawSettings) {
            enqueueRawPreview(session.content.rawSettings, session.history.present, final = true)
            return
        }
        val updated = session.history.applyRawDevelopment(session.content.rawSettings)
        if (updated == session.history) return
        mutablePhotoEditor.value = session.copy(
            history = updated,
            content = session.content.copy(
                isRendering = true,
                isDirty = true,
                canUndo = updated.past.isNotEmpty(),
                canRedo = false,
            ),
        )
        persistPhotoRecipe(updated.present)
        enqueueRawPreview(session.content.rawSettings, updated.present, final = true)
    }

    fun setRawOutputFormat(format: RawOutputFormat) {
        val session = mutablePhotoEditor.value ?: return
        mutablePhotoEditor.value = session.copy(content = session.content.copy(rawOutputFormat = format))
    }

    fun applyPhotoEdit(operation: EditOperation) {
        commitPhotoEdit(
            operation,
            renderRequired = operation !is EditOperation.Tone && operation !is EditOperation.Filter,
        )
    }

    fun applyPhotoAutoSuggestion(tone: EditOperation.Tone?) {
        val session = mutablePhotoEditor.value ?: return
        if (session.content.isRaw) return
        val updated = session.history.replaceColorOperations(tone)
        if (updated == session.history) return
        mutablePhotoEditor.value = session.copy(
            history = updated,
            content = session.content.copy(
                isRendering = false,
                isDirty = updated.present.operations.isNotEmpty(),
                canUndo = updated.past.isNotEmpty(),
                canRedo = false,
                selectedFilter = selectedFilter(updated.present),
                tone = selectedTone(updated.present),
                colorOperations = photoColorOperations(updated.present),
                statusMessage = null,
            ),
        )
        persistPhotoRecipe(updated.present)
    }

    private fun commitPhotoEdit(operation: EditOperation, renderRequired: Boolean) {
        val session = mutablePhotoEditor.value ?: return
        val updated = session.history.apply(operation)
        if (updated == session.history) return
        if (renderRequired) retargetExperimentalPhotoEdits(updated.present)
        mutablePhotoEditor.value = (mutablePhotoEditor.value ?: session).copy(
            history = updated,
            content = (mutablePhotoEditor.value ?: session).content.copy(
                isRendering = renderRequired,
                isDirty = updated.present.operations.isNotEmpty() ||
                    mutablePhotoEditor.value?.eraseMarks?.isNotEmpty() == true,
                canUndo = updated.past.isNotEmpty(),
                canRedo = false,
                selectedFilter = selectedFilter(updated.present),
                tone = selectedTone(updated.present),
                colorOperations = photoColorOperations(updated.present),
                crop = selectedCrop(updated.present),
                straightenDegrees = selectedStraighten(updated.present),
                geometry = photoGeometryOperations(updated.present.operations),
                statusMessage = null,
            ),
        )
        persistPhotoRecipe(updated.present)
        if (renderRequired && session.content.isRaw) {
            enqueueRawPreview(session.content.rawSettings, updated.present, final = true)
        } else if (renderRequired) {
            photoEditorJob?.cancel()
            photoEditorJob = viewModelScope.launch {
                val active = runtime.value ?: return@launch
                renderPhotoEditorPreview(active, session.source, updated.present)
            }
        }
    }

    private fun persistPhotoRecipe(recipe: EditRecipe) {
        if (recipe.source == null) return
        photoRecipeJob?.cancel()
        photoRecipeJob = viewModelScope.launch(Dispatchers.IO) {
            delay(80)
            runtime.value?.database?.editRecipeDao()?.replace(recipe, System.currentTimeMillis())
        }
    }

    fun undoPhotoEdit() = movePhotoHistory { it.undo() }
    fun redoPhotoEdit() = movePhotoHistory { it.redo() }

    private fun movePhotoHistory(transform: (EditHistory) -> EditHistory) {
        val session = mutablePhotoEditor.value ?: return
        val updated = transform(session.history)
        if (updated == session.history) return
        val renderRequired = photoBaseOperations(session.history.present) != photoBaseOperations(updated.present)
        if (renderRequired) retargetExperimentalPhotoEdits(updated.present)
        mutablePhotoEditor.value = (mutablePhotoEditor.value ?: session).copy(
            history = updated,
            content = (mutablePhotoEditor.value ?: session).content.copy(
                isRendering = renderRequired,
                isDirty = updated.present.operations.isNotEmpty(),
                canUndo = updated.past.isNotEmpty(),
                canRedo = updated.future.isNotEmpty(),
                selectedFilter = selectedFilter(updated.present),
                tone = selectedTone(updated.present),
                colorOperations = photoColorOperations(updated.present),
                crop = selectedCrop(updated.present),
                straightenDegrees = selectedStraighten(updated.present),
                geometry = photoGeometryOperations(updated.present.operations),
                rawSettings = selectedRawSettings(updated.present),
                statusMessage = null,
            ),
        )
        persistPhotoRecipe(updated.present)
        if (session.content.isRaw && renderRequired) {
            enqueueRawPreview(selectedRawSettings(updated.present), updated.present, final = true)
        } else if (renderRequired) {
            photoEditorJob?.cancel()
            photoEditorJob = viewModelScope.launch {
                val active = runtime.value ?: return@launch
                renderPhotoEditorPreview(active, session.source, updated.present)
            }
        }
    }

    fun savePhotoEditorCopy() {
        val session = mutablePhotoEditor.value ?: return
        if (session.content.isExporting) return
        mutablePhotoEditor.value = session.copy(content = session.content.copy(isExporting = true, statusMessage = null))
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val storedMime = withContext(Dispatchers.IO) {
                session.source.libraryMedia?.let { media ->
                    active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)?.mimeType
                } ?: session.source.mimeType
            }
            val outputMime = if (session.content.isRaw) {
                if (session.content.rawOutputFormat == RawOutputFormat.JpegSrgb) "image/jpeg" else "image/tiff"
            } else imageExportMime(storedMime)
            val extension = if (outputMime == "image/tiff") "tif" else
                MimeTypeMap.getSingleton().getExtensionFromMimeType(outputMime) ?: "jpg"
            val temp = java.io.File(getApplication<Application>().cacheDir, "photo-edit-${System.nanoTime()}.$extension")
            val transformedRaw = java.io.File(getApplication<Application>().cacheDir, "photo-edit-geometry-${System.nanoTime()}.jpg")
            try {
                if (session.content.isRaw) {
                    val geometryRecipe = session.history.present.copy(
                        operations = session.history.present.operations.filterNot { it is EditOperation.RawDevelop },
                    )
                    if (session.content.rawOutputFormat == RawOutputFormat.Tiff16Srgb && !geometryRecipe.isIdentity) {
                        updatePhotoExportFailure(getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_tiff_geometry_unsupported,
                        ))
                        return@launch
                    }
                    when (val result = RawDeveloper(
                        getApplication<Application>().contentResolver,
                        java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                    ).export(session.source.uri, session.content.rawSettings, session.content.rawOutputFormat, temp)) {
                        is RawExportOutcome.Completed -> if (geometryRecipe.isIdentity) {
                            publishPhotoResult(result.file, result.mimeType, extension, emptyList())
                        } else when (val transformed = PhotoImageRenderer(getApplication<Application>().contentResolver).export(
                            Uri.fromFile(result.file), geometryRecipe, transformedRaw, preserveMetadata = true,
                        )) {
                            is PhotoExportOutcome.Completed -> publishPhotoResult(
                                transformed.file, transformed.mimeType ?: "image/jpeg", "jpg",
                                transformed.warnings,
                            )
                            is PhotoExportOutcome.Failure -> updatePhotoExportFailure(photoExportFailureMessage(transformed))
                        }
                        is RawExportOutcome.Failure -> updatePhotoExportFailure(rawExportFailureMessage(result))
                    }
                } else if (session.eraseMarks.isNotEmpty()) {
                    val erased = renderExperimentalPhotoSource(session, transformedRaw) ?: return@launch
                    val format = when (outputMime) {
                        "image/png" -> Bitmap.CompressFormat.PNG
                        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    val erasedWidth = erased.width
                    val erasedHeight = erased.height
                    try {
                        withContext(Dispatchers.IO) { temp.outputStream().use { erased.compress(format, 95, it) } }
                    } finally {
                        erased.recycle()
                    }
                    // Same capture EXIF as a normal save copy; the pixels are already upright.
                    PhotoImageRenderer(getApplication<Application>().contentResolver)
                        .copyCaptureMetadata(session.source.uri, temp, erasedWidth, erasedHeight)
                    publishPhotoResult(temp, outputMime, extension, emptyList())
                } else when (val result = PhotoImageRenderer(getApplication<Application>().contentResolver).export(
                    session.source.uri, session.history.present, temp, preserveMetadata = true,
                )) {
                    is PhotoExportOutcome.Completed -> publishPhotoResult(
                        result.file, result.mimeType ?: outputMime, extension, result.warnings,
                    )
                    is PhotoExportOutcome.Failure -> updatePhotoExportFailure(photoExportFailureMessage(result))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutablePhotoEditor.value = mutablePhotoEditor.value?.copy(
                    content = mutablePhotoEditor.value!!.content.copy(
                        isExporting = false,
                        statusMessage = getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_save_failed,
                        ),
                    ),
                )
            } finally {
                temp.delete()
                transformedRaw.delete()
            }
        }
    }

    /** Experimental object eraser: erases the preview copy only; Save copy re-applies the dabs at full size. */
    fun applyObjectErase(points: List<PhotoPoint>) {
        val session = mutablePhotoEditor.value ?: return
        if (session.content.preview == null) return
        if (points.isEmpty() || session.content.isRaw) return
        val geometry = photoGeometryOperations(session.history.present.operations)
        val updated = if (photoGeometryProjectable(geometry) && session.eraseGeometry == null) {
            session.copy(eraseMarks = session.eraseMarks + points.mapNotNull { projectToSource(it, geometry) })
        } else {
            session.copy(eraseMarks = session.eraseMarks + points, eraseGeometry = geometry)
        }
        runObjectErase(updated)
    }

    /** Erases [session]'s marks from its current preview, e.g. after a geometry edit re-rendered it. */
    private fun runObjectErase(session: PhotoEditorSession) {
        val preview = session.content.preview ?: return
        val marks = editedEraseMarks(session)
        // The new marks are stored only once the erase succeeds.
        val shown = mutablePhotoEditor.value ?: return
        mutablePhotoEditor.value = shown.copy(
            content = shown.content.copy(isExperimentalProcessing = true, statusMessage = null),
        )
        photoExperimentalJob?.cancel()
        photoExperimentalJob = viewModelScope.launch {
            val erased = try {
                photoInpaintingSession().erase(preview, eraseRegionsFor(marks, preview.width, preview.height))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val current = mutablePhotoEditor.value
            if (current == null || current.content.preview !== preview) {
                erased?.bitmap?.recycle()
                return@launch
            }
            if (erased == null) {
                mutablePhotoEditor.value = current.copy(
                    content = current.content.copy(
                        isExperimentalProcessing = false,
                        statusMessage = getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_preview_unavailable,
                        ),
                    ),
                )
                return@launch
            }
            current.content.erasePreview?.recycle()
            mutablePhotoEditor.value = current.copy(
                eraseMarks = session.eraseMarks,
                eraseGeometry = session.eraseGeometry,
                content = current.content.copy(
                    erasePreview = erased.bitmap,
                    eraseMethod = erased.method,
                    isExperimentalProcessing = false,
                    isDirty = true,
                ),
            )
        }
    }

    private fun photoInpaintingSession(): InpaintingSession =
        photoInpainting ?: InpaintingSession(getApplication()).also { photoInpainting = it }

    private fun releasePhotoInpainting() {
        photoInpainting?.close()
        photoInpainting = null
    }

    /** The session's eraser marks in the coordinates of its current edited image. */
    private fun editedEraseMarks(session: PhotoEditorSession): List<PhotoPoint> {
        session.eraseGeometry?.let { return session.eraseMarks }
        val geometry = photoGeometryOperations(session.history.present.operations)
        return session.eraseMarks.mapNotNull { projectToEdited(it, geometry) }
    }

    fun clearObjectErase() {
        val session = mutablePhotoEditor.value ?: return
        photoExperimentalJob?.cancel()
        session.content.erasePreview?.recycle()
        mutablePhotoEditor.value = session.copy(
            eraseMarks = emptyList(),
            eraseGeometry = null,
            content = session.content.copy(
                erasePreview = null,
                eraseMethod = null,
                isExperimentalProcessing = false,
                isDirty = session.history.present.operations.isNotEmpty(),
            ),
        )
    }

    /** Experimental subject clip: previews the cut-out of the displayed photo around [seed]. */
    fun previewSubjectClip(seed: PhotoPoint) {
        val session = mutablePhotoEditor.value ?: return
        val source = session.content.erasePreview ?: session.content.preview ?: return
        if (session.content.isRaw) return
        mutablePhotoEditor.value = session.copy(
            content = session.content.copy(subjectClipSeed = seed, isExperimentalProcessing = true, statusMessage = null),
        )
        photoExperimentalJob?.cancel()
        photoExperimentalJob = viewModelScope.launch {
            val clip = try {
                withContext(Dispatchers.Default) {
                    SubjectClipper().clipSubject(
                        source,
                        seedX = (seed.x * source.width).toInt(),
                        seedY = (seed.y * source.height).toInt(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val current = mutablePhotoEditor.value
            if (current == null || current.content.subjectClipSeed != seed ||
                (current.content.erasePreview ?: current.content.preview) !== source
            ) {
                clip?.bitmap?.recycle()
                return@launch
            }
            current.content.subjectClipPreview?.recycle()
            mutablePhotoEditor.value = current.copy(
                content = current.content.copy(
                    subjectClipPreview = clip?.bitmap,
                    subjectClipMethod = clip?.method,
                    isExperimentalProcessing = false,
                ),
            )
        }
    }

    /** Saves the subject cut-out as a new PNG with transparency; the original is never modified. */
    fun saveSubjectClip() {
        val session = mutablePhotoEditor.value ?: return
        val seed = session.content.subjectClipSeed ?: return
        if (session.content.isExporting || session.content.isRaw) return
        mutablePhotoEditor.value = session.copy(content = session.content.copy(isExporting = true, statusMessage = null))
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            val cache = getApplication<Application>().cacheDir
            val rendered = java.io.File(cache, "photo-clip-render-${System.nanoTime()}")
            val temp = java.io.File(cache, "photo-clip-${System.nanoTime()}.png")
            try {
                val bitmap = renderExperimentalPhotoSource(session, rendered) ?: return@launch
                val clip = try {
                    withContext(Dispatchers.Default) {
                        SubjectClipper().clipSubject(
                            bitmap,
                            seedX = (seed.x * bitmap.width).toInt(),
                            seedY = (seed.y * bitmap.height).toInt(),
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
                try {
                    withContext(Dispatchers.IO) {
                        temp.outputStream().use { clip.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally {
                    clip.bitmap.recycle()
                }
                publishPhotoResult(temp, "image/png", "png", emptyList())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                updatePhotoExportFailure(getApplication<Application>().getString(
                    com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_save,
                ))
            } finally {
                rendered.delete()
                temp.delete()
            }
        }
    }

    /**
     * Renders the recipe upright at full size and applies the experimental eraser dabs. Returns
     * null after reporting a failure; sources past the single-bitmap budget are refused.
     */
    private suspend fun renderExperimentalPhotoSource(
        session: PhotoEditorSession,
        rendered: java.io.File,
    ): Bitmap? {
        val renderer = PhotoImageRenderer(getApplication<Application>().contentResolver)
        val bounds = renderer.bounds(session.source.uri)
        if (bounds.width.toLong() * bounds.height.toLong() > EXPERIMENTAL_PHOTO_MAX_PIXELS) {
            updatePhotoExportFailure(getApplication<Application>().getString(
                com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_experimental_too_large,
            ))
            return null
        }
        // A full-frame crop forces a decoded, EXIF-upright render even for an unedited photo.
        val recipe = session.history.present.let {
            if (it.isIdentity) it.copy(operations = listOf(EditOperation.Crop(0, 0, 1_000, 1_000))) else it
        }
        return when (val result = renderer.export(session.source.uri, recipe, rendered)) {
            is PhotoExportOutcome.Failure -> {
                updatePhotoExportFailure(photoExportFailureMessage(result))
                null
            }
            is PhotoExportOutcome.Completed -> {
                val decoded = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(
                        result.file.path,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                    )
                }
                if (decoded == null) {
                    updatePhotoExportFailure(getApplication<Application>().getString(
                        com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_decode,
                    ))
                    return null
                }
                if (session.eraseMarks.isEmpty()) return decoded
                try {
                    photoInpaintingSession().erase(
                        decoded,
                        eraseRegionsFor(editedEraseMarks(session), decoded.width, decoded.height),
                    ).bitmap
                } finally {
                    decoded.recycle()
                }
            }
        }
    }

    /**
     * Geometry changed to [recipe]: source-anchored eraser marks survive and are re-applied when
     * the new preview renders; marks drawn under a straighten (and the clip seed) are dropped.
     */
    private fun retargetExperimentalPhotoEdits(recipe: EditRecipe) {
        val session = mutablePhotoEditor.value ?: return
        val geometry = photoGeometryOperations(recipe.operations)
        val keep = session.eraseMarks.isNotEmpty() && (
            (session.eraseGeometry == null && photoGeometryProjectable(geometry)) || session.eraseGeometry == geometry
            )
        if (!keep) return dropExperimentalPhotoEdits()
        photoExperimentalJob?.cancel()
        session.content.erasePreview?.recycle()
        session.content.subjectClipPreview?.recycle()
        mutablePhotoEditor.value = session.copy(
            content = session.content.copy(
                erasePreview = null,
                subjectClipPreview = null,
                subjectClipSeed = null,
                subjectClipMethod = null,
                isExperimentalProcessing = false,
            ),
        )
    }

    /** Geometry changed: eraser dabs and the clip seed no longer line up with the photo. */
    private fun dropExperimentalPhotoEdits() {
        val session = mutablePhotoEditor.value ?: return
        val hadErase = session.eraseMarks.isNotEmpty()
        if (!hadErase && session.content.subjectClipSeed == null && session.content.erasePreview == null) return
        photoExperimentalJob?.cancel()
        session.content.erasePreview?.recycle()
        session.content.subjectClipPreview?.recycle()
        mutablePhotoEditor.value = session.copy(
            eraseMarks = emptyList(),
            eraseGeometry = null,
            content = session.content.copy(
                erasePreview = null,
                eraseMethod = null,
                subjectClipPreview = null,
                subjectClipSeed = null,
                subjectClipMethod = null,
                isExperimentalProcessing = false,
                statusMessage = if (hadErase) getApplication<Application>().getString(
                    com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_eraser_reset,
                ) else session.content.statusMessage,
            ),
        )
    }

    private fun recycleExperimentalPreviews(content: PhotoEditorContentState?) {
        content?.erasePreview?.recycle()
        content?.subjectClipPreview?.recycle()
    }

    private suspend fun publishPhotoResult(
        file: java.io.File,
        mimeType: String,
        fallbackExtension: String,
        warnings: List<PhotoExportWarning>,
    ) {
        val publishedExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: fallbackExtension
        val published = PendingMediaWriter(getApplication<Application>().contentResolver).publishFile(
            file,
            MediaWriteSpec(
                MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaKind.Image,
                "Lightforge-edited-${System.currentTimeMillis()}.$publishedExtension",
                mimeType, "Pictures/Lightforge",
            ),
        )
        finishEditorCopy(published, MediaKind.Image)
        if (warnings.isNotEmpty()) {
            mutableEditorCopyNotice.emit(
                warnings.distinct().joinToString("\n") { warning ->
                    getApplication<Application>().getString(
                        when (warning) {
                            PhotoExportWarning.FullResolutionPng ->
                                com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_warning_png
                            PhotoExportWarning.HdrMetadataNotCopied ->
                                com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_warning_hdr_metadata
                        },
                    )
                },
            )
        }
    }

    private fun photoExportFailureMessage(failure: PhotoExportOutcome.Failure): String =
        getApplication<Application>().getString(
            when (failure.kind) {
                PhotoExportFailureKind.DecodeFailed -> com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_decode
                PhotoExportFailureKind.Failed -> com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_save
            },
        )

    private fun rawExportFailureMessage(failure: RawExportOutcome.Failure): String {
        val resources = getApplication<Application>().resources
        return when (failure.kind) {
            RawExportFailureKind.InsufficientStorage -> resources.getQuantityString(
                com.librestatic.lightforge.feature.photoeditor.R.plurals.photo_editor_error_raw_storage,
                failure.requiredMegabytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                failure.requiredMegabytes,
            )
            RawExportFailureKind.TooLargeForJpeg ->
                resources.getString(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_raw_too_large)
            RawExportFailureKind.RenderFailed ->
                resources.getString(com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_error_raw_render)
        }
    }

    private fun updatePhotoExportFailure(reason: String) {
        mutablePhotoEditor.value = mutablePhotoEditor.value?.copy(
            content = mutablePhotoEditor.value!!.content.copy(isExporting = false, statusMessage = reason),
        )
    }

    fun closePhotoEditor() {
        val session = mutablePhotoEditor.value
        photoEditorOpenGeneration++
        mutablePhotoEditorOpening.value = false
        photoEditorJob?.cancel()
        photoAutoEnhancementJob?.cancel()
        photoAutoEnhancementGeneration++
        val pendingRecipeWrite = photoRecipeJob
        photoRecipeJob = null
        pendingRecipeWrite?.cancel()
        closeRawPreviewSession()
        photoExperimentalJob?.cancel()
        releasePhotoInpainting()
        recycleExperimentalPreviews(session?.content)
        if (session?.source?.libraryMedia != null) {
            val previousDiscard = photoRecipeDiscardJob
            photoRecipeDiscardJob = viewModelScope.launch {
                previousDiscard?.join()
                pendingRecipeWrite?.join()
                restorePhotoRecipeEntry(session)
            }
        }
        session?.content?.preview?.recycle()
        session?.content?.originalPreview?.takeIf { it !== session.content.preview }?.recycle()
        session?.content?.cropSourcePreview?.takeIf {
            it !== session.content.preview && it !== session.content.originalPreview
        }?.recycle()
        mutablePhotoEditor.value = null
    }

    private suspend fun restorePhotoRecipeEntry(session: PhotoEditorSession) {
        val media = session.source.libraryMedia ?: return
        val active = runtime.value ?: return
        withContext(Dispatchers.IO) {
            active.database.withTransaction {
                val row = active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)
                // A deleted/replaced source is not resurrected by editor cleanup.
                if (row == null || row.generationModified != media.generationModified) return@withTransaction
                val dao = active.database.editRecipeDao()
                val entry = session.entryRecipe
                if (entry == null) dao.deleteRecipe(session.history.present.recipeId)
                else if (dao.load(entry.recipeId) != entry) dao.replace(entry, session.entryRecipeUpdatedAtMillis)
            }
        }
    }

    private fun closeRawPreviewSession() {
        rawPreviewRequests?.close()
        rawPreviewRequests = null
        rawPreviewJob?.cancel()
        rawPreviewJob = null
        rawPreviewSession?.close()
        rawPreviewSession = null
        rawPreviewProfile = null
        rawPreviewGeneration++
    }

    private fun startRawPreviewWorker(
        source: EditorMediaSource,
        stagedRaw: RawPreviewSession,
    ) {
        rawPreviewRequests?.close()
        rawPreviewJob?.cancel()
        rawPreviewProfile = RawPreviewPolicy.detect(getApplication())
        val requests = Channel<RawPreviewRequest>(Channel.CONFLATED)
        rawPreviewRequests = requests
        rawPreviewJob = viewModelScope.launch {
            var lastStartedAt = 0L
            for (request in requests) {
                val remaining = request.minimumIntervalMillis -
                    (SystemClock.elapsedRealtime() - lastStartedAt)
                if (remaining > 0) delay(remaining)
                lastStartedAt = SystemClock.elapsedRealtime()
                var cropSource: Bitmap? = null
                val rendered = try {
                    val developed = stagedRaw.renderPreview(request.settings, request.maxDimension)
                    val cropOperations = request.recipe.operations.filter {
                        it is EditOperation.Rotate || it is EditOperation.Flip
                    }
                    val cropInput = if (request.final) developed.copy(Bitmap.Config.ARGB_8888, false) else null
                    cropSource = cropInput?.let {
                        PhotoImageRenderer(getApplication<Application>().contentResolver).renderDecoded(
                            it,
                            request.recipe.copy(operations = cropOperations),
                        )
                    }
                    PhotoImageRenderer(getApplication<Application>().contentResolver).renderDecoded(
                        developed,
                        request.recipe.copy(
                            operations = photoBaseOperations(request.recipe)
                                .filterNot { it is EditOperation.RawDevelop },
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    cropSource?.recycle()
                    throw cancelled
                } catch (failure: Throwable) {
                    cropSource?.recycle()
                    if (request.generation == rawPreviewGeneration) {
                        mutablePhotoEditor.value = mutablePhotoEditor.value?.let { current ->
                            current.copy(content = current.content.copy(
                                isRendering = false,
                                statusMessage = getApplication<Application>().getString(
                                    com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_preview_unavailable,
                                ),
                            ))
                        }
                    }
                    continue
                }
                val current = mutablePhotoEditor.value
                if (
                    request.generation == rawPreviewGeneration &&
                    current?.source?.stableId == source.stableId
                ) {
                    current.content.preview?.takeIf { old ->
                        old !== rendered && old !== current.content.originalPreview &&
                            old !== current.content.cropSourcePreview
                    }?.recycle()
                    if (cropSource != null) {
                        current.content.cropSourcePreview?.takeIf { old ->
                            old !== current.content.preview && old !== current.content.originalPreview &&
                                old !== cropSource
                        }?.recycle()
                    }
                    mutablePhotoEditor.value = current.copy(
                        content = current.content.copy(
                            preview = rendered,
                            cropSourcePreview = cropSource ?: current.content.cropSourcePreview,
                            isRendering = false,
                            statusMessage = null,
                        ),
                    )
                } else {
                    rendered.recycle()
                    cropSource?.takeIf { it !== rendered }?.recycle()
                }
            }
        }
    }

    private fun enqueueRawPreview(
        settings: RawDevelopmentSettings,
        recipe: EditRecipe,
        final: Boolean,
    ) {
        val profile = rawPreviewProfile ?: return
        val generation = ++rawPreviewGeneration
        rawPreviewRequests?.trySend(
            RawPreviewRequest(
                generation = generation,
                settings = settings,
                recipe = recipe,
                maxDimension = if (final) 1_600 else profile.maxDimension,
                minimumIntervalMillis = if (final) 0 else profile.minimumIntervalMillis,
                final = final,
            ),
        )
    }

    private suspend fun renderPhotoEditorPreview(
        active: GalleryRuntime,
        source: EditorMediaSource,
        recipe: EditRecipe,
    ) {
        val currentBeforeRender = mutablePhotoEditor.value
        val baseRecipe = recipe.copy(operations = photoBaseOperations(recipe))
        val preview = try {
            if (currentBeforeRender?.content?.isRaw == true) {
                val developed = rawPreviewSession?.renderPreview(
                    currentBeforeRender.content.rawSettings,
                    1_600,
                ) ?: RawDeveloper(
                    getApplication<Application>().contentResolver,
                    java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                ).renderPreview(source.uri, currentBeforeRender.content.rawSettings, 1_600)
                PhotoImageRenderer(getApplication<Application>().contentResolver).renderDecoded(
                    developed,
                    baseRecipe.copy(operations = baseRecipe.operations.filterNot { it is EditOperation.RawDevelop }),
                )
            } else PhotoImageRenderer(getApplication<Application>().contentResolver)
                .renderPreview(source.uri, baseRecipe, 1_600)
        } catch (failure: Throwable) {
            val current = mutablePhotoEditor.value
            if (current?.history?.present?.revision == recipe.revision) {
                mutablePhotoEditor.value = current.copy(
                    content = current.content.copy(
                        isRendering = false,
                        statusMessage = getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.photoeditor.R.string.photo_editor_preview_unavailable,
                        ),
                    ),
                )
            }
            return
        }
        val originalPreview = if (currentBeforeRender?.content?.originalPreview == null) {
            runCatching {
                if (currentBeforeRender?.content?.isRaw == true) {
                    rawPreviewSession?.renderPreview(RawDevelopmentSettings(), 1_600)
                        ?: RawDeveloper(
                            getApplication<Application>().contentResolver,
                            java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                        ).renderPreview(source.uri, RawDevelopmentSettings(), 1_600)
                } else {
                    PhotoImageRenderer(getApplication<Application>().contentResolver).renderPreview(
                        source.uri,
                        recipe.copy(operations = emptyList(), revision = 0),
                        1_600,
                    )
                }
            }.getOrNull()
        } else currentBeforeRender.content.originalPreview
        val cropSourceRecipe = baseRecipe.copy(
            operations = recipe.operations.filter { it is EditOperation.Rotate || it is EditOperation.Flip },
        )
        val cropSourcePreview = runCatching {
            if (cropSourceRecipe.isIdentity) {
                originalPreview
            } else if (currentBeforeRender?.content?.isRaw == true) {
                val developed = rawPreviewSession?.renderPreview(
                    currentBeforeRender.content.rawSettings,
                    1_600,
                ) ?: RawDeveloper(
                    getApplication<Application>().contentResolver,
                    java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                ).renderPreview(source.uri, currentBeforeRender.content.rawSettings, 1_600)
                PhotoImageRenderer(getApplication<Application>().contentResolver).renderDecoded(
                    developed,
                    cropSourceRecipe,
                )
            } else PhotoImageRenderer(getApplication<Application>().contentResolver).renderPreview(
                source.uri, cropSourceRecipe, 1_600,
            )
        }.getOrNull()
        val current = mutablePhotoEditor.value
        if (current?.history?.present?.revision == recipe.revision) {
            current.content.preview?.takeIf { it !== preview }?.recycle()
            current.content.cropSourcePreview?.takeIf {
                it !== preview && it !== originalPreview && it !== cropSourcePreview
            }?.recycle()
            mutablePhotoEditor.value = current.copy(
                content = current.content.copy(
                    preview = preview,
                    originalPreview = originalPreview,
                    cropSourcePreview = cropSourcePreview,
                    isRendering = false,
                ),
            )
            if (!current.content.isRaw && current.content.autoEnhancementSuggestions == null) {
                startPhotoAutoEnhancementAnalysis(current.source.stableId, originalPreview ?: preview)
            }
            // Eraser marks kept across a geometry edit are re-applied to the new preview.
            mutablePhotoEditor.value?.takeIf { it.eraseMarks.isNotEmpty() && it.content.erasePreview == null }
                ?.let(::runObjectErase)
        } else {
            preview.recycle()
            cropSourcePreview?.takeIf { it !== originalPreview && it !== preview }?.recycle()
            originalPreview?.takeIf {
                it !== currentBeforeRender?.content?.originalPreview && it !== preview
            }?.recycle()
        }
    }

    private fun startPhotoAutoEnhancementAnalysis(sourceId: String, source: Bitmap) {
        photoAutoEnhancementJob?.cancel()
        val generation = ++photoAutoEnhancementGeneration
        photoAutoEnhancementJob = viewModelScope.launch(Dispatchers.Default) {
            val suggestions = runCatching { PhotoAutoEnhancementAnalyzer.analyze(source) }.getOrNull()
            val current = mutablePhotoEditor.value
            if (generation != photoAutoEnhancementGeneration || current?.source?.stableId != sourceId) return@launch
            mutablePhotoEditor.value = current.copy(
                content = current.content.copy(
                    autoEnhancementSuggestions = suggestions,
                    isAutoEnhancementAnalyzing = false,
                ),
            )
        }
    }

    private fun VideoEditorSession.withVideoExportState(
        jobs: List<VideoExportJob>,
    ): VideoEditorSession {
        val job = if (recovered) jobs.firstOrNull { it.id == exportJobId && it.inputUri == source.uriString }
            else trackedVideoExport(jobs, exportJobId, source.uriString)
        if (job == null) return this
        val exportedRecipe = pendingExportRecipe
            ?.takeIf { exportJobId == null || exportJobId == job.id }
            ?: if (recovered) runCatching { VideoEditorRestoreSnapshot.decodeRecipeExact(job.encodedRecipe) }.getOrNull()
            else runCatching { VideoEditRecipeCodec.decode(job.encodedRecipe) }.getOrNull()
                ?.normalizedForEditor(content.durationMillis, content.isHevcMain10Available)
        val nextBaseline = if (job.status == VideoExportJobStatus.Completed) {
            exportedRecipe ?: baselineRecipe
        } else {
            baselineRecipe
        }
        val nextPending = exportedRecipe.takeIf { job.isActive }
        val statusMessage = when (job.status) {
            VideoExportJobStatus.Queued -> getApplication<Application>().getString(R.string.video_export_queued)
            VideoExportJobStatus.Completed -> getApplication<Application>().getString(
                if (job.usedSoftwareCodec || job.usedEncoderFallback) {
                    com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_encoder_fallback
                } else com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_copy_saved,
            )
            VideoExportJobStatus.Failed -> job.error
                ?: getApplication<Application>().getString(R.string.video_export_failed)
            VideoExportJobStatus.Cancelled ->
                getApplication<Application>().getString(R.string.video_export_cancelled)
            VideoExportJobStatus.Running -> null
        }
        return copy(
            baselineRecipe = nextBaseline,
            pendingExportRecipe = nextPending,
            exportJobId = job.id,
            content = content.copy(
                isExporting = job.isActive,
                isDirty = recipe != (nextPending ?: nextBaseline),
                exportProgress = job.progressPermille / 1000f,
                exportPhase = job.phase,
                usedSoftwareCodec = job.usedSoftwareCodec,
                statusMessage = statusMessage,
            ),
        )
    }

    fun openVideoEditor(media: TimelineMedia) {
        if (media.kind != MediaKind.Video) return
        mutableCurrentMedia.value = media
        captureViewerRecovery(media)
        openVideoEditor(EditorMediaSource(
            uriString = mediaUri(media).toString(), kind = media.kind, libraryMedia = media,
            displayName = media.displayName, width = media.width, height = media.height,
            durationMillis = media.durationMillis,
        ))
    }

    private fun openVideoEditor(source: EditorMediaSource, restoring: VideoEditorDraftRestore? = null) {
        val externalRestoring = restoring as? ExternalVideoEditorRestoreSnapshot
        val libraryRestoring = restoring as? VideoEditorRestoreSnapshot
        externalVideoAccessJob?.cancel()
        pendingRestoredExternalVideoEditor = null
        externalVideoEditorSnapshot = externalRestoring
        videoEditorJob?.cancel()
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        videoAnnotationUndo.clear()
        videoAnnotationRedo.clear()
        videoEditHistory.clear()
        val generation = ++videoEditorOpenGeneration
        val sessionId = restoring?.id ?: java.util.UUID.randomUUID().toString()
        pendingRestoredVideoEditor = null
        videoEditorSnapshot = libraryRestoring
        mutableVideoEditorSessionId.value = sessionId
        mutableVideoEditor.value = null
        saveCreationState()
        val pendingDiscard = videoRecipeDiscardJob
        val sourceQuery = libraryRestoring?.source?.query ?: viewerQuery
        mutableVideoEditorOpening.value = true
        videoEditorJob = viewModelScope.launch {
            try {
                pendingDiscard?.join()
                if (generation != videoEditorOpenGeneration) return@launch
                val active = runtime.value ?: return@launch
                val permission = access.value
                val recoverySource = source.libraryMedia?.let { media -> withContext(Dispatchers.IO) {
                    val row = active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)
                        ?: return@withContext null
                    val actual = com.librestatic.lightforge.core.mediastore.MediaStoreReader(getApplication<Application>().contentResolver)
                        .readOne(media.key) ?: return@withContext null
                    if (!row.isAccessible || row.mediaType != 3 || actual.kind != MediaKind.Video || actual.isTrashed ||
                        row.isTrashed || actual.key != media.key || row.generationModified != media.generationModified ||
                        row.generationModified != actual.generationModified || row.generationAdded != actual.generationAdded ||
                        actual.durationMillis != source.durationMillis || source.durationMillis <= 0) return@withContext null
                    ViewerRestoreSnapshot(media.key, MediaKind.Video, actual.generationModified, actual.generationAdded, false, sourceQuery)
                } }
                if (source.libraryMedia != null && recoverySource == null) return@launch
                if (libraryRestoring != null && (recoverySource != libraryRestoring.source || source.durationMillis != libraryRestoring.durationMillis)) return@launch
                val externalProof = if (source.libraryMedia != null) null else externalRestoring?.source
                    ?: withContext(Dispatchers.IO) {
                        val fingerprint = externalVideoFingerprint {
                            requireNotNull(getApplication<Application>().contentResolver.openInputStream(source.uri))
                        }
                        ExternalVideoSourceSnapshot.validatedCopy(ExternalVideoSourceSnapshot(
                            source.uriString, source.mimeType ?: "video/*", source.displayName ?: source.uriString,
                            source.width, source.height, source.durationMillis, fingerprint.sizeBytes, fingerprint.sha256,
                        ))
                    }
                val storedEntity = if (restoring != null) null else source.libraryMedia?.let { media -> withContext(Dispatchers.IO) {
                    active.database.colorEditDao().videoRecipe(
                        media.key.volumeName, media.key.mediaStoreId, media.generationModified,
                    )
                } }
                val stored = storedEntity
                    ?.let { runCatching { VideoEditRecipeCodec.decode(it.encodedRecipe) }.getOrNull() }
                val inputUri = source.uriString
                val activeExport = if (restoring != null) videoExportStore.jobs.value.firstOrNull {
                    it.id == restoring.exportJobId && it.inputUri == inputUri
                } else activeVideoExportForInput(videoExportStore.jobs.value, inputUri)
                val exportedRecipe = activeExport?.let { job ->
                    runCatching { VideoEditRecipeCodec.decode(job.encodedRecipe) }.getOrNull()
                }
                val detection = if (restoring == null) LogProfileDetector(getApplication<Application>()).detect(source.uri)
                    else com.librestatic.lightforge.core.editing.video.LogProfileDetection(
                        VideoEditorRestoreSnapshot.decodeRecipeExact(restoring.recipe).colorGrade.inputProfile, 0f, "",
                    )
                val initialRecipe = VideoEditRecipe(
                    colorGrade = VideoColorGrade(
                        inputProfile = detection.profile,
                        profileWasAutoDetected = detection.confidence >= 0.8f,
                    ),
                )
                val supportsMain10 = VideoOutputCapabilities.supportsHevcMain10()
                val hdrCapabilities = VideoOutputCapabilities.hdr(getApplication<Application>())
                val loadedRecipe = restoring?.let { VideoEditorRestoreSnapshot.decodeRecipeExact(it.recipe) } ?: (preferredVideoEditorRecipe(
                    storedRecipe = stored,
                    storedUpdatedAtMillis = storedEntity?.updatedAtMillis,
                    activeExportRecipe = exportedRecipe,
                    activeExportCreatedAtMillis = activeExport?.createdAtMillis,
                ) ?: initialRecipe)
                    .normalizedForEditor(
                        source.durationMillis,
                        supportsMain10,
                        hdrCapabilities.hlg,
                        hdrCapabilities.hdr10,
                    )
                val pendingExportRecipe = if (restoring != null) restoring.pendingExportRecipe?.let(VideoEditorRestoreSnapshot::decodeRecipeExact)
                    else exportedRecipe?.normalizedForEditor(
                        source.durationMillis,
                        supportsMain10,
                        hdrCapabilities.hlg,
                        hdrCapabilities.hdr10,
                    )
                val lutRepository = lutRepository(active)
                val requestedCustomLutId = loadedRecipe.colorGrade.lut.customId
                val customLut = requestedCustomLutId?.let { customId ->
                    runCatching { lutRepository.load(customId) }.getOrNull()
                }
                val customLutUnavailable = requestedCustomLutId != null && customLut == null
                if (libraryRestoring != null) {
                    if (customLutUnavailable) return@launch
                    val musicAvailable = withContext(Dispatchers.IO) {
                        loadedRecipe.musicUri?.let { uri -> runCatching {
                            getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                        }.getOrDefault(false) } ?: true
                    }
                    if (!musicAvailable) return@launch
                }
                val recipe = if (customLutUnavailable && externalRestoring == null) {
                    loadedRecipe.copy(colorGrade = loadedRecipe.colorGrade.copy(lut = LutReference()))
                } else {
                    loadedRecipe
                }
                val baselineRecipe = restoring?.let { VideoEditorRestoreSnapshot.decodeRecipeExact(it.baselineRecipe) } ?: when {
                    activeExport != null -> initialRecipe
                    stored == null -> recipe
                    else -> VideoEditRecipe()
                }
                val session = VideoEditorSession(
                    id = sessionId,
                    recoverySource = recoverySource,
                    recovered = restoring != null,
                    externalRecoverySource = externalProof,
                    externalAccessBlocked = externalRestoring != null,
                    source = source,
                    baselineRecipe = baselineRecipe,
                    recipe = recipe,
                    pendingExportRecipe = pendingExportRecipe,
                    exportJobId = restoring?.exportJobId ?: activeExport?.id,
                    content = VideoEditorContentState(
                        currentMillis = restoring?.positionMillis ?: recipe.startMillis,
                        selectedAnnotationId = restoring?.selectedAnnotationId,
                        selectedSlowMotionSegmentId = restoring?.selectedSlowMotionSegmentId,
                        slowMotionMarkInMillis = restoring?.slowMotionMarkInMillis,
                        annotationTrackingCorrectionMillis = restoring?.annotationTrackingCorrectionMillis,
                        durationMillis = source.durationMillis,
                        trimStartMillis = recipe.startMillis,
                        trimEndMillis = recipe.endMillis ?: source.durationMillis,
                        speed = recipe.speed,
                        originalAudioVolume = recipe.originalAudioVolume,
                        selectedMusicName = recipe.musicUri?.lastPathSegment,
                        selectedMusicUri = recipe.musicUri,
                        musicVolume = recipe.musicVolume,
                        colorGrade = recipe.colorGrade,
                        customLuts = lutRepository.summaries(),
                        activeCustomLut = customLut,
                        outputQuality = recipe.outputQuality,
                        dynamicRange = recipe.dynamicRange,
                        geometry = recipe.geometry,
                        isHevcMain10Available = supportsMain10,
                        isHlgExportAvailable = hdrCapabilities.hlg,
                        isHdr10ExportAvailable = hdrCapabilities.hdr10,
                        slowMotionSegments = recipe.slowMotionSegments,
                        annotations = recipe.annotations,
                        output = recipe.output,
                        isDirty = recipe != (pendingExportRecipe ?: baselineRecipe),
                        statusMessage = if (customLutUnavailable) {
                            getApplication<Application>().getString(
                                com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_lut_unavailable,
                            )
                        } else null,
                        logDetectionMessage = if (detection.confidence >= 0.8f) {
                            getApplication<Application>().getString(
                                com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_detected_profile,
                                getApplication<Application>().getString(detection.profile.labelResource()),
                            )
                        } else getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_profile_unknown,
                        ),
                    ),
                )
                if (generation == videoEditorOpenGeneration && runtime.value === active && permission == access.value) {
                    restoring?.undoAnnotations?.forEach { videoAnnotationUndo.addLast(VideoEditorRestoreSnapshot.decodeAnnotationsExact(it)) }
                    restoring?.redoAnnotations?.forEach { videoAnnotationRedo.addLast(VideoEditorRestoreSnapshot.decodeAnnotationsExact(it)) }
                    videoEditHistory.seedAnnotationOrder(videoAnnotationUndo.size, videoAnnotationRedo.size)
                    mutableVideoEditor.value = session.withVideoExportState(videoExportStore.jobs.value)
                    canUseExternalVideoSource(requireNotNull(mutableVideoEditor.value))
                    captureVideoEditorRecovery()
                    loadVideoOutputSource(sessionId, source.uri)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Preserve a source-bound unavailable draft and Back; never substitute a default recipe.
            } finally {
                if (generation == videoEditorOpenGeneration) {
                    mutableVideoEditorOpening.value = false
                }
            }
        }
    }

    fun setVideoTrim(startMillis: Long, endMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val duration = session.content.durationMillis.coerceAtLeast(1)
        val start = startMillis.coerceIn(0, duration - 1)
        val end = endMillis.coerceIn(start + 1, duration)
        if (start == session.recipe.startMillis &&
            end == (session.recipe.endMillis ?: duration)
        ) return
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        val recipe = session.recipe.withTrimRange(start, end).let { trimmed ->
            if (end == duration) trimmed.copy(endMillis = null) else trimmed
        }
        val adjustedSegments = recipe.slowMotionSegments
        val adjustedAnnotations = recipe.annotations
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                currentMillis = session.content.currentMillis.takeIf { it in start until end } ?: start,
                isDirty = session.isDirty(recipe),
                trimStartMillis = start,
                trimEndMillis = end,
                slowMotionSegments = adjustedSegments,
                annotations = adjustedAnnotations,
                slowMotionMarkInMillis = session.content.slowMotionMarkInMillis
                    ?.takeIf { it in start until end },
                selectedAnnotationId = session.content.selectedAnnotationId
                    ?.takeIf { id -> adjustedAnnotations.any { it.id == id } },
                selectedSlowMotionSegmentId = session.content.selectedSlowMotionSegmentId
                    ?.takeIf { id -> adjustedSegments.any { it.id == id } },
                annotationTrackingProgress = null,
                annotationTrackingCorrectionMillis = session.content.annotationTrackingCorrectionMillis
                    ?.takeIf { it in start until end },
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoSpeed(speed: Float) {
        val session = mutableVideoEditor.value ?: return
        val safeSpeed = speed.coerceIn(0.25f, 4f)
        val recipe = session.recipe.copy(speed = safeSpeed)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(speed = safeSpeed, isDirty = session.isDirty(recipe)),
        )
        persistVideoRecipe(recipe)
    }

    fun markVideoSlowMotionIn(positionMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val position = positionMillis.coerceIn(
            session.content.trimStartMillis,
            (session.content.trimEndMillis - 1).coerceAtLeast(session.content.trimStartMillis),
        )
        mutableVideoEditor.value = session.copy(
            content = session.content.copy(slowMotionMarkInMillis = position, statusMessage = null),
        )
        captureVideoEditorRecovery()
    }

    fun markVideoSlowMotionOut(positionMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val start = session.content.slowMotionMarkInMillis ?: return
        val end = positionMillis.coerceAtMost(session.content.trimEndMillis)
        if (end <= start) {
            mutableVideoEditor.value = session.copy(content = session.content.copy(
                statusMessage = getApplication<Application>().getString(
                    com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_slow_invalid_range,
                ),
            ))
            return
        }
        val candidate = SlowMotionSegment(startMillis = start, endMillis = end)
        if (session.recipe.slowMotionSegments.any { it.startMillis < end && start < it.endMillis }) {
            mutableVideoEditor.value = session.copy(content = session.content.copy(
                statusMessage = getApplication<Application>().getString(
                    com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_slow_overlap,
                ),
            ))
            return
        }
        val segments = (session.recipe.slowMotionSegments + candidate).sortedBy(SlowMotionSegment::startMillis)
        val recipe = session.recipe.copy(slowMotionSegments = segments)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                isDirty = session.isDirty(recipe),
                slowMotionSegments = segments,
                selectedSlowMotionSegmentId = candidate.id,
                slowMotionMarkInMillis = null,
                statusMessage = null,
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun selectVideoSlowMotionSegment(id: String) {
        val session = mutableVideoEditor.value ?: return
        if (session.recipe.slowMotionSegments.none { it.id == id }) return
        mutableVideoEditor.value = session.copy(
            content = session.content.copy(selectedSlowMotionSegmentId = id),
        )
        captureVideoEditorRecovery()
    }

    fun updateVideoSlowMotionSegment(segment: SlowMotionSegment) {
        val session = mutableVideoEditor.value ?: return
        val trimEnd = session.recipe.endMillis ?: session.content.durationMillis
        if (session.recipe.slowMotionSegments.none { it.id == segment.id } ||
            segment.startMillis < session.recipe.startMillis || segment.endMillis > trimEnd
        ) return
        val replacement = session.recipe.slowMotionSegments.map {
            if (it.id == segment.id) segment else it
        }.sortedBy(SlowMotionSegment::startMillis)
        if (replacement == session.recipe.slowMotionSegments ||
            replacement.zipWithNext().any { (left, right) -> left.endMillis > right.startMillis }
        ) return
        val recipe = session.recipe.copy(slowMotionSegments = replacement)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(slowMotionSegments = replacement, statusMessage = null, isDirty = session.isDirty(recipe)),
        )
        persistVideoRecipe(recipe)
    }

    fun deleteVideoSlowMotionSegment(id: String) {
        val session = mutableVideoEditor.value ?: return
        val segments = session.recipe.slowMotionSegments.filterNot { it.id == id }
        if (segments.size == session.recipe.slowMotionSegments.size) return
        val recipe = session.recipe.copy(slowMotionSegments = segments)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                slowMotionSegments = segments,
                isDirty = session.isDirty(recipe),
                selectedSlowMotionSegmentId = null,
                statusMessage = null,
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun cancelVideoExport() {
        cancelVideoEditorExport()
    }

    fun saveQuickSlowMotionClip(media: TimelineMedia, startMillis: Long, endMillis: Long) {
        val safeStart = startMillis.coerceIn(0L, (media.durationMillis - 1L).coerceAtLeast(0L))
        val safeEnd = endMillis.coerceAtMost(media.durationMillis)
        if (media.kind != MediaKind.Video || safeEnd <= safeStart || slowMotionSaveJob?.isActive == true) return
        slowMotionSaveJob = viewModelScope.launch {
            val temp = java.io.File(getApplication<Application>().cacheDir, "instant-slow-${System.nanoTime()}.mp4")
            mutableQuickSlowMotionSave.value = mutableQuickSlowMotionSave.value.copy(progress = 0f)
            try {
                val segment = SlowMotionSegment(
                    startMillis = safeStart,
                    endMillis = safeEnd,
                    speed = 0.25f,
                )
                Media3VideoExporter(getApplication<Application>()).export(
                    VideoExportRequest(
                        input = mediaUri(media),
                        output = temp,
                        recipe = VideoEditRecipe(
                            startMillis = safeStart,
                            endMillis = safeEnd,
                            slowMotionSegments = listOf(segment),
                        ),
                        onProgress = { progress ->
                            progress.fraction?.let { fraction ->
                                mutableQuickSlowMotionSave.value = mutableQuickSlowMotionSave.value.copy(progress = fraction)
                            }
                        },
                    ),
                )
                val published = PendingMediaWriter(getApplication<Application>().contentResolver).publishFile(
                    temp,
                    MediaWriteSpec(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        MediaKind.Video,
                        "Lightforge-slow-motion-${System.currentTimeMillis()}.mp4",
                        "video/mp4",
                        "Movies/Lightforge",
                    ),
                )
                mutableQuickSlowMotionSave.value = QuickSlowMotionSaveState(
                    completionGeneration = mutableQuickSlowMotionSave.value.completionGeneration + 1,
                )
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.e("LightforgeSlowMotion", "Quick slow-motion export failed", failure)
                mutableQuickSlowMotionSave.value = mutableQuickSlowMotionSave.value.copy(progress = null)
                mutableShareError.emit(
                    getApplication<Application>().getString(
                        com.librestatic.lightforge.feature.viewer.R.string.viewer_slow_motion_save_failed,
                    ),
                )
            } finally {
                temp.delete()
            }
        }
    }

    fun setVideoOriginalVolume(volume: Float) {
        val session = mutableVideoEditor.value ?: return
        val safeVolume = volume.coerceIn(0f, 1f)
        val recipe = session.recipe.copy(originalAudioVolume = safeVolume)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(originalAudioVolume = safeVolume, isDirty = session.isDirty(recipe)),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoMusic(uri: Uri, displayName: String) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(musicUri = uri)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                selectedMusicName = displayName,
                selectedMusicUri = uri,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun removeVideoMusic() {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(musicUri = null)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                selectedMusicName = null,
                selectedMusicUri = null,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoMusicVolume(volume: Float) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(musicVolume = volume.coerceIn(0f, 1f))
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                musicVolume = recipe.musicVolume,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoColorGrade(grade: VideoColorGrade) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(colorGrade = grade)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                colorGrade = grade,
                activeCustomLut = null,
                statusMessage = null,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
        val customId = grade.lut.customId ?: return
        viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val lut = runCatching { lutRepository(active).load(customId) }.getOrNull()
            val current = mutableVideoEditor.value
            if (current?.recipe?.colorGrade?.lut?.customId == customId) {
                if (lut != null) {
                    mutableVideoEditor.value = current.copy(
                        content = current.content.copy(activeCustomLut = lut),
                    )
                } else {
                    val safeGrade = current.recipe.colorGrade.copy(lut = LutReference())
                    val safeRecipe = current.recipe.copy(colorGrade = safeGrade)
                    mutableVideoEditor.value = current.copy(
                        recipe = safeRecipe,
                        content = current.content.copy(
                            colorGrade = safeGrade,
                            activeCustomLut = null,
                            customLuts = current.content.customLuts.filterNot { it.id == customId },
                            statusMessage = getApplication<Application>().getString(
                                com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_lut_unavailable,
                            ),
                            isDirty = current.isDirty(safeRecipe),
                        ),
                    )
                    persistVideoRecipe(safeRecipe)
                }
            }
        }
    }

    fun setVideoOutputQuality(quality: VideoOutputQuality) {
        val session = mutableVideoEditor.value ?: return
        if (quality == VideoOutputQuality.HevcMain10 && !session.content.isHevcMain10Available) return
        val recipe = session.recipe.copy(
            outputQuality = quality,
            dynamicRange = if (quality == VideoOutputQuality.H264Compatible) {
                VideoDynamicRange.SdrRec709
            } else session.recipe.dynamicRange,
        )
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                outputQuality = quality,
                dynamicRange = recipe.dynamicRange,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoDynamicRange(dynamicRange: VideoDynamicRange) {
        val session = mutableVideoEditor.value ?: return
        val supported = when (dynamicRange) {
            VideoDynamicRange.SdrRec709 -> true
            VideoDynamicRange.HdrHlg -> session.content.isHlgExportAvailable
            VideoDynamicRange.Hdr10Pq -> session.content.isHdr10ExportAvailable
        }
        if (!supported) return
        val outputQuality = if (dynamicRange == VideoDynamicRange.SdrRec709) {
            session.recipe.outputQuality
        } else VideoOutputQuality.HevcMain10
        val recipe = session.recipe.copy(dynamicRange = dynamicRange, outputQuality = outputQuality)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                dynamicRange = dynamicRange,
                outputQuality = outputQuality,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    /**
     * Probes the source and the device encoders for the Output tool once the editor is showing:
     * the summary, estimate and codec choice fill in when ready, without delaying the editor.
     */
    private fun loadVideoOutputSource(sessionId: String, uri: Uri) {
        viewModelScope.launch {
            val encoders = withContext(Dispatchers.Default) { VideoOutputCapabilities.encoders() }
            val info = VideoSourceInfoReader.read(getApplication(), uri)
            val session = mutableVideoEditor.value?.takeIf { it.id == sessionId } ?: return@launch
            mutableVideoEditor.value = session.copy(
                content = session.content.copy(
                    outputSource = info,
                    outputEncoders = encoders,
                    supportedOutputCodecs = com.librestatic.lightforge.core.editing.video.VideoOutputCodec.entries
                        .filter(encoders::supports).toSet(),
                ),
            )
        }
    }

    fun setVideoOutputSettings(settings: com.librestatic.lightforge.core.editing.video.VideoOutputSettings) {
        val session = mutableVideoEditor.value ?: return
        val codecs = session.content.supportedOutputCodecs
        if (codecs != null && settings.codec != com.librestatic.lightforge.core.editing.video.VideoOutputCodec.Auto &&
            settings.codec !in codecs
        ) return
        if (settings == session.recipe.output) return
        val recipe = session.recipe.copy(output = settings)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(output = settings, isDirty = session.isDirty(recipe)),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoGeometry(geometry: VideoGeometry) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(geometry = geometry)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                geometry = geometry,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun addVideoAnnotation(layer: VideoAnnotationLayer) {
        val session = mutableVideoEditor.value ?: return
        if (layer.startMillis < session.recipe.startMillis ||
            layer.endMillis > (session.recipe.endMillis ?: session.content.durationMillis)
        ) return
        commitVideoAnnotations(session.recipe.annotations + layer, layer.id)
    }

    fun updateVideoAnnotation(layer: VideoAnnotationLayer) {
        val session = mutableVideoEditor.value ?: return
        if (session.recipe.annotations.none { it.id == layer.id } ||
            layer.startMillis < session.recipe.startMillis ||
            layer.endMillis > (session.recipe.endMillis ?: session.content.durationMillis)
        ) return
        commitVideoAnnotations(session.recipe.annotations.map { if (it.id == layer.id) layer else it }, layer.id)
    }

    fun eraseVideoAnnotations(points: List<NormalizedPoint>, timeMillis: Long) {
        if (points.isEmpty()) return
        val session = mutableVideoEditor.value ?: return
        val updated = session.recipe.annotations.flatMap { layer ->
            if (timeMillis !in layer.startMillis until layer.endMillis) return@flatMap listOf(layer)
            if (layer.shape != VideoAnnotationShape.Freehand) {
                val left = layer.points.minOf(NormalizedPoint::x) - AnnotationEraserRadius
                val right = layer.points.maxOf(NormalizedPoint::x) + AnnotationEraserRadius
                val top = layer.points.minOf(NormalizedPoint::y) - AnnotationEraserRadius
                val bottom = layer.points.maxOf(NormalizedPoint::y) + AnnotationEraserRadius
                if (points.any { it.x in left..right && it.y in top..bottom }) emptyList() else listOf(layer)
            } else {
                val groups = mutableListOf<MutableList<NormalizedPoint>>()
                layer.points.forEach { layerPoint ->
                    val erased = points.any { eraserPoint ->
                        val dx = layerPoint.x - eraserPoint.x
                        val dy = layerPoint.y - eraserPoint.y
                        dx * dx + dy * dy <= AnnotationEraserRadius * AnnotationEraserRadius
                    }
                    if (erased) {
                        if (groups.lastOrNull()?.isNotEmpty() == true) groups.add(mutableListOf())
                    } else {
                        if (groups.isEmpty()) groups.add(mutableListOf())
                        groups.last() += layerPoint
                    }
                }
                groups.filter { it.size >= 2 }.mapIndexed { index, segment ->
                    layer.copy(
                        id = if (index == 0) layer.id else java.util.UUID.randomUUID().toString(),
                        points = segment,
                    )
                }
            }
        }
        if (updated != session.recipe.annotations) commitVideoAnnotations(updated, null)
    }

    fun selectVideoAnnotation(id: String?) {
        val session = mutableVideoEditor.value ?: return
        if (id != null && session.recipe.annotations.none { it.id == id }) return
        mutableVideoEditor.value = session.copy(content = session.content.copy(selectedAnnotationId = id))
        captureVideoEditorRecovery()
    }

    fun deleteVideoAnnotation(id: String) {
        val session = mutableVideoEditor.value ?: return
        val updated = session.recipe.annotations.filterNot { it.id == id }
        if (updated.size == session.recipe.annotations.size) return
        commitVideoAnnotations(updated, null)
    }

    fun moveVideoAnnotation(id: String, delta: Int) {
        val session = mutableVideoEditor.value ?: return
        val index = session.recipe.annotations.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, session.recipe.annotations.lastIndex)
        if (target == index) return
        val updated = session.recipe.annotations.toMutableList()
        val layer = updated.removeAt(index)
        updated.add(target, layer)
        commitVideoAnnotations(updated, id)
    }

    fun clearVideoAnnotations() {
        if (mutableVideoEditor.value?.recipe?.annotations.isNullOrEmpty()) return
        commitVideoAnnotations(emptyList(), null)
    }

    /** Undoes the newest edit of any kind; the name predates the global history and callers still bind to it. */
    fun undoVideoAnnotation() = undoVideoEdit()

    fun redoVideoAnnotation() = redoVideoEdit()

    fun undoVideoEdit() {
        val session = mutableVideoEditor.value ?: return
        when (val step = videoEditHistory.undo(VideoEditHistory.Snapshot(session.recipe, session.content))) {
            is VideoEditHistory.Step.Restore -> applyVideoHistorySnapshot(step.snapshot)
            VideoEditHistory.Step.Annotation -> {
                val previous = videoAnnotationUndo.removeLastOrNull() ?: return
                pushBounded(videoAnnotationRedo, session.recipe.annotations)
                applyVideoAnnotations(previous, null)
            }
            null -> Unit
        }
    }

    fun redoVideoEdit() {
        val session = mutableVideoEditor.value ?: return
        when (val step = videoEditHistory.redo(VideoEditHistory.Snapshot(session.recipe, session.content))) {
            is VideoEditHistory.Step.Restore -> applyVideoHistorySnapshot(step.snapshot)
            VideoEditHistory.Step.Annotation -> {
                val next = videoAnnotationRedo.removeLastOrNull() ?: return
                pushBounded(videoAnnotationUndo, session.recipe.annotations)
                applyVideoAnnotations(next, null)
            }
            null -> Unit
        }
    }

    private fun applyVideoHistorySnapshot(snapshot: VideoEditHistory.Snapshot) {
        val session = mutableVideoEditor.value ?: return
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        if (snapshot.recipe != session.recipe) videoHistoryApplying = snapshot.recipe
        val content = with(VideoEditHistory) { session.content.withRecipeFieldsFrom(snapshot.content) }
        mutableVideoEditor.value = session.copy(
            recipe = snapshot.recipe,
            content = content.copy(isDirty = session.isDirty(snapshot.recipe)),
        )
        persistVideoRecipe(snapshot.recipe)
    }

    /**
     * Turns a recipe change into a history step. Drawing edits are recorded where they are
     * committed (they keep their own layer stacks), so they are ignored here; changes we applied
     * ourselves through undo/redo are skipped.
     */
    private fun trackVideoHistory(old: VideoEditorSession?, new: VideoEditorSession?) {
        if (new == null) return
        if (old != null && old.id == new.id && old.recipe != new.recipe) {
            val applying = videoHistoryApplying
            if (applying != null && applying == new.recipe) {
                videoHistoryApplying = null
            } else if (old.recipe.copy(annotations = emptyList()) != new.recipe.copy(annotations = emptyList())) {
                videoEditHistory.recordRecipeChange(
                    VideoEditHistory.Snapshot(old.recipe, old.content),
                    VideoEditHistory.changeKey(old.recipe, new.recipe),
                    SystemClock.elapsedRealtime(),
                )
                videoAnnotationRedo.clear()
            }
        }
        val canUndo = videoEditHistory.canUndo
        val canRedo = videoEditHistory.canRedo
        if (new.content.canUndo != canUndo || new.content.canRedo != canRedo) {
            mutableVideoEditor.value = new.copy(content = new.content.copy(canUndo = canUndo, canRedo = canRedo))
        }
    }

    fun addVideoAnnotationKeyframe(id: String, timeMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val layer = session.recipe.annotations.firstOrNull { it.id == id } ?: return
        val safeTime = timeMillis.coerceIn(layer.startMillis, layer.endMillis)
        val current = layer.transformAt(safeTime)
        val keyframes = (layer.keyframes.filterNot { it.timeMillis == safeTime } +
            VideoAnnotationKeyframe(safeTime, current)).sortedBy(VideoAnnotationKeyframe::timeMillis)
        updateVideoAnnotation(layer.copy(
            trackingMode = VideoAnnotationTrackingMode.Keyframes,
            keyframes = keyframes,
        ))
    }

    fun startVideoAnnotationTracking(id: String, seedTimeMillis: Long) =
        startVideoAnnotationTracking(id, seedTimeMillis, onTrackingProgress = {})

    /** Observer runs on the tracker IO thread, after real frame processing; it never supplies results. */
    internal fun startVideoAnnotationTracking(
        id: String,
        seedTimeMillis: Long,
        onTrackingProgress: (Float) -> Unit,
    ) {
        val session = mutableVideoEditor.value ?: return
        if (!canUseExternalVideoSource(session)) return
        val layer = session.recipe.annotations.firstOrNull { it.id == id } ?: return
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        mutableVideoEditor.value = session.copy(content = session.content.copy(
            annotationTrackingProgress = 0f,
            annotationTrackingCorrectionMillis = null,
            statusMessage = null,
        ))
        captureVideoEditorRecovery()
        val request = videoAnnotationTrackingEpoch.begin()
        fun isCurrent() = videoAnnotationTrackingEpoch.isCurrent(request) && mutableVideoEditor.value?.id == session.id
        fun canApplyTrackingResult(): Boolean {
            // A descriptor opened before READ revocation may still decode successfully. Recheck
            // ownership first, then current access immediately before any recipe/history mutation.
            if (!isCurrent()) return false
            val current = mutableVideoEditor.value ?: return false
            return canUseExternalVideoSource(current)
        }
        videoAnnotationTrackingJob = viewModelScope.launch {
            try {
                val trackedLayer = layer.copy(trackingMode = VideoAnnotationTrackingMode.Automatic)
                when (val result = VideoAnnotationTracker(getApplication<Application>()).track(
                    session.source.uri,
                    trackedLayer,
                    seedTimeMillis.coerceIn(layer.startMillis, layer.endMillis - 1),
                    onProgress = { progress ->
                        viewModelScope.launch {
                            if (isCurrent() && videoAnnotationTrackingJob?.isActive == true) mutableVideoEditor.value = mutableVideoEditor.value?.let { current ->
                                current.copy(content = current.content.copy(annotationTrackingProgress = progress))
                            }
                        }
                        onTrackingProgress(progress)
                    },
                )) {
                    is VideoAnnotationTrackingResult.Complete -> {
                        if (!canApplyTrackingResult()) return@launch
                        updateVideoAnnotation(trackedLayer.copy(keyframes = result.keyframes))
                        mutableVideoEditor.value = mutableVideoEditor.value?.let { current ->
                            current.copy(content = current.content.copy(annotationTrackingProgress = null))
                        }
                    }
                    is VideoAnnotationTrackingResult.NeedsCorrection -> {
                        if (!canApplyTrackingResult()) return@launch
                        updateVideoAnnotation(trackedLayer.copy(keyframes = result.completedKeyframes))
                        mutableVideoEditor.value = mutableVideoEditor.value?.let { current ->
                            current.copy(content = current.content.copy(
                                annotationTrackingProgress = null,
                                currentMillis = clampVideoPosition(result.timeMillis, current.content.trimStartMillis, current.content.trimEndMillis),
                                annotationTrackingCorrectionMillis = clampVideoPosition(result.timeMillis, current.content.trimStartMillis, current.content.trimEndMillis),
                                statusMessage = getApplication<Application>().getString(
                                    com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_tracking_needs_correction,
                                ),
                            ))
                        }
                    }
                }
                captureVideoEditorRecovery()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!canApplyTrackingResult()) return@launch
                mutableVideoEditor.value = mutableVideoEditor.value?.let { current ->
                    current.copy(content = current.content.copy(
                        annotationTrackingProgress = null,
                        statusMessage = getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_tracking_failed,
                        ),
                    ))
                }
            }
        }
    }

    fun cancelVideoAnnotationTracking() {
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        mutableVideoEditor.value = mutableVideoEditor.value?.let { session ->
            session.copy(content = session.content.copy(annotationTrackingProgress = null))
        }
    }

    private fun commitVideoAnnotations(updated: List<VideoAnnotationLayer>, selectedId: String?) {
        val session = mutableVideoEditor.value ?: return
        if (updated == session.recipe.annotations) return
        pushBounded(videoAnnotationUndo, session.recipe.annotations)
        videoAnnotationRedo.clear()
        videoEditHistory.recordAnnotationChange()
        applyVideoAnnotations(updated, selectedId)
    }

    private fun applyVideoAnnotations(updated: List<VideoAnnotationLayer>, selectedId: String?) {
        val session = mutableVideoEditor.value ?: return
        val trimEnd = session.recipe.endMillis ?: session.content.durationMillis
        if (updated.any { it.startMillis < session.recipe.startMillis || it.endMillis > trimEnd }) return
        val recipe = session.recipe.copy(annotations = updated)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                annotations = updated,
                selectedAnnotationId = selectedId,
                isDirty = session.isDirty(recipe),
            ),
        )
        persistVideoRecipe(recipe)
    }

    private fun pushBounded(
        stack: ArrayDeque<List<VideoAnnotationLayer>>,
        value: List<VideoAnnotationLayer>,
    ) {
        if (stack.size == 100) stack.removeFirst()
        stack.addLast(value)
    }

    fun importVideoLut(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val active = runtime.value ?: return@launch
            try {
                val repository = lutRepository(active)
                val id = repository.import(uri, displayName)
                val summaries = repository.summaries()
                mutableVideoEditor.value = mutableVideoEditor.value?.let { session ->
                    session.copy(content = session.content.copy(
                        customLuts = summaries,
                        statusMessage = getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_lut_imported,
                        ),
                    ))
                }
                val session = mutableVideoEditor.value ?: return@launch
                setVideoColorGrade(session.recipe.colorGrade.copy(lut = com.librestatic.lightforge.core.editing.video.LutReference(customId = id)))
            } catch (failure: Throwable) {
                mutableVideoEditor.value = mutableVideoEditor.value?.let { session ->
                    session.copy(content = session.content.copy(statusMessage = failure.message ?:
                        getApplication<Application>().getString(
                            com.librestatic.lightforge.feature.videoeditor.R.string.video_editor_lut_import_failed,
                        )))
                }
            }
        }
    }

    private fun captureVideoEditorRecovery() {
        val session = mutableVideoEditor.value ?: return
        if (session.id != mutableVideoEditorSessionId.value) return
        val source = session.recoverySource
        externalVideoEditorSnapshot = session.externalRecoverySource?.let { externalSource -> runCatching {
            ExternalVideoEditorRestoreSnapshot.validatedCopy(ExternalVideoEditorRestoreSnapshot(
                id = session.id, source = externalSource, durationMillis = session.content.durationMillis,
                recipe = VideoEditorRestoreSnapshot.encodeRecipeExact(session.recipe),
                baselineRecipe = VideoEditorRestoreSnapshot.encodeRecipeExact(session.baselineRecipe),
                positionMillis = clampVideoPosition(session.content.currentMillis, session.content.trimStartMillis, session.content.trimEndMillis),
                pendingExportRecipe = session.pendingExportRecipe?.let(VideoEditorRestoreSnapshot::encodeRecipeExact),
                exportJobId = session.exportJobId, selectedAnnotationId = session.content.selectedAnnotationId,
                selectedSlowMotionSegmentId = session.content.selectedSlowMotionSegmentId,
                slowMotionMarkInMillis = session.content.slowMotionMarkInMillis,
                annotationTrackingCorrectionMillis = session.content.annotationTrackingCorrectionMillis,
                undoAnnotations = videoAnnotationUndo.map(VideoEditorRestoreSnapshot::encodeAnnotationsExact),
                redoAnnotations = videoAnnotationRedo.map(VideoEditorRestoreSnapshot::encodeAnnotationsExact),
            ))
        }.getOrNull() }
        videoEditorSnapshot = if (source == null) null else runCatching {
            VideoEditorRestoreSnapshot.validatedCopy(VideoEditorRestoreSnapshot(
                id = session.id, source = source, durationMillis = session.content.durationMillis,
                recipe = VideoEditorRestoreSnapshot.encodeRecipeExact(session.recipe),
                baselineRecipe = VideoEditorRestoreSnapshot.encodeRecipeExact(session.baselineRecipe),
                positionMillis = clampVideoPosition(session.content.currentMillis, session.content.trimStartMillis, session.content.trimEndMillis),
                pendingExportRecipe = session.pendingExportRecipe?.let(VideoEditorRestoreSnapshot::encodeRecipeExact),
                exportJobId = session.exportJobId,
                selectedAnnotationId = session.content.selectedAnnotationId,
                selectedSlowMotionSegmentId = session.content.selectedSlowMotionSegmentId,
                slowMotionMarkInMillis = session.content.slowMotionMarkInMillis,
                annotationTrackingCorrectionMillis = session.content.annotationTrackingCorrectionMillis,
                undoAnnotations = videoAnnotationUndo.map(VideoEditorRestoreSnapshot::encodeAnnotationsExact),
                redoAnnotations = videoAnnotationRedo.map(VideoEditorRestoreSnapshot::encodeAnnotationsExact),
            ))
        }.getOrNull()
        saveCreationState()
    }

    /** The UI may checkpoint while an old controller is disposing. It cannot mutate another session. */
    fun checkpointVideoPosition(sessionId: String, positionMillis: Long) {
        if (mutableVideoEditor.value?.id != sessionId || mutableVideoEditorSessionId.value != sessionId) return
        seekVideo(positionMillis)
    }

    private fun restorePendingExternalVideoEditor() {
        val snapshot = pendingRestoredExternalVideoEditor ?: return
        val source = snapshot.source
        // Construct review state without opening the revoked original. Retry checks bytes before playback.
        openVideoEditor(EditorMediaSource(source.uriString, MediaKind.Video,
            mimeType = source.mimeType, displayName = source.displayName, width = source.width,
            height = source.height, durationMillis = source.durationMillis), restoring = snapshot)
    }

    private fun ExternalVideoSourceSnapshot.toExternalMedia() = ExternalMedia(
        uri = Uri.parse(uriString), mimeType = mimeType, kind = MediaKind.Video, editMode = true,
        available = false, displayName = displayName, sizeBytes = sizeBytes,
        width = width, height = height, durationMillis = durationMillis, metadataReady = true,
    )

    private fun restorePendingVideoEditor(active: GalleryRuntime) {
        val snapshot = pendingRestoredVideoEditor ?: return
        val media = mutableCurrentMedia.value
        if (runtime.value !== active || media == null || mutableViewerRestoreSnapshot.value != snapshot.source ||
            media.key != snapshot.source.key || media.kind != MediaKind.Video) {
            pendingRestoredVideoEditor = null
            mutableVideoEditorOpening.value = false
            saveCreationState()
            return
        }
        openVideoEditor(EditorMediaSource(
            uriString = mediaUri(media).toString(), kind = media.kind, libraryMedia = media,
            displayName = media.displayName, width = media.width, height = media.height,
            durationMillis = media.durationMillis,
        ), restoring = snapshot)
    }

    private fun persistVideoRecipe(recipe: VideoEditRecipe) {
        captureVideoEditorRecovery()
        val session = mutableVideoEditor.value ?: return
        val media = session.source.libraryMedia ?: return
        videoRecipeJob?.cancel()
        videoRecipeJob = viewModelScope.launch {
            delay(100)
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.colorEditDao().saveVideoRecipe(
                    VideoEditRecipeEntity(
                        media.key.volumeName,
                        media.key.mediaStoreId,
                        media.generationModified,
                        VideoEditRecipeCodec.encode(recipe),
                        System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun lutRepository(active: GalleryRuntime) = LutRepository(
        getApplication<Application>().contentResolver,
        active.database.colorEditDao(),
        java.io.File(getApplication<Application>().filesDir, "luts"),
    )

    fun seekVideo(positionMillis: Long): Long {
        val session = mutableVideoEditor.value ?: return positionMillis
        val position = clampVideoPosition(
            positionMillis,
            session.content.trimStartMillis,
            session.content.trimEndMillis,
        )
        mutableVideoEditor.value = session.copy(content = session.content.copy(currentMillis = position, annotationTrackingCorrectionMillis = null))
        captureVideoEditorRecovery()
        return position
    }

    fun saveVideoEditorCopy() {
        val session = mutableVideoEditor.value ?: return
        if (session.content.isExporting || !canUseExternalVideoSource(session)) return
        val job = videoExportStore.enqueue(session.source.uri, session.recipe)
        mutableVideoEditor.value = session.copy(
            pendingExportRecipe = session.recipe,
            exportJobId = job.id,
            content = session.content.copy(
                isExporting = true,
                isDirty = false,
                exportProgress = 0f,
                exportPhase = job.phase,
                usedSoftwareCodec = false,
                statusMessage = getApplication<Application>().getString(R.string.video_export_queued),
            ))
        captureVideoEditorRecovery()
    }

    fun cancelVideoEditorExport() {
        val session = mutableVideoEditor.value ?: return
        val jobs = videoExportStore.jobs.value
        val tracked = session.exportJobId
            ?.let { id -> jobs.firstOrNull { it.id == id && it.isActive } }
        val active = tracked ?: activeVideoExportForInput(jobs, session.source.uri.toString())
        active?.let(videoExportStore::cancel)
    }

    fun cancelVideoExportJob(jobId: String) {
        videoExportStore.jobs.value
            .firstOrNull { it.id == jobId && it.isActive }
            ?.let(videoExportStore::cancel)
    }

    fun setUserHardwareWorkload(workload: UserHardwareWorkload?) {
        if (workload == userHardwareWorkload) return
        userHardwareLease?.let(UserHardwareWorkloadGate::release)
        userHardwareLease = null
        userHardwareWorkload = workload
        if (workload != null) {
            val lease = UserHardwareWorkloadGate.acquire(workload)
            userHardwareLease = lease
            if (lease.activatedGate) {
                mlScheduler.suspendForUserWork()
                SemanticAnalysisPriority.suspendForUserWork(getApplication())
            }
        } else if (!UserHardwareWorkloadGate.isActive()) {
            mlScheduler.resumeAfterUserWork()
            SemanticAnalysisPriority.resumeAfterUserWork(getApplication())
        }
    }

    fun closeVideoEditor() {
        externalVideoAccessJob?.cancel()
        pendingRestoredExternalVideoEditor = null
        externalVideoEditorSnapshot = null
        val session = mutableVideoEditor.value
        pendingRestoredVideoEditor = null
        videoEditorSnapshot = null
        mutableVideoEditorSessionId.value = null
        saveCreationState()
        videoEditorOpenGeneration++
        mutableVideoEditorOpening.value = false
        videoEditorJob?.cancel()
        val pendingRecipeWrite = videoRecipeJob
        videoRecipeJob = null
        pendingRecipeWrite?.cancel()
        videoAnnotationTrackingEpoch.cancel()
        videoAnnotationTrackingJob?.cancel()
        videoAnnotationUndo.clear()
        videoAnnotationRedo.clear()
        videoEditHistory.clear()
        val libraryMedia = session?.source?.libraryMedia
        if (libraryMedia != null) {
            val previousDiscard = videoRecipeDiscardJob
            videoRecipeDiscardJob = viewModelScope.launch {
                previousDiscard?.join()
                pendingRecipeWrite?.join()
                val active = runtime.value ?: return@launch
                withContext(Dispatchers.IO) {
                    active.database.colorEditDao().deleteVideoRecipes(
                        libraryMedia.key.volumeName,
                        libraryMedia.key.mediaStoreId,
                    )
                }
            }
        }
        mutableVideoEditor.value = null
    }

    private suspend fun finishEditorCopy(copy: PublishedCopy, kind: MediaKind) {
        val photoSession = mutablePhotoEditor.value
        val videoSession = mutableVideoEditor.value
        val active = runtime.value
        val pendingPhotoRecipe = photoRecipeJob
        photoRecipeJob = null
        pendingPhotoRecipe?.cancel()
        pendingPhotoRecipe?.join()
        photoRecipeDiscardJob?.join()
        if (photoSession != null) restorePhotoRecipeEntry(photoSession)
        withContext(Dispatchers.IO) {
            videoSession?.source?.libraryMedia?.let { media -> active?.database?.colorEditDao()
                ?.deleteVideoRecipes(media.key.volumeName, media.key.mediaStoreId) }
        }
        photoSession?.content?.preview?.recycle()
        photoSession?.content?.originalPreview
            ?.takeIf { it !== photoSession.content.preview }
            ?.recycle()
        photoSession?.content?.cropSourcePreview
            ?.takeIf { it !== photoSession.content.preview && it !== photoSession.content.originalPreview }
            ?.recycle()
        photoExperimentalJob?.cancel()
        releasePhotoInpainting()
        recycleExperimentalPreviews(photoSession?.content)
        mutablePhotoEditor.value = null
        mutableVideoEditor.value = null
        externalVideoAccessJob?.cancel()
        pendingRestoredExternalVideoEditor = null
        externalVideoEditorSnapshot = null
        pendingRestoredVideoEditor = null
        videoEditorSnapshot = null
        mutableVideoEditorSessionId.value = null
        saveCreationState()
        refreshLibrary()
        mutableExternalMedia.value?.let { external ->
            if (external.editMode) {
                mutableExternalSaved.emit(copy.uri)
                return
            }
            mutableExternalMedia.value = null
            mutableExternalPhotoState.value = null
        }
        val media = publishedTimelineMedia(copy.uri, kind)
        if (media != null) openMedia(media)
        mutableEditorCopyOpened.emit(Unit)
    }

    private suspend fun publishedTimelineMedia(uri: Uri, kind: MediaKind): TimelineMedia? =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.GENERATION_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            getApplication<Application>().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val dateAddedSeconds = cursor.getLong(3)
                TimelineMedia(
                    key = MediaKey(MediaStore.getVolumeName(uri), cursor.getLong(0)),
                    kind = kind,
                    generationModified = cursor.getLong(1),
                    timelineSortMillis = cursor.getLong(2).takeIf { it > 0 } ?: dateAddedSeconds * 1_000,
                    width = cursor.getInt(4),
                    height = cursor.getInt(5),
                    durationMillis = cursor.getLong(6),
                    displayName = cursor.getString(7),
                    sizeBytes = cursor.getLong(8),
                    dateModifiedSeconds = cursor.getLong(9),
                )
            }
        }

    private fun selectedFilter(recipe: EditRecipe): String =
        recipe.operations.asReversed().filterIsInstance<EditOperation.Filter>().firstOrNull()?.name ?: "none"

    private fun selectedTone(recipe: EditRecipe): EditOperation.Tone =
        recipe.operations.asReversed().filterIsInstance<EditOperation.Tone>().firstOrNull()
            ?: EditOperation.Tone()

    private fun selectedRawSettings(recipe: EditRecipe): RawDevelopmentSettings =
        recipe.operations.asReversed().filterIsInstance<EditOperation.RawDevelop>()
            .firstOrNull()?.settings ?: RawDevelopmentSettings()

    private fun photoColorOperations(recipe: EditRecipe): List<EditOperation> =
        recipe.operations.filter { it is EditOperation.Tone || it is EditOperation.Filter }

    private fun photoBaseOperations(recipe: EditRecipe): List<EditOperation> =
        recipe.operations.filterNot { it is EditOperation.Tone || it is EditOperation.Filter }

    private fun replacePhotoColorOperation(
        operations: List<EditOperation>,
        tone: EditOperation.Tone,
    ): List<EditOperation> {
        val replacement = tone.takeUnless { it == EditOperation.Tone() }
        val result = operations.toMutableList()
        val index = result.indexOfFirst { it is EditOperation.Tone }
        when {
            index >= 0 && replacement != null -> result[index] = replacement
            index >= 0 -> result.removeAt(index)
            replacement != null -> result += replacement
        }
        return result.filter { it is EditOperation.Tone || it is EditOperation.Filter }
    }

    private fun selectedCrop(recipe: EditRecipe): EditOperation.Crop? =
        recipe.operations.filterIsInstance<EditOperation.Crop>().firstOrNull()

    private fun selectedStraighten(recipe: EditRecipe): Float =
        recipe.operations.filterIsInstance<EditOperation.Straighten>().firstOrNull()?.degrees ?: 0f

    private fun imageExportMime(sourceMime: String?): String = when (sourceMime?.lowercase()) {
        "image/png", "image/webp" -> sourceMime.lowercase()
        else -> "image/jpeg"
    }

    fun loadDetails() {
        val media = mutableCurrentMedia.value ?: return
        val active = runtime.value ?: return
        viewModelScope.launch {
            mutableCheapDetails.value = active.metadata.cheapDetails(media.key)
            mutableExifDetails.value = active.metadata.exifDetails(
                media.key,
                permissions.access.value.unredactedLocation,
            )
            mutableDetectedText.value = DetectedContentRepository(active.database)
                .ocr(media.key)
                ?.rawText
                ?.takeIf(String::isNotBlank)
        }
    }

    fun originalShareIntent(media: TimelineMedia): Intent = ShareCoordinator(
        getApplication<Application>().contentResolver,
    ).original(
        listOf(ShareCandidate(MediaActionTarget(media.key, media.kind), mediaMime(media.kind))),
    ).intent

    fun sanitizedShare(media: TimelineMedia) {
        viewModelScope.launch {
            try {
                val candidate = ShareCandidate(
                    MediaActionTarget(media.key, media.kind),
                    mediaMime(media.kind),
                )
                val asset = LocalShareSanitizer(getApplication<Application>()).prepare(candidate)
                mutableSanitizedShare.emit(
                    ShareCoordinator(getApplication<Application>().contentResolver)
                        .sanitized(listOf(asset), excludedPrivateCount = 0).intent,
                )
            } catch (failure: Throwable) {
                mutableShareError.emit(getApplication<Application>().getString(R.string.share_sanitized_failed))
            }
        }
    }

    fun beginSystemAction(media: TimelineMedia, action: MediaAction) {
        beginTargetsAction(listOf(MediaActionTarget(media.key, media.kind)), action)
    }

    fun beginSystemAction(media: List<TimelineMedia>, action: MediaAction) {
        val targets = media
            .distinctBy(TimelineMedia::key)
            .map { MediaActionTarget(it.key, it.kind) }
        beginTargetsAction(targets, action)
    }

    fun requestRename(media: TimelineMedia, displayName: String) {
        val safeName = ScopedMediaOperations.validateDisplayName(displayName)
        pendingWriteMutation = PendingWriteMutation.Rename(media.key, media.kind, safeName)
        savedStateHandle[WriteMutationStateKey] = pendingWriteMutation
        beginSystemAction(media, MediaAction.Write)
    }

    fun requestDateRepair(media: TimelineMedia, dateTakenMillis: Long) {
        require(dateTakenMillis > 0L)
        pendingWriteMutation = PendingWriteMutation.DateTaken(media.key, media.kind, dateTakenMillis)
        savedStateHandle[WriteMutationStateKey] = pendingWriteMutation
        beginSystemAction(media, MediaAction.Write)
    }

    internal val verifiedMove = VerifiedMoveController(getApplication<Application>(), viewModelScope)

    /**
     * A verified move retires the original outside the MediaStore action coordinator, so nothing
     * else hints the row away. Without this the timeline - and the viewer pager reading it - keeps
     * counting a file that is no longer on disk.
     */
    init {
        viewModelScope.launch {
            verifiedMove.state
                .map { retiredMoveRow(it.entry) }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { key ->
                    runtime.value?.synchronizer?.applyRowHint(key)
                    refreshWidget()
                }
        }
    }

    private var widgetRefresh: Job? = null

    /**
     * The home-screen widget keeps its bitmap until its 30-minute tick, so tell it when a photo
     * leaves the library. Coalesced: a bulk action or a burst of archives is one update.
     */
    private fun refreshWidget() {
        widgetRefresh?.cancel()
        widgetRefresh = viewModelScope.launch {
            delay(WidgetRefreshDebounceMillis)
            com.librestatic.lightforge.feature.widget.GalleryWidgetProvider.triggerUpdate(getApplication())
        }
    }

    internal val pendingTreeOperation: PendingTreeOperation?
        get() = savedStateHandle["pending_tree_operation_v1"]

    internal fun prepareTreeOperation(media: TimelineMedia, move: Boolean): Boolean {
        if (pendingTreeOperation != null) return false
        if (move && (verifiedMove.state.value.busy || verifiedMove.state.value.entry != null || verifiedMove.state.value.copyDraft != null || verifiedMove.state.value.unreadable)) return false
        savedStateHandle["pending_tree_operation_v1"] = PendingTreeOperation(
            MediaActionTarget(media.key, media.kind),
            media.displayName ?: "Lightforge-${media.key.mediaStoreId}.${if (media.kind == MediaKind.Video) "mp4" else "jpg"}",
            mediaMime(media.kind),
            media.dateModifiedSeconds.takeIf { gallerySettings.value.operations.keepLastModifiedWhenPossible }?.times(1_000L),
            move,
        )
        return true
    }

    internal fun clearTreeOperation() { savedStateHandle["pending_tree_operation_v1"] = null }

    internal enum class TreeOperationOutcome { Copied, MoveCopied, Failed }

    // Buffered so an outcome finished during a configuration change still reaches the new UI.
    private val treeOperationOutcomeChannel = Channel<TreeOperationOutcome>(Channel.BUFFERED)
    internal val treeOperationOutcomes = treeOperationOutcomeChannel.receiveAsFlow()

    /** Runs in viewModelScope so rotating the device does not cancel a large copy. */
    internal fun startTreeOperation(input: PendingTreeOperation, treeUri: Uri) {
        viewModelScope.launch {
            val outcome = copyMediaToTree(input, treeUri).fold(
                onSuccess = { if (input.move) TreeOperationOutcome.MoveCopied else TreeOperationOutcome.Copied },
                onFailure = { TreeOperationOutcome.Failed },
            )
            treeOperationOutcomeChannel.send(outcome)
        }
    }

    private suspend fun copyMediaToTree(input: PendingTreeOperation, treeUri: Uri): Result<Uri> = try {
        val result = if (input.move) verifiedMove.copy(input, treeUri) else ScopedMediaOperations.copyToTree(
            getApplication<Application>().contentResolver, input.target, treeUri, input.name, input.mime, input.lastModifiedMillis,
        )
        Result.success(result)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { Result.failure(failure) }

    fun openMoment(momentId: String) {
        mutableSelectedMomentId.value = momentId
        savedStateHandle[SelectedMomentStateKey] = momentId
    }

    suspend fun saveMoment() {
        selectedMoment.value?.let { runtime.value?.moments?.save(it.momentId) }
    }

    fun deleteSelectedMoment() {
        val moment = selectedMoment.value ?: return
        val active = runtime.value ?: return
        viewModelScope.launch {
            // Automatic stories need a durable dismissal so later imports do not recreate
            // the same deleted suggestion. User/manual memories keep true deletion semantics.
            val removed = if (moment.origin == "AUTO") active.moments.dismiss(moment.momentId)
                else active.moments.delete(moment.momentId)
            if (removed && mutableSelectedMomentId.value == moment.momentId) {
                mutableSelectedMomentId.value = null
                savedStateHandle[SelectedMomentStateKey] = null
            }
        }
    }

    suspend fun renameMoment(title: String) {
        selectedMoment.value?.let { runtime.value?.moments?.rename(it.momentId, title) }
    }

    suspend fun reorderMoment(orderedKeys: List<MediaKey>) {
        selectedMoment.value?.let { runtime.value?.moments?.reorderVisible(it.momentId, orderedKeys) }
    }

    suspend fun setMomentCover(ordinal: Int) {
        val moment = selectedMoment.value ?: return
        val target = momentMembers.value.firstOrNull { it.member.ordinal == ordinal } ?: return
        runtime.value?.moments?.setCover(moment.momentId, target.key)
    }

    val momentMembers = combine(runtime, mutableSelectedMomentId) { current, id -> current to id }
        .flatMapLatest { (current, id) ->
            if (current == null || id == null) flowOf(emptyList<MomentMemberUi>())
            else current.moments.observeMembers(id).map { rows ->
                rows.map { row -> MomentMemberUi(row.member,
                    MediaKey(row.media.volumeName, row.media.mediaStoreId), row.media.generationModified) }
            }.onStart { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun beginTargetsAction(targets: List<MediaActionTarget>, action: MediaAction) {
        if (targets.size > MediaActionReducer.MaxChunkSize) {
            // Hand-picked selections can outgrow one system request; stage them in bounded chunks.
            viewModelScope.launch { beginChunkedTargetsAction(targets, action) }
            return
        }
        val sizedInitial = MediaActionReducer.start(action, targets.size.toLong())
        val coordinator = coordinator(sizedInitial)
        currentSystemCoordinator = coordinator
        mutableActionLaunches.tryEmit(coordinator.stageChunk(targets))
    }

    /**
     * [inFlightRequestId] is the request the UI launched and still expects a result for; that
     * dialog is still up (or its result is being handled), so it must not be launched twice.
     */
    fun resumePendingSystemAction(inFlightRequestId: Long?) {
        val coordinator = restoredSystemCoordinator() ?: return
        when (val phase = coordinator.snapshot.value.phase) {
            is com.librestatic.lightforge.core.mediastore.MediaActionPhase.AwaitingSystem -> {
                if (phase.requestId == inFlightRequestId || phase.requestId == handledSystemRequestId) return
                runCatching { coordinator.recreateCurrentRequest() }
                    .getOrNull()?.let(mutableActionLaunches::tryEmit)
            }
            com.librestatic.lightforge.core.mediastore.MediaActionPhase.ReadyForChunk -> {
                viewModelScope.launch {
                    when {
                        bulkCursor != null -> stageNextBulkChunk()
                        favoriteImportCursor != null -> stageNextFavoriteImportChunk()
                    }
                }
            }
            else -> Unit
        }
    }

    /** A denied confirmation belongs to the surface that asked for it; leaving drops its retry. */
    fun dismissCancelledSystemAction() {
        if (mutableSystemAction.value?.phase !is com.librestatic.lightforge.core.mediastore.MediaActionPhase.Cancelled) return
        currentSystemCoordinator = null
        mutableSystemAction.value = null
        savedStateHandle[ActionStateKey] = null
    }

    fun retrySystemAction() {
        currentSystemCoordinator?.let { coordinator ->
            runCatching { coordinator.retryCurrent() }.getOrNull()?.let(mutableActionLaunches::tryEmit)
        }
    }

    /** Keeps a live coordinator; after process death rebuilds it from the saved snapshot. */
    private fun restoredSystemCoordinator(): MediaStoreActionCoordinator? = currentSystemCoordinator
        ?: mutableSystemAction.value?.let(::coordinator)?.also { currentSystemCoordinator = it }

    fun onSystemActionResult(requestId: Long, approved: Boolean) {
        // A result can arrive before resumePendingSystemAction() runs (it is delivered when the
        // launcher registers), so it must not depend on resume having rebuilt the coordinator.
        val coordinator = restoredSystemCoordinator() ?: return
        handledSystemRequestId = requestId
        viewModelScope.launch {
            val approvedTargets = (coordinator.snapshot.value.phase as?
                com.librestatic.lightforge.core.mediastore.MediaActionPhase.AwaitingSystem)?.targets.orEmpty()
            val approvedAction = coordinator.snapshot.value.progress.action
            val snapshot = coordinator.onSystemResult(requestId, approved)
            if (approved && approvedAction == MediaAction.Write) {
                applyPendingWriteMutation(approvedTargets.singleOrNull())
            }
            if (approved) approvedTargets.forEach { runtime.value?.synchronizer?.applyRowHint(it.key) }
            if (snapshot?.phase == com.librestatic.lightforge.core.mediastore.MediaActionPhase.ReadyForChunk) {
                when {
                    bulkCursor != null -> stageNextBulkChunk()
                    favoriteImportCursor != null -> stageNextFavoriteImportChunk()
                }
            } else if (snapshot?.phase == com.librestatic.lightforge.core.mediastore.MediaActionPhase.Complete) {
                val completedProgress = snapshot.progress
                runtime.value?.activity?.let { activity ->
                    when (val action = completedProgress.action) {
                        is MediaAction.Trash -> activity.record(
                            if (action.enabled) GalleryActivityType.Trashed else GalleryActivityType.Restored,
                            completedProgress.totalSelected,
                        )
                        MediaAction.Delete -> activity.record(
                            GalleryActivityType.Deleted,
                            completedProgress.totalSelected,
                        )
                        else -> Unit
                    }
                }
                val action = completedProgress.action
                if ((action is MediaAction.Trash && action.enabled) || action == MediaAction.Delete) refreshWidget()
                clearSelection()
                bulkCursor = null
                savedStateHandle[BulkStateKey] = null
                favoriteImportCursor = null
                savedStateHandle[FavoriteImportStateKey] = null
            }
        }
    }

    private suspend fun applyPendingWriteMutation(authorizedTarget: MediaActionTarget?) {
        val mutation = pendingWriteMutation ?: return
        pendingWriteMutation = null
        savedStateHandle[WriteMutationStateKey] = null
        if (authorizedTarget == null || authorizedTarget.key != mutation.key || authorizedTarget.kind != mutation.kind) {
            mutableShareError.emit(getApplication<Application>().getString(R.string.media_change_mismatch))
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                when (mutation) {
                    is PendingWriteMutation.Rename -> ScopedMediaOperations.rename(resolver, authorizedTarget, mutation.displayName)
                    is PendingWriteMutation.DateTaken -> {
                        ScopedMediaOperations.repairDateTaken(resolver, authorizedTarget, mutation.dateTakenMillis)
                        runtime.value?.database?.portableTimelineOverrideDao()?.remove(mutation.key.volumeName, mutation.key.mediaStoreId)
                    }
                }
            }
        }.onFailure { mutableShareError.emit(getApplication<Application>().getString(R.string.media_change_failed)) }
    }

    fun mediaUri(media: TimelineMedia): Uri = media.uri()

    private var verifiedRuntime: GalleryRuntime? = null

    private suspend fun refreshLibrary(
        indexedHintCount: Int = 0,
        forceFullReconciliation: Boolean = false,
        reconcileUnobservedChanges: Boolean = false,
        invalidateViews: Boolean = false,
    ): Unit = refreshMutex.withLock {
        val active = runtime.value ?: return@withLock
        if (access.value.images == com.librestatic.lightforge.core.model.GrantLevel.None &&
            access.value.videos == com.librestatic.lightforge.core.model.GrantLevel.None
        ) {
            mutableEngineState.value = LibraryEngineState.PermissionRequired
            return@withLock
        }
        val previousState = mutableEngineState.value
        // Only a first pass or a state that is not browsable yet announces work. Routine syncs
        // (foreground, MediaStore changes) must not flip a usable library back to "preparing".
        if (verifiedRuntime !== active || previousState != LibraryEngineState.Ready) {
            mutableEngineState.value = LibraryEngineState.Indexing
        }
        try {
            var requiresSearchRebuild = false
            var changedItems = 0L
            val completed = withContext(Dispatchers.IO) {
                for (volume in active.generations.snapshot()) {
                    if (forceFullReconciliation) {
                        scanVolume(active, volume, "full reconciliation")
                        requiresSearchRebuild = true
                        continue
                    }
                    when (val result = active.synchronizer.sync(volume, reconcileUnobservedChanges)) {
                        IncrementalSyncResult.NeedsInitialScan -> {
                            scanVolume(active, volume, "initial scan"); requiresSearchRebuild = true
                        }
                        IncrementalSyncResult.NeedsFullVolumeReconciliation -> {
                            scanVolume(active, volume, "volume reconciliation"); requiresSearchRebuild = true
                        }
                        is IncrementalSyncResult.Complete -> changedItems += result.changedItems
                        is IncrementalSyncResult.PausedPermission -> return@withContext false
                        else -> Unit
                    }
                }
                true
            }
            // Scans, reconciliations, explicit refreshes and the first pass of a runtime reload
            // the timeline and album counts themselves, so a Room notification missed while the
            // first page loaded mid-scan cannot leave the newest days out (V-06).
            if (completed && (requiresSearchRebuild || reconcileUnobservedChanges || invalidateViews ||
                    changedItems > 0 || verifiedRuntime !== active)
            ) {
                verifiedRuntime = active
                Log.i(LibraryLogTag, "Invalidating timeline and collections (changed=$changedItems)")
                active.timeline.invalidate()
                active.albums.invalidateSummaries()
            }
            if (completed) mutableEngineState.value = LibraryEngineState.Ready
            // The timeline is already usable, so failing maintenance is logged instead of
            // replacing it with the library error screen; the next refresh retries.
            if (completed) try { withContext(Dispatchers.IO) {
                val prefs = getApplication<Application>().getSharedPreferences("search-production", Context.MODE_PRIVATE)
                val needsInitial = prefs.getLong("schema", 0) != com.librestatic.lightforge.core.search.MediaSearchSchema.Version
                if (needsInitial || requiresSearchRebuild || changedItems > indexedHintCount) {
                    mutableLibraryMaintenance.value = LibraryMaintenance.SearchIndex
                    active.searchIndex.rebuild(restart = true)
                    prefs.edit().putLong("schema", com.librestatic.lightforge.core.search.MediaSearchSchema.Version).commit()
                }
                // Moment generation is a resumable Room keyset pass, not a MediaStore scan.
                // Its durable input revision resumes or regenerates after actual candidate
                // changes, including old-dated imports; identical reindexing is a no-op.
                mutableLibraryMaintenance.value = LibraryMaintenance.Moments
                active.moments.generateIfNeeded()
                mutableSearchIndexReady.value = true
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (denied: SecurityException) {
                throw denied
            } catch (failure: Throwable) {
                Log.w(LibraryLogTag, "Library maintenance failed", failure)
            }
            if (!completed) {
                permissions.revalidate()
                mutableEngineState.value = LibraryEngineState.PermissionRequired
            }
        } catch (cancelled: CancellationException) {
            // A cancelled pass must not leave the library announced as preparing forever.
            if (mutableEngineState.value == LibraryEngineState.Indexing) mutableEngineState.value = previousState
            throw cancelled
        } catch (_: SecurityException) {
            permissions.revalidate()
            mutableEngineState.value = LibraryEngineState.PermissionRequired
        } catch (_: Throwable) {
            mutableEngineState.value = LibraryEngineState.Error
        } finally {
            mutableLibraryMaintenance.value = null
        }
    }

    private suspend fun scanVolume(active: GalleryRuntime, volume: VolumeGeneration, reason: String) {
        // A scan rewrites the timeline, so even an already-browsable library shows it.
        mutableEngineState.value = LibraryEngineState.Indexing
        Log.i(LibraryLogTag, "Scan start: ${volume.volumeName} ($reason) generation=${volume.generation}")
        active.scanner.scan(volume)
        Log.i(LibraryLogTag, "Scan complete: ${volume.volumeName} generation=${volume.generation}")
    }

    override fun onCleared() {
        mutableLocalSharingController.value?.close()
        ownSyncController.close()
        offlinePlacesController.close()
        mutableRemoteBackupController.value?.close()
        backupTaskController.close()
        userHardwareLease?.let(UserHardwareWorkloadGate::release)
        semanticSearchEngine?.close()
        semanticModelManager?.close()
        releasePhotoInpainting()
        runtime.value?.let {
            it.monitor?.close()
            it.thumbnails.close()
            it.searchIndex.close()
        }
    }

    private fun createRuntime(context: Context): GalleryRuntime {
        val database = GalleryDatabaseFactory.open(context)
        val reader = MediaStoreReader(context.contentResolver)
        val store = RoomMediaIndexStore(database.libraryDao())
        val maxCache = context.getSystemService(ActivityManager::class.java).memoryClass
            .toLong().times(1024 * 1024).div(8)
            .coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024)
        val decoder = NativeImageDecoder(context.contentResolver)
        val motionKeyFrames = com.librestatic.lightforge.core.data.MotionKeyFrameRepository(context, database)
        val thumbnailFactory = { motionThumbnailLoader(context, decoder, maxCache, motionKeyFrames) }
        return GalleryRuntime(
            database = database,
            timeline = GalleryTimelineRepository(database),
            scanner = InitialMediaScanner(reader, store, { permissions.access.value }),
            synchronizer = IncrementalMediaSynchronizer(reader, store, { permissions.access.value }),
            generations = MediaStoreGenerationProbe(context),
            thumbnails = thumbnailFactory(),
            motionKeyFrames = motionKeyFrames,
            thumbnailFactory = thumbnailFactory,
            organizationBackup = GalleryOrganizationBackupAdapter(context, database),
            decoder = decoder,
            albums = GalleryAlbumRepository(database),
            trash = GalleryTrashRepository(database),
            archive = GalleryArchiveRepository(database),
            activity = GalleryActivityRepository(database),
            highlights = GalleryHighlightsRepository(database),
            queryMedia = GalleryQueryMediaRepository(database),
            metadata = MediaMetadataRepository(context.contentResolver, database),
            selectionTargets = RoomSelectionTargetSource(database),
            viewerMedia = RoomViewerMediaSource(database),
            searchIndex = GallerySearchIndexRepository(context, database),
            moments = MomentRepository(database),
        )
    }

    private fun coordinator(snapshot: MediaActionSnapshot) = MediaStoreActionCoordinator(
        getApplication<Application>().contentResolver,
        snapshot,
        persist = { state ->
            mutableSystemAction.value = state
            savedStateHandle[ActionStateKey] = state
        },
    )

    private fun TimelineMedia.uri(): Uri = ContentUris.withAppendedId(
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
        },
        key.mediaStoreId,
    )

    private fun MediaActionTarget.uri(): Uri = ContentUris.withAppendedId(
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
        },
        key.mediaStoreId,
    )

    private fun mediaMime(kind: MediaKind) = if (kind == MediaKind.Image) "image/*" else "video/*"

    private fun albumQuery(key: AlbumKey, filter: AlbumMediaFilter, sort: AlbumSort) = MediaQuery(
        scope = when (key) {
            is AlbumKey.Physical -> MediaQuery.Scope.PhysicalAlbum(key.volumeName, key.bucketId)
            is AlbumKey.Virtual -> MediaQuery.Scope.VirtualAlbum(key.albumId)
        },
        kindFilter = when (filter) {
            AlbumMediaFilter.All -> MediaQuery.KindFilter.ImagesAndVideos
            AlbumMediaFilter.Images -> MediaQuery.KindFilter.Images
            AlbumMediaFilter.Videos -> MediaQuery.KindFilter.Videos
        },
        sort = if (sort.ascending) MediaQuery.Sort.OldestFirst else MediaQuery.Sort.NewestFirst,
        sortField = when (sort) {
            AlbumSort.NameAscending, AlbumSort.NameDescending -> MediaQuery.SortField.Name
            AlbumSort.SizeAscending, AlbumSort.SizeDescending -> MediaQuery.SortField.Size
            AlbumSort.NewestFirst, AlbumSort.OldestFirst -> MediaQuery.SortField.DateTaken
        },
        grouping = MediaQuery.Grouping.None,
        archiveMode = MediaQuery.ArchiveMode.Include,
    )

    private fun currentLibraryQuery(): MediaQuery {
        val library = gallerySettings.value.library
        return MediaQuery(
            kindFilter = when (library.filter) {
                com.librestatic.lightforge.core.preferences.LibraryFilter.All -> MediaQuery.KindFilter.ImagesAndVideos
                com.librestatic.lightforge.core.preferences.LibraryFilter.Images -> MediaQuery.KindFilter.Images
                com.librestatic.lightforge.core.preferences.LibraryFilter.Videos -> MediaQuery.KindFilter.Videos
                com.librestatic.lightforge.core.preferences.LibraryFilter.Animated -> MediaQuery.KindFilter.Animated
                com.librestatic.lightforge.core.preferences.LibraryFilter.Raw -> MediaQuery.KindFilter.Raw
            },
            sort = if (library.ascending) MediaQuery.Sort.OldestFirst else MediaQuery.Sort.NewestFirst,
            sortField = when (library.sort) {
                com.librestatic.lightforge.core.preferences.LibrarySort.DateTaken -> MediaQuery.SortField.DateTaken
                com.librestatic.lightforge.core.preferences.LibrarySort.DateModified -> MediaQuery.SortField.DateModified
                com.librestatic.lightforge.core.preferences.LibrarySort.Name -> MediaQuery.SortField.Name
                com.librestatic.lightforge.core.preferences.LibrarySort.Size -> MediaQuery.SortField.Size
            },
            grouping = when (library.grouping) {
                com.librestatic.lightforge.core.preferences.LibraryGrouping.Day -> MediaQuery.Grouping.Day
                com.librestatic.lightforge.core.preferences.LibraryGrouping.Month -> MediaQuery.Grouping.Month
                com.librestatic.lightforge.core.preferences.LibraryGrouping.Year -> MediaQuery.Grouping.Year
                com.librestatic.lightforge.core.preferences.LibraryGrouping.None -> MediaQuery.Grouping.None
            },
            folderMode = if (library.folderSelectionMode == com.librestatic.lightforge.core.preferences.FolderSelectionMode.OnlyIncluded) {
                MediaQuery.FolderMode.OnlyIncluded
            } else MediaQuery.FolderMode.AllExceptExcluded,
            folderRules = library.folderRules,
        )
    }

    private fun beginQueryAction(selection: SelectionSpec.QueryAll, action: MediaAction) {
        viewModelScope.launch {
            val source = runtime.value?.selectionTargets ?: return@launch
            val total = (source.count(selection.querySnapshot) - selection.exclusions.size).coerceAtLeast(0)
            if (total == 0L) return@launch
            val coordinator = coordinator(MediaActionReducer.start(action, total))
            currentSystemCoordinator = coordinator
            bulkCursor = BulkCursor(selection, action, null).also { savedStateHandle[BulkStateKey] = it }
            stageNextBulkChunk()
        }
    }

    private suspend fun beginFavoriteImport(targets: List<MediaActionTarget>) =
        beginChunkedTargetsAction(targets, MediaAction.Favorite(true))

    /** Stages an explicit target list through the favorite-import cursor, which is action-agnostic. */
    private suspend fun beginChunkedTargetsAction(targets: List<MediaActionTarget>, action: MediaAction) {
        if (targets.isEmpty()) return
        currentSystemCoordinator = coordinator(MediaActionReducer.start(action, targets.size.toLong()))
        favoriteImportCursor = FavoriteImportCursor(ArrayList(targets)).also {
            savedStateHandle[FavoriteImportStateKey] = it
        }
        stageNextFavoriteImportChunk()
    }

    private suspend fun stageNextFavoriteImportChunk() {
        val cursor = favoriteImportCursor ?: return
        if (!systemCoordinatorReadyForChunk()) return
        val chunk = cursor.remaining.take(MediaActionReducer.MaxChunkSize)
        if (chunk.isEmpty()) {
            val coordinator = currentSystemCoordinator ?: return
            val finished = MediaActionReducer.failUnresolvedRemainder(coordinator.snapshot.value)
            currentSystemCoordinator = coordinator(finished)
            favoriteImportCursor = null
            savedStateHandle[FavoriteImportStateKey] = null
            return
        }
        favoriteImportCursor = FavoriteImportCursor(ArrayList(cursor.remaining.drop(chunk.size))).also {
            savedStateHandle[FavoriteImportStateKey] = it
        }
        stageSystemChunk(chunk)
    }

    // Checked before a cursor advances, so a stale duplicate callback neither skips nor re-stages a chunk.
    private fun systemCoordinatorReadyForChunk() = currentSystemCoordinator?.snapshot?.value?.phase ==
        com.librestatic.lightforge.core.mediastore.MediaActionPhase.ReadyForChunk

    /** A request-creation failure is already recorded as RequestFailed; it must not crash the scope. */
    private suspend fun stageSystemChunk(targets: List<MediaActionTarget>) {
        val coordinator = currentSystemCoordinator ?: return
        runCatching { coordinator.stageChunk(targets) }.getOrNull()?.let { mutableActionLaunches.emit(it) }
    }

    private suspend fun stageNextBulkChunk() {
        val cursor = bulkCursor ?: return
        if (!systemCoordinatorReadyForChunk()) return
        val source = runtime.value?.selectionTargets ?: return
        var after = cursor.afterExclusive
        while (true) {
            val page = source.page(cursor.selection.querySnapshot, after, 500)
            if (page.isEmpty()) {
                val coordinator = currentSystemCoordinator ?: return
                val finished = MediaActionReducer.failUnresolvedRemainder(coordinator.snapshot.value)
                currentSystemCoordinator = coordinator(finished)
                bulkCursor = null
                savedStateHandle[BulkStateKey] = null
                return
            }
            after = page.last().key
            bulkCursor = cursor.copy(afterExclusive = after).also { savedStateHandle[BulkStateKey] = it }
            val targets = page.filterNot { it.key in cursor.selection.exclusions }
            if (targets.isNotEmpty()) {
                stageSystemChunk(targets)
                return
            }
        }
    }

    private fun loadExternalPhoto(uri: Uri) {
        photoJob?.cancel()
        photoJob = viewModelScope.launch {
            PhotoViewerPipeline(NativeImageDecoder(getApplication<Application>().contentResolver))
                .load(uri, 1_440, 3_120).collect { mutableExternalPhotoState.value = it }
        }
    }

    private fun revalidateExternalGrant() {
        val current = mutableExternalMedia.value ?: return
        val restoring = externalVideoDraftIsRestoring(pendingRestoredExternalVideoEditor ?: externalVideoEditorSnapshot,
            mutableVideoEditorSessionId.value, mutableVideoEditorOpening.value, current.uri.toString())
        if (restoring || mutableVideoEditor.value?.let { it.source.uri == current.uri && it.externalAccessBlocked } == true) {
            // A blocked/restoring editor only checks the original after explicit Retry.
            mutableExternalMedia.value = current.copy(available = false)
            return
        }
        val available = canOpen(current.uri)
        mutableExternalMedia.value = current.copy(available = available)
        mutableVideoEditor.value?.takeIf { it.source.libraryMedia == null && it.source.uri == current.uri }
            ?.let { session ->
                if (externalVideoAccessBlocked(session.externalAccessBlocked, available, explicitRetry = false)) {
                    blockExternalVideoAccess(session.id)
                }
            }
        if (available && current.kind == MediaKind.Image && mutableExternalPhotoState.value == null) {
            loadExternalPhoto(current.uri)
        }
    }

    /** Fresh preflight before enqueuing work; restored grants never implicitly resume a blocked editor. */
    private fun canUseExternalVideoSource(session: VideoEditorSession): Boolean {
        if (session.source.libraryMedia != null) return true
        val readable = !session.externalAccessBlocked && canOpen(session.source.uri)
        if (!externalVideoAccessBlocked(session.externalAccessBlocked, readable, explicitRetry = false)) return true
        blockExternalVideoAccess(session.id)
        return false
    }

    private fun blockExternalVideoAccess(sessionId: String) {
        val session = mutableVideoEditor.value?.takeIf { it.id == sessionId } ?: return
        mutableVideoEditor.value = session.copy(externalAccessBlocked = true)
        // Keep recipe, identity and undo/redo. Disposing the gated UI releases video and music players.
        cancelVideoAnnotationTracking()
        cancelVideoEditorExport()
    }

    fun retryExternalVideoAccess() {
        val session = mutableVideoEditor.value?.takeIf {
            it.externalAccessBlocked && !it.externalAccessChecking && it.source.libraryMedia == null
        } ?: return
        mutableVideoEditor.value = session.copy(externalAccessChecking = true)
        externalVideoAccessJob = viewModelScope.launch {
            try {
                val proof = withContext(Dispatchers.IO) {
                    externalVideoFingerprint {
                        requireNotNull(getApplication<Application>().contentResolver.openInputStream(session.source.uri))
                    }
                }
                val current = mutableVideoEditor.value?.takeIf { it.id == session.id } ?: return@launch
                val expected = current.externalRecoverySource
                val matches = expected == null || (expected.sha256 == proof.sha256 && expected.sizeBytes == proof.sizeBytes)
                val assetsAvailable = withContext(Dispatchers.IO) {
                    val music = current.recipe.musicUri
                    (music == null || canOpen(music)) && canOpen(current.source.uri)
                }
                val lutId = current.recipe.colorGrade.lut.customId
                val lut = lutId?.let { id -> runtime.value?.let { active ->
                    withContext(Dispatchers.IO) { runCatching { lutRepository(active).load(id) }.getOrNull() }
                } }
                val latest = mutableVideoEditor.value?.takeIf { it.id == session.id } ?: return@launch
                if (latest.recipe != current.recipe) {
                    mutableVideoEditor.value = latest.copy(externalAccessChecking = false)
                    return@launch
                }
                val available = matches && assetsAvailable && (lutId == null || lut != null)
                mutableVideoEditor.value = latest.copy(
                    externalAccessChecking = false, externalAccessBlocked = !available, externalSourceChanged = !matches,
                    content = latest.content.copy(activeCustomLut = if (available) lut else latest.content.activeCustomLut),
                )
                mutableExternalMedia.value?.takeIf { it.uri == current.source.uri }?.let {
                    mutableExternalMedia.value = it.copy(available = available)
                }
                captureVideoEditorRecovery()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableVideoEditor.value?.takeIf { it.id == session.id }?.let {
                    mutableVideoEditor.value = it.copy(externalAccessChecking = false, externalAccessBlocked = true)
                }
            }
        }
    }

    private fun probeExternalMedia(media: ExternalMedia): ExternalMedia {
        val resolver = getApplication<Application>().contentResolver
        var displayName: String? = media.displayName
        var sizeBytes = media.sizeBytes
        runCatching {
            resolver.query(media.uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        displayName = cursor.getString(0)
                        if (!cursor.isNull(1)) sizeBytes = cursor.getLong(1)
                    }
                }
        }
        var width = media.width
        var height = media.height
        var duration = media.durationMillis
        if (media.kind == MediaKind.Image) {
            runCatching {
                resolver.openInputStream(media.uri)?.use { input ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, options)
                    width = options.outWidth.coerceAtLeast(0)
                    height = options.outHeight.coerceAtLeast(0)
                }
            }
        } else {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(getApplication<Application>(), media.uri)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation % 180 != 0) {
                        val unrotatedWidth = width
                        width = height
                        height = unrotatedWidth
                    }
                }
            }
        }
        return media.copy(
            displayName = displayName,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            durationMillis = duration,
            metadataReady = true,
        )
    }

    private fun canOpen(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    private fun MediaItemEntity.toFavoriteBackupRecord() = FavoriteBackupRecord(
        volumeName = volumeName,
        mediaStoreId = mediaStoreId,
        mediaKind = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image",
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateTakenMillis = dateTakenMillis,
        dateModifiedSeconds = dateModifiedSeconds,
        relativePath = relativePath,
    )

    private fun MediaItemEntity.matchesBackupRecord(
        record: FavoriteBackupRecord,
        requireIdentityConfidence: Boolean,
    ): Boolean {
        val kind = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) "video" else "image"
        if (kind != record.mediaKind) return false
        if (!requireIdentityConfidence) return true
        val matchingSignals = listOf(
            sizeBytes == record.sizeBytes,
            displayName == record.displayName,
            mimeType == record.mimeType,
            relativePath == record.relativePath,
        ).count { it }
        return matchingSignals >= 2
    }

    private fun MediaItemEntity.toActionTarget() = MediaActionTarget(
        key = MediaKey(volumeName, mediaStoreId),
        kind = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaKind.Video else MediaKind.Image,
    )

    private data class AlbumRequest(val key: AlbumKey, val filter: AlbumMediaFilter, val sort: AlbumSort)

    private data class BulkCursor(
        val selection: SelectionSpec.QueryAll,
        val action: MediaAction,
        val afterExclusive: com.librestatic.lightforge.core.model.MediaKey?,
    ) : java.io.Serializable

    private data class FavoriteImportCursor(
        val remaining: ArrayList<MediaActionTarget>,
    ) : java.io.Serializable

    private sealed interface PendingWriteMutation : java.io.Serializable {
        val key: MediaKey
        val kind: MediaKind

        data class Rename(
            override val key: MediaKey,
            override val kind: MediaKind,
            val displayName: String,
        ) : PendingWriteMutation

        data class DateTaken(
            override val key: MediaKey,
            override val kind: MediaKind,
            val dateTakenMillis: Long,
        ) : PendingWriteMutation
    }

    data class ExternalMedia(
        val uri: Uri,
        val mimeType: String?,
        override val kind: MediaKind,
        val editMode: Boolean,
        val available: Boolean,
        override val displayName: String? = null,
        val sizeBytes: Long = 0L,
        override val width: Int = 0,
        override val height: Int = 0,
        override val durationMillis: Long = 0,
        override val timelineSortMillis: Long = System.currentTimeMillis(),
        val metadataReady: Boolean = false,
    ) : ViewerMedia {
        override val viewerId: String get() = "external:$uri"
        override val mediaKey: MediaKey? get() = null
        override val generationModified: Long get() = 0
        override val isFavorite: Boolean get() = false
    }

    private companion object {
        const val CreationStateKey = "local_creation_ui_v1"
        const val SelectedMomentStateKey = "selected_moment_id"
        const val ActionStateKey = "media_action_state"
        const val WidgetRefreshDebounceMillis = 1_500L
        const val BulkStateKey = "bulk_action_state"
        const val FavoriteImportStateKey = "favorite_import_state"
        const val WriteMutationStateKey = "pending_write_mutation"
        const val ViewerWindowRadius = 80
        const val ViewerWindowRefreshThreshold = 12
        const val AnnotationEraserRadius = 0.028f
        const val PersonMemberPageSize = 200
        const val CleanupGroupLimit = 50
        const val CleanupPreviewLimit = 30
    }
}
