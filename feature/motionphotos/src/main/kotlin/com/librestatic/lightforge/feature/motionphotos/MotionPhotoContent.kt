@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.feature.motionphotos

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator

/**
 * Actual embedded motion playback; key-frame persistence is delegated to the source-aware
 * repository.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MotionPhotoContent(
    input: MotionPhotoInput,
    keyFrameTimeUs: Long?,
    onSetKeyFrame: suspend (Long, File) -> Boolean,
    onBack: () -> Unit,
    onResetKeyFrame: (suspend () -> Boolean)? = null,
    modifier: Modifier = Modifier,
    sourceAvailable: Boolean = true,
    onBackKeepingRecovery: () -> Unit = onBack,
    onOpen: ((publicationId: String, uri: Uri, kind: MotionPhotoPublicationKind) -> Unit)? = null,
    onShare: ((publicationId: String, uri: Uri, kind: MotionPhotoPublicationKind) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity = input.identity
    val savedIdentity = rememberSaveable(identity) { identity }
    var session by remember(input) { mutableStateOf<MotionPhotoSession?>(null) }
    var loading by remember(input) { mutableStateOf(true) }
    var unsupported by remember(input) { mutableStateOf(false) }
    var error by remember(input) { mutableStateOf(false) }
    var selectedUs by
        rememberSaveable(identity) { mutableLongStateOf(-1) }
    var keyWorking by remember(identity) { mutableStateOf(false) }
    var operation by remember(identity) { mutableStateOf<Job?>(null) }
    var publicationId by rememberSaveable(identity) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var publicationMarker by rememberSaveable(identity) { mutableStateOf<ArrayList<String>?>(null) }
    val boundId = publicationId
    val publication = remember(boundId, identity) {
        MotionPhotoPublicationController(context.applicationContext, scope, boundId, input, savedIdentity,
            publicationMarker, onMarkerChanged = { marker -> if (publicationId == boundId) publicationMarker = marker })
    }
    val result by publication.state.collectAsState()
    LaunchedEffect(publication) { publication.checkPublication() }
    var closing by remember(publicationId) { mutableStateOf(false) }
    val publicationAttachment = remember(publicationId) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(publicationAttachment) { onDispose { publicationAttachment.set(false) } }
    val working = keyWorking || result.busy || closing
    val sourceGate = motionMayOpenSource(result.status, result.exporting, sourceAvailable)
    var saved by remember(identity) { mutableStateOf(false) }
    var playing by remember(identity) { mutableStateOf(false) }
    var exitDialog by remember(identity) { mutableStateOf(false) }
    val latestSet by rememberUpdatedState(onSetKeyFrame)
    val latestReset by rememberUpdatedState(onResetKeyFrame)
    LaunchedEffect(input, sourceGate, publication) {
        if (!sourceGate) { session = null; loading = false; return@LaunchedEffect }
        loading = true
        unsupported = false
        var owned: MotionPhotoSession? = null
        try {
            // rememberSaveable inputs alone do not validate the inputs of a restored Bundle.
            requireMotionPhotoIdentity(savedIdentity, identity)
            owned = MotionPhotoSession.open(context.applicationContext, input)
            selectedUs = restoredMotionPhotoFrame(selectedUs, keyFrameTimeUs, owned.defaultTimeUs, owned.durationUs)
            session = owned // Publish controls only after both source identity and frame are valid.
            loading = false
            awaitCancellation()
        } catch (_: TimeoutCancellationException) {
            unsupported = true
            loading = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            unsupported = true
            loading = false
        } finally {
            withContext(NonCancellable) {
                operation?.cancelAndJoin()
                publication.cancelAndWait()
                session = null
                withContext(Dispatchers.IO) { owned?.close() }
            }
        }
    }
    val player =
        remember(session) {
            session?.let { s ->
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(s.clip)))
                    playWhenReady = false
                    prepare()
                }
            }
        }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (player?.isPlaying == true) session?.let { active ->
                    selectedUs = (player.currentPosition * 1000).coerceIn(0, active.durationUs - 1)
                }
                player?.pause()
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            player?.pause()
            playing = false
        }
        onDispose { lifecycle.removeObserver(observer) }
    }
    var videoAspect by remember(session) { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                    if (size.width > 0 && size.height > 0)
                        videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // A queued end event must not overwrite a subsequent manual seek.
                    if (playbackState == Player.STATE_ENDED && player?.playbackState == Player.STATE_ENDED) {
                        session?.let { selectedUs = (it.durationUs - 1).coerceAtLeast(0) }
                        playing = false
                    }
                }

                override fun onPlayerError(failure: androidx.media3.common.PlaybackException) {
                    playing = false
                    error = true
                }
            }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            if (player?.isPlaying == true)
                selectedUs =
                    (player.currentPosition * 1000).coerceIn(
                        0,
                        ((session?.durationUs ?: 1) - 1).coerceAtLeast(0),
                    )
            delay(100)
        }
    }
    var frame by remember(session) { mutableStateOf<Bitmap?>(null) }
    var strip by remember(session) { mutableStateOf<List<Bitmap>>(emptyList()) }
    LaunchedEffect(session) {
        session?.let { s ->
            try {
                strip = s.frameTimesUs.map { s.frame(it, 240) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = true
            }
        }
    }
    LaunchedEffect(session, selectedUs, playing) {
        val s = session
        if (s != null && !playing && selectedUs in 0 until s.durationUs) {
            try {
                frame = s.frame(selectedUs)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = true
            }
        }
    }
    fun select(time: Long) {
        player?.pause()
        playing = false
        selectedUs = time
        player?.seekTo(time / 1000)
    }
    fun runOperation(action: suspend () -> Unit) {
        if (keyWorking || result.busy || closing || operation?.isCompleted == false) return
        player?.pause()
        playing = false
        keyWorking = true
        error = false
        saved = false
        operation =
            scope.launch {
                try {
                    action()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    error = true
                } finally {
                    keyWorking = false
                    operation = null
                }
            }
    }
    fun closeDraft() {
        if (closing) return
        closing = true
        player?.pause(); playing = false
        val closingId = publicationId
        scope.launch {
            try {
                operation?.cancelAndJoin()
                if (publication.closeResolved()) onBack() else onBackKeepingRecovery()
            } finally {
                if (publicationAttachment.get() && publicationId == closingId) closing = false
            }
        }
    }
    fun back() { if (working) exitDialog = true else closeDraft() }
    BackHandler { back() }
    fun createAnother() {
        if (working || !sourceAvailable || result.status !in setOf(MotionPhotoPublicationUi.Published, MotionPhotoPublicationUi.Retired)) return
        closing = true
        player?.pause(); playing = false
        val closingId = publicationId
        scope.launch {
            try {
                operation?.cancelAndJoin()
                if (publication.closeResolved()) {
                    publicationId = java.util.UUID.randomUUID().toString()
                    error = false; saved = false
                }
            } finally {
                if (publicationAttachment.get() && publicationId == closingId) closing = false
            }
        }
    }
    fun handoff(share: Boolean) {
        scope.launch {
            val receipt = publication.verifyForHandoff() ?: return@launch
            val uri = Uri.parse(checkNotNull(receipt.destination).uri)
            val mime = if (receipt.kind == MotionPhotoPublicationKind.Clip) "video/mp4" else "image/jpeg"
            try {
                val callback = if (share) onShare else onOpen
                if (callback != null) callback(receipt.publicationId, uri, receipt.kind)
                else {
                    val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(if (share) Intent.createChooser(intent, null) else intent)
                }
            } catch (_: Exception) { error = true }
        }
    }
    val previewReceipt = result.receipt.takeIf { result.status == MotionPhotoPublicationUi.Published }
    var resultPreview by remember(publicationId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(publication, previewReceipt) {
        resultPreview = null
        previewReceipt?.let { resultPreview = publication.loadPreview(it) }
    }
    LaunchedEffect(previewReceipt) {
        previewReceipt?.selectedTimeUs?.let { selectedUs = it }
        if (previewReceipt != null) { player?.pause(); playing = false }
    }
    DisposableEffect(resultPreview) {
        val ownedBitmap = resultPreview
        onDispose { ownedBitmap?.recycle() }
    }
    Surface(
        modifier.fillMaxSize().testTag("motion-screen").semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                GalleryTopAppBar(
                    title = stringResource(R.string.motion_title),
                    onBack = ::back,
                    navigationContentDescription = stringResource(R.string.motion_back),
                )
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (loading) {
                        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.motion_loading))
                    }
                    if (unsupported) {
                        Text(
                            stringResource(R.string.motion_unavailable),
                            Modifier.testTag("motion-unsupported"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(stringResource(R.string.motion_unavailable_hint))
                        Button(
                            onClick = ::closeDraft,
                            modifier = Modifier.testTag("motion-return-original"),
                        ) {
                            Text(stringResource(R.string.motion_original))
                        }
                    }
                    session?.let { s ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (player != null)
                                    AndroidView(
                                        factory = {
                                            SurfaceView(it).also(player::setVideoSurfaceView)
                                        },
                                        modifier =
                                            Modifier.aspectRatio(videoAspect)
                                                .testTag("motion-player"),
                                    )
                                if (!playing)
                                    frame?.let {
                                        Image(
                                            it.asImageBitmap(),
                                            stringResource(R.string.motion_frame),
                                            Modifier.fillMaxSize().testTag("motion-selected-frame"),
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                            }
                        }
                        Text(
                            stringResource(
                                R.string.motion_time,
                                selectedUs.coerceAtLeast(0) / 1000,
                                s.durationUs / 1000,
                            ),
                            Modifier.testTag("motion-time"),
                        )
                        Slider(
                            value = selectedUs.coerceAtLeast(0).toFloat(),
                            onValueChange = { select(it.toLong().coerceIn(0, s.durationUs - 1)) },
                            valueRange = 0f..(s.durationUs - 1).toFloat(),
                            enabled = !working,
                            modifier = Modifier.testTag("motion-scrub"),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            strip.forEachIndexed { index, bitmap ->
                                Surface(
                                    Modifier.weight(1f)
                                        .aspectRatio(1f)
                                        .selectable(
                                            selected = selectedUs == s.frameTimesUs[index],
                                            enabled = !working,
                                            role = Role.RadioButton,
                                            onClick = { select(s.frameTimesUs[index]) },
                                        )
                                        .testTag("motion-frame-$index"),
                                    color =
                                        if (selectedUs == s.frameTimesUs[index])
                                            MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainer,
                                    contentColor =
                                        if (selectedUs == s.frameTimesUs[index])
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                ) {
                                    Image(
                                        bitmap.asImageBitmap(),
                                        stringResource(R.string.motion_frame_number, index + 1),
                                        Modifier.padding(3.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (playing) {
                                        player?.pause()
                                    } else {
                                        player?.seekTo(
                                            if (player?.playbackState == Player.STATE_ENDED || selectedUs >= s.durationUs - 1000) 0
                                            else selectedUs.coerceAtLeast(0) / 1000
                                        )
                                        player?.play()
                                    }
                                },
                                enabled = !working,
                                modifier = Modifier.testTag("motion-play"),
                            ) {
                                Text(
                                    stringResource(
                                        if (playing) R.string.motion_pause else R.string.motion_play
                                    )
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val time = selectedUs
                                    runOperation {
                                        saved = s.withFrameFile(time) { latestSet(time, it) }
                                        if (!saved) error = true
                                    }
                                },
                                enabled = !working && frame != null && keyFrameTimeUs != selectedUs,
                                modifier = Modifier.testTag("motion-set-key-frame"),
                            ) {
                                Text(stringResource(R.string.motion_set_key))
                            }
                            if (keyFrameTimeUs != null && latestReset != null)
                                TextButton(
                                    onClick = {
                                        runOperation {
                                            saved = latestReset?.invoke() == true
                                            if (!saved) error = true
                                        }
                                    },
                                    enabled = !working,
                                    modifier = Modifier.testTag("motion-reset-key-frame"),
                                ) {
                                    Text(stringResource(R.string.motion_reset_key))
                                }
                        }
                        Text(
                            if (keyFrameTimeUs == null) stringResource(R.string.motion_original_key)
                            else
                                stringResource(R.string.motion_selected_key, keyFrameTimeUs / 1000),
                            Modifier.testTag("motion-key-frame"),
                        )
                        Text(
                            stringResource(R.string.motion_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val time = selectedUs
                                    player?.pause(); playing = false
                                    publication.exportFrame(s, time)
                                },
                                enabled = !working && frame != null,
                                modifier = Modifier.testTag("motion-export-jpeg"),
                            ) {
                                Text(stringResource(R.string.motion_save_jpeg))
                            }
                            OutlinedButton(
                                onClick = {
                                    player?.pause(); playing = false
                                    publication.exportClip(s)
                                },
                                enabled = !working,
                                modifier = Modifier.testTag("motion-export-mp4"),
                            ) {
                                Text(stringResource(R.string.motion_save_mp4))
                            }
                        }
                    }
                    if (working) {
                        GalleryIndeterminateProgressIndicator(Modifier.fillMaxWidth().testTag("motion-working"))
                        TextButton(onClick = { scope.launch { operation?.cancelAndJoin(); publication.cancelAndWait() } },
                            modifier = Modifier.testTag("motion-cancel-operation")) {
                            Text(stringResource(R.string.motion_cancel))
                        }
                    }
                    if (saved)
                        Text(
                            stringResource(R.string.motion_saved),
                            Modifier.testTag("motion-key-saved"),
                        )
                    if (error)
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                stringResource(R.string.motion_error),
                                Modifier.padding(12.dp).testTag("motion-error"),
                            )
                        }
                    if (!sourceAvailable) Text(stringResource(R.string.motion_publication_source_unavailable))
                    val publicationMessage = when (result.status) {
                        MotionPhotoPublicationUi.None -> null
                        MotionPhotoPublicationUi.Checking -> R.string.motion_publication_checking
                        MotionPhotoPublicationUi.RetryableMissing -> R.string.motion_publication_not_started
                        MotionPhotoPublicationUi.Incomplete -> R.string.motion_publication_incomplete
                        MotionPhotoPublicationUi.Conflict -> R.string.motion_publication_conflict
                        MotionPhotoPublicationUi.Unreadable -> R.string.motion_publication_unreadable
                        MotionPhotoPublicationUi.Published, MotionPhotoPublicationUi.Retired -> R.string.motion_publication_saved
                    }
                    publicationMessage?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp).testTag("motion-publication-recovery")) {
                                Text(stringResource(message))
                                if (motionKeepsPublication(result.status) && !working) {
                                    TextButton(onClick = publication::checkPublication, modifier = Modifier.testTag("motion-check-publication"),
                                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.motion_publication_check)) }
                                    if (result.status == MotionPhotoPublicationUi.RetryableMissing && sourceAvailable)
                                        TextButton(onClick = { scope.launch { publication.acknowledgeMissing() } },
                                            modifier = Modifier.testTag("motion-keep-editing"),
                                            colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.motion_publication_keep_editing)) }
                                }
                            }
                        }
                    }
                    if (result.status == MotionPhotoPublicationUi.Published) {
                        resultPreview?.let { bitmap ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface,
                                shape = MaterialTheme.shapes.large) {
                                Image(bitmap.asImageBitmap(), stringResource(R.string.motion_publication_preview),
                                    Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("motion-result-preview"), contentScale = ContentScale.Fit)
                            }
                        }
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.motion_exported), Modifier.testTag("motion-exported"))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { handoff(false) }, enabled = !working, modifier = Modifier.testTag("motion-open"),
                                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.motion_open)) }
                                    TextButton(onClick = { handoff(true) }, enabled = !working, modifier = Modifier.testTag("motion-share"),
                                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text(stringResource(R.string.motion_share)) }
                                }
                            }
                        }
                    }
                    if (result.status in setOf(MotionPhotoPublicationUi.Published, MotionPhotoPublicationUi.Retired))
                        OutlinedButton(onClick = ::createAnother, enabled = sourceAvailable && !working,
                            modifier = Modifier.testTag("motion-create-another")) { Text(stringResource(R.string.motion_publication_create_another)) }

                }
            }
        }
    }
    if (exitDialog)
        AlertDialog(
            onDismissRequest = { exitDialog = false },
            title = { Text(stringResource(R.string.motion_cancel_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        exitDialog = false
                        closeDraft()
                    }
                ) {
                    Text(stringResource(R.string.motion_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { exitDialog = false }) {
                    Text(stringResource(R.string.motion_continue))
                }
            },
        )
}
