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
import com.librestatic.lightforge.core.preferences.GallerySettings

// Debug-only, zero-argument entry point for tools/compose-driver. It feeds default settings into
// the real RecognitionSettingsContent; the window size picks one or two panes like the app does
// (the preview has no navigation rail, so the whole width is content).

/**
 * Settings at the current window size. Open a category with `click text=…`; theme choices made
 * under Appearance restyle the preview like they restyle the app.
 */
@Composable
fun SettingsPreview() {
    var settings by remember { mutableStateOf(GallerySettings()) }
    LightforgeTheme(settings.appearance) {
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
                    settings = settings,
                    onSettingsChange = { update -> settings = update(settings) },
                    onLocalBackup = {},
                    onRemoteBackup = {},
                    onLocalSharing = {},
                    onOwnSync = {},
                    adaptiveInfo = adaptiveInfo,
                )
            }
        }
    }
}
