package com.librestatic.lightforge

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
) {
    suspend fun snapshot(message: StorageAccessMessage? = null, busy: Boolean = false): StorageAccessUiState {
        val hidden = withContext(io) { advanced.hiddenByApp() }
        return StorageAccessUiState(
            manageMediaSupported = manageMediaSupported,
            manageMediaGranted = manageMediaSupported && canManageMedia(),
            advancedSupported = advanced.isSupported,
            advancedEnabled = readEnabled(),
            allFilesGranted = advanced.isSupported && advanced.isGranted(),
            hidden = hidden.sortedBy { it.absolutePath }.map { HiddenFolderUi(it.absolutePath, it.createdAtMillis) },
            busy = busy,
            message = message,
        )
    }

    suspend fun setEnabled(enabled: Boolean) = writeEnabled(enabled)

    suspend fun unhide(absolutePath: String): StorageAccessMessage? {
        if (!advanced.isGranted()) return permissionNeeded
        return when (withContext(io) { advanced.unhideFolder(absolutePath) }) {
            AdvancedResult.Done -> null
            AdvancedResult.NotGranted -> permissionNeeded
            else -> notUnhidden(1)
        }
    }

    suspend fun unhideAll(): StorageAccessMessage? {
        if (!advanced.isGranted()) return permissionNeeded
        val failed = withContext(io) { advanced.unhideAllRecorded() }
        return if (failed.isEmpty()) null else notUnhidden(failed.size)
    }

    /** Without the permission nothing changes: the mode stays on and the folders stay listed. */
    suspend fun unhideAndTurnOff(): StorageAccessMessage? {
        if (!advanced.isGranted()) return permissionNeeded
        val failed = withContext(io) { advanced.unhideAllRecorded() }
        writeEnabled(false)
        return if (failed.isEmpty()) null else notUnhidden(failed.size)
    }

    private val permissionNeeded = StorageAccessMessage(StorageAccessMessageKind.PermissionNeeded)
    private fun notUnhidden(count: Int) = StorageAccessMessage(StorageAccessMessageKind.NotAllUnhidden, count)
}

@HiltViewModel
class StorageAccessViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val advanced: AdvancedStorage,
) : ViewModel() {
    private val settings = GallerySettingsRepository(context)
    private val model = StorageAccessModel(
        advanced = advanced,
        manageMediaSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        canManageMedia = { MediaManagementAccess.canManageMedia(context) },
        readEnabled = { settings.settings.first().operations.advancedStorageEnabled },
        writeEnabled = { enabled -> settings.update { it.copy(operations = it.operations.copy(advancedStorageEnabled = enabled)) } },
    )
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(StorageAccessUiState())
    val state: StateFlow<StorageAccessUiState> = mutableState.asStateFlow()

    val actions: StorageAccessActions = StorageAccessActions(
        onRefresh = ::refresh,
        onSetEnabled = { enabled -> run { model.setEnabled(enabled); null } },
        onUnhideAndTurnOff = { run { model.unhideAndTurnOff() } },
        onUnhide = { path -> run { model.unhide(path) } },
        onUnhideAll = { run { model.unhideAll() } },
        manageMediaIntent = { MediaManagementAccess.requestIntent(context) },
        permissionIntent = { advanced.permissionIntent() },
    )

    init {
        // Also picks up an imported or reset settings file while the page is open.
        viewModelScope.launch {
            settings.settings.map { it.operations.advancedStorageEnabled }.distinctUntilChanged().collect { refresh() }
        }
    }

    /** Re-reads both system permissions and the hide registry; the user changes them in system screens. */
    fun refresh() {
        viewModelScope.launch { lock.withLock { mutableState.value = model.snapshot() } }
    }

    private fun run(action: suspend () -> StorageAccessMessage?) {
        viewModelScope.launch {
            lock.withLock {
                mutableState.value = mutableState.value.copy(busy = true, message = null)
                val message = action()
                mutableState.value = model.snapshot(message)
            }
        }
    }
}
