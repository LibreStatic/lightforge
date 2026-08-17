package com.ugallery.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ugallery.core.data.GalleryTimelineRepository
import com.ugallery.core.data.IncrementalMediaSynchronizer
import com.ugallery.core.data.IncrementalSyncResult
import com.ugallery.core.data.InitialMediaScanner
import com.ugallery.core.data.MediaStoreChangeMonitor
import com.ugallery.core.data.RoomMediaIndexStore
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.mediastore.MediaStoreGenerationProbe
import com.ugallery.core.mediastore.MediaStoreReader
import com.ugallery.core.model.TimelineEntry
import com.ugallery.core.thumbnail.NativeImageDecoder
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.feature.permissions.PermissionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

enum class LibraryEngineState { Starting, Indexing, Ready, PermissionRequired, Error }

private data class GalleryRuntime(
    val database: GalleryDatabase,
    val timeline: GalleryTimelineRepository,
    val scanner: InitialMediaScanner,
    val synchronizer: IncrementalMediaSynchronizer,
    val generations: MediaStoreGenerationProbe,
    val thumbnails: ThumbnailLoader,
    var monitor: MediaStoreChangeMonitor? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    val permissions: PermissionCoordinator,
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
        permissions.revalidate()
        viewModelScope.launch { refreshLibrary() }
    }

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
        return GalleryRuntime(
            database = database,
            timeline = GalleryTimelineRepository(database),
            scanner = InitialMediaScanner(reader, store, { permissions.access.value }),
            synchronizer = IncrementalMediaSynchronizer(reader, store, { permissions.access.value }),
            generations = MediaStoreGenerationProbe(context),
            thumbnails = ThumbnailLoader.native(NativeImageDecoder(context.contentResolver), maxCache),
        )
    }
}
