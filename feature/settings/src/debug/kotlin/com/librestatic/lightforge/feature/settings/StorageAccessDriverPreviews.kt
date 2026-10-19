package com.librestatic.lightforge.feature.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.designsystem.galleryAdaptiveLayoutInfo
import com.librestatic.lightforge.core.preferences.AppearanceSettings

// Debug-only entry points for tools/compose-driver: fake StorageAccessUiState fed into the real
// StorageAccessSettingsContent inside the real settings screen; open the page with
// `click tag=settings_storage_access_row`.

private val DayMillis = 86_400_000L
private const val FixedNow = 1_760_000_000_000L

private val TwoHidden = listOf(
    HiddenFolderUi("/storage/emulated/0/Pictures/Screenshots/Old", FixedNow),
    HiddenFolderUi("/storage/1A2B-3C4D/DCIM/Private", FixedNow - 40 * DayMillis),
)

/** Two files and one folder, the mix the turn-off dialog has to name correctly. */
private val ThreeHidden = listOf(
    HiddenFolderUi("/storage/emulated/0/Pictures/Travel/.IMG_20260710_173000.jpg", FixedNow, isFile = true),
    HiddenFolderUi("/storage/emulated/0/Pictures/Travel/.VID_20260711_091500.mp4", FixedNow - DayMillis, isFile = true),
    HiddenFolderUi("/storage/emulated/0/DCIM/Private", FixedNow - 40 * DayMillis),
)

private val TwoIncluded = listOf("/storage/emulated/0/Download/Scans", "/storage/1A2B-3C4D/Camera backup")

/** Supported build, mode off, media management not granted. Toggle the switch to see the explanation dialog. */
@Composable
fun StorageAccessOffPreview() = StorageAccessPreviewFrame(
    StorageAccessUiState(manageMediaSupported = true, advancedSupported = true),
)

/** Mode on but the permission is gone, with two hidden folders: inline warning row and the list. */
@Composable
fun StorageAccessRevokedPreview() = StorageAccessPreviewFrame(
    StorageAccessUiState(
        manageMediaSupported = true,
        manageMediaGranted = true,
        advancedSupported = true,
        advancedEnabled = true,
        allFilesGranted = false,
        hidden = TwoHidden,
        treeGrantPaths = setOf(TwoHidden[1].absolutePath),
    ),
)

/** Mode on and granted, two included folders and three hidden items; the switch opens the turn-off dialog. */
@Composable
fun StorageAccessActivePreview() = StorageAccessPreviewFrame(
    StorageAccessUiState(
        manageMediaSupported = true,
        advancedSupported = true,
        advancedEnabled = true,
        allFilesGranted = true,
        hidden = ThreeHidden,
        scannedFolders = TwoIncluded,
        showHidden = true,
    ),
)

/** Same as the active page with the app lock protecting the hidden items list. */
@Composable
fun StorageAccessLockedPreview() = StorageAccessPreviewFrame(
    StorageAccessUiState(
        manageMediaSupported = true,
        advancedSupported = true,
        advancedEnabled = true,
        allFilesGranted = true,
        hidden = ThreeHidden,
        appLockEnabled = true,
        protectHiddenItems = true,
    ),
)

/** Build without the advanced storage module: only the media management section. */
@Composable
fun StorageAccessUnsupportedPreview() = StorageAccessPreviewFrame(
    StorageAccessUiState(manageMediaSupported = true),
)

@Composable
private fun StorageAccessPreviewFrame(initial: StorageAccessUiState) {
    var state by remember { mutableStateOf(initial) }
    val actions = StorageAccessActions(
        onSetEnabled = { enabled -> state = state.copy(advancedEnabled = enabled) },
        onUnhideAndTurnOff = { state = state.copy(advancedEnabled = false, hidden = emptyList()) },
        onUnhide = { path -> state = state.copy(hidden = state.hidden.filterNot { it.absolutePath == path }) },
        onUnhideAll = { state = state.copy(hidden = emptyList()) },
        onSetShowHidden = { on -> state = state.copy(showHidden = on) },
        onSetDeleteEmptyFolders = { on -> state = state.copy(deleteEmptyFolders = on) },
    )
    LightforgeTheme(AppearanceSettings()) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val adaptiveInfo = galleryAdaptiveLayoutInfo(maxWidth, height = maxHeight).copy(contentWidth = maxWidth)
                RecognitionSettingsContent(
                    state = FaceAnalysisUiState(),
                    onEnable = {},
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDelete = {},
                    petCollectionsEnabled = false,
                    onPetCollectionsEnabledChange = {},
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    storageAccess = StorageAccessSettings(state, actions),
                    adaptiveInfo = adaptiveInfo,
                )
            }
        }
    }
}
