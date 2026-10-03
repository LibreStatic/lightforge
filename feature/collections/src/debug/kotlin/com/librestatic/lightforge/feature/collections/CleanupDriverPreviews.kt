package com.librestatic.lightforge.feature.collections

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds fake state into the real CleanupContent; thumbnails show their placeholders.

/** Analysis results with duplicates and every section filled. Trash buttons open the confirmation dialog. */
@Preview
@Composable
fun CleanupPreview() = CleanupFrame(
    CleanupUiState(
        loading = false,
        analysisEnabled = true,
        duplicateGroups = listOf(
            CleanupDuplicateGroupUi("beach", keys(1, 3), keep = key(1), memberCount = 3, recoverableBytes = 8_400_000),
            CleanupDuplicateGroupUi("receipt", keys(10, 2), keep = key(11), memberCount = 2, recoverableBytes = 1_900_000),
        ),
        duplicateGroupCount = 2,
        duplicateBytes = 10_300_000,
        largeVideos = keys(20, 4),
        largeVideoCount = 4,
        largeVideoBytes = 2_150_000_000,
        screenshots = keys(30, 12),
        screenshotCount = 37,
        blurry = keys(60, 5),
        blurryCount = 5,
    ),
)

/** First visit: analysis is off, so the screen offers to enable it. */
@Preview
@Composable
fun CleanupAnalysisOffPreview() = CleanupFrame(CleanupUiState(loading = false, analysisEnabled = false))

/** Analysis is on but found nothing to clean up. */
@Preview
@Composable
fun CleanupEmptyPreview() = CleanupFrame(CleanupUiState(loading = false, analysisEnabled = true))

/** Results are still loading. */
@Preview
@Composable
fun CleanupLoadingPreview() = CleanupFrame(CleanupUiState(loading = true, analysisEnabled = true))

@Composable
private fun CleanupFrame(state: CleanupUiState) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            CleanupContent(
                state = state,
                thumbnailLoader = null,
                onBack = {},
                onEnableAnalysis = {},
                onOpen = { _, _ -> },
                onTrashDuplicateCopies = {},
                onTrashSection = {},
            )
        }
    }
}

private fun key(id: Long) = MediaKey("external_primary", id)

private fun keys(first: Long, count: Int) = List(count) { key(first + it) }
