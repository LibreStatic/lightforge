package com.librestatic.lightforge.feature.picker

import android.app.Application
import android.os.CancellationSignal
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import com.librestatic.lightforge.core.model.GrantLevel
import com.librestatic.lightforge.core.model.LibraryAccess
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import com.librestatic.lightforge.core.thumbnail.NativeImageDecoder
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** A folder the picker grid is narrowed to; null shows every visible folder. */
data class PickerAlbumKey(val volumeName: String, val bucketId: Long)

sealed interface PickerCatalogState {
    data object Loading : PickerCatalogState
    data class Ready(val catalog: PickerCatalog) : PickerCatalogState
    data object Failed : PickerCatalogState
}

class PickerViewModel(
    application: Application,
    val request: PickRequest,
) : AndroidViewModel(application) {
    private val store = PickerMediaStore(application.contentResolver)
    private val permissions = PermissionCoordinator(application)

    val settings: StateFlow<GallerySettings?> = GallerySettingsRepository(application).settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val access: StateFlow<LibraryAccess> = permissions.access

    /** App lock is honoured here too: another app must not browse a locked gallery. */
    private val mutableUnlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = mutableUnlocked.asStateFlow()

    private val mutableCatalog = MutableStateFlow<PickerCatalogState>(PickerCatalogState.Loading)
    val catalog: StateFlow<PickerCatalogState> = mutableCatalog.asStateFlow()

    private val mutableAlbum = MutableStateFlow<PickerAlbumKey?>(null)
    val album: StateFlow<PickerAlbumKey?> = mutableAlbum.asStateFlow()

    /** Insertion-ordered so the caller receives items in the order they were tapped. */
    private val mutableSelection = MutableStateFlow<Map<MediaKey, PickerMedia>>(emptyMap())
    val selection: StateFlow<Map<MediaKey, PickerMedia>> = mutableSelection.asStateFlow()

    val thumbnails: ThumbnailLoader = ThumbnailLoader.native(
        application,
        NativeImageDecoder(application.contentResolver),
        maxCacheBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(64L * 1024 * 1024),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val media: Flow<PagingData<PickerMedia>> = combine(mutableCatalog, mutableAlbum) { state, album -> state to album }
        .flatMapLatest { (state, album) ->
            if (state !is PickerCatalogState.Ready) return@flatMapLatest emptyFlow()
            val selection = SqlSelection.allOf(
                state.catalog.baseSelection,
                album?.let { PickerSql.albumSelection(it.volumeName, it.bucketId) },
            )
            Pager(PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE * 2, enablePlaceholders = false)) {
                PickerPagingSource(store, selection)
            }.flow
        }
        .cachedIn(viewModelScope)

    private var catalogJob: Job? = null
    private var loadedFor: LibraryAccess? = null

    fun unlock() { mutableUnlocked.value = true }

    /** Re-reads grants on every resume; a change (e.g. the user just allowed access) rescans. */
    fun refreshAccess() {
        val current = permissions.revalidate()
        if (current == loadedFor && mutableCatalog.value != PickerCatalogState.Failed) return
        loadedFor = current
        if (current.images == GrantLevel.None && current.videos == GrantLevel.None) return
        catalogJob?.cancel()
        mutableCatalog.value = PickerCatalogState.Loading
        catalogJob = viewModelScope.launch {
            mutableCatalog.value = runCatching {
                val rules = FolderRules.load(getApplication())
                PickerCatalogState.Ready(cancellableIo { signal -> store.catalog(request, rules, signal) })
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else PickerCatalogState.Failed }
            val albums = (mutableCatalog.value as? PickerCatalogState.Ready)?.catalog?.albums.orEmpty()
            mutableAlbum.value?.let { key ->
                if (albums.none { it.volumeName == key.volumeName && it.bucketId == key.bucketId }) mutableAlbum.value = null
            }
        }
    }

    fun permissionRequest(): List<String> = permissions.initialMediaRequest(
        includeImages = request.images.accepts,
        includeVideos = request.videos.accepts,
    ).permissions

    fun selectAlbum(key: PickerAlbumKey?) { mutableAlbum.value = key }

    fun toggle(media: PickerMedia) {
        mutableSelection.value = mutableSelection.value.toMutableMap().apply {
            if (remove(media.key) == null) put(media.key, media)
        }
    }

    fun clearSelection() { mutableSelection.value = emptyMap() }

    override fun onCleared() {
        thumbnails.close()
    }

    private companion object {
        const val PAGE_SIZE = 120
    }
}

private class PickerPagingSource(
    private val store: PickerMediaStore,
    private val selection: SqlSelection?,
) : PagingSource<Int, PickerMedia>() {
    override fun getRefreshKey(state: PagingState<Int, PickerMedia>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PickerMedia> {
        val offset = params.key ?: 0
        return try {
            val rows = cancellableIo { signal -> store.page(selection, offset, params.loadSize, signal) }
            LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }
}

/** Runs a provider read on IO and cancels its [CancellationSignal] when the caller is cancelled. */
internal suspend fun <T> cancellableIo(block: (CancellationSignal) -> T): T = withContext(Dispatchers.IO) {
    val signal = CancellationSignal()
    val watcher = launch {
        try { awaitCancellation() } finally { signal.cancel() }
    }
    try { block(signal) } finally { watcher.cancel() }
}
