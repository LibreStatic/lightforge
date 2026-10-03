package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

// Debug-only, zero-argument entry points for tools/compose-driver. They feed fake state into the
// real PeopleContent; there are no thumbnails.

/** People before the user opts in: the consent card with the enable button. */
@Composable
fun PeopleConsentPreview() = PeopleFrame(PeopleUiState(consentGranted = false))

/** People after opting in, with analysis still running and nobody found yet. */
@Composable
fun PeopleAnalyzingPreview() = PeopleFrame(
    PeopleUiState(consentGranted = true, running = true, analysisStage = PeopleAnalysisStage.FaceDetection, completedItems = 412),
)

@Composable
private fun PeopleFrame(state: PeopleUiState) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PeopleContent(
                state = state,
                thumbnailLoader = null,
                onBack = {},
                onEnable = {},
                onPause = {},
                onResume = {},
                onAnalyzeAll = {},
                onDeleteAll = {},
                onPersonClick = {},
                onRenamePerson = { _, _ -> },
                onHidePerson = {},
                onSetSelectedAsMe = {},
                onResetMe = {},
            )
        }
    }
}
