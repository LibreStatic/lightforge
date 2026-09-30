package com.librestatic.lightforge.feature.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressSlot
import kotlinx.coroutines.delay

/** A visible, operation-level status; thumbnails and animation frames are not live regions. */
/** Work that keeps running once the library is browsable, most relevant first. */
enum class LibraryBackgroundWork { SearchIndex, Moments, Faces, People, Pets }

/** An external condition holding analysis back; user-initiated pauses are not reported here. */
enum class LibraryBackgroundWait { Battery, Heat }

/** [waitingOn] is null while [work] runs and set while it is held back by the device. */
data class LibraryBackgroundStatus(val work: LibraryBackgroundWork, val waitingOn: LibraryBackgroundWait? = null)

/** Short passes (an unchanged Moments check) finish before this and never flash a hint. */
private const val BackgroundHintDelayMillis = 1_500L

@Composable
internal fun LibraryIndexStatus(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    background: LibraryBackgroundStatus? = null,
) {
    val hint by produceState<LibraryBackgroundStatus?>(null, background) {
        if (background != null && value == null) delay(BackgroundHintDelayMillis)
        value = background
    }
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
            if (!observedNonReady && hint == null) return
            R.string.library_ready_status
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    .testTag("library-index-status")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            // The slot keeps its height in every state and the bar only fades, so indexing starting or
            // ending never resizes the pill or moves the timeline below it. The bar sits at the top of the
            // slot, so the top padding keeps the wave's crests clear of the text.
            GalleryProgressSlot(
                visible = true,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            ) {
                val preparing = state == LibraryUiState.Starting || state == LibraryUiState.Indexing
                AnimatedVisibility(
                    visible = preparing,
                    enter = fadeIn(),
                    exit = fadeOut(tween(durationMillis = 400)),
                ) {
                    GalleryIndeterminateProgressIndicator(Modifier.testTag("library-index-progress"))
                }
                // Once the library is browsable the bar gives way to a quieter line naming what
                // still runs, or what the device is holding back.
                var lastHint by remember { mutableStateOf(hint) }
                if (hint != null) lastHint = hint
                AnimatedVisibility(
                    visible = !preparing && hint != null,
                    enter = fadeIn(tween(delayMillis = 400)),
                    exit = fadeOut(),
                ) {
                    lastHint?.let { shown ->
                        Text(
                            text = backgroundHint(shown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .testTag("library-background-status")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun backgroundHint(status: LibraryBackgroundStatus): String {
    // Only analysis can be held back by the device; library maintenance always reads as running.
    val pausedTask = when (status.work) {
        LibraryBackgroundWork.Faces -> R.string.library_task_faces
        LibraryBackgroundWork.People -> R.string.library_task_people
        LibraryBackgroundWork.Pets -> R.string.library_task_pets
        LibraryBackgroundWork.SearchIndex, LibraryBackgroundWork.Moments -> null
    }
    val wait = status.waitingOn
    if (wait != null && pausedTask != null) {
        return stringResource(
            when (wait) {
                LibraryBackgroundWait.Battery -> R.string.library_paused_battery
                LibraryBackgroundWait.Heat -> R.string.library_paused_heat
            },
            stringResource(pausedTask),
        )
    }
    return stringResource(
        when (status.work) {
            LibraryBackgroundWork.SearchIndex -> R.string.library_background_search
            LibraryBackgroundWork.Moments -> R.string.library_background_moments
            LibraryBackgroundWork.Faces -> R.string.library_background_faces
            LibraryBackgroundWork.People -> R.string.library_background_people
            LibraryBackgroundWork.Pets -> R.string.library_background_pets
        },
    )
}
