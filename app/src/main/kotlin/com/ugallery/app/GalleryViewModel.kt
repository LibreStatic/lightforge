package com.ugallery.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
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
import com.ugallery.core.model.AlbumKey
import com.ugallery.core.model.AlbumSummary
import com.ugallery.core.model.CheapMediaDetails
import com.ugallery.core.model.ExifLoadResult
import com.ugallery.core.model.MediaKind
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.selection.SelectionReducer
import com.ugallery.core.selection.SelectionSpec
import com.ugallery.core.thumbnail.NativeImageDecoder
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.feature.permissions.PermissionCoordinator
import com.ugallery.feature.viewer.PhotoLoadState
import com.ugallery.feature.viewer.PhotoViewerPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
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
    var monitor: MediaStoreChangeMonitor? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    val permissions: PermissionCoordinator,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val runtime = MutableStateFlow<GalleryRuntime?>(null)
    private val mutableEngineState = MutableStateFlow(LibraryEngineState.Starting)
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
    private val mutablePhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val photoState = mutablePhotoState.asStateFlow()
    private val mutableCheapDetails = MutableStateFlow<CheapMediaDetails?>(null)
    val cheapDetails = mutableCheapDetails.asStateFlow()
    private val mutableExifDetails = MutableStateFlow<ExifLoadResult?>(null)
    val exifDetails = mutableExifDetails.asStateFlow()
    private val mutableSystemAction = MutableStateFlow(savedStateHandle[ActionStateKey] as? MediaActionSnapshot)
    val systemAction = mutableSystemAction.asStateFlow()
    private val mutableActionLaunches = MutableSharedFlow<MediaActionLaunch>(extraBufferCapacity = 1)
    val actionLaunches = mutableActionLaunches.asSharedFlow()
    private var currentSystemCoordinator: MediaStoreActionCoordinator? = null
    private var photoJob: Job? = null
    private val mutableExternalMedia = MutableStateFlow<ExternalMedia?>(null)
    val externalMedia = mutableExternalMedia.asStateFlow()
    private val mutableExternalPhotoState = MutableStateFlow<PhotoLoadState?>(null)
    val externalPhotoState = mutableExternalPhotoState.asStateFlow()
    private val mutableExternalSaved = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val externalSaved = mutableExternalSaved.asSharedFlow()
    private val refreshMutex = Mutex()

    init {
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
                batch.rowHints.forEach { created.synchronizer.applyRowHint(it) }
                refreshLibrary()
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
        mutableSelection.value = SelectionReducer.toggle(mutableSelection.value, media.key)
    }

    fun clearSelection() { mutableSelection.value = SelectionReducer.clear() }

    fun createVirtualAlbum(name: String) {
        viewModelScope.launch { runtime.value?.albums?.createVirtualAlbum(name) }
    }

    fun openMedia(media: TimelineMedia) {
        mutableCurrentMedia.value = media
        mutableCheapDetails.value = null
        mutableExifDetails.value = null
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
        }
    }

    fun originalShareIntent(media: TimelineMedia): Intent = ShareCoordinator(
        getApplication<Application>().contentResolver,
    ).original(
        listOf(ShareCandidate(MediaActionTarget(media.key, media.kind), mediaMime(media.kind))),
    ).intent

    fun beginSystemAction(media: TimelineMedia, action: MediaAction) {
        val initial = MediaActionReducer.start(action, 1)
        val coordinator = coordinator(initial)
        currentSystemCoordinator = coordinator
        mutableActionLaunches.tryEmit(coordinator.stageChunk(listOf(MediaActionTarget(media.key, media.kind))))
    }

    fun resumePendingSystemAction() {
        val snapshot = mutableSystemAction.value ?: return
        val coordinator = coordinator(snapshot)
        currentSystemCoordinator = coordinator
        runCatching { coordinator.recreateCurrentRequest() }.getOrNull()?.let(mutableActionLaunches::tryEmit)
    }

    fun retrySystemAction() {
        currentSystemCoordinator?.let { coordinator ->
            runCatching { coordinator.retryCurrent() }.getOrNull()?.let(mutableActionLaunches::tryEmit)
        }
    }

    fun onSystemActionResult(requestId: Long, approved: Boolean) {
        val media = mutableCurrentMedia.value
        viewModelScope.launch {
            currentSystemCoordinator?.onSystemResult(requestId, approved)
            if (approved && media != null) runtime.value?.synchronizer?.applyRowHint(media.key)
        }
    }

    fun mediaUri(media: TimelineMedia): Uri = media.uri()

    private suspend fun refreshLibrary() = refreshMutex.withLock {
        val active = runtime.value ?: return
        if (access.value.images == com.ugallery.core.model.GrantLevel.None &&
            access.value.videos == com.ugallery.core.model.GrantLevel.None
        ) {
            mutableEngineState.value = LibraryEngineState.PermissionRequired
            return
        }
        mutableEngineState.value = LibraryEngineState.Indexing
        try {
            val completed = withContext(Dispatchers.IO) {
                for (volume in active.generations.snapshot()) {
                    when (active.synchronizer.sync(volume)) {
                        IncrementalSyncResult.NeedsInitialScan,
                        IncrementalSyncResult.NeedsFullVolumeReconciliation,
                        -> active.scanner.scan(volume)
                        is IncrementalSyncResult.PausedPermission -> return@withContext false
                        else -> Unit
                    }
                }
                true
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

    data class ExternalMedia(
        val uri: Uri,
        val mimeType: String?,
        val kind: MediaKind,
        val editMode: Boolean,
        val available: Boolean,
    )

    private companion object { const val ActionStateKey = "media_action_state" }
}
