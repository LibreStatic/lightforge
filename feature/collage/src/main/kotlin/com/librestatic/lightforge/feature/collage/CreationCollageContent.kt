package com.librestatic.lightforge.feature.collage

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.MediaEditorScaffold
import com.librestatic.lightforge.core.designsystem.MediaEditorTopBar
import kotlinx.coroutines.launch
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

/** Caller supplies a fresh session ID per selection and public URI callbacks for app navigation. */
@Composable
fun CreationCollageContent(
    sessionId: String,
    sources: List<CreationCollageSource>,
    onBack: () -> Unit,
    onOpen: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
    onExported: (Uri) -> Unit = {},
    modifier: Modifier = Modifier,
    sourcesAvailable: Boolean = true,
    onBackKeepingRecovery: () -> Unit = onBack,
) {
    require(sessionId.isNotBlank())
    val controller: CreationCollageState = viewModel(key = "creation-collage-$sessionId")
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val identities = sources.map { it.identity }
    var exitConfirmation by remember(sessionId) { mutableStateOf(false) }
    var callbackError by remember(sessionId) { mutableStateOf(false) }
    var closing by remember(sessionId) { mutableStateOf(false) }
    val activity = LocalContext.current.collageActivity()
    val latestExported by rememberUpdatedState(onExported)
    val supported = CreationCollageTemplate.forCount(sources.size).isNotEmpty() && identities.distinct().size == sources.size
    LaunchedEffect(sessionId, identities, sourcesAvailable) {
        if (supported) controller.bind(sessionId, sources, sourcesAvailable)
    }
    DisposableEffect(controller, activity) {
        onDispose {
            // A retained ViewModel owns publication through configuration recreation. Route exit
            // still cancels work and drops snapshots; onCleared handles permanent Activity exit.
            if (activity?.isChangingConfigurations != true) controller.detach()
        }
    }
    LaunchedEffect(state.result) {
        state.result?.let { uri -> if (controller.claimResultNotification(uri)) latestExported(Uri.parse(uri)) }
    }
    fun closeDraft() {
        if (closing) return
        closing = true
        scope.launch {
            if (controller.closeResolvedDraft()) onBack()
            else {
                controller.detach()
                onBackKeepingRecovery()
            }
        }
    }
    fun back() { if (state.busy) exitConfirmation = true else closeDraft() }
    BackHandler { back() }
    val editingEnabled = supported && !state.busy &&
        collageAllowsNewRender(state.publication, state.sourcesAvailable)
    val contentScroll = rememberScrollState()
    // A finished export disables every editor control; without this the outcome stays below the fold
    // and the screen is indistinguishable from one still working.
    val revealsOutcome = collageRevealsOutcome(state.publication, state.busy)
    LaunchedEffect(revealsOutcome) {
        if (!revealsOutcome) return@LaunchedEffect
        snapshotFlow { contentScroll.maxValue }.collect { maximum -> contentScroll.animateScrollTo(maximum) }
    }
    fun handoff(target: (Uri) -> Unit) {
        scope.launch {
            controller.verifyResultForHandoff()?.let { uri ->
                try { target(uri) } catch (_: Exception) { callbackError = true }
            }
        }
    }
    CreationCollageEditorLayout(
        state = state,
        templates = CreationCollageTemplate.forCount(sources.size),
        supported = supported,
        editingEnabled = editingEnabled,
        callbackError = callbackError,
        contentScroll = contentScroll,
        actions = CreationCollageEditorActions(
            onBack = ::back,
            onTemplate = controller::chooseTemplate,
            onSelect = controller::select,
            onMove = controller::move,
            onCrop = controller::crop,
            onAcknowledgeInterruption = controller::acknowledgeInterruptedPublication,
            onRetry = { callbackError = false; controller.retry() },
            onExport = controller::export,
            onCancelExport = { scope.launch { controller.cancelAndWait() } },
            onOpen = { handoff(onOpen) },
            onShare = { handoff(onShare) },
        ),
        modifier = modifier,
    )
    if (exitConfirmation) AlertDialog(onDismissRequest = { exitConfirmation = false },
        title = { Text(stringResource(R.string.creation_collage_leave)) },
        text = { Text(stringResource(R.string.creation_collage_leave_hint)) },
        confirmButton = { TextButton(onClick = { exitConfirmation = false; closeDraft() }) {
            Text(stringResource(R.string.creation_collage_cancel)) } },
        dismissButton = { TextButton(onClick = { exitConfirmation = false }) { Text(stringResource(R.string.creation_collage_keep)) } })
}

internal class CreationCollageEditorActions(
    val onBack: () -> Unit = {},
    val onTemplate: (CreationCollageTemplate) -> Unit = {},
    val onSelect: (Int) -> Unit = {},
    val onMove: (Int) -> Unit = {},
    val onCrop: (CreationCollageCrop) -> Unit = {},
    val onAcknowledgeInterruption: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onShare: () -> Unit = {},
)

/**
 * Stateless collage editor on [MediaEditorScaffold]: the preview fills the media pane, the
 * properties scroll in their own panel (beside the preview on wide windows) and Export sits in the
 * top bar, clear of the system bars.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun CreationCollageEditorLayout(
    state: CreationCollageUiState,
    templates: List<CreationCollageTemplate>,
    supported: Boolean,
    editingEnabled: Boolean,
    callbackError: Boolean,
    contentScroll: ScrollState,
    actions: CreationCollageEditorActions,
    modifier: Modifier = Modifier,
) {
    val layout = state.layout
    MediaEditorScaffold(
        modifier = modifier.testTag("creation-collage-screen").semantics { testTagsAsResourceId = true },
        topBar = {
            MediaEditorTopBar(
                title = stringResource(R.string.creation_collage_title),
                onCancel = actions.onBack,
                cancelLabel = stringResource(R.string.creation_collage_back),
                actionLabel = stringResource(R.string.creation_collage_export),
                onAction = actions.onExport,
                actionEnabled = editingEnabled && state.preview != null && !state.failed &&
                    !state.publicationUncertain && state.result == null && !state.publishing,
                actionTestTag = "creation-collage-export",
            )
        },
        media = { mediaModifier ->
            Box(mediaModifier.background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                // Bound the square to the pane so a wide or short pane never clips it.
                Box(Modifier.padding(16.dp).aspectRatio(1f, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
                    state.preview?.takeIf { !it.isRecycled }?.let { image ->
                        Image(image.asImageBitmap(), stringResource(R.string.creation_collage_preview),
                            Modifier.fillMaxSize().testTag("creation-collage-preview"), contentScale = ContentScale.Fit)
                    }
                    if (state.busy && !state.publishing) GalleryIndeterminateProgressIndicator(Modifier.width(160.dp).testTag("creation-collage-loading"))
                }
            }
        },
        inspector = { inspectorModifier, _ ->
            Surface(inspectorModifier, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(contentScroll).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.creation_collage_originals), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.creation_collage_template), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { template ->
                            FilterChip(selected = layout?.template == template, onClick = { actions.onTemplate(template) },
                                enabled = editingEnabled, label = { Text(stringResource(template.label())) },
                                modifier = Modifier.testTag("creation-collage-template-${template.name}"))
                        }
                    }
                    if (layout != null) CollageSlotControls(state, layout, editingEnabled, actions)
                    CollageStatus(state, supported, callbackError, actions)
                }
            }
        },
        stackedMediaWeight = 1f,
        stackedInspectorWeight = 1f,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollageSlotControls(
    state: CreationCollageUiState,
    layout: CreationCollageLayout,
    editingEnabled: Boolean,
    actions: CreationCollageEditorActions,
) {
    Text(stringResource(R.string.creation_collage_order), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        layout.order.forEachIndexed { slot, source ->
            FilterChip(selected = state.selectedSlot == slot, onClick = { actions.onSelect(slot) },
                enabled = editingEnabled, label = { Text(stringResource(R.string.creation_collage_photo, source + 1)) },
                modifier = Modifier.testTag("creation-collage-slot-$slot"))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onMove(-1) }, enabled = editingEnabled && state.selectedSlot > 0,
            modifier = Modifier.testTag("creation-collage-earlier")) { Text(stringResource(R.string.creation_collage_earlier)) }
        OutlinedButton(onClick = { actions.onMove(1) }, enabled = editingEnabled && state.selectedSlot < layout.order.lastIndex,
            modifier = Modifier.testTag("creation-collage-later")) { Text(stringResource(R.string.creation_collage_later)) }
    }
    val crop = layout.crops[layout.order[state.selectedSlot]]
    var zoom by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.zoom) }
    var horizontal by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.horizontal) }
    var vertical by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.vertical) }
    fun commitCrop() = actions.onCrop(CreationCollageCrop(zoom, horizontal, vertical))
    Text(stringResource(R.string.creation_collage_crop, layout.order[state.selectedSlot] + 1), style = MaterialTheme.typography.titleSmall)
    val zoomLabel = stringResource(R.string.creation_collage_zoom)
    val horizontalLabel = stringResource(R.string.creation_collage_horizontal)
    val verticalLabel = stringResource(R.string.creation_collage_vertical)
    Text("${zoomLabel}: ${java.text.NumberFormat.getNumberInstance().format(zoom)}", Modifier.testTag("creation-collage-zoom-value"))
    Slider(zoom, { zoom = it }, enabled = editingEnabled, valueRange = 1f..3f,
        onValueChangeFinished = ::commitCrop, modifier = Modifier.testTag("creation-collage-zoom").semantics { contentDescription = "${zoomLabel}: ${java.text.NumberFormat.getNumberInstance().format(zoom)}" })
    // Positions are bidirectional around the centre of the slot.
    EditorAdjustmentSlider(horizontalLabel, horizontal, { horizontal = it }, Modifier.fillMaxWidth(),
        onValueChangeFinished = ::commitCrop, enabled = editingEnabled, testTag = "creation-collage-horizontal")
    EditorAdjustmentSlider(verticalLabel, vertical, { vertical = it }, Modifier.fillMaxWidth(),
        onValueChangeFinished = ::commitCrop, enabled = editingEnabled, testTag = "creation-collage-vertical")
    TextButton(onClick = { actions.onCrop(CreationCollageCrop()) }, enabled = editingEnabled,
        modifier = Modifier.testTag("creation-collage-reset")) { Text(stringResource(R.string.creation_collage_reset)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollageStatus(
    state: CreationCollageUiState,
    supported: Boolean,
    callbackError: Boolean,
    actions: CreationCollageEditorActions,
) {
    val recoveryIssue = collageKeepsRecovery(state.publication) && !state.busy
    if (recoveryIssue) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                val message = when (state.publication) {
                    CreationCollagePublicationUi.RetryableMissing -> R.string.creation_collage_publication_not_started
                    CreationCollagePublicationUi.Incomplete -> R.string.creation_collage_publication_uncertain
                    CreationCollagePublicationUi.Conflict -> R.string.creation_collage_publication_conflict
                    else -> R.string.creation_collage_publication_unreadable
                }
                Text(stringResource(message), Modifier.testTag("creation-collage-publication-uncertain"))
                TextButton(onClick = actions.onAcknowledgeInterruption,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    modifier = Modifier.testTag("creation-collage-acknowledge-interruption")) {
                    Text(stringResource(if (state.publication == CreationCollagePublicationUi.RetryableMissing)
                        R.string.creation_collage_keep_editing else R.string.creation_collage_check_export))
                }
            }
        }
    } else if (!supported || state.failed || callbackError) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(if (!state.sourcesAvailable && state.publication == CreationCollagePublicationUi.None)
                    R.string.creation_collage_sources_unavailable else R.string.creation_collage_error),
                    Modifier.testTag("creation-collage-error"))
                TextButton(onClick = actions.onRetry, enabled = supported && !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    modifier = Modifier.testTag("creation-collage-retry")) { Text(stringResource(R.string.creation_collage_retry)) }
            }
        }
    }
    if (state.cancelled) Text(stringResource(R.string.creation_collage_cancelled))
    if (state.publishing) {
        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.creation_collage_publishing))
        TextButton(onClick = actions.onCancelExport, modifier = Modifier.testTag("creation-collage-cancel")) {
            Text(stringResource(R.string.creation_collage_cancel))
        }
    }
    state.result?.let {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.creation_collage_saved), Modifier.testTag("creation-collage-saved"))
                Text(stringResource(R.string.creation_collage_published_new_draft))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = actions.onOpen, enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        modifier = Modifier.testTag("creation-collage-open")) { Text(stringResource(R.string.creation_collage_open)) }
                    TextButton(onClick = actions.onShare, enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                        modifier = Modifier.testTag("creation-collage-share")) { Text(stringResource(R.string.creation_collage_share)) }
                }
            }
        }
    }
}

private fun CreationCollageTemplate.label(): Int = when (this) {
    CreationCollageTemplate.Grid2 -> R.string.creation_collage_grid2
    CreationCollageTemplate.Grid3 -> R.string.creation_collage_grid3
    CreationCollageTemplate.Grid4 -> R.string.creation_collage_grid4
    CreationCollageTemplate.Stack3 -> R.string.creation_collage_stack3
    CreationCollageTemplate.Strip3 -> R.string.creation_collage_strip3
    CreationCollageTemplate.Polaroid3 -> R.string.creation_collage_polaroid3
    CreationCollageTemplate.Strip4 -> R.string.creation_collage_strip4
}

private tailrec fun Context.collageActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.collageActivity() else null
    else -> null
}
