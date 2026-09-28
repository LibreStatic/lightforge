package com.librestatic.lightforge.feature.collage

import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import kotlinx.coroutines.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CreationGifContent(sessionId: String, title: String, sources: List<CreationGifSource>, onBack: () -> Unit,
    modifier: Modifier = Modifier, onExported: (Uri) -> Unit = {},
    sourcesAvailable: Boolean = true, onBackKeepingRecovery: () -> Unit = onBack,
    onOpen: ((Uri) -> Unit)? = null, onShare: ((Uri) -> Unit)? = null) {
    require(sessionId.isNotBlank() && sessionId.length <= 128)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = sources.map { it.identity }
    val savedSessionId = rememberSaveable(sessionId) { sessionId }
    val savedIdentity = rememberSaveable(sessionId, saver = listSaver<List<String>, String>(save = { it }, restore = { it })) { identity }
    val controller: CreationGifViewModel = viewModel(key = "creation-gif-$sessionId")
    val export by controller.state.collectAsState()
    LaunchedEffect(sessionId, identity) { controller.bind(sessionId, sources) }
    var order by rememberSaveable(sessionId, stateSaver = listSaver(save = { it }, restore = { it })) { mutableStateOf(sources.indices.toList()) }
    var seconds by rememberSaveable(sessionId) { mutableIntStateOf(GifFrameTiming.Default) }
    var current by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    var notified by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    var playing by remember(sessionId) { mutableStateOf(false) }
    var exitDialog by remember(sessionId) { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, sessionId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var previewError by remember(identity, current, order) { mutableStateOf(false) }
    var openError by remember(sessionId) { mutableStateOf(false) }
    val valid = validCreationGifDraft(savedSessionId, sessionId, savedIdentity, identity, order, seconds, current)
    val index = current.coerceIn(0, (order.size - 1).coerceAtLeast(0))
    val editable = valid && gifAllowsNewExport(export.publication, sourcesAvailable) && !export.running
    val contentScroll = rememberScrollState()
    // A finished export disables every editor control; without this the outcome stays below the fold
    // and the screen is indistinguishable from one still working.
    val revealsOutcome = gifRevealsOutcome(export.publication, export.running)
    LaunchedEffect(revealsOutcome) {
        if (!revealsOutcome) return@LaunchedEffect
        snapshotFlow { contentScroll.maxValue }.collect { maximum -> contentScroll.animateScrollTo(maximum) }
    }
    val selected = if (editable) sources[order[index]] else null
    LaunchedEffect(export.receipt) {
        export.receipt?.let { receipt ->
            if (receipt.sessionId == sessionId && receipt.sourceIdentities == identity) {
                order = receipt.order
                seconds = receipt.frameTiming
                current = current.coerceIn(0, order.lastIndex)
                playing = false
            }
        }
    }
    val latestExported by rememberUpdatedState(onExported)
    LaunchedEffect(export.uri) {
        if (export.publication == CreationGifPublicationUi.Published) export.uri?.let {
            if (notified != it) { notified = it; latestExported(Uri.parse(it)) }
        }
    }
    LaunchedEffect(valid) { if (!valid) { playing = false; controller.cancelAndWait() } }
    LaunchedEffect(playing, seconds, order) {
        while (playing && valid) {
            // Same per-frame delays the exported file uses, so the preview matches its pacing.
            delay(GifFrameTiming.delaysMillis(seconds, current + 1)[current].toLong())
            // STOP updates playing even when background composition has not cancelled this effect.
            if (!playing || !valid) break
            current = (current + 1) % order.size
        }
    }
    var preview by remember(sessionId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    DisposableEffect(sessionId) {
        onDispose { preview?.recycle(); preview = null }
    }
    val publishedPreview = export.receipt.takeIf { export.publication == CreationGifPublicationUi.Published }
    LaunchedEffect(selected, publishedPreview) {
        preview = null
        if (publishedPreview != null) {
            preview = controller.loadVerifiedResultPreview(publishedPreview)
        } else if (selected != null) try {
            preview = withContext(Dispatchers.IO) {
                CreationGifExporter(context).verify(selected)
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, selected.uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1f, 512f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }.also { CreationGifExporter(context).verify(selected) }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { previewError = true; playing = false }
    }
    var closing by remember(sessionId) { mutableStateOf(false) }
    fun closeDraft() {
        if (closing) return
        closing = true
        playing = false
        scope.launch {
            // Only confirmed closure removes the root provider; unresolved publication retains its session.
            if (controller.closeResolvedDraft()) onBack() else onBackKeepingRecovery()
        }
    }
    fun back() { if (export.running) exitDialog = true else closeDraft() }
    BackHandler { back() }
    fun move(delta: Int) {
        playing = false
        val next = index + delta
        if (next in order.indices) { order = order.toMutableList().apply { val value = removeAt(index); add(next, value) }; current = next }
    }
    fun open(share: Boolean) {
        scope.launch {
            val uri = controller.verifyResultForHandoff() ?: return@launch
            try {
                val callback = if (share) onShare else onOpen
                if (callback != null) callback(uri)
                else {
                    val intent = if (share) Intent(Intent.ACTION_SEND).setType("image/gif").putExtra(Intent.EXTRA_STREAM, uri)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/gif")
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(if (share) Intent.createChooser(intent, null) else intent)
                }
            } catch (_: Exception) { openError = true }
        }
    }
    Surface(modifier.fillMaxSize().testTag("creation-gif-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().widthIn(max = 840.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GalleryTopAppBar(title = title, onBack = ::back, navigationContentDescription = stringResource(R.string.creation_gif_back))
            Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().verticalScroll(contentScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.large) {
                    Box(Modifier.fillMaxWidth().heightIn(max = 380.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        preview?.let { Image(it.asImageBitmap(), stringResource(R.string.creation_gif_preview), Modifier.fillMaxSize().testTag("creation-gif-preview"), contentScale = ContentScale.Fit) }
                    }
                }
                Text(stringResource(R.string.creation_gif_position, index + 1, order.size), Modifier.testTag("creation-gif-position"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { playing = !playing }, enabled = editable && preview != null, modifier = Modifier.testTag("creation-gif-play")) {
                        Text(stringResource(if (playing) R.string.creation_gif_pause else R.string.creation_gif_play))
                    }
                    OutlinedButton(onClick = { playing = false; current = (index - 1 + order.size) % order.size }, enabled = editable, modifier = Modifier.testTag("creation-gif-previous")) { Text(stringResource(R.string.creation_gif_previous)) }
                    OutlinedButton(onClick = { playing = false; current = (index + 1) % order.size }, enabled = editable, modifier = Modifier.testTag("creation-gif-next")) { Text(stringResource(R.string.creation_gif_next)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { move(-1) }, enabled = editable && index > 0, modifier = Modifier.testTag("creation-gif-earlier")) { Text(stringResource(R.string.creation_gif_earlier)) }
                    TextButton(onClick = { move(1) }, enabled = editable && index < order.lastIndex, modifier = Modifier.testTag("creation-gif-later")) { Text(stringResource(R.string.creation_gif_later)) }
                    TextButton(onClick = { playing = false; order = order.filterIndexed { i, _ -> i != index }; current = index.coerceAtMost(order.lastIndex) }, enabled = editable && order.size > 2, modifier = Modifier.testTag("creation-gif-remove")) { Text(stringResource(R.string.creation_gif_remove)) }
                }
                Text(stringResource(R.string.creation_gif_duration))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GifFrameTiming.SecondChoices.forEach { value -> FilterChip(selected = seconds == value, onClick = { seconds = value }, enabled = editable,
                        label = { Text(stringResource(R.string.creation_gif_seconds, value)) }, modifier = Modifier.testTag("creation-gif-seconds-$value")) }
                }
                Text(stringResource(R.string.creation_gif_frame_rate))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GifFrameTiming.FpsChoices.forEach { fps ->
                        val code = GifFrameTiming.fps(fps)
                        FilterChip(selected = seconds == code, onClick = { seconds = code }, enabled = editable,
                            label = { Text(stringResource(R.string.creation_gif_fps, fps)) }, modifier = Modifier.testTag("creation-gif-fps-$fps"))
                    }
                }
                val requestedFps = GifFrameTiming.fpsOf(seconds)
                if (requestedFps != null && requestedFps > GifFrameTiming.MaxReliableFps) Text(
                    stringResource(R.string.creation_gif_fps_limit, requestedFps, GifFrameTiming.MaxReliableFps),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("creation-gif-fps-limit"),
                )
                Text(stringResource(R.string.creation_gif_note))
                if (!valid || previewError || export.failed || openError) Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    Text(stringResource(R.string.creation_gif_error), Modifier.padding(12.dp).testTag("creation-gif-error"))
                }
                if (export.cancelled) Text(stringResource(R.string.creation_gif_cancelled), Modifier.testTag("creation-gif-cancelled"))
                if (export.running) {
                    LinearProgressIndicator(progress = { export.progress / 100f }, modifier = Modifier.fillMaxWidth().testTag("creation-gif-progress"))
                    TextButton(onClick = { scope.launch { controller.cancelAndWait() } }, modifier = Modifier.testTag("creation-gif-cancel")) { Text(stringResource(R.string.creation_gif_cancel)) }
                } else Button(onClick = { playing = false; controller.start(CreationGifRequest(sources, seconds), order) }, enabled = editable && !previewError, modifier = Modifier.testTag("creation-gif-export")) { Text(stringResource(R.string.creation_gif_export)) }
                if (!sourcesAvailable) Text(stringResource(R.string.gif_publication_sources_unavailable))
                val recoveryMessage = when (export.publication) {
                    CreationGifPublicationUi.Checking -> R.string.gif_publication_checking
                    CreationGifPublicationUi.RetryableMissing -> R.string.gif_publication_not_started
                    CreationGifPublicationUi.Incomplete -> R.string.gif_publication_incomplete
                    CreationGifPublicationUi.Conflict -> R.string.gif_publication_conflict
                    CreationGifPublicationUi.Unreadable -> R.string.gif_publication_unreadable
                    CreationGifPublicationUi.Unverified -> R.string.gif_publication_unverified
                    CreationGifPublicationUi.Published, CreationGifPublicationUi.Retired -> R.string.gif_publication_new_draft
                    CreationGifPublicationUi.None -> null
                }
                recoveryMessage?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Column(Modifier.padding(12.dp).testTag("creation-gif-recovery")) {
                            Text(stringResource(message))
                            if (gifKeepsRecovery(export.publication) && !export.running) {
                                TextButton(onClick = controller::checkPublication, modifier = Modifier.testTag("creation-gif-check-publication"),
                                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.gif_publication_check)) }
                                if (export.publication == CreationGifPublicationUi.RetryableMissing && sourcesAvailable)
                                    TextButton(onClick = { scope.launch { controller.acknowledgeMissing() } },
                                        modifier = Modifier.testTag("creation-gif-keep-editing"),
                                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.gif_publication_keep_editing)) }
                            }
                        }
                    }
                }
                val handoffReady = export.publication == CreationGifPublicationUi.Published
                export.uri?.takeIf { handoffReady || export.publication == CreationGifPublicationUi.Unverified }?.let {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.creation_gif_saved), Modifier.testTag("creation-gif-saved"))
                            Row {
                                TextButton(onClick = { open(false) }, enabled = handoffReady && !export.running && !closing, modifier = Modifier.testTag("creation-gif-open"), colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.creation_gif_open)) }
                                TextButton(onClick = { open(true) }, enabled = handoffReady && !export.running && !closing, modifier = Modifier.testTag("creation-gif-share"), colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.creation_gif_share)) }
                            }
                        }
                    }
                }
            }
        }
        if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false }, title = { Text(stringResource(R.string.creation_gif_leave)) }, text = { Text(stringResource(R.string.creation_gif_leave_hint)) },
            confirmButton = { TextButton(onClick = { exitDialog = false; closeDraft() }) { Text(stringResource(R.string.creation_gif_cancel)) } },
            dismissButton = { TextButton(onClick = { exitDialog = false }) { Text(stringResource(R.string.creation_gif_keep)) } })
    }
}
