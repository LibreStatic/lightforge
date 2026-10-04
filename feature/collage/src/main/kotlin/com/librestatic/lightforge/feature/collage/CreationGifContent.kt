package com.librestatic.lightforge.feature.collage

import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

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
    val handoffReady = export.publication == CreationGifPublicationUi.Published
    val ui = CreationGifEditorUi(
        title = title,
        preview = preview,
        index = index,
        count = order.size,
        playing = playing,
        editable = editable,
        seconds = seconds,
        exportEnabled = editable && !previewError && !export.running,
        running = export.running,
        progress = export.progress,
        showError = !valid || previewError || export.failed || openError,
        cancelled = export.cancelled,
        sourcesAvailable = sourcesAvailable,
        recoveryMessage = recoveryMessage,
        showRecoveryActions = gifKeepsRecovery(export.publication) && !export.running,
        showKeepEditing = export.publication == CreationGifPublicationUi.RetryableMissing && sourcesAvailable,
        showDiscard = export.publication == CreationGifPublicationUi.Incomplete,
        savedVisible = export.uri != null && (handoffReady || export.publication == CreationGifPublicationUi.Unverified),
        handoffEnabled = handoffReady && !export.running && !closing,
    )
    val actions = CreationGifEditorActions(
        onBack = ::back,
        onTogglePlay = { playing = !playing },
        onPrevious = { playing = false; current = (index - 1 + order.size) % order.size },
        onNext = { playing = false; current = (index + 1) % order.size },
        onEarlier = { move(-1) },
        onLater = { move(1) },
        onRemove = { playing = false; order = order.filterIndexed { i, _ -> i != index }; current = index.coerceAtMost(order.lastIndex) },
        onSeconds = { seconds = it },
        onExport = { playing = false; controller.start(CreationGifRequest(sources, seconds), order) },
        onCancelExport = { scope.launch { controller.cancelAndWait() } },
        onCheckPublication = controller::checkPublication,
        onKeepEditing = { scope.launch { controller.acknowledgeMissing() } },
        onDiscard = { scope.launch { controller.discardIncomplete() } },
        onOpen = { open(false) },
        onShare = { open(true) },
    )
    CreationGifEditorLayout(ui, actions, contentScroll, modifier)
    if (exitDialog) AlertDialog(onDismissRequest = { exitDialog = false }, title = { Text(stringResource(R.string.creation_gif_leave)) }, text = { Text(stringResource(R.string.creation_gif_leave_hint)) },
        confirmButton = { TextButton(onClick = { exitDialog = false; closeDraft() }) { Text(stringResource(R.string.creation_collage_leave_confirm)) } },
        dismissButton = { TextButton(onClick = { exitDialog = false }) { Text(stringResource(R.string.creation_gif_keep)) } })
}
