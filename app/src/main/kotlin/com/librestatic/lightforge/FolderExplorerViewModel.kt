package com.librestatic.lightforge

import android.content.Context
import android.os.storage.StorageManager
import android.os.StatFs
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librestatic.lightforge.core.data.FolderTreeRepository
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.librestatic.lightforge.core.data.MediaStoreChangeMonitor
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenMediaRow
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.model.folders.EmptyDirectory
import com.librestatic.lightforge.core.model.folders.mergeAdvancedOverlay
import com.librestatic.lightforge.feature.folders.FolderCardUi
import com.librestatic.lightforge.feature.folders.HiddenItemUi
import com.librestatic.lightforge.feature.folders.normalizedFolderPath
import com.librestatic.lightforge.feature.folders.resolveLocation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.feature.folders.FolderExplorerUiState
import com.librestatic.lightforge.feature.folders.FolderLocation
import com.librestatic.lightforge.feature.folders.VolumeSpace
import com.librestatic.lightforge.feature.folders.buildFolderExplorerUiState
import com.librestatic.lightforge.feature.folders.parentLocation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** What the DeviceFolders top bar offers right now. */
data class FolderChrome(
    val canCreateFolder: Boolean = false,
    /** The "Show hidden items for now" entry is offered (advanced mode active). */
    val canShowHidden: Boolean = false,
    val temporaryShowHidden: Boolean = false,
    val includeSubfolders: Boolean = false,
    val compactChains: Boolean = true,
)

private sealed interface TreeLoad {
    data class Loaded(val tree: FolderTree) : TreeLoad

    data object Failed : TreeLoad
}

/**
 * Owns the folder explorer: the live folder tree, platform volume info and the current location.
 * The location lives in [SavedStateHandle], so it survives leaving to an album and process death.
 *
 * With advanced storage active the tree also carries hidden folders and empty directories, and the
 * folder operations (hide, rename, delete, create) are available; otherwise it is a read-only view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderExplorerViewModel @Inject constructor(
    repository: dagger.Lazy<FolderTreeRepository>,
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    private val advanced: AdvancedStorage,
    private val advancedStatus: AdvancedStorageStatus,
    private val settings: GallerySettingsRepository,
) : ViewModel() {
    private val reload = MutableStateFlow(0)

    /** Bumped after every operation and on Refresh, so the on-disk overlays are read again. */
    private val diskTick = MutableStateFlow(0)
    private val library = MutableStateFlow<FolderLibrary?>(null)

    // The view model is created at app start; the database (and any pending migration) opens on IO, on first collection.
    private val baseTree: Flow<TreeLoad> = reload.flatMapLatest {
        flow<TreeLoad> { emitAll(repository.get().observeFolderTree().map { TreeLoad.Loaded(it) }) }
            .catch { emit(TreeLoad.Failed) }
    }.flowOn(Dispatchers.IO)

    private val location: StateFlow<FolderLocation> = combine(
        savedState.getStateFlow<String?>(VolumeKey, null),
        savedState.getStateFlow<String?>(PathKey, null),
        ::FolderLocation,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, FolderLocation.Start)

    private val includeSubfolders = savedState.getStateFlow(IncludeKey, false)

    /** Persisted preference (default on): merge chains of single-child folders into one card. */
    private val compactChains: StateFlow<Boolean> = settings.settings.map { it.operations.compactFolderChains }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val status get() = advancedStatus.status

    private val hiddenRows: Flow<List<HiddenMediaRow>> = status.map { it.showHidden }.distinctUntilChanged()
        .flatMapLatest { show ->
            if (!show) {
                flowOf(emptyList())
            } else {
                merge(flowOf(Unit), diskTick.map { }, mediaStoreChanges())
                    .mapLatest { advanced.queryHiddenMedia() }
                    .flowOn(Dispatchers.IO)
            }
        }

    /** Absolute paths (no trailing slash) of the folders this app hid, so only those offer Unhide. */
    private val recordedHides: Flow<Set<String>> = combine(status.map { it.active }.distinctUntilChanged(), diskTick) { _, _ -> }
        .mapLatest { advanced.hiddenByApp().mapTo(HashSet()) { it.absolutePath.trimEnd('/') } }
        .flowOn(Dispatchers.IO)

    private val emptyDirectories: Flow<List<EmptyDirectory>> =
        combine(status, baseTree, location, diskTick) { st, load, at, _ -> Triple(st, load, at) }
            .mapLatest { (st, load, at) ->
                val tree = (load as? TreeLoad.Loaded)?.tree
                if (!st.active || tree == null) return@mapLatest emptyList()
                val resolved = resolveLocation(tree, at)
                val volume = resolved.volumeName ?: return@mapLatest emptyList()
                val path = resolved.relativePath
                val absolute = advanced.absolutePathOf(volume, path) ?: return@mapLatest emptyList()
                val base = path?.let(::normalizedFolderPath).orEmpty()
                advanced.listDirectories(absolute)
                    .filter { it.isEmpty && (st.showHidden || !it.isDotPrefixed) }
                    .map { EmptyDirectory(volume, "$base${it.name}/") }
            }
            .flowOn(Dispatchers.IO)

    private val mergedTree: StateFlow<TreeLoad?> = combine(baseTree, hiddenRows, emptyDirectories, status) { load, rows, empty, st ->
        when {
            load !is TreeLoad.Loaded -> load
            !st.active -> load
            else -> TreeLoad.Loaded(
                mergeAdvancedOverlay(load.tree, if (st.showHidden) groupHiddenRows(rows) else emptyList(), empty),
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val spaces: StateFlow<Map<String, VolumeSpace>> = mergedTree
        .map { current -> (current as? TreeLoad.Loaded)?.tree?.volumes?.map { it.volumeName }.orEmpty() }
        .distinctUntilChanged()
        .mapLatest { names -> withContext(Dispatchers.IO) { readSpaces(context, names) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val overlayDetails: Flow<Pair<List<HiddenItemUi>, Set<String>>> =
        combine(hiddenRows, recordedHides, status) { rows, recorded, st ->
            (if (st.showHidden) rows.map { it.toUi() } else emptyList()) to (if (st.active) recorded else emptySet())
        }

    private val viewOptions: Flow<Pair<Boolean, Boolean>> = combine(includeSubfolders, compactChains, ::Pair)

    val uiState: StateFlow<FolderExplorerUiState> =
        combine(mergedTree, location, spaces, viewOptions, overlayDetails) { load, at, space, options, details ->
            val (subfolders, compact) = options
            when (load) {
                null -> FolderExplorerUiState.Loading
                TreeLoad.Failed -> FolderExplorerUiState.Error
                is TreeLoad.Loaded -> buildFolderExplorerUiState(
                    tree = load.tree,
                    location = at,
                    spaces = space,
                    includeSubfolders = subfolders,
                    hiddenItems = details.first,
                    isRecordedHide = { target -> isRecorded(target, details.second) },
                    compactChains = compact,
                )
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderExplorerUiState.Loading)

    /** Whether the advanced actions (overflow menu, hidden items, empty folders) are on. */
    val advancedActive: StateFlow<Boolean> = status.map { it.active }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Folders hidden while the permission is missing; the explorer offers to unhide them. */
    val revokedWithHidden: StateFlow<Int> = status.map { it.revokedWithHidden }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val chrome: StateFlow<FolderChrome> = combine(status, uiState, includeSubfolders, compactChains) { st, ui, subfolders, compact ->
        FolderChrome(
            canCreateFolder = st.active && ui is FolderExplorerUiState.Folder,
            canShowHidden = st.active,
            temporaryShowHidden = st.temporaryShowHidden,
            includeSubfolders = subfolders,
            compactChains = compact,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderChrome())

    /** The paged files of the current folder; empty while there is none. */
    val media: Flow<PagingData<TimelineMedia>> = combine(
        uiState.map { (it as? FolderExplorerUiState.Folder)?.media }.distinctUntilChanged(),
        library,
    ) { scope, lib -> scope to lib }
        .flatMapLatest { (scope, lib) ->
            if (scope == null || lib == null) flowOf(PagingData.empty()) else lib.media(scope.volumeName, scope.relativePath, scope.includeSubfolders)
        }
        .cachedIn(viewModelScope)

    private val mutableMessages = MutableSharedFlow<FolderMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<FolderMessage> = mutableMessages.asSharedFlow()

    private val mutableNewFolderOpen = MutableStateFlow(false)
    val newFolderOpen: StateFlow<Boolean> = mutableNewFolderOpen.asStateFlow()

    /** Connects the library the files grid and the reconciliation use; the activity-scoped gallery owns it. */
    fun bind(folderLibrary: FolderLibrary) {
        library.value = folderLibrary
    }

    fun open(target: FolderLocation) {
        savedState[VolumeKey] = target.volumeName
        savedState[PathKey] = target.relativePath
    }

    /** Goes up one level; false when already at the start level, so the caller leaves the screen. */
    fun navigateUp(): Boolean {
        val current = (mergedTree.value as? TreeLoad.Loaded)?.tree ?: return false
        val parent = parentLocation(current, location.value, compactChains.value) ?: return false
        open(parent)
        return true
    }

    fun setCompactChains(enabled: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(operations = it.operations.copy(compactFolderChains = enabled)) }
        }
    }

    fun setIncludeSubfolders(enabled: Boolean) {
        savedState[IncludeKey] = enabled
    }

    fun retry() {
        reload.value += 1
    }

    /** A file operation changed the disk outside this screen; the empty-folder overlay is read again. */
    fun diskChanged() {
        diskTick.value += 1
    }

    fun refresh() {
        reload.value += 1
        diskTick.value += 1
        viewModelScope.launch { library.value?.refresh() }
    }

    fun hide(card: FolderCardUi) = operate(FolderOp.Hide, card.location, listOf(card.location.relativePath)) { advanced.hideFolder(it) }

    fun unhide(card: FolderCardUi) = operate(FolderOp.Unhide, card.location, listOf(card.location.relativePath)) { advanced.unhideFolder(it) }

    fun delete(card: FolderCardUi) = operate(FolderOp.Delete, card.location, listOf(card.location.relativePath)) { advanced.deleteEmptyDirectory(it) }

    fun rename(card: FolderCardUi, newName: String) {
        val old = card.location.relativePath ?: return
        val renamed = renamedRelativePath(old, newName)
        operate(FolderOp.Rename, card.location, listOf(old, renamed), relocatedTo = renamed) { advanced.renameDirectory(it, newName) }
    }

    fun beginNewFolder() {
        mutableNewFolderOpen.value = status.value.active
    }

    fun dismissNewFolder() {
        mutableNewFolderOpen.value = false
    }

    /** Creates [name] inside the folder being shown. */
    fun createFolder(name: String) {
        mutableNewFolderOpen.value = false
        val state = uiState.value as? FolderExplorerUiState.Folder ?: return
        val parent = state.crumbs.last().location
        operate(FolderOp.Create, parent, emptyList()) { advanced.createDirectory(it, name) }
    }

    /** [target] is the folder acted on; [reconcile] the relative prefixes to re-read afterwards. */
    private fun operate(
        op: FolderOp,
        target: FolderLocation,
        reconcile: List<String?>,
        /** The new relative path of [target] when the call renames it; its folder rules follow it. */
        relocatedTo: String? = null,
        call: suspend (String) -> AdvancedResult,
    ) {
        viewModelScope.launch {
            val volume = target.volumeName
            val absolute = volume?.let { advanced.absolutePathOf(it, target.relativePath) }
            val oldPath = target.relativePath
            val buckets = if (relocatedTo != null && volume != null && oldPath != null) {
                runCatching { library.value?.folderBuckets(volume, oldPath) }.getOrNull().orEmpty()
            } else {
                emptyMap()
            }
            val result = when {
                !status.value.active -> AdvancedResult.NotGranted
                absolute == null -> AdvancedResult.Failed("unknown path")
                else -> withContext(Dispatchers.IO) { runCatching { call(absolute) }.getOrElse { AdvancedResult.Failed(it.message.orEmpty()) } }
            }
            if (result == AdvancedResult.Done && volume != null) {
                reconcile.filterNotNull().distinct().forEach { prefix ->
                    runCatching { library.value?.reconcile(volume, prefix) }
                }
                if (relocatedTo != null && oldPath != null) {
                    runCatching { library.value?.folderRelocated(volume, oldPath, volume, relocatedTo, buckets) }
                }
                diskTick.value += 1
            }
            mutableMessages.emit(result.toFolderMessage(op))
        }
    }

    private fun isRecorded(target: FolderLocation, recorded: Set<String>): Boolean {
        val volume = target.volumeName ?: return false
        val absolute = advanced.absolutePathOf(volume, target.relativePath) ?: return false
        return absolute.trimEnd('/') in recorded
    }

    private fun mediaStoreChanges(): Flow<Unit> = callbackFlow {
        val monitor = MediaStoreChangeMonitor(context.contentResolver, this, ChangeQuietWindowMillis) { trySend(Unit) }
        monitor.start()
        awaitClose { monitor.close() }
    }

    private companion object {
        const val VolumeKey = "folders.volume"
        const val PathKey = "folders.path"
        const val IncludeKey = "folders.subfolders"
        const val ChangeQuietWindowMillis = 500L
    }
}

private fun HiddenMediaRow.toUi() = HiddenItemUi(
    volumeName = volumeName,
    relativePath = relativePath,
    mediaStoreId = mediaStoreId,
    name = displayName,
    mimeType = mimeType,
    isVideo = kind == com.librestatic.lightforge.core.mediastore.HiddenMediaKind.Video,
    sizeBytes = sizeBytes,
    dateMillis = dateTakenOrModifiedMillis,
    uri = contentUri.toString(),
)

private fun readSpaces(context: Context, names: List<String>): Map<String, VolumeSpace> {
    if (names.isEmpty()) return emptyMap()
    val manager = context.getSystemService(StorageManager::class.java) ?: return emptyMap()
    return manager.storageVolumes.mapNotNull { volume ->
        val name = volume.mediaStoreVolumeName?.takeIf { it in names } ?: return@mapNotNull null
        val stat = runCatching { volume.directory?.let { StatFs(it.path) } }.getOrNull()
        name to VolumeSpace(
            label = if (volume.isPrimary) null else runCatching { volume.getDescription(context) }.getOrNull(),
            freeBytes = stat?.availableBytes,
            totalBytes = stat?.totalBytes,
        )
    }.toMap()
}

@Module
@InstallIn(SingletonComponent::class)
object FolderExplorerModule {
    @Provides
    @Singleton
    fun folderTreeRepository(@ApplicationContext context: Context): FolderTreeRepository =
        FolderTreeRepository(GalleryDatabaseFactory.open(context).libraryDao())
}
