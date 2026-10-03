package com.librestatic.lightforge.feature.collage

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.MediaEditorScaffold
import com.librestatic.lightforge.core.designsystem.MediaEditorTopBar

/** Everything the GIF editor shows, already resolved from the draft, playback and export state. */
internal data class CreationGifEditorUi(
    val title: String,
    val preview: Bitmap?,
    val index: Int,
    val count: Int,
    val playing: Boolean = false,
    val editable: Boolean = true,
    val seconds: Int = GifFrameTiming.Default,
    val exportEnabled: Boolean = true,
    val running: Boolean = false,
    val progress: Int = 0,
    val showError: Boolean = false,
    val cancelled: Boolean = false,
    val sourcesAvailable: Boolean = true,
    @StringRes val recoveryMessage: Int? = null,
    val showRecoveryActions: Boolean = false,
    val showKeepEditing: Boolean = false,
    val savedVisible: Boolean = false,
    val handoffEnabled: Boolean = false,
)

internal class CreationGifEditorActions(
    val onBack: () -> Unit = {},
    val onTogglePlay: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onEarlier: () -> Unit = {},
    val onLater: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onSeconds: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onCheckPublication: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onShare: () -> Unit = {},
)

/**
 * Stateless GIF editor layout on [MediaEditorScaffold]: the preview with a frame toolbar (playback,
 * frame navigation and order) under it, timing and export status in their own scrolling panel —
 * beside the preview on wide windows — and Export in the top bar, clear of the system bars (bug 24).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun CreationGifEditorLayout(
    ui: CreationGifEditorUi,
    actions: CreationGifEditorActions,
    contentScroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    MediaEditorScaffold(
        modifier = modifier.testTag("creation-gif-screen").semantics { testTagsAsResourceId = true },
        topBar = {
            MediaEditorTopBar(
                title = ui.title,
                onCancel = actions.onBack,
                cancelLabel = stringResource(R.string.creation_gif_back),
                actionLabel = stringResource(R.string.creation_gif_export),
                onAction = actions.onExport,
                actionEnabled = ui.exportEnabled,
                actionTestTag = "creation-gif-export",
            )
        },
        media = { mediaModifier ->
            Box(
                mediaModifier.background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center,
            ) {
                ui.preview?.takeIf { !it.isRecycled }?.let {
                    Image(
                        it.asImageBitmap(),
                        stringResource(R.string.creation_gif_preview),
                        Modifier.fillMaxSize().padding(8.dp).testTag("creation-gif-preview"),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        },
        mediaSupport = { supportModifier, _ -> GifFrameToolbar(ui, actions, supportModifier) },
        inspector = { inspectorModifier, _ ->
            Surface(
                inspectorModifier,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                GifProperties(ui, actions, Modifier.fillMaxSize().verticalScroll(contentScroll).padding(16.dp))
            }
        },
        stackedMediaWeight = 1f,
        stackedInspectorWeight = 1f,
    )
}

/** Frame navigation and order in one toolbar under the preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GifFrameToolbar(ui: CreationGifEditorUi, actions: CreationGifEditorActions, modifier: Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        FlowRow(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("creation-gif-toolbar"),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = actions.onTogglePlay,
                    enabled = ui.editable && ui.preview != null,
                    modifier = Modifier.testTag("creation-gif-play"),
                ) {
                    Icon(
                        if (ui.playing) GalleryIcons.Pause else GalleryIcons.Play,
                        stringResource(if (ui.playing) R.string.creation_gif_pause else R.string.creation_gif_play),
                    )
                }
                IconButton(onClick = actions.onPrevious, enabled = ui.editable, modifier = Modifier.testTag("creation-gif-previous")) {
                    // The forward chevron mirrored: it is auto-mirrored, so this stays "previous" in RTL too.
                    Icon(GalleryIcons.ChevronForward, stringResource(R.string.creation_gif_previous), Modifier.scale(scaleX = -1f, scaleY = 1f))
                }
                Text(
                    stringResource(R.string.creation_gif_position, ui.index + 1, ui.count),
                    Modifier.testTag("creation-gif-position"),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = actions.onNext, enabled = ui.editable, modifier = Modifier.testTag("creation-gif-next")) {
                    Icon(GalleryIcons.ChevronForward, stringResource(R.string.creation_gif_next))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions.onEarlier, enabled = ui.editable && ui.index > 0, modifier = Modifier.testTag("creation-gif-earlier")) {
                    Text(stringResource(R.string.creation_gif_earlier))
                }
                TextButton(onClick = actions.onLater, enabled = ui.editable && ui.index < ui.count - 1, modifier = Modifier.testTag("creation-gif-later")) {
                    Text(stringResource(R.string.creation_gif_later))
                }
                TextButton(onClick = actions.onRemove, enabled = ui.editable && ui.count > 2, modifier = Modifier.testTag("creation-gif-remove")) {
                    Text(stringResource(R.string.creation_gif_remove))
                }
            }
        }
    }
}

/** Timing, export progress and the outcome of the last export. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GifProperties(ui: CreationGifEditorUi, actions: CreationGifEditorActions, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.creation_gif_duration), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GifFrameTiming.SecondChoices.forEach { value ->
                FilterChip(
                    selected = ui.seconds == value, onClick = { actions.onSeconds(value) }, enabled = ui.editable,
                    label = { Text(stringResource(R.string.creation_gif_seconds, value)) },
                    modifier = Modifier.testTag("creation-gif-seconds-$value"),
                )
            }
        }
        Text(stringResource(R.string.creation_gif_frame_rate), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GifFrameTiming.FpsChoices.forEach { fps ->
                val code = GifFrameTiming.fps(fps)
                FilterChip(
                    selected = ui.seconds == code, onClick = { actions.onSeconds(code) }, enabled = ui.editable,
                    label = { Text(stringResource(R.string.creation_gif_fps, fps)) },
                    modifier = Modifier.testTag("creation-gif-fps-$fps"),
                )
            }
        }
        val requestedFps = GifFrameTiming.fpsOf(ui.seconds)
        if (requestedFps != null && requestedFps > GifFrameTiming.MaxReliableFps) Text(
            stringResource(R.string.creation_gif_fps_limit, requestedFps, GifFrameTiming.MaxReliableFps),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("creation-gif-fps-limit"),
        )
        Text(stringResource(R.string.creation_gif_note), style = MaterialTheme.typography.bodyMedium)
        if (ui.showError) Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.creation_gif_error), Modifier.padding(12.dp).testTag("creation-gif-error"))
        }
        if (ui.cancelled) Text(stringResource(R.string.creation_gif_cancelled), Modifier.testTag("creation-gif-cancelled"))
        if (ui.running) {
            GalleryProgressIndicator(progress = { ui.progress / 100f }, modifier = Modifier.fillMaxWidth().testTag("creation-gif-progress"))
            TextButton(onClick = actions.onCancelExport, modifier = Modifier.testTag("creation-gif-cancel")) {
                Text(stringResource(R.string.creation_gif_cancel))
            }
        }
        if (!ui.sourcesAvailable) Text(stringResource(R.string.gif_publication_sources_unavailable))
        ui.recoveryMessage?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(12.dp).testTag("creation-gif-recovery")) {
                    Text(stringResource(message))
                    if (ui.showRecoveryActions) {
                        TextButton(
                            onClick = actions.onCheckPublication, modifier = Modifier.testTag("creation-gif-check-publication"),
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        ) { Text(stringResource(R.string.gif_publication_check)) }
                        if (ui.showKeepEditing) TextButton(
                            onClick = actions.onKeepEditing, modifier = Modifier.testTag("creation-gif-keep-editing"),
                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        ) { Text(stringResource(R.string.gif_publication_keep_editing)) }
                    }
                }
            }
        }
        if (ui.savedVisible) Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.creation_gif_saved), Modifier.testTag("creation-gif-saved"))
                Row {
                    TextButton(
                        onClick = actions.onOpen, enabled = ui.handoffEnabled, modifier = Modifier.testTag("creation-gif-open"),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) { Text(stringResource(R.string.creation_gif_open)) }
                    TextButton(
                        onClick = actions.onShare, enabled = ui.handoffEnabled, modifier = Modifier.testTag("creation-gif-share"),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) { Text(stringResource(R.string.creation_gif_share)) }
                }
            }
        }
    }
}
