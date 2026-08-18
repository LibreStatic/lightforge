package com.ugallery.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
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
import com.ugallery.core.ml.DetectedContentRepository
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
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.CheapMediaDetails
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
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
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.viewer.PhotoLoadState
import com.ugallery.feature.viewer.PhotoViewerPipeline
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

enum class LibraryEngineState { Starting, Indexing, Ready, PermissionRequired, Error }

data class GallerySearchUiState(
    val query: String = "",
    val hits: List<MediaSearchHit> = emptyList(),
    val loading: Boolean = false,
    val terminal: Boolean = true,
    val error: Boolean = false,
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
    private val petSettings = PetCollectionSettings(application)
    private val mutableDetectedContentEnabled = MutableStateFlow(
        mlScheduler.hasConsent(MlTaskType.ImageLabels) || mlScheduler.hasConsent(MlTaskType.Ocr),
    )
    val detectedContentEnabled = mutableDetectedContentEnabled.asStateFlow()
    private val mutableFaceAnalysis = MutableStateFlow(mlScheduler.controlState(MlTaskType.FaceDetection))
    val faceAnalysis = mutableFaceAnalysis.asStateFlow()
    private val mutablePeopleAnalysis = MutableStateFlow(peopleControlState())
    val peopleAnalysis = mutablePeopleAnalysis.asStateFlow()
    private val mutablePetCollectionsEnabled = MutableStateFlow(petSettings.isEnabled())
    val petCollectionsEnabled = mutablePetCollectionsEnabled.asStateFlow()
    val petSummary = runtime.filterNotNull()
        .flatMapLatest { PetCollectionRepository(it.database).summary() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PetCollectionSummary())
    val peopleSummaries = runtime.filterNotNull()
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
    private val mutableBenchmarkMlRunning = MutableStateFlow(false)
    val benchmarkMlRunning = mutableBenchmarkMlRunning.asStateFlow()
    private var benchmarkMlJob: Job? = null
    private var searchCursor: MediaSearchCursor? = null
    val engineState = mutableEngineState.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        LibraryEngineState.Starting,
    )
    val access = permissions.access
    val timeline: Flow<PagingData<TimelineEntry>> = runtime.filterNotNull()
        .flatMapLatest { it.timeline.timeline(ZoneId.systemDefault()) }
        .cachedIn(viewModelScope)
    val thumbnailLoader = runtime.map { it?.thumbnails }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        null,
    )
    val physicalAlbums: Flow<PagingData<AlbumSummary>> = runtime.filterNotNull()
        .flatMapLatest { it.albums.physicalAlbums() }
        .cachedIn(viewModelScope)
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
    private var currentSystemCoordinator: MediaStoreActionCoordinator? = null
    private var photoJob: Job? = null
    private var bulkCursor: BulkCursor? = savedStateHandle[BulkStateKey]
    private val mutableExternalMedia = MutableStateFlow<ExternalMedia?>(null)
    val externalMedia = mutableExternalMedia.asStateFlow()
    private val mutableExternalPhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val externalPhotoState = mutableExternalPhotoState.asStateFlow()
    private val mutableExternalSaved = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val externalSaved = mutableExternalSaved.asSharedFlow()
    private val mutableSelectedMoment = MutableStateFlow<MomentEntity?>(null)
    val selectedMoment = mutableSelectedMoment.asStateFlow()
    private val refreshMutex = Mutex()

    init {
        if (mlScheduler.hasConsent(MlTaskType.FaceDetection)) monitorFaceProgress()
        if (mlScheduler.hasConsent(MlTaskType.PersonClustering)) monitorPeopleProgress()
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
            created.monitor = MediaStoreChangeMonitor(application.contentResolver, viewModelScope) { batch ->
                batch.rowHints.forEach {
                    created.synchronizer.applyRowHint(it)
                    created.searchIndex.indexKey(it)
                }
                refreshLibrary(batch.rowHints.size)
            }.also { it.start() }
            refreshLibrary()
        }
    }

    fun onForeground() {
        val before = permissions.access.value.unredactedLocation
        val after = permissions.revalidate().unredactedLocation
        if (before && !after) viewModelScope.launch { runtime.value?.metadata?.onLocationPermissionRevoked() }
        viewModelScope.launch { refreshLibrary() }
        revalidateExternalGrant()
    }

    fun setSearchQuery(value: String) { mutableSearch.value = mutableSearch.value.copy(query = value) }

    fun search(value: String = mutableSearch.value.query) {
        val raw = value.trim()
        if (raw.isEmpty()) return
        mutableSearch.value = GallerySearchUiState(query = raw, loading = true, terminal = false)
        searchCursor?.close()
        searchCursor = null
        viewModelScope.launch {
            try {
                val cursor = AppSearchMediaSearchRepository(getApplication()).search(raw, 100)
                searchCursor = cursor
                val page = cursor.nextPage()
                mutableSearch.value = GallerySearchUiState(raw, page.hits, false, page.isTerminal, false)
            } catch (_: Throwable) {
                mutableSearch.value = GallerySearchUiState(raw, error = true)
            }
        }
    }

    fun loadMoreSearch() {
        val cursor = searchCursor ?: return
        if (mutableSearch.value.loading || mutableSearch.value.terminal) return
        mutableSearch.value = mutableSearch.value.copy(loading = true)
        viewModelScope.launch {
            runCatching { cursor.nextPage() }.onSuccess { page ->
                mutableSearch.value = mutableSearch.value.copy(
                    hits = mutableSearch.value.hits + page.hits,
                    loading = false,
                    terminal = page.isTerminal,
                )
            }.onFailure { mutableSearch.value = mutableSearch.value.copy(loading = false, error = true) }
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
            )
        }
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
        listOf(MlTaskType.FaceDetection, MlTaskType.FaceEmbeddings, MlTaskType.PersonClustering).forEach(mlScheduler::grantConsent)
        val next = nextPeopleTask() ?: MlTaskType.FaceDetection
        mlScheduler.enqueue(next, MlRunMode.Recent)
        mutablePeopleAnalysis.value = peopleControlState()
        monitorFaceProgress()
        monitorPeopleProgress()
    }

    fun analyzeAllPeople() {
        viewModelScope.launch {
            listOf(MlTaskType.PersonClustering, MlTaskType.FaceEmbeddings, MlTaskType.FaceDetection).forEach {
                mlScheduler.deleteDerivedData(it)
            }
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

    fun renamePerson(clusterId: String, name: String?) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.rename(clusterId, name)
            mutableSelectedPerson.value = mutableSelectedPerson.value?.takeIf { it.clusterId == clusterId }?.copy(displayName = name)
        }
    }

    fun hidePerson(clusterId: String) {
        viewModelScope.launch {
            runtime.value?.database?.let(::PeopleRepository)?.hide(clusterId)
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
        mlScheduler.enqueue(MlTaskType.ImageLabels, MlRunMode.Recent)
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
            statuses.isNotEmpty() -> com.ugallery.core.ml.MlCheckpoint.Status.Ready
            else -> null
        }
        return MlControlState(
            consentGranted = states.any { it.consentGranted },
            paused = states.any { it.paused },
            completedItems = states.sumOf { it.completedItems },
            status = status,
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
                if (next == null) break
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

    fun selectAllTimeline() = selectAll(MediaQuery())

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

    fun openMedia(media: TimelineMedia) {
        mutableCurrentMedia.value = media
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

    fun beginSystemAction(media: TimelineMedia, action: MediaAction) {
        beginTargetsAction(listOf(MediaActionTarget(media.key, media.kind)), action)
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
                if (bulkCursor != null) viewModelScope.launch { stageNextBulkChunk() }
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
        val media = mutableCurrentMedia.value
        viewModelScope.launch {
            val snapshot = currentSystemCoordinator?.onSystemResult(requestId, approved)
            if (approved && media != null) runtime.value?.synchronizer?.applyRowHint(media.key)
            if (snapshot?.phase == com.ugallery.core.mediastore.MediaActionPhase.ReadyForChunk) {
                stageNextBulkChunk()
            } else if (snapshot?.phase == com.ugallery.core.mediastore.MediaActionPhase.Complete) {
                clearSelection()
                bulkCursor = null
                savedStateHandle[BulkStateKey] = null
            }
        }
    }

    fun mediaUri(media: TimelineMedia): Uri = media.uri()

    private suspend fun refreshLibrary(indexedHintCount: Int = 0): Unit = refreshMutex.withLock {
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

    private data class AlbumRequest(val key: AlbumKey, val filter: AlbumMediaFilter, val sort: AlbumSort)

    private data class BulkCursor(
        val selection: SelectionSpec.QueryAll,
        val action: MediaAction,
        val afterExclusive: com.ugallery.core.model.MediaKey?,
    ) : java.io.Serializable

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
    }
}
