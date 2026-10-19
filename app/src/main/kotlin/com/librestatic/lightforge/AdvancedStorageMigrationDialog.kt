package com.librestatic.lightforge

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.preferences.GallerySettingsRepository
import com.librestatic.lightforge.feature.settings.StorageAccessMigrationDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Plain logic behind the one-time offer to switch an upgraded install to the advanced storage mode
 * (ADR-046), free of Android and Hilt so it is unit tested. The offer is visible only while the
 * migration is pending AND the build supports the mode.
 */
internal class AdvancedStorageMigrationModel(
    private val supported: Boolean,
    pending: Flow<Boolean>,
    private val writeEnabled: suspend (Boolean) -> Unit,
    private val resolve: suspend () -> Unit,
    private val expectLocalChange: (Boolean) -> Unit,
    private val isGranted: () -> Boolean,
    private val permissionIntent: () -> Intent?,
) {
    val visible: Flow<Boolean> = pending.map { it && supported }

    /**
     * "Switch": turns the wish on, settles the offer for good and returns the system All files access
     * screen to open, or null when the permission is already granted or there is nothing to open.
     */
    suspend fun switchOn(): Intent? {
        expectLocalChange(true)
        writeEnabled(true)
        resolve()
        return if (isGranted()) null else permissionIntent()
    }

    /** "Not now", Back or an outside tap: the wish stays off and the offer never returns. */
    suspend fun notNow() = resolve()
}

@HiltViewModel
internal class AdvancedStorageMigrationViewModel @Inject constructor(
    advanced: AdvancedStorage,
    settings: GallerySettingsRepository,
    status: AdvancedStorageStatus,
) : ViewModel() {
    private val model = AdvancedStorageMigrationModel(
        supported = advanced.isSupported,
        pending = settings.advancedStorageMigrationPending,
        writeEnabled = { enabled ->
            settings.update { it.copy(operations = it.operations.copy(advancedStorageEnabled = enabled)) }
        },
        resolve = settings::resolveAdvancedStorageMigration,
        expectLocalChange = status::expectLocalChange,
        isGranted = advanced::isGranted,
        permissionIntent = advanced::permissionIntent,
    )

    val visible: StateFlow<Boolean> = model.visible.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun switchOn(openPermissionScreen: (Intent) -> Unit) {
        viewModelScope.launch { model.switchOn()?.let(openPermissionScreen) }
    }

    fun notNow() {
        viewModelScope.launch { model.notNow() }
    }
}

/**
 * The migration offer. Call it only where the gallery root is showing; [allowed] is false while the
 * app lock or the external single-file viewer covers it. The gallery root re-reads the system
 * permission on the next resume, so nothing else is needed on return.
 */
@Composable
internal fun AdvancedStorageMigrationPrompt(allowed: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val viewModel = remember(activity) { ViewModelProvider(activity)[AdvancedStorageMigrationViewModel::class.java] }
    val visible by viewModel.visible.collectAsState()
    if (!visible || !allowed) return
    StorageAccessMigrationDialog(
        onSwitch = { viewModel.switchOn { intent -> activity.startSafely(intent) } },
        onNotNow = viewModel::notNow,
    )
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}
