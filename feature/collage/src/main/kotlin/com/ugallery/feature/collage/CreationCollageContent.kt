package com.ugallery.feature.collage

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import com.ugallery.core.designsystem.GalleryTopAppBar
import kotlinx.coroutines.launch

/** Caller supplies a fresh session ID per selection and public URI callbacks for app navigation. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
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
    val layout = state.layout
    val editingEnabled = supported && !state.busy &&
        collageAllowsNewRender(state.publication, state.sourcesAvailable)
    Surface(modifier.fillMaxSize().testTag("creation-collage-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            GalleryTopAppBar(title = stringResource(R.string.creation_collage_title), onBack = ::back,
                navigationContentDescription = stringResource(R.string.creation_collage_back))
            Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.creation_collage_originals))
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = MaterialTheme.shapes.large) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Bound width before deriving height: a wide viewport must not clip a square
                        // preview or leave contradictory pending measurements in Compose.
                        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            state.preview?.let { image -> Image(image.asImageBitmap(), stringResource(R.string.creation_collage_preview),
                                Modifier.fillMaxSize().testTag("creation-collage-preview"), contentScale = ContentScale.Fit) }
                            if (state.busy && !state.publishing) CircularProgressIndicator(Modifier.testTag("creation-collage-loading"))
                        }
                    }
                }
                Text(stringResource(R.string.creation_collage_template), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CreationCollageTemplate.forCount(sources.size).forEach { template ->
                        FilterChip(selected = layout?.template == template, onClick = { controller.chooseTemplate(template) },
                            enabled = editingEnabled, label = { Text(stringResource(template.label())) },
                            modifier = Modifier.testTag("creation-collage-template-${template.name}"))
                    }
                }
                if (layout != null) {
                    Text(stringResource(R.string.creation_collage_order), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        layout.order.forEachIndexed { slot, source ->
                            FilterChip(selected = state.selectedSlot == slot, onClick = { controller.select(slot) },
                                enabled = editingEnabled, label = { Text(stringResource(R.string.creation_collage_photo, source + 1)) },
                                modifier = Modifier.testTag("creation-collage-slot-$slot"))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { controller.move(-1) }, enabled = editingEnabled && state.selectedSlot > 0,
                            modifier = Modifier.testTag("creation-collage-earlier")) { Text(stringResource(R.string.creation_collage_earlier)) }
                        OutlinedButton(onClick = { controller.move(1) }, enabled = editingEnabled && state.selectedSlot < layout.order.lastIndex,
                            modifier = Modifier.testTag("creation-collage-later")) { Text(stringResource(R.string.creation_collage_later)) }
                    }
                    val crop = layout.crops[layout.order[state.selectedSlot]]
                    var zoom by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.zoom) }
                    var horizontal by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.horizontal) }
                    var vertical by remember(layout, state.selectedSlot) { mutableFloatStateOf(crop.vertical) }
                    fun commitCrop() = controller.crop(CreationCollageCrop(zoom, horizontal, vertical))
                    Text(stringResource(R.string.creation_collage_crop, layout.order[state.selectedSlot] + 1), style = MaterialTheme.typography.titleMedium)
                    val zoomLabel = stringResource(R.string.creation_collage_zoom)
                    val horizontalLabel = stringResource(R.string.creation_collage_horizontal)
                    val verticalLabel = stringResource(R.string.creation_collage_vertical)
                    Text("${zoomLabel}: ${java.text.NumberFormat.getNumberInstance().format(zoom)}", Modifier.testTag("creation-collage-zoom-value"))
                    Slider(zoom, { zoom = it }, enabled = editingEnabled, valueRange = 1f..3f,
                        onValueChangeFinished = ::commitCrop, modifier = Modifier.testTag("creation-collage-zoom").semantics { contentDescription = "${zoomLabel}: ${java.text.NumberFormat.getNumberInstance().format(zoom)}" })
                    Text("${horizontalLabel}: ${java.text.NumberFormat.getNumberInstance().format(horizontal)}", Modifier.testTag("creation-collage-horizontal-value"))
                    Slider(horizontal, { horizontal = it }, enabled = editingEnabled, valueRange = -1f..1f,
                        onValueChangeFinished = ::commitCrop, modifier = Modifier.testTag("creation-collage-horizontal").semantics { contentDescription = "${horizontalLabel}: ${java.text.NumberFormat.getNumberInstance().format(horizontal)}" })
                    Text("${verticalLabel}: ${java.text.NumberFormat.getNumberInstance().format(vertical)}", Modifier.testTag("creation-collage-vertical-value"))
                    Slider(vertical, { vertical = it }, enabled = editingEnabled, valueRange = -1f..1f,
                        onValueChangeFinished = ::commitCrop, modifier = Modifier.testTag("creation-collage-vertical").semantics { contentDescription = "${verticalLabel}: ${java.text.NumberFormat.getNumberInstance().format(vertical)}" })
                    TextButton(onClick = { controller.crop(CreationCollageCrop()) }, enabled = editingEnabled,
                        modifier = Modifier.testTag("creation-collage-reset")) { Text(stringResource(R.string.creation_collage_reset)) }
                }
                val recoveryIssue = collageKeepsRecovery(state.publication) && !state.busy
                if (recoveryIssue) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.padding(12.dp)) {
                            val message = when (state.publication) {
                                CreationCollagePublicationUi.RetryableMissing -> R.string.creation_collage_publication_not_started
                                CreationCollagePublicationUi.Incomplete -> R.string.creation_collage_publication_uncertain
                                CreationCollagePublicationUi.Conflict -> R.string.creation_collage_publication_conflict
                                else -> R.string.creation_collage_publication_unreadable
                            }
                            Text(stringResource(message), Modifier.testTag("creation-collage-publication-uncertain"))
                            TextButton(onClick = controller::acknowledgeInterruptedPublication,
                                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                                modifier = Modifier.testTag("creation-collage-acknowledge-interruption")) {
                                Text(stringResource(if (state.publication == CreationCollagePublicationUi.RetryableMissing)
                                    R.string.creation_collage_keep_editing else R.string.creation_collage_check_export))
                            }
                        }
                    }
                } else if (!supported || state.failed || callbackError) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(if (!state.sourcesAvailable && state.publication == CreationCollagePublicationUi.None)
                                R.string.creation_collage_sources_unavailable else R.string.creation_collage_error),
                                Modifier.testTag("creation-collage-error"))
                            TextButton(onClick = { callbackError = false; controller.retry() }, enabled = supported && !state.busy,
                                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                                modifier = Modifier.testTag("creation-collage-retry")) { Text(stringResource(R.string.creation_collage_retry)) }
                        }
                    }
                }
                if (state.cancelled) Text(stringResource(R.string.creation_collage_cancelled))
                if (state.publishing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.creation_collage_publishing))
                    TextButton(onClick = { scope.launch { controller.cancelAndWait() } }, modifier = Modifier.testTag("creation-collage-cancel")) {
                        Text(stringResource(R.string.creation_collage_cancel))
                    }
                } else Button(onClick = controller::export,
                    enabled = editingEnabled && state.preview != null && !state.failed && !state.publicationUncertain && state.result == null,
                    modifier = Modifier.fillMaxWidth().testTag("creation-collage-export")) { Text(stringResource(R.string.creation_collage_export)) }
                state.result?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.creation_collage_saved), Modifier.testTag("creation-collage-saved"))
                            Text(stringResource(R.string.creation_collage_published_new_draft))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { scope.launch {
                                    controller.verifyResultForHandoff()?.let { uri ->
                                        try { onOpen(uri) } catch (_: Exception) { callbackError = true }
                                    }
                                } }, enabled = !state.busy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                                    modifier = Modifier.testTag("creation-collage-open")) { Text(stringResource(R.string.creation_collage_open)) }
                                TextButton(onClick = { scope.launch {
                                    controller.verifyResultForHandoff()?.let { uri ->
                                        try { onShare(uri) } catch (_: Exception) { callbackError = true }
                                    }
                                } }, enabled = !state.busy,
                                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                                    modifier = Modifier.testTag("creation-collage-share")) { Text(stringResource(R.string.creation_collage_share)) }
                            }
                        }
                    }
                }
            }
        }
        if (exitConfirmation) AlertDialog(onDismissRequest = { exitConfirmation = false },
            title = { Text(stringResource(R.string.creation_collage_leave)) },
            text = { Text(stringResource(R.string.creation_collage_leave_hint)) },
            confirmButton = { TextButton(onClick = { exitConfirmation = false; closeDraft() }) {
                Text(stringResource(R.string.creation_collage_cancel)) } },
            dismissButton = { TextButton(onClick = { exitConfirmation = false }) { Text(stringResource(R.string.creation_collage_keep)) } })
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
