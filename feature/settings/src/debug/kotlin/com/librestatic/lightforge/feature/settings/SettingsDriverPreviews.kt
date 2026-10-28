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
import com.librestatic.lightforge.core.preferences.DismissibleWarning
import com.librestatic.lightforge.core.model.AnalysisProgress
import com.librestatic.lightforge.core.model.AnalysisWait
import com.librestatic.lightforge.core.preferences.GallerySettings

// Debug-only, zero-argument entry point for tools/compose-driver. It feeds default settings into
// the real RecognitionSettingsContent; the window size picks one or two panes like the app does
// (the preview has no navigation rail, so the whole width is content).

/**
 * Settings at the current window size. Open a category with `click text=…`; theme choices made
 * under Appearance restyle the preview like they restyle the app.
 */
@Composable
fun SettingsPreview() = SettingsPreviewFrame(GallerySettings())

/** Settings with the virtual crop warning dismissed, so the Warnings page offers "Show all warnings again". */
@Composable
fun SettingsDismissedWarningsPreview() = SettingsPreviewFrame(
    GallerySettings().let {
        it.copy(operations = it.operations.copy(dismissedWarnings = setOf(DismissibleWarning.VirtualCropShare)))
    },
)

/** Settings opened on the Viewer gestures page: video hold and double-tap rows open their choice dialogs. */
@Composable
fun SettingsGesturesPreview() = SettingsPreviewFrame(GallerySettings(), startOnGestures = true)

/** Local analysis page mid-run: faces with an ETA, pets waiting on battery, and the Individual pets row. */
@Composable
fun SettingsAiAnalysisPreview() = SettingsPreviewFrame(GallerySettings(), startOnAiAnalysis = true)

@Composable
private fun SettingsPreviewFrame(initial: GallerySettings, startOnGestures: Boolean = false, startOnAiAnalysis: Boolean = false) {
    var settings by remember { mutableStateOf(initial) }
    LightforgeTheme(settings.appearance) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val adaptiveInfo = galleryAdaptiveLayoutInfo(maxWidth, height = maxHeight).copy(contentWidth = maxWidth)
                RecognitionSettingsContent(
                    state = if (startOnAiAnalysis) {
                        FaceAnalysisUiState(
                            consentGranted = true,
                            status = AnalysisStatus.Running,
                            progress = AnalysisProgress(4_210, 13_600, 9_390, etaMillis = 25 * 60_000L, running = true),
                        )
                    } else FaceAnalysisUiState(),
                    petAnalysisState = if (startOnAiAnalysis) {
                        FaceAnalysisUiState(
                            consentGranted = true,
                            status = AnalysisStatus.Running,
                            progress = AnalysisProgress(2_000, 13_600, 11_600, running = true, waitReason = AnalysisWait.Battery),
                        )
                    } else FaceAnalysisUiState(),
                    peopleAnalysisEnabled = startOnAiAnalysis,
                    petIdentity = PetIdentitySettingsUi(enabled = true, identityCount = 3, suggestedGroups = 2, photosWithPets = 640),
                    onPetIdentity = if (startOnAiAnalysis) ({}) else null,
                    onEnable = {},
                    onPause = {},
                    onResume = {},
                    onAnalyzeAll = {},
                    onDelete = {},
                    petCollectionsEnabled = startOnAiAnalysis,
                    onPetCollectionsEnabledChange = {},
                    onHideDogResults = {},
                    onHideCatResults = {},
                    onRestorePetResults = {},
                    settings = settings,
                    dspAvailable = true,
                    onSettingsChange = { update -> settings = update(settings) },
                    onLocalBackup = {},
                    onRemoteBackup = {},
                    onLocalSharing = {},
                    onOwnSync = {},
                    adaptiveInfo = adaptiveInfo,
                    startOnGestures = startOnGestures,
                    startOnAiAnalysis = startOnAiAnalysis,
                )
            }
        }
    }
}
