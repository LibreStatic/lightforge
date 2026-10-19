package com.librestatic.lightforge

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.MediaManagementAccess
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import com.librestatic.lightforge.feature.settings.HiddenFolderUi
import com.librestatic.lightforge.feature.settings.StorageAccessActions
import com.librestatic.lightforge.feature.settings.StorageAccessMessage
import com.librestatic.lightforge.feature.settings.StorageAccessMessageKind
import com.librestatic.lightforge.feature.settings.StorageAccessUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The settings the Storage access page shows next to the system permissions. */
internal data class StorageExtras(
    val showHidden: Boolean = false,
    val deleteEmptyFolders: Boolean = false,
    val protectHiddenItems: Boolean = false,
    val appLockEnabled: Boolean = false,
    val scannedFolders: Set<String> = emptySet(),
)

/**
 * The volume name MediaStore uses and the relative path of a folder picked in the system picker, from the
 * tree's document id (`primary:Scans/2024` or `ABCD-1234:Photos`). Null for ids that are not a folder of a volume.
 */
internal fun treeDocumentVolumeAndPath(documentId: String): Pair<String, String>? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volume = documentId.substring(0, separator)
    val path = documentId.substring(separator + 1).trim('/')
    if (path.isEmpty()) return null
    return (if (volume.equals("primary", ignoreCase = true)) "external_primary" else volume.lowercase()) to path
}

/**
 * Plain logic behind the "Storage access" page (ADR-046), kept free of Android and Hilt so it is unit
 * tested. File work runs on [io]; every function returns the message to show, or null.
 */
internal class StorageAccessModel(
    private val advanced: AdvancedStorage,
    private val manageMediaSupported: Boolean,
    private val canManageMedia: () -> Boolean,
    private val readEnabled: suspend () -> Boolean,
    private val writeEnabled: suspend (Boolean) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val readExtras: suspend () -> StorageExtras = { StorageExtras() },
    private val writeShowHidden: suspend (Boolean) -> Unit = {},
    private val writeDeleteEmptyFolders: suspend (Boolean) -> Unit = {},
    private val expectLocalChange: (Boolean) -> Unit = {},
    private val writeScannedFolders: suspend (Set<String>) -> Unit = {},
) {
    /** Entries whose unhide needs the user to pick the folder (the owner path failed without the permission). */
    private val needsTreeGrant = linkedSetOf<String>()

    suspend fun snapshot(message: StorageAccessMessage? = null, busy: Boolean = false): StorageAccessUiState {
        val hidden = withContext(io) { advanced.hiddenByApp() }
        val extras = readExtras()
        needsTreeGrant.retainAll(hidden.mapTo(hashSetOf()) { it.absolutePath })
        return StorageAccessUiState(
            manageMediaSupported = manageMediaSupported,
            manageMediaGranted = manageMediaSupported && canManageMedia(),
            advancedSupported = advanced.isSupported,
            advancedEnabled = readEnabled(),
            allFilesGranted = advanced.isSupported && advanced.isGranted(),
            hidden = hidden.sortedBy { it.absolutePath }.map { HiddenFolderUi(it.absolutePath, it.createdAtMillis) },
            treeGrantPaths = needsTreeGrant.toSet(),
            showHidden = extras.showHidden,
            deleteEmptyFolders = extras.deleteEmptyFolders,
            protectHiddenItems = extras.protectHiddenItems,
            appLockEnabled = extras.appLockEnabled,
            scannedFolders = extras.scannedFolders.sorted(),
            busy = busy,
            message = message,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        expectLocalChange(enabled)
        writeEnabled(enabled)
    }

    suspend fun setShowHidden(enabled: Boolean) = writeShowHidden(enabled)

    suspend fun setDeleteEmptyFolders(enabled: Boolean) = writeDeleteEmptyFolders(enabled)

    /**
     * Adds a folder the user picked (the tree's document id) to the library: the scanner indexes its photos
     * and videos, and the folder is remembered so it is scanned again when the mode turns on.
     */
    suspend fun includeFolder(documentId: String?): StorageAccessMessage? {
        val (volume, relative) = documentId?.let(::treeDocumentVolumeAndPath) ?: return includeFailed
        val path = advanced.absolutePathOf(volume, "$relative/")?.trimEnd('/') ?: return includeFailed
        if (withContext(io) { advanced.scanFolderTree(path) } == null) {
            return if (advanced.isGranted()) includeFailed else permissionNeeded
        }
        writeScannedFolders(readExtras().scannedFolders + path)
        return null
    }

    suspend fun removeIncludedFolder(absolutePath: String) {
        writeScannedFolders(readExtras().scannedFolders - absolutePath)
    }

    /** Scans every included folder again; a folder that is gone is left in the list for the user to remove. */
    suspend fun rescanScannedFolders() {
        readExtras().scannedFolders.forEach { withContext(io) { advanced.scanFolderTree(it) } }
    }

    /** Works without the permission when the marker is ours; otherwise the row asks for the folder. */
    suspend fun unhide(absolutePath: String): StorageAccessMessage? {
        return when (withContext(io) { advanced.unhideFolder(absolutePath) }) {
            AdvancedResult.Done -> { needsTreeGrant.remove(absolutePath); null }
            AdvancedResult.NotGranted -> permissionNeeded
            is AdvancedResult.NeedsTreeGrant -> { needsTreeGrant.add(absolutePath); notUnhidden(1) }
            else -> notUnhidden(1)
        }
    }

    /** Finishes an unhide through the folder the user picked; the entry stays listed on any failure. */
    suspend fun unhideWithTree(absolutePath: String, treeUri: Uri): StorageAccessMessage? =
        afterTreeUnhide(absolutePath, withContext(io) { advanced.unhideWithTree(absolutePath, treeUri) })

    fun afterTreeUnhide(absolutePath: String, result: AdvancedResult): StorageAccessMessage? =
        when (result) {
            AdvancedResult.Done -> { needsTreeGrant.remove(absolutePath); null }
            else -> notUnhidden(1)
        }

    /** Partial progress is kept: whatever was unhidden before a revoke stays unhidden. */
    suspend fun unhideAll(): StorageAccessMessage? {
        val failed = unhideRecorded()
        return if (failed.isEmpty()) null else notUnhidden(failed.size)
    }

    /**
     * Unhides what it can and switches the mode off. When the permission is missing and entries remain,
     * the mode stays on so the revoked notice and the per-row actions stay reachable.
     */
    suspend fun unhideAndTurnOff(): StorageAccessMessage? {
        val failed = unhideRecorded()
        if (failed.isEmpty() || advanced.isGranted()) setEnabled(false)
        return if (failed.isEmpty()) null else notUnhidden(failed.size)
    }

    private suspend fun unhideRecorded(): List<HiddenEntry> {
        val failed = withContext(io) { advanced.unhideAllRecorded() }
        // A second attempt tells which leftovers only need the user to pick the folder.
        return failed.filter { entry ->
            when (withContext(io) { advanced.unhideFolder(entry.absolutePath) }) {
                AdvancedResult.Done -> false
                is AdvancedResult.NeedsTreeGrant -> { needsTreeGrant.add(entry.absolutePath); true }
                else -> true
            }
        }
    }

    private val permissionNeeded = StorageAccessMessage(StorageAccessMessageKind.PermissionNeeded)
    private val includeFailed = StorageAccessMessage(StorageAccessMessageKind.IncludeFailed)
    private fun notUnhidden(count: Int) = StorageAccessMessage(StorageAccessMessageKind.NotAllUnhidden, count)
}

@HiltViewModel
class StorageAccessViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val advanced: AdvancedStorage,
    private val settings: GallerySettingsRepository,
    private val status: AdvancedStorageStatus,
) : ViewModel() {
    private val model = StorageAccessModel(
        advanced = advanced,
        manageMediaSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        canManageMedia = { MediaManagementAccess.canManageMedia(context) },
        readEnabled = { settings.settings.first().operations.advancedStorageEnabled },
        writeEnabled = { enabled -> settings.update { it.copy(operations = it.operations.copy(advancedStorageEnabled = enabled)) } },
        readExtras = {
            val current = settings.settings.first()
            StorageExtras(
                showHidden = current.operations.advancedShowHidden,
                deleteEmptyFolders = current.operations.advancedDeleteEmptyFolders,
                protectHiddenItems = current.security.protectHiddenItems,
                appLockEnabled = current.security.appLockEnabled,
                scannedFolders = current.operations.scannedFolders,
            )
        },
        writeShowHidden = { on -> settings.update { it.copy(operations = it.operations.copy(advancedShowHidden = on)) } },
        writeDeleteEmptyFolders = { on -> settings.update { it.copy(operations = it.operations.copy(advancedDeleteEmptyFolders = on)) } },
        expectLocalChange = status::expectLocalChange,
        writeScannedFolders = { paths -> settings.update { it.copy(operations = it.operations.copy(scannedFolders = paths)) } },
    )
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(StorageAccessUiState())

    /** The page state plus the unhide prompt an import or reset can raise (see [AdvancedStorageStatus.pendingUnhide]). */
    val state: StateFlow<StorageAccessUiState> =
        combine(mutableState, status.pendingUnhide) { page, prompt -> page.copy(unhidePromptCount = prompt) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, StorageAccessUiState())

    val actions: StorageAccessActions = StorageAccessActions(
        onRefresh = ::refresh,
        onSetEnabled = { enabled -> run { model.setEnabled(enabled); null } },
        onUnhideAndTurnOff = { run { model.unhideAndTurnOff() } },
        onUnhide = { path -> run { model.unhide(path) } },
        onUnhideAll = { run { model.unhideAll() } },
        onUnhideWithTree = { path, uri -> run { model.unhideWithTree(path, uri) } },
        onSetShowHidden = { on -> run { model.setShowHidden(on); null } },
        onSetDeleteEmptyFolders = { on -> run { model.setDeleteEmptyFolders(on); null } },
        onIncludeFolder = { uri ->
            run { model.includeFolder(runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()) }
        },
        onRemoveScannedFolder = { path -> run { model.removeIncludedFolder(path); null } },
        onRescanScannedFolders = { run { model.rescanScannedFolders(); null } },
        onDismissUnhidePrompt = status::dismissPendingUnhide,
        onKeepHidden = status::keepHidden,
        manageMediaIntent = { MediaManagementAccess.requestIntent(context) },
        permissionIntent = { advanced.permissionIntent() },
    )

    init {
        // Also picks up an imported or reset settings file while the page is open.
        viewModelScope.launch {
            settings.settings.map { listOf(it.operations.advancedStorageEnabled, it.operations.advancedShowHidden, it.operations.advancedDeleteEmptyFolders, it.operations.scannedFolders, it.security.protectHiddenItems, it.security.appLockEnabled) }
                .distinctUntilChanged().collect { refresh() }
        }
    }

    /** Re-reads both system permissions and the hide registry; the user changes them in system screens. */
    fun refresh() {
        status.refresh()
        viewModelScope.launch { lock.withLock { mutableState.value = model.snapshot() } }
    }

    private fun run(action: suspend () -> StorageAccessMessage?) {
        viewModelScope.launch {
            lock.withLock {
                mutableState.value = mutableState.value.copy(busy = true, message = null)
                val message = action()
                mutableState.value = model.snapshot(message)
                status.refresh()
            }
        }
    }
}
