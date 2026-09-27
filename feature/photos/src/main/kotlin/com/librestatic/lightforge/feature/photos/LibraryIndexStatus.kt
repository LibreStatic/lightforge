package com.librestatic.lightforge.feature.photos

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A visible, operation-level status; thumbnails and animation frames are not live regions. */
@Composable
internal fun LibraryIndexStatus(state: LibraryUiState, modifier: Modifier = Modifier) {
    var observedNonReady by rememberSaveable { mutableStateOf(state != LibraryUiState.Ready) }
    SideEffect {
        if (state != LibraryUiState.Ready) observedNonReady = true
    }
    val label = when (state) {
        LibraryUiState.Starting, LibraryUiState.Indexing -> R.string.library_loading_title
        LibraryUiState.Error -> R.string.library_error_title
        LibraryUiState.PermissionRequired -> R.string.permission_title
        LibraryUiState.Ready -> {
            // Opening an already-ready library is silent. Once work/state recovery was observed,
            // retain its completion through recomposition/recreation; leaving the route owns reset.
            if (!observedNonReady) return
            R.string.library_ready_status
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("library-index-status")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
