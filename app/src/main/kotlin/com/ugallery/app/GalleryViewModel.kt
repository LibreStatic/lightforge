package com.ugallery.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ugallery.core.data.MomentRepository
import com.ugallery.core.database.MomentEntity
import com.ugallery.core.database.MomentMemberRow
import com.ugallery.core.database.MomentSummaryRow
import com.ugallery.core.data.GalleryTimelineRepository
import com.ugallery.core.data.GalleryAlbumRepository
import com.ugallery.core.data.GalleryTrashRepository
import com.ugallery.core.data.MediaMetadataRepository
import com.ugallery.core.database.AlbumMediaFilter
import com.ugallery.core.database.AlbumSort
import com.ugallery.core.data.IncrementalMediaSynchronizer
import com.ugallery.core.data.IncrementalSyncResult
import com.ugallery.core.data.InitialMediaScanner
import com.ugallery.core.data.MediaStoreChangeMonitor
import com.ugallery.core.data.RoomMediaIndexStore
import com.ugallery.core.data.RoomSelectionTargetSource
import com.ugallery.core.data.RoomViewerMediaSource
import com.ugallery.core.data.GallerySearchIndexRepository
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.mediastore.MediaStoreGenerationProbe
import com.ugallery.core.mediastore.MediaStoreReader
import com.ugallery.core.mediastore.MediaAction
import com.ugallery.core.mediastore.MediaActionLaunch
import com.ugallery.core.mediastore.MediaActionReducer
import com.ugallery.core.mediastore.MediaActionSnapshot
import com.ugallery.core.mediastore.MediaActionTarget
import com.ugallery.core.mediastore.MediaStoreActionCoordinator
import com.ugallery.core.mediastore.ShareCandidate
import com.ugallery.core.mediastore.ShareCoordinator
import com.ugallery.core.mediastore.PendingMediaWriter
import com.ugallery.core.mediastore.MediaWriteSpec
import com.ugallery.core.mediastore.PublishedCopy
import com.ugallery.core.mediastore.LocalShareSanitizer
import com.ugallery.core.mediastore.ScopedMediaOperations
import com.ugallery.core.ml.DetectedContentRepository
import com.ugallery.core.ml.LocalAnalysisOnboardingDecision
import com.ugallery.core.ml.LocalAnalysisOnboardingStore
import com.ugallery.core.ml.MlScheduler
import com.ugallery.core.ml.MlTaskType
import com.ugallery.core.ml.PetCollectionRepository
import com.ugallery.core.ml.PetCollectionSettings
import com.ugallery.core.ml.PetCollectionSummary
import com.ugallery.core.ml.PetType
import com.ugallery.core.ml.PeopleRepository
import com.ugallery.core.ml.MlRunMode
import com.ugallery.core.ml.MlControlState
import com.ugallery.core.search.AppSearchMediaSearchRepository
import com.ugallery.core.search.MediaSearchCursor
import com.ugallery.core.search.MediaSearchHit
import com.ugallery.core.search.SearchConcept
import com.ugallery.core.search.SearchRankingDebug
import com.ugallery.core.search.SearchVocabulary
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.CheapMediaDetails
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.model.EditHistory
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import com.ugallery.core.model.RawDevelopmentSettings
import com.ugallery.core.model.RawOutputFormat
import com.ugallery.core.preferences.GallerySettings
import com.ugallery.core.preferences.GallerySettingsRepository
import com.ugallery.core.preferences.VideoResumePolicy
import com.ugallery.core.preferences.FavoriteBackupRecord
import com.ugallery.core.preferences.GalleryBackupCodec
import com.ugallery.core.preferences.GalleryFolderToken
import com.ugallery.core.editing.image.PhotoExportOutcome
import com.ugallery.core.editing.image.PhotoImageRenderer
import com.ugallery.core.editing.video.Media3VideoExporter
import com.ugallery.core.editing.video.VideoEditRecipe
import com.ugallery.core.editing.video.VideoExportRequest
import com.ugallery.core.editing.video.CubeLut
import com.ugallery.core.editing.video.CustomLutOption
import com.ugallery.core.editing.video.LogProfileDetector
import com.ugallery.core.editing.video.VideoColorGrade
import com.ugallery.core.editing.video.VideoEditRecipeCodec
import com.ugallery.core.editing.video.VideoOutputQuality
import com.ugallery.core.editing.video.VideoOutputCapabilities
import com.ugallery.core.editing.video.SlowMotionSegment
import com.ugallery.core.database.VideoEditRecipeEntity
import com.ugallery.core.database.VideoPlaybackPositionEntity
import com.ugallery.core.database.MediaItemEntity
import com.ugallery.core.database.PhysicalAlbumRow
import com.ugallery.core.raw.RawDeveloper
import com.ugallery.core.raw.RawExportOutcome
import com.ugallery.core.raw.isRawMimeOrName
import com.ugallery.core.selection.SelectionReducer
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.core.selection.MediaQuery
import com.ugallery.core.thumbnail.NativeImageDecoder
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.feature.collections.LocalMeUiState
import com.ugallery.feature.collections.MomentMemberUi
import com.ugallery.feature.collections.PeopleUiState
import com.ugallery.feature.collections.PersonCardUi
import com.ugallery.feature.collections.PersonMemberCardUi
import com.ugallery.feature.settings.GalleryFolderOption
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.viewer.PhotoLoadState
import com.ugallery.feature.viewer.PhotoViewerPipeline
import com.ugallery.feature.viewer.ViewerUiState
import com.ugallery.feature.photoeditor.PhotoEditorContentState
import com.ugallery.feature.videoeditor.VideoEditorContentState
import com.ugallery.feature.collage.CollageConfig
import com.ugallery.feature.collage.CollageTemplate
import com.ugallery.feature.collage.CollageTemplates
import com.ugallery.feature.semanticsearch.ReciprocalRankFusion
import com.ugallery.feature.semanticsearch.RankedSemanticKey
import com.ugallery.feature.semanticsearch.SemanticModelManager
import com.ugallery.feature.semanticsearch.SemanticModelManagerState
import com.ugallery.feature.semanticsearch.SemanticSearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import javax.inject.Inject
import org.json.JSONObject

enum class LibraryEngineState { Starting, Indexing, Ready, PermissionRequired, Error }

data class GallerySearchUiState(
    val query: String = "",
    val hits: List<MediaSearchHit> = emptyList(),
    val loading: Boolean = false,
    val terminal: Boolean = true,
    val error: Boolean = false,
)

internal fun GallerySearchUiState.withEditedQuery(value: String): GallerySearchUiState {
    if (value == query) return this
    return GallerySearchUiState(query = value, terminal = value.isBlank())
}

data class PhotoEditorSession(
    val media: TimelineMedia,
    val history: EditHistory,
    val content: PhotoEditorContentState,
)

data class VideoEditorSession(
    val media: TimelineMedia,
    val recipe: VideoEditRecipe,
    val content: VideoEditorContentState,
)

data class QuickSlowMotionSaveState(
    val progress: Float? = null,
    val completionGeneration: Long = 0,
)

private data class GalleryRuntime(
    val database: GalleryDatabase,
    val timeline: GalleryTimelineRepository,
    val scanner: InitialMediaScanner,
    val synchronizer: IncrementalMediaSynchronizer,
    val generations: MediaStoreGenerationProbe,
    val thumbnails: ThumbnailLoader,
    val decoder: NativeImageDecoder,
    val albums: GalleryAlbumRepository,
    val trash: GalleryTrashRepository,
    val metadata: MediaMetadataRepository,
    val selectionTargets: RoomSelectionTargetSource,
    val viewerMedia: RoomViewerMediaSource,
    val searchIndex: GallerySearchIndexRepository,
    val moments: MomentRepository,
    var monitor: MediaStoreChangeMonitor? = null,
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
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
    val searchIndexReady = mutableSearchIndexReady.asStateFlow()
    private val mlScheduler = MlScheduler(application)
    private val localAnalysisOnboardingStore = LocalAnalysisOnboardingStore(application)
    private val peopleAnalysisTasks = listOf(
        MlTaskType.FaceDetection,
        MlTaskType.FaceEmbeddings,
        MlTaskType.PersonClustering,
    )
    private val contentAnalysisTasks = listOf(MlTaskType.ImageLabels, MlTaskType.Ocr)
    private val localAnalysisTasks = peopleAnalysisTasks + contentAnalysisTasks
    private val gallerySettingsRepository = GallerySettingsRepository(application)
    val gallerySettings = gallerySettingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        GallerySettings(),
    )

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
    private val mutableLocalAnalysisOnboarding = MutableStateFlow(localAnalysisOnboardingStore.decision())
    val localAnalysisOnboarding = mutableLocalAnalysisOnboarding.asStateFlow()
    private val mutableDetectedContentEnabled = MutableStateFlow(
        contentAnalysisTasks.all(mlScheduler::hasConsent),
    )
    val detectedContentEnabled = mutableDetectedContentEnabled.asStateFlow()
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
        .map { rows ->
            rows.map { row ->
                PersonCardUi(
                    clusterId = row.cluster.clusterId,
                    displayName = row.cluster.displayName,
                    memberCount = row.visibleMemberCount,
                    coverKey = row.coverVolumeName?.let { volume ->
                        row.coverMediaStoreId?.let { id -> MediaKey(volume, id) }
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
    val timeline: Flow<PagingData<TimelineEntry>> = gallerySettings
        .map { it.library }
        .flatMapLatest { library ->
            runtime.filterNotNull().flatMapLatest { it.timeline.timeline(ZoneId.systemDefault(), library) }
        }
        .cachedIn(viewModelScope)
    val thumbnailLoader = runtime.map { it?.thumbnails }.stateIn(
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
                    token = GalleryFolderToken.encode(row.volumeName, row.bucketId),
                    label = "${row.displayName ?: row.bucketId} (${row.itemCount})",
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
    private val mutableSelection = MutableStateFlow<SelectionSpec>(SelectionSpec.explicit())
    val selection = mutableSelection.asStateFlow()
    private val mutableSelectionCount = MutableStateFlow(0L)
    val selectionCount = mutableSelectionCount.asStateFlow()
    private val explicitTargets = linkedMapOf<com.ugallery.core.model.MediaKey, MediaActionTarget>()
    private val mutablePhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val photoState = mutablePhotoState.asStateFlow()
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
    private var pendingWriteMutation: PendingWriteMutation? = savedStateHandle[WriteMutationStateKey]
    private var photoJob: Job? = null
    private var photoEditorJob: Job? = null
    private var videoEditorJob: Job? = null
    private var videoRecipeJob: Job? = null
    private var slowMotionSaveJob: Job? = null
    private var bulkCursor: BulkCursor? = savedStateHandle[BulkStateKey]
    private var favoriteImportCursor: FavoriteImportCursor? = savedStateHandle[FavoriteImportStateKey]
    private val mutableExternalMedia = MutableStateFlow<ExternalMedia?>(null)
    val externalMedia = mutableExternalMedia.asStateFlow()
    private val mutableExternalPhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val externalPhotoState = mutableExternalPhotoState.asStateFlow()
    private val mutableExternalSaved = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val externalSaved = mutableExternalSaved.asSharedFlow()
    private val mutablePhotoEditor = MutableStateFlow<PhotoEditorSession?>(null)
    val photoEditor = mutablePhotoEditor.asStateFlow()
    private val mutableVideoEditor = MutableStateFlow<VideoEditorSession?>(null)
    val videoEditor = mutableVideoEditor.asStateFlow()
    private val mutableQuickSlowMotionSave = MutableStateFlow(QuickSlowMotionSaveState())
    val quickSlowMotionSave = mutableQuickSlowMotionSave.asStateFlow()
    private val mutableSanitizedShare = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val sanitizedShare = mutableSanitizedShare.asSharedFlow()
    private val mutableShareError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val shareError = mutableShareError.asSharedFlow()
    private val mutableSelectedMoment = MutableStateFlow<MomentEntity?>(null)
    val selectedMoment = mutableSelectedMoment.asStateFlow()
    private val refreshMutex = Mutex()

    fun onHardwareVolumeKey() {
        mutableHardwareVolumeKeys.tryEmit(Unit)
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
                return@launch
            }
            runtime.value = created
            semanticModelManager = SemanticModelManager(application, created.database).also { manager ->
                manager.initializeEnabledDefault(
                    mutableLocalAnalysisOnboarding.value == LocalAnalysisOnboardingDecision.Accepted,
                )
                semanticSearchEngine = SemanticSearchEngine(application, created.database, manager)
                viewModelScope.launch { manager.state.collect { mutableSemanticModels.value = it } }
                manager.ensureAutomaticDownload()
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
            refreshLibrary()
            semanticModelManager?.scheduleActiveIndexUpdate()
        }
    }

    fun onForeground() {
        val before = permissions.access.value.unredactedLocation
        val after = permissions.revalidate().unredactedLocation
        if (before && !after) viewModelScope.launch { runtime.value?.metadata?.onLocationPermissionRevoked() }
        viewModelScope.launch { refreshLibrary() }
        revalidateExternalGrant()
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
                faceSearchHits(raw)?.let { hits ->
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
                val semanticHits = runCatching { semanticSearchEngine?.search(raw).orEmpty() }
                    .getOrDefault(emptyList())
                if (isCurrentSearch(generation, raw)) {
                    mutableSearch.value = GallerySearchUiState(
                        raw,
                        fuseSearchHits(page.hits, semanticHits),
                        false,
                        page.isTerminal,
                        false,
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

    fun setSemanticSearchEnabled(enabled: Boolean) { semanticModelManager?.setEnabled(enabled) }
    fun downloadSemanticModel(modelId: String, allowMetered: Boolean) { semanticModelManager?.download(modelId, allowMetered) }
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
        enableAllLocalAnalysis(fullLibrary = true)
        semanticModelManager?.setEnabled(true)
    }

    fun declineLocalAnalysisDefaults() {
        localAnalysisOnboardingStore.setDecision(LocalAnalysisOnboardingDecision.Declined)
        mutableLocalAnalysisOnboarding.value = LocalAnalysisOnboardingDecision.Declined
        semanticModelManager?.setEnabled(false)
        viewModelScope.launch {
            disableAllLocalAnalysis()
            localAnalysisTasks.reversed().forEach { mlScheduler.deleteDerivedData(it) }
            refreshLocalAnalysisControls()
        }
    }

    fun setAllLocalAnalysisEnabled(enabled: Boolean) {
        semanticModelManager?.setEnabled(enabled)
        if (enabled) enableAllLocalAnalysis(fullLibrary = true)
        else viewModelScope.launch { disableAllLocalAnalysis() }
    }

    fun setPeopleAnalysisEnabled(enabled: Boolean) {
        if (enabled) {
            peopleAnalysisTasks.forEach {
                mlScheduler.grantConsent(it)
                mlScheduler.unpause(it)
            }
            nextPeopleTask()?.let { mlScheduler.enqueue(it, MlRunMode.Recent) }
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

    fun setContentAnalysisEnabled(enabled: Boolean) {
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
        viewModelScope.launch {
            disableAllLocalAnalysis()
            localAnalysisTasks.reversed().forEach { mlScheduler.deleteDerivedData(it) }
            refreshLocalAnalysisControls()
        }
    }

    private fun enableAllLocalAnalysis(fullLibrary: Boolean) {
        petSettings.setEnabled(true)
        mutablePetCollectionsEnabled.value = true
        localAnalysisTasks.forEach {
            mlScheduler.grantConsent(it)
            mlScheduler.unpause(it)
        }
        mutableDetectedContentEnabled.value = true
        viewModelScope.launch {
            val mode = if (fullLibrary) MlRunMode.FullLibrary else MlRunMode.Recent
            mlScheduler.restart(MlTaskType.FaceDetection, mode)
            contentAnalysisTasks.forEach { mlScheduler.restart(it, mode) }
            refreshLocalAnalysisControls()
            monitorFaceProgress()
            monitorPeopleProgress()
            monitorPetProgress()
        }
    }

    private suspend fun disableAllLocalAnalysis() {
        localAnalysisTasks.forEach { mlScheduler.setConsent(it, false) }
        petSettings.setEnabled(false)
        mutablePetCollectionsEnabled.value = false
        faceProgressJob?.cancel()
        peopleProgressJob?.cancel()
        petProgressJob?.cancel()
        refreshLocalAnalysisControls()
    }

    private fun refreshLocalAnalysisControls() {
        mutableDetectedContentEnabled.value = contentAnalysisTasks.all(mlScheduler::hasConsent)
        mutableFaceAnalysis.value = mlScheduler.controlState(MlTaskType.FaceDetection)
        mutablePeopleAnalysis.value = peopleControlState()
        mutablePetAnalysis.value = mlScheduler.controlState(MlTaskType.ImageLabels)
    }

    fun enableDetectedContent() {
        listOf(MlTaskType.ImageLabels, MlTaskType.Ocr).forEach {
            mlScheduler.grantConsent(it); mlScheduler.enqueue(it, MlRunMode.Recent)
        }
        mutableDetectedContentEnabled.value = true
    }

    fun pauseDetectedContent() {
        listOf(MlTaskType.ImageLabels, MlTaskType.Ocr).forEach(mlScheduler::pause)
        mutableDetectedContentEnabled.value = false
    }

    fun deleteDetectedContent() {
        viewModelScope.launch {
            mlScheduler.deleteDerivedData(MlTaskType.ImageLabels)
            mlScheduler.deleteDerivedData(MlTaskType.Ocr)
            mutableDetectedContentEnabled.value = false
        }
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

    fun enablePeopleRecognition() {
        listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach(mlScheduler::grantConsent)
        mlScheduler.enqueue(MlTaskType.FaceDetection, MlRunMode.Recent)
        mutablePeopleAnalysis.value = peopleControlState()
        monitorFaceProgress()
        monitorPeopleProgress()
    }

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
        nextPeopleTask()?.let { mlScheduler.enqueue(it, MlRunMode.Recent) }
        mutablePeopleAnalysis.value = peopleControlState()
        monitorFaceProgress()
        monitorPeopleProgress()
    }

    fun analyzeAllPeople() {
        viewModelScope.launch {
            listOf(MlTaskType.PersonClustering, MlTaskType.FaceEmbeddings, MlTaskType.FaceDetection).forEach {
                mlScheduler.deleteDerivedData(it)
            }
            peopleRefreshGeneration.value++
            listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach(mlScheduler::grantConsent)
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
        viewModelScope.launch {
            val repo = runtime.value?.database?.let(::PeopleRepository) ?: return@launch
            mutableSelectedPersonMembers.value = repo.members(clusterId).map {
                PersonMemberCardUi(MediaKey(it.media.volumeName, it.media.mediaStoreId), it.membership.faceOrdinal)
            }
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

    fun enablePetCollections() {
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

    fun disablePetCollections() {
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
                    current.status == com.ugallery.core.ml.MlCheckpoint.Status.Complete
                ) {
                    // The summary joins label and media tables. Re-subscribe after the final
                    // chunk so every device observes the complete counts immediately.
                    petRefreshGeneration.value++
                    break
                }
                if (!current.consentGranted || current.paused) break
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
                    current.status == com.ugallery.core.ml.MlCheckpoint.Status.Complete
                ) break
                delay(500)
            }
        }
    }

    private fun peopleControlState(): MlControlState {
        val tasks = listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering)
        val states = tasks.map(mlScheduler::controlState)
        val statuses = states.mapNotNull { it.status }
        val status = when {
            statuses.any { it == com.ugallery.core.ml.MlCheckpoint.Status.Running } -> com.ugallery.core.ml.MlCheckpoint.Status.Running
            states.any { it.paused } -> com.ugallery.core.ml.MlCheckpoint.Status.Paused
            statuses.size == tasks.size && statuses.all { it == com.ugallery.core.ml.MlCheckpoint.Status.Complete } -> com.ugallery.core.ml.MlCheckpoint.Status.Complete
            statuses.isNotEmpty() || states.any { it.requested } -> com.ugallery.core.ml.MlCheckpoint.Status.Ready
            else -> null
        }
        val active = states.firstOrNull { it.status == com.ugallery.core.ml.MlCheckpoint.Status.Running }
            ?: states.firstOrNull { it.requested }
            ?: states.firstOrNull { it.status != com.ugallery.core.ml.MlCheckpoint.Status.Complete }
        return MlControlState(
            consentGranted = states.all { it.consentGranted },
            paused = states.any { it.paused },
            completedItems = states.sumOf { it.completedItems },
            status = status,
            requested = states.any { it.requested },
            runMode = active?.runMode,
            activeTask = active?.activeTask,
        )
    }

    private fun nextPeopleTask(): MlTaskType? {
        val face = mlScheduler.checkpoint(MlTaskType.FaceDetection)
        val embeddings = mlScheduler.checkpoint(MlTaskType.FaceEmbeddings)
        val clustering = mlScheduler.checkpoint(MlTaskType.PersonClustering)
        return when {
            face?.status != com.ugallery.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.FaceDetection
            embeddings?.status != com.ugallery.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.FaceEmbeddings
            clustering?.status != com.ugallery.core.ml.MlCheckpoint.Status.Complete -> MlTaskType.PersonClustering
            else -> null
        }
    }

    private fun monitorPeopleProgress() {
        peopleProgressJob?.cancel()
        peopleProgressJob = viewModelScope.launch {
            while (isActive) {
                val next = nextPeopleTask()
                if (next != null && mlScheduler.hasConsent(next)) mlScheduler.enqueue(next, MlRunMode.Recent)
                mutablePeopleAnalysis.value = peopleControlState()
                refreshMeState()
                if (next == null) {
                    // Person clustering writes several related tables in one background pass.
                    // Re-subscribing guarantees the UI observes the completed projection even
                    // on devices where Room's multi-table invalidation arrives late.
                    peopleRefreshGeneration.value++
                    break
                }
                if (listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).any {
                    mlScheduler.controlState(it).paused
                }) break
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
                    it.status == com.ugallery.core.ml.MlCheckpoint.Status.Running
                }
                val complete = checkpoints.size == tasks.size && checkpoints.all {
                        it.status == com.ugallery.core.ml.MlCheckpoint.Status.Complete
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
        val mime = getApplication<Application>().contentResolver.getType(uri) ?: intent.type
        val kind = when {
            mime?.startsWith("image/") == true -> MediaKind.Image
            mime?.startsWith("video/") == true -> MediaKind.Video
            else -> return false
        }
        val available = canOpen(uri)
        mutableExternalMedia.value = ExternalMedia(uri, mime, kind, action == Intent.ACTION_EDIT, available)
        if (available && kind == MediaKind.Image) loadExternalPhoto(uri)
        return true
    }

    fun clearExternal() {
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
            val name = "UGallery-copy-${System.currentTimeMillis()}.$extension"
            val copy = PendingMediaWriter(getApplication<Application>().contentResolver).copy(
                external.uri,
                MediaWriteSpec(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    external.kind,
                    name,
                    concreteMime,
                    if (external.kind == MediaKind.Image) "Pictures/UGallery" else "Movies/UGallery",
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
        val before = mutableSelection.value
        val wasSelected = SelectionReducer.isSelected(before, media.key)
        val updated = SelectionReducer.toggle(before, media.key)
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
                    mutableSelectionCount.value + if (wasSelected) -1 else 1
                ).coerceAtLeast(0)
            }
        }
    }

    fun clearSelection() {
        mutableSelection.value = SelectionReducer.clear()
        explicitTargets.clear()
        mutableSelectionCount.value = 0
    }

    fun selectAllAlbum(album: AlbumSummary, filter: AlbumMediaFilter, sort: AlbumSort) {
        val query = albumQuery(album.key, filter, sort)
        mutableSelection.value = SelectionSpec.queryAll(query)
        explicitTargets.clear()
        viewModelScope.launch {
            mutableSelectionCount.value = runtime.value?.selectionTargets?.count(query) ?: 0
        }
    }

    fun selectAllTimeline() = selectAll(currentLibraryQuery())

    private fun selectAll(query: MediaQuery) {
        mutableSelection.value = SelectionSpec.queryAll(query)
        explicitTargets.clear()
        viewModelScope.launch {
            mutableSelectionCount.value = runtime.value?.selectionTargets?.count(query) ?: 0
        }
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
        SelectionSpec.queryAll(MediaQuery(trashedOnly = true)),
        MediaAction.Delete,
    )

    fun createVirtualAlbum(name: String) {
        viewModelScope.launch { runtime.value?.albums?.createVirtualAlbum(name) }
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
                    "UGallery-collage-${System.currentTimeMillis()}.jpg",
                    "image/jpeg",
                    "Pictures/UGallery",
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
        mutableSelectedMoment.value = null
        mutableCheapDetails.value = null
        mutableExifDetails.value = null
        mutableDetectedText.value = null
        mutablePhotoState.value = null
        photoJob?.cancel()
        if (media.kind == MediaKind.Image) {
            val active = runtime.value ?: return
            photoJob = viewModelScope.launch {
                PhotoViewerPipeline(active.decoder).load(media.uri(), 1_440, 3_120)
                    .collect { mutablePhotoState.value = it }
            }
        }
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
        }
    }

    fun openPhotoEditor(media: TimelineMedia) {
        if (media.kind != MediaKind.Image) return
        mutableCurrentMedia.value = media
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val initial = withContext(Dispatchers.IO) {
                active.database.editRecipeDao().load(
                    EditRecipe.forSource(media.key, media.generationModified).recipeId,
                )
            } ?: EditRecipe.forSource(media.key, media.generationModified)
            val storedMedia = withContext(Dispatchers.IO) {
                active.database.libraryDao().media(media.key.volumeName, media.key.mediaStoreId)
            }
            val isRaw = isRawMimeOrName(storedMedia?.mimeType, storedMedia?.displayName)
            val rawSettings = initial.operations.filterIsInstance<EditOperation.RawDevelop>()
                .lastOrNull()?.settings ?: RawDevelopmentSettings()
            val rawMetadata = if (isRaw) runCatching {
                RawDeveloper(
                    getApplication<Application>().contentResolver,
                    java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                ).inspect(mediaUri(media))
            }.getOrNull() else null
            mutablePhotoEditor.value = PhotoEditorSession(
                media = media,
                history = EditHistory.initial(initial),
                content = PhotoEditorContentState(
                    isRendering = true,
                    selectedFilter = selectedFilter(initial),
                    isRaw = isRaw,
                    rawMetadata = rawMetadata,
                    rawSettings = rawSettings,
                ),
            )
            renderPhotoEditorPreview(active, media, initial)
        }
    }

    fun setRawDevelopment(settings: RawDevelopmentSettings) {
        val session = mutablePhotoEditor.value ?: return
        if (!session.content.isRaw) return
        val updated = session.history.applyRawDevelopment(settings)
        mutablePhotoEditor.value = session.copy(
            history = updated,
            content = session.content.copy(
                rawSettings = settings,
                isRendering = true,
                canUndo = true,
                canRedo = false,
                statusMessage = null,
            ),
        )
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            delay(60)
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.editRecipeDao().replace(updated.present, System.currentTimeMillis())
            }
            renderPhotoEditorPreview(active, session.media, updated.present)
        }
    }

    fun setRawOutputFormat(format: RawOutputFormat) {
        val session = mutablePhotoEditor.value ?: return
        mutablePhotoEditor.value = session.copy(content = session.content.copy(rawOutputFormat = format))
    }

    fun applyPhotoEdit(operation: EditOperation) {
        val session = mutablePhotoEditor.value ?: return
        val updated = session.history.apply(operation)
        mutablePhotoEditor.value = session.copy(
            history = updated,
            content = session.content.copy(
                isRendering = true,
                canUndo = updated.past.isNotEmpty(),
                canRedo = false,
                selectedFilter = selectedFilter(updated.present),
                statusMessage = null,
            ),
        )
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.editRecipeDao().replace(updated.present, System.currentTimeMillis())
            }
            renderPhotoEditorPreview(active, session.media, updated.present)
        }
    }

    fun undoPhotoEdit() = movePhotoHistory { it.undo() }
    fun redoPhotoEdit() = movePhotoHistory { it.redo() }

    private fun movePhotoHistory(transform: (EditHistory) -> EditHistory) {
        val session = mutablePhotoEditor.value ?: return
        val updated = transform(session.history)
        if (updated == session.history) return
        mutablePhotoEditor.value = session.copy(
            history = updated,
            content = session.content.copy(
                isRendering = true,
                canUndo = updated.past.isNotEmpty(),
                canRedo = updated.future.isNotEmpty(),
                selectedFilter = selectedFilter(updated.present),
                statusMessage = null,
            ),
        )
        photoEditorJob?.cancel()
        photoEditorJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.editRecipeDao().replace(updated.present, System.currentTimeMillis())
            }
            renderPhotoEditorPreview(active, session.media, updated.present)
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
                active.database.libraryDao().media(session.media.key.volumeName, session.media.key.mediaStoreId)?.mimeType
            }
            val outputMime = if (session.content.isRaw) {
                if (session.content.rawOutputFormat == RawOutputFormat.JpegSrgb) "image/jpeg" else "image/tiff"
            } else imageExportMime(storedMime)
            val extension = if (outputMime == "image/tiff") "tif" else
                MimeTypeMap.getSingleton().getExtensionFromMimeType(outputMime) ?: "jpg"
            val temp = java.io.File(getApplication<Application>().cacheDir, "photo-edit-${System.nanoTime()}.$extension")
            try {
                if (session.content.isRaw) {
                    when (val result = RawDeveloper(
                        getApplication<Application>().contentResolver,
                        java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                    ).export(mediaUri(session.media), session.content.rawSettings, session.content.rawOutputFormat, temp)) {
                        is RawExportOutcome.Completed -> publishPhotoResult(result.file, result.mimeType, extension, result.warnings)
                        is RawExportOutcome.Failure -> updatePhotoExportFailure(result.reason)
                    }
                } else when (val result = PhotoImageRenderer(getApplication<Application>().contentResolver).export(
                    mediaUri(session.media), session.history.present, temp,
                )) {
                    is PhotoExportOutcome.Completed -> publishPhotoResult(
                        result.file, result.mimeType ?: outputMime, extension, result.warnings,
                    )
                    is PhotoExportOutcome.Failure -> updatePhotoExportFailure(result.reason)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutablePhotoEditor.value = mutablePhotoEditor.value?.copy(
                    content = mutablePhotoEditor.value!!.content.copy(
                        isExporting = false,
                        statusMessage = failure.message ?: "Could not save copy",
                    ),
                )
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun publishPhotoResult(file: java.io.File, mimeType: String, fallbackExtension: String, warnings: List<String>) {
        val publishedExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: fallbackExtension
        PendingMediaWriter(getApplication<Application>().contentResolver).publishFile(
            file,
            MediaWriteSpec(
                MediaStore.VOLUME_EXTERNAL_PRIMARY, MediaKind.Image,
                "UGallery-edited-${System.currentTimeMillis()}.$publishedExtension",
                mimeType, "Pictures/UGallery",
            ),
        )
        mutablePhotoEditor.value = mutablePhotoEditor.value?.copy(
            content = mutablePhotoEditor.value!!.content.copy(
                isExporting = false,
                statusMessage = buildString {
                    append(getApplication<Application>().getString(
                        com.ugallery.feature.photoeditor.R.string.photo_editor_copy_saved,
                    ))
                    if (warnings.isNotEmpty()) append(" — ").append(warnings.joinToString("; "))
                },
            ),
        )
        refreshLibrary()
    }

    private fun updatePhotoExportFailure(reason: String) {
        mutablePhotoEditor.value = mutablePhotoEditor.value?.copy(
            content = mutablePhotoEditor.value!!.content.copy(isExporting = false, statusMessage = reason),
        )
    }

    fun closePhotoEditor() {
        val session = mutablePhotoEditor.value
        photoEditorJob?.cancel()
        if (session != null) viewModelScope.launch {
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.editRecipeDao().replace(session.history.present, System.currentTimeMillis())
            }
        }
        session?.content?.preview?.recycle()
        mutablePhotoEditor.value = null
    }

    private suspend fun renderPhotoEditorPreview(
        active: GalleryRuntime,
        media: TimelineMedia,
        recipe: EditRecipe,
    ) {
        val currentBeforeRender = mutablePhotoEditor.value
        val preview = try {
            if (currentBeforeRender?.content?.isRaw == true) {
                RawDeveloper(
                    getApplication<Application>().contentResolver,
                    java.io.File(getApplication<Application>().cacheDir, "raw-scratch"),
                ).renderPreview(mediaUri(media), currentBeforeRender.content.rawSettings, 1_600)
            } else PhotoImageRenderer(getApplication<Application>().contentResolver)
                .renderPreview(mediaUri(media), recipe, 1_600)
        } catch (failure: Throwable) {
            val current = mutablePhotoEditor.value
            if (current?.history?.present?.revision == recipe.revision) {
                mutablePhotoEditor.value = current.copy(
                    content = current.content.copy(isRendering = false, statusMessage = failure.message),
                )
            }
            return
        }
        val current = mutablePhotoEditor.value
        if (current?.history?.present?.revision == recipe.revision) {
            current.content.preview?.takeIf { it !== preview }?.recycle()
            mutablePhotoEditor.value = current.copy(
                content = current.content.copy(preview = preview, isRendering = false),
            )
        } else preview.recycle()
    }

    fun openVideoEditor(media: TimelineMedia) {
        if (media.kind != MediaKind.Video) return
        mutableCurrentMedia.value = media
        videoEditorJob?.cancel()
        videoEditorJob = viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val stored = withContext(Dispatchers.IO) {
                active.database.colorEditDao().videoRecipe(
                    media.key.volumeName, media.key.mediaStoreId, media.generationModified,
                )
            }?.let { runCatching { VideoEditRecipeCodec.decode(it.encodedRecipe) }.getOrNull() }
            val detection = LogProfileDetector(getApplication<Application>()).detect(mediaUri(media))
            val loadedRecipe = stored ?: VideoEditRecipe(
                colorGrade = VideoColorGrade(
                    inputProfile = detection.profile,
                    profileWasAutoDetected = detection.confidence >= 0.8f,
                ),
            )
            // Version-one recipes represented slow motion as a global speed. Convert that
            // legacy shape into the new editable full-range segment when the duration is known.
            val legacySegmentEnd = loadedRecipe.endMillis ?: media.durationMillis
            val detectedRecipe = if (
                loadedRecipe.speed in setOf(0.5f, 0.25f, 0.125f) &&
                loadedRecipe.slowMotionSegments.isEmpty() &&
                legacySegmentEnd > loadedRecipe.startMillis
            ) {
                loadedRecipe.copy(
                    speed = 1f,
                    slowMotionSegments = listOf(
                        SlowMotionSegment(
                            startMillis = loadedRecipe.startMillis,
                            endMillis = legacySegmentEnd,
                            speed = loadedRecipe.speed,
                        ),
                    ),
                )
            } else loadedRecipe
            val supportsMain10 = VideoOutputCapabilities.supportsHevcMain10()
            val recipe = if (detectedRecipe.outputQuality == VideoOutputQuality.HevcMain10 && !supportsMain10) {
                detectedRecipe.copy(outputQuality = VideoOutputQuality.H264Compatible)
            } else detectedRecipe
            val lutRepository = lutRepository(active)
            val customLut = recipe.colorGrade.lut.customId?.let { lutRepository.load(it) }
            mutableVideoEditor.value = VideoEditorSession(
                media = media,
                recipe = recipe,
                content = VideoEditorContentState(
                    durationMillis = media.durationMillis,
                    trimStartMillis = recipe.startMillis,
                    trimEndMillis = recipe.endMillis ?: media.durationMillis,
                    speed = recipe.speed,
                    originalAudioVolume = recipe.originalAudioVolume,
                    selectedMusicName = recipe.musicUri?.lastPathSegment,
                    colorGrade = recipe.colorGrade,
                    customLuts = lutRepository.summaries(),
                    activeCustomLut = customLut,
                    outputQuality = recipe.outputQuality,
                    isHevcMain10Available = supportsMain10,
                    slowMotionSegments = recipe.slowMotionSegments,
                    logDetectionMessage = if (detection.confidence >= 0.8f) {
                        getApplication<Application>().getString(
                            com.ugallery.feature.videoeditor.R.string.video_editor_detected_profile,
                            detection.profile.displayName,
                        )
                    } else getApplication<Application>().getString(
                        com.ugallery.feature.videoeditor.R.string.video_editor_profile_unknown,
                    ),
                ),
            )
        }
    }

    fun setVideoTrim(startMillis: Long, endMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val duration = session.content.durationMillis.coerceAtLeast(1)
        val start = startMillis.coerceIn(0, duration - 1)
        val end = endMillis.coerceIn(start + 1, duration)
        val adjustedSegments = session.recipe.slowMotionSegments.mapNotNull { segment ->
            val adjustedStart = segment.startMillis.coerceAtLeast(start)
            val adjustedEnd = segment.endMillis.coerceAtMost(end)
            if (adjustedEnd <= adjustedStart) null else segment.copy(
                startMillis = adjustedStart,
                endMillis = adjustedEnd,
            )
        }
        val recipe = session.recipe.copy(
            startMillis = start,
            endMillis = end,
            slowMotionSegments = adjustedSegments,
        )
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                trimStartMillis = start,
                trimEndMillis = end,
                slowMotionSegments = adjustedSegments,
                selectedSlowMotionSegmentId = session.content.selectedSlowMotionSegmentId
                    ?.takeIf { id -> adjustedSegments.any { it.id == id } },
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun setVideoSpeed(speed: Float) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(speed = speed)
        mutableVideoEditor.value = session.copy(recipe = recipe, content = session.content.copy(speed = speed))
        persistVideoRecipe(recipe)
    }

    fun markVideoSlowMotionIn(positionMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val position = positionMillis.coerceIn(
            session.content.trimStartMillis,
            session.content.trimEndMillis,
        )
        mutableVideoEditor.value = session.copy(
            content = session.content.copy(slowMotionMarkInMillis = position, statusMessage = null),
        )
    }

    fun markVideoSlowMotionOut(positionMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        val start = session.content.slowMotionMarkInMillis ?: return
        val end = positionMillis.coerceAtMost(session.content.trimEndMillis)
        if (end <= start) {
            mutableVideoEditor.value = session.copy(content = session.content.copy(
                statusMessage = getApplication<Application>().getString(
                    com.ugallery.feature.videoeditor.R.string.video_editor_slow_invalid_range,
                ),
            ))
            return
        }
        val candidate = SlowMotionSegment(startMillis = start, endMillis = end)
        if (session.recipe.slowMotionSegments.any { it.startMillis < end && start < it.endMillis }) {
            mutableVideoEditor.value = session.copy(content = session.content.copy(
                statusMessage = getApplication<Application>().getString(
                    com.ugallery.feature.videoeditor.R.string.video_editor_slow_overlap,
                ),
            ))
            return
        }
        val segments = (session.recipe.slowMotionSegments + candidate).sortedBy(SlowMotionSegment::startMillis)
        val recipe = session.recipe.copy(slowMotionSegments = segments)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
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
    }

    fun updateVideoSlowMotionSegment(segment: SlowMotionSegment) {
        val session = mutableVideoEditor.value ?: return
        val replacement = session.recipe.slowMotionSegments.map {
            if (it.id == segment.id) segment else it
        }.sortedBy(SlowMotionSegment::startMillis)
        if (replacement == session.recipe.slowMotionSegments ||
            replacement.zipWithNext().any { (left, right) -> left.endMillis > right.startMillis }
        ) return
        val recipe = session.recipe.copy(slowMotionSegments = replacement)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(slowMotionSegments = replacement, statusMessage = null),
        )
        persistVideoRecipe(recipe)
    }

    fun deleteVideoSlowMotionSegment(id: String) {
        val session = mutableVideoEditor.value ?: return
        val segments = session.recipe.slowMotionSegments.filterNot { it.id == id }
        val recipe = session.recipe.copy(slowMotionSegments = segments)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(
                slowMotionSegments = segments,
                selectedSlowMotionSegmentId = null,
                statusMessage = null,
            ),
        )
        persistVideoRecipe(recipe)
    }

    fun cancelVideoExport() {
        videoEditorJob?.cancel()
        mutableVideoEditor.value = mutableVideoEditor.value?.let { session ->
            session.copy(content = session.content.copy(isExporting = false, exportProgress = null))
        }
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
                            mutableQuickSlowMotionSave.value = mutableQuickSlowMotionSave.value.copy(progress = progress)
                        },
                    ),
                )
                PendingMediaWriter(getApplication<Application>().contentResolver).publishFile(
                    temp,
                    MediaWriteSpec(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        MediaKind.Video,
                        "UGallery-slow-motion-${System.currentTimeMillis()}.mp4",
                        "video/mp4",
                        "Movies/UGallery",
                    ),
                )
                mutableQuickSlowMotionSave.value = QuickSlowMotionSaveState(
                    completionGeneration = mutableQuickSlowMotionSave.value.completionGeneration + 1,
                )
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.e("UGallerySlowMotion", "Quick slow-motion export failed", failure)
                mutableQuickSlowMotionSave.value = mutableQuickSlowMotionSave.value.copy(progress = null)
                mutableShareError.emit(
                    getApplication<Application>().getString(
                        com.ugallery.feature.viewer.R.string.viewer_slow_motion_save_failed,
                    ),
                )
            } finally {
                temp.delete()
            }
        }
    }

    fun setVideoOriginalVolume(volume: Float) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(originalAudioVolume = volume)
        mutableVideoEditor.value = session.copy(recipe = recipe, content = session.content.copy(originalAudioVolume = volume))
        persistVideoRecipe(recipe)
    }

    fun setVideoMusic(uri: Uri, displayName: String) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(musicUri = uri)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(selectedMusicName = displayName),
        )
        persistVideoRecipe(recipe)
    }

    fun removeVideoMusic() {
        val session = mutableVideoEditor.value ?: return
        mutableVideoEditor.value = session.copy(
            recipe = session.recipe.copy(musicUri = null),
            content = session.content.copy(selectedMusicName = null),
        )
        persistVideoRecipe(session.recipe.copy(musicUri = null))
    }

    fun setVideoColorGrade(grade: VideoColorGrade) {
        val session = mutableVideoEditor.value ?: return
        val recipe = session.recipe.copy(colorGrade = grade)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(colorGrade = grade, activeCustomLut = null, statusMessage = null),
        )
        persistVideoRecipe(recipe)
        val customId = grade.lut.customId ?: return
        viewModelScope.launch {
            val active = runtime.value ?: return@launch
            val lut = lutRepository(active).load(customId)
            val current = mutableVideoEditor.value
            if (current?.recipe?.colorGrade?.lut?.customId == customId) {
                mutableVideoEditor.value = current.copy(content = current.content.copy(activeCustomLut = lut))
            }
        }
    }

    fun setVideoOutputQuality(quality: VideoOutputQuality) {
        val session = mutableVideoEditor.value ?: return
        if (quality == VideoOutputQuality.HevcMain10 && !session.content.isHevcMain10Available) return
        val recipe = session.recipe.copy(outputQuality = quality)
        mutableVideoEditor.value = session.copy(
            recipe = recipe,
            content = session.content.copy(outputQuality = quality),
        )
        persistVideoRecipe(recipe)
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
                            com.ugallery.feature.videoeditor.R.string.video_editor_lut_imported,
                        ),
                    ))
                }
                val session = mutableVideoEditor.value ?: return@launch
                setVideoColorGrade(session.recipe.colorGrade.copy(lut = com.ugallery.core.editing.video.LutReference(customId = id)))
            } catch (failure: Throwable) {
                mutableVideoEditor.value = mutableVideoEditor.value?.let { session ->
                    session.copy(content = session.content.copy(statusMessage = failure.message ?:
                        getApplication<Application>().getString(
                            com.ugallery.feature.videoeditor.R.string.video_editor_lut_import_failed,
                        )))
                }
            }
        }
    }

    private fun persistVideoRecipe(recipe: VideoEditRecipe) {
        val session = mutableVideoEditor.value ?: return
        videoRecipeJob?.cancel()
        videoRecipeJob = viewModelScope.launch {
            delay(100)
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.colorEditDao().saveVideoRecipe(
                    VideoEditRecipeEntity(
                        session.media.key.volumeName,
                        session.media.key.mediaStoreId,
                        session.media.generationModified,
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

    fun seekVideo(positionMillis: Long) {
        val session = mutableVideoEditor.value ?: return
        mutableVideoEditor.value = session.copy(content = session.content.copy(currentMillis = positionMillis))
    }

    fun saveVideoEditorCopy() {
        val session = mutableVideoEditor.value ?: return
        if (session.content.isExporting) return
        mutableVideoEditor.value = session.copy(content = session.content.copy(
            isExporting = true,
            exportProgress = if (session.recipe.slowMotionSegments.isEmpty()) null else 0f,
            statusMessage = null,
        ))
        videoEditorJob?.cancel()
        videoEditorJob = viewModelScope.launch {
            val temp = java.io.File(getApplication<Application>().cacheDir, "video-edit-${System.nanoTime()}.mp4")
            try {
                val result = Media3VideoExporter(getApplication<Application>()).export(
                    VideoExportRequest(
                        mediaUri(session.media), temp, session.recipe,
                        customLut = session.content.activeCustomLut,
                        onProgress = { progress ->
                            mutableVideoEditor.value = mutableVideoEditor.value?.let { current ->
                                current.copy(content = current.content.copy(exportProgress = progress))
                            }
                        },
                    ),
                )
                PendingMediaWriter(getApplication<Application>().contentResolver).publishFile(
                    result.output,
                    MediaWriteSpec(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        MediaKind.Video,
                        "UGallery-edited-${System.currentTimeMillis()}.mp4",
                        "video/mp4",
                        "Movies/UGallery",
                    ),
                )
                mutableVideoEditor.value = mutableVideoEditor.value?.copy(
                    content = mutableVideoEditor.value!!.content.copy(
                        isExporting = false,
                        exportProgress = null,
                        statusMessage = getApplication<Application>().getString(
                            if (result.fallbackWarning == null) {
                                com.ugallery.feature.videoeditor.R.string.video_editor_copy_saved
                            } else com.ugallery.feature.videoeditor.R.string.video_editor_encoder_fallback
                        ),
                    ),
                )
                refreshLibrary()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                android.util.Log.e("UGalleryVideoEditor", "Video copy export failed", failure)
                mutableVideoEditor.value = mutableVideoEditor.value?.copy(
                    content = mutableVideoEditor.value!!.content.copy(
                        isExporting = false,
                        exportProgress = null,
                        statusMessage = failure.message ?: getApplication<Application>().getString(
                            com.ugallery.feature.videoeditor.R.string.video_editor_save_failed,
                        ),
                    ),
                )
            } finally {
                temp.delete()
            }
        }
    }

    fun closeVideoEditor() {
        val session = mutableVideoEditor.value
        videoEditorJob?.cancel()
        videoRecipeJob?.cancel()
        if (session != null) viewModelScope.launch {
            val active = runtime.value ?: return@launch
            withContext(Dispatchers.IO) {
                active.database.colorEditDao().saveVideoRecipe(
                    VideoEditRecipeEntity(
                        session.media.key.volumeName,
                        session.media.key.mediaStoreId,
                        session.media.generationModified,
                        VideoEditRecipeCodec.encode(session.recipe),
                        System.currentTimeMillis(),
                    ),
                )
            }
        }
        mutableVideoEditor.value = null
    }

    private fun selectedFilter(recipe: EditRecipe): String =
        recipe.operations.asReversed().filterIsInstance<EditOperation.Filter>().firstOrNull()?.name ?: "none"

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
                mutableShareError.emit(failure.message ?: "Could not prepare a sanitized share copy")
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
        require(targets.size <= MediaActionReducer.MaxChunkSize)
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

    suspend fun copyMediaToTree(media: TimelineMedia, treeUri: Uri, move: Boolean): Result<Uri> = try {
        val resolver = getApplication<Application>().contentResolver
        val target = MediaActionTarget(media.key, media.kind)
        val destination = ScopedMediaOperations.copyToTree(
            resolver = resolver,
            target = target,
            treeUri = treeUri,
            displayName = media.displayName ?: "UGallery-${media.key.mediaStoreId}.${if (media.kind == MediaKind.Video) "mp4" else "jpg"}",
            mimeType = mediaMime(media.kind),
            lastModifiedMillis = media.dateModifiedSeconds.takeIf {
                gallerySettings.value.operations.keepLastModifiedWhenPossible
            }?.times(1_000L),
        )
        if (move) beginSystemAction(media, MediaAction.Delete)
        Result.success(destination)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    fun openMoment(momentId: String) {
        val repo = runtime.value?.moments ?: return
        viewModelScope.launch {
            mutableSelectedMoment.value = repo.summaries().first().firstOrNull { it.moment.momentId == momentId }?.moment
        }
    }

    suspend fun saveMoment() {
        selectedMoment.value?.let { runtime.value?.moments?.save(it.momentId) }
    }

    fun deleteSelectedMoment() {
        selectedMoment.value?.let { viewModelScope.launch { runtime.value?.moments?.delete(it.momentId) } }
    }

    suspend fun renameMoment(title: String) {
        selectedMoment.value?.let { runtime.value?.moments?.rename(it.momentId, title) }
    }

    suspend fun reorderMoment(orderedKeys: List<MediaKey>) {
        selectedMoment.value?.let { runtime.value?.moments?.reorder(it.momentId, orderedKeys) }
    }

    suspend fun setMomentCover(ordinal: Int) {
        val moment = selectedMoment.value ?: return
        val target = momentMembers.value.firstOrNull { it.member.ordinal == ordinal } ?: return
        runtime.value?.moments?.setCover(moment.momentId, target.key)
    }

    val momentMembers = selectedMoment.filterNotNull().flatMapLatest { moment ->
        runtime.value?.moments?.members(moment.momentId)?.let { rows: List<MomentMemberRow> ->
            flowOf(rows.map { row: MomentMemberRow -> MomentMemberUi(row.member, MediaKey(row.media.volumeName, row.media.mediaStoreId)) })
        } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun beginTargetsAction(targets: List<MediaActionTarget>, action: MediaAction) {
        require(targets.size <= MediaActionReducer.MaxChunkSize)
        val sizedInitial = MediaActionReducer.start(action, targets.size.toLong())
        val coordinator = coordinator(sizedInitial)
        currentSystemCoordinator = coordinator
        mutableActionLaunches.tryEmit(coordinator.stageChunk(targets))
    }

    fun resumePendingSystemAction() {
        val snapshot = mutableSystemAction.value ?: return
        val coordinator = coordinator(snapshot)
        currentSystemCoordinator = coordinator
        when (snapshot.phase) {
            is com.ugallery.core.mediastore.MediaActionPhase.AwaitingSystem -> {
                runCatching { coordinator.recreateCurrentRequest() }
                    .getOrNull()?.let(mutableActionLaunches::tryEmit)
            }
            com.ugallery.core.mediastore.MediaActionPhase.ReadyForChunk -> {
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

    fun retrySystemAction() {
        currentSystemCoordinator?.let { coordinator ->
            runCatching { coordinator.retryCurrent() }.getOrNull()?.let(mutableActionLaunches::tryEmit)
        }
    }

    fun onSystemActionResult(requestId: Long, approved: Boolean) {
        viewModelScope.launch {
            val approvedTargets = (currentSystemCoordinator?.snapshot?.value?.phase as?
                com.ugallery.core.mediastore.MediaActionPhase.AwaitingSystem)?.targets.orEmpty()
            val approvedAction = currentSystemCoordinator?.snapshot?.value?.progress?.action
            val snapshot = currentSystemCoordinator?.onSystemResult(requestId, approved)
            if (approved && approvedAction == MediaAction.Write) {
                applyPendingWriteMutation(approvedTargets.singleOrNull())
            }
            if (approved) approvedTargets.forEach { runtime.value?.synchronizer?.applyRowHint(it.key) }
            if (snapshot?.phase == com.ugallery.core.mediastore.MediaActionPhase.ReadyForChunk) {
                when {
                    bulkCursor != null -> stageNextBulkChunk()
                    favoriteImportCursor != null -> stageNextFavoriteImportChunk()
                }
            } else if (snapshot?.phase == com.ugallery.core.mediastore.MediaActionPhase.Complete) {
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
            mutableShareError.emit("The approved item did not match the pending media change")
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                when (mutation) {
                    is PendingWriteMutation.Rename -> ScopedMediaOperations.rename(resolver, authorizedTarget, mutation.displayName)
                    is PendingWriteMutation.DateTaken -> ScopedMediaOperations.repairDateTaken(
                        resolver,
                        authorizedTarget,
                        mutation.dateTakenMillis,
                    )
                }
            }
        }.onFailure { mutableShareError.emit(it.message ?: "The media change could not be applied") }
    }

    fun mediaUri(media: TimelineMedia): Uri = media.uri()

    private suspend fun refreshLibrary(
        indexedHintCount: Int = 0,
        forceFullReconciliation: Boolean = false,
    ): Unit = refreshMutex.withLock {
        val active = runtime.value ?: return@withLock
        if (access.value.images == com.ugallery.core.model.GrantLevel.None &&
            access.value.videos == com.ugallery.core.model.GrantLevel.None
        ) {
            mutableEngineState.value = LibraryEngineState.PermissionRequired
            return@withLock
        }
        mutableEngineState.value = LibraryEngineState.Indexing
        try {
            var requiresSearchRebuild = false
            var changedItems = 0L
            val completed = withContext(Dispatchers.IO) {
                for (volume in active.generations.snapshot()) {
                    if (forceFullReconciliation) {
                        active.scanner.scan(volume)
                        requiresSearchRebuild = true
                        continue
                    }
                    when (val result = active.synchronizer.sync(volume)) {
                        IncrementalSyncResult.NeedsInitialScan,
                        IncrementalSyncResult.NeedsFullVolumeReconciliation,
                        -> { active.scanner.scan(volume); requiresSearchRebuild = true }
                        is IncrementalSyncResult.Complete -> changedItems += result.changedItems
                        is IncrementalSyncResult.PausedPermission -> return@withContext false
                        else -> Unit
                    }
                }
                true
            }
            if (completed) withContext(Dispatchers.IO) {
                val prefs = getApplication<Application>().getSharedPreferences("search-production", Context.MODE_PRIVATE)
                val needsInitial = prefs.getLong("schema", 0) != com.ugallery.core.search.MediaSearchSchema.Version
                if (needsInitial || requiresSearchRebuild || changedItems > indexedHintCount) {
                    active.searchIndex.rebuild(restart = true)
                    prefs.edit().putLong("schema", com.ugallery.core.search.MediaSearchSchema.Version).commit()
                }
                // Moment generation is a resumable Room keyset pass, not a MediaStore scan.
                // Run it once after the initial index; the completed checkpoint prevents
                // repeating the bounded pass on every foreground/startup.
                active.moments.generateIfNeeded()
                mutableSearchIndexReady.value = true
            }
            mutableEngineState.value = if (completed) {
                LibraryEngineState.Ready
            } else {
                permissions.revalidate()
                LibraryEngineState.PermissionRequired
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            permissions.revalidate()
            mutableEngineState.value = LibraryEngineState.PermissionRequired
        } catch (_: Throwable) {
            mutableEngineState.value = LibraryEngineState.Error
        }
    }

    override fun onCleared() {
        semanticSearchEngine?.close()
        semanticModelManager?.close()
        runtime.value?.let {
            it.monitor?.close()
            it.thumbnails.close()
            it.database.close()
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
        return GalleryRuntime(
            database = database,
            timeline = GalleryTimelineRepository(database),
            scanner = InitialMediaScanner(reader, store, { permissions.access.value }),
            synchronizer = IncrementalMediaSynchronizer(reader, store, { permissions.access.value }),
            generations = MediaStoreGenerationProbe(context),
            thumbnails = ThumbnailLoader.native(decoder, maxCache),
            decoder = decoder,
            albums = GalleryAlbumRepository(database),
            trash = GalleryTrashRepository(database),
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
        sort = if (sort == AlbumSort.NewestFirst) MediaQuery.Sort.NewestFirst else MediaQuery.Sort.OldestFirst,
    )

    private fun currentLibraryQuery(): MediaQuery {
        val library = gallerySettings.value.library
        fun decode(tokens: Set<String>) = tokens.mapNotNull(GalleryFolderToken::decode)
            .mapTo(linkedSetOf()) { MediaQuery.PhysicalFolder(it.first, it.second) }
        return MediaQuery(
            kindFilter = when (library.filter) {
                com.ugallery.core.preferences.LibraryFilter.All -> MediaQuery.KindFilter.ImagesAndVideos
                com.ugallery.core.preferences.LibraryFilter.Images -> MediaQuery.KindFilter.Images
                com.ugallery.core.preferences.LibraryFilter.Videos -> MediaQuery.KindFilter.Videos
                com.ugallery.core.preferences.LibraryFilter.Animated -> MediaQuery.KindFilter.Animated
                com.ugallery.core.preferences.LibraryFilter.Raw -> MediaQuery.KindFilter.Raw
            },
            sort = if (library.ascending) MediaQuery.Sort.OldestFirst else MediaQuery.Sort.NewestFirst,
            sortField = when (library.sort) {
                com.ugallery.core.preferences.LibrarySort.DateTaken -> MediaQuery.SortField.DateTaken
                com.ugallery.core.preferences.LibrarySort.DateModified -> MediaQuery.SortField.DateModified
                com.ugallery.core.preferences.LibrarySort.Name -> MediaQuery.SortField.Name
                com.ugallery.core.preferences.LibrarySort.Size -> MediaQuery.SortField.Size
            },
            grouping = when (library.grouping) {
                com.ugallery.core.preferences.LibraryGrouping.Day -> MediaQuery.Grouping.Day
                com.ugallery.core.preferences.LibraryGrouping.Month -> MediaQuery.Grouping.Month
                com.ugallery.core.preferences.LibraryGrouping.Year -> MediaQuery.Grouping.Year
                com.ugallery.core.preferences.LibraryGrouping.None -> MediaQuery.Grouping.None
            },
            folderMode = if (library.folderSelectionMode == com.ugallery.core.preferences.FolderSelectionMode.OnlyIncluded) {
                MediaQuery.FolderMode.OnlyIncluded
            } else MediaQuery.FolderMode.AllExceptExcluded,
            includedFolders = decode(library.includedFolders),
            excludedFolders = decode(library.excludedFolders),
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

    private suspend fun beginFavoriteImport(targets: List<MediaActionTarget>) {
        if (targets.isEmpty()) return
        val action = MediaAction.Favorite(true)
        currentSystemCoordinator = coordinator(MediaActionReducer.start(action, targets.size.toLong()))
        favoriteImportCursor = FavoriteImportCursor(ArrayList(targets)).also {
            savedStateHandle[FavoriteImportStateKey] = it
        }
        stageNextFavoriteImportChunk()
    }

    private suspend fun stageNextFavoriteImportChunk() {
        val cursor = favoriteImportCursor ?: return
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
        currentSystemCoordinator?.stageChunk(chunk)?.let { mutableActionLaunches.emit(it) }
    }

    private suspend fun stageNextBulkChunk() {
        val cursor = bulkCursor ?: return
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
                currentSystemCoordinator?.stageChunk(targets)?.let { mutableActionLaunches.emit(it) }
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
        val available = canOpen(current.uri)
        mutableExternalMedia.value = current.copy(available = available)
        if (available && current.kind == MediaKind.Image && mutableExternalPhotoState.value == null) {
            loadExternalPhoto(current.uri)
        }
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
        val afterExclusive: com.ugallery.core.model.MediaKey?,
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
        val kind: MediaKind,
        val editMode: Boolean,
        val available: Boolean,
    )

    private companion object {
        const val ActionStateKey = "media_action_state"
        const val BulkStateKey = "bulk_action_state"
        const val FavoriteImportStateKey = "favorite_import_state"
        const val WriteMutationStateKey = "pending_write_mutation"
        const val ViewerWindowRadius = 80
        const val ViewerWindowRefreshThreshold = 12
    }
}
