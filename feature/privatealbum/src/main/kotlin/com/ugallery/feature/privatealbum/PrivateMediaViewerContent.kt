package com.ugallery.feature.privatealbum

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.core.security.PrivateAlbumCrypto
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** No public URI, export, disk bitmap cache, or saved authentication enters this screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateMediaViewerContent(
    repository: PrivateAlbumRepository,
    mediaId: Long,
    onBack: () -> Unit,
    onAuthenticationRequired: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = remember(context) { viewerActivity(context) }
    val authRequired by rememberUpdatedState(onAuthenticationRequired)
    var source by remember(repository, mediaId) { mutableStateOf<PrivateViewerSource?>(null) }
    var blocked by remember(repository, mediaId) { mutableStateOf(false) }
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var error by remember(repository, mediaId) { mutableStateOf(false) }
    var attempt by remember(repository, mediaId) { mutableIntStateOf(0) }
    val indexState by repository.accessState.collectAsState()
    val indexReady = !repository.isSessionBacked || indexState == PrivateIndexAccessState.Ready
    val active = foreground && !blocked && indexReady
    BackHandler { blocked = true; source?.close(); onBack() }
    DisposableEffect(activity) {
        val window = activity?.window
        val previouslySecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false; blocked = true; source?.close()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(indexReady) { if (!indexReady) blocked = true }
    LaunchedEffect(repository, mediaId, active, attempt, error) {
        if (!active || error) return@LaunchedEffect
        var owned: PrivateViewerSource? = null
        try {
            error = false
            // Assign in IO so cancellation during dispatcher return still has a close owner.
            withContext(Dispatchers.IO) { owned = repository.openViewerSource(mediaId) }
            val opened = requireNotNull(owned)
            check(opened.metadata.mediaId == mediaId && opened.valid.value)
            source = opened
            opened.valid.collect { if (!it) { blocked = true; source = null } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (PrivateAlbumCrypto.requiresAuthentication(failure) || !indexReady) blocked = true
            else error = true
        } finally {
            source = null
            withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.testTag("private-viewer-screen"),
        topBar = {
            GalleryTopAppBar(
                title = if (active) source?.metadata?.displayName ?: stringResource(R.string.private_viewer_title)
                    else stringResource(R.string.private_viewer_title),
                onBack = { blocked = true; source?.close(); onBack() },
                navigationContentDescription = stringResource(R.string.private_viewer_back),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val current = source
            when {
                !active -> {
                    Text(stringResource(R.string.private_viewer_locked), Modifier.testTag("private-viewer-locked"))
                    Button(onClick = { authRequired() }, enabled = foreground) { Text(stringResource(R.string.private_viewer_unlock)) }
                }
                error -> {
                    Text(stringResource(R.string.private_viewer_error))
                    Button(onClick = { error = false; attempt++ }) { Text(stringResource(R.string.private_viewer_retry)) }
                }
                current == null -> CircularProgressIndicator(Modifier.testTag("private-viewer-loading"))
                else -> {
                    key(current) {
                        val valid by current.valid.collectAsState()
                        if (valid) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (current.metadata.mediaKind) {
                                    "image" -> PrivateViewerPhoto(current, onFailure = { error = true })
                                    "video" -> PrivateViewerVideo(current, onFailure = { error = true })
                                    else -> Text(stringResource(R.string.private_viewer_error))
                                }
                            }
                            TextButton(onClick = {
                                if (current.valid.value && foreground && !blocked) { blocked = true; current.close(); onExport() }
                            }, modifier = Modifier.testTag("private-viewer-export")) {
                                Text(stringResource(R.string.private_viewer_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateViewerPhoto(source: PrivateViewerSource, onFailure: () -> Unit) {
    val context = LocalContext.current
    val failure by rememberUpdatedState(onFailure)
    var tiles by remember(source) { mutableStateOf<PrivateImageTileSource?>(null) }
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    val frames = remember(source) { PrivateViewerFrameOwner<Bitmap> { it.recycle() } }
    DisposableEffect(frames) { onDispose { frames.close() } }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var centerX by remember(source) { mutableFloatStateOf(.5f) }
    var centerY by remember(source) { mutableFloatStateOf(.5f) }
    LaunchedEffect(source) {
        var owned: PrivateImageTileSource? = null
        try {
            withContext(Dispatchers.IO) { owned = PrivateImageTileSource(context, source) }
            tiles = requireNotNull(owned)
            // A stopped Activity may not recompose. Release the proxy on revocation itself.
            source.valid.first { !it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure() }
        finally {
            tiles = null
            withContext(NonCancellable + Dispatchers.IO) { owned?.close() }
        }
    }
    val decoder = tiles
    LaunchedEffect(decoder, zoom, centerX, centerY) {
        if (decoder == null) return@LaunchedEffect
        var decoded: Bitmap? = null
        try {
            val width = (decoder.width / zoom).roundToInt().coerceIn(1, decoder.width)
            val height = (decoder.height / zoom).roundToInt().coerceIn(1, decoder.height)
            val left = (centerX * decoder.width - width / 2f).roundToInt().coerceIn(0, decoder.width - width)
            val top = (centerY * decoder.height - height / 2f).roundToInt().coerceIn(0, decoder.height - height)
            var sample = 1
            while (maxOf(width, height).toLong() > 1600L * sample) sample *= 2
            withContext(Dispatchers.IO) { decoded = decoder.decodeRegion(Rect(left, top, left + width, top + height), sample) }
            ensureActive()
            check(source.valid.value)
            frames.offer(requireNotNull(decoded))
            bitmap = requireNotNull(decoded); decoded = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure() }
        finally { decoded?.recycle() }
    }
    val ownedBitmap = bitmap
    DisposableEffect(frames, ownedBitmap) {
        ownedBitmap?.let(frames::present)
        onDispose { ownedBitmap?.let(frames::releasePresented) }
    }
    val zoomLabel = stringResource(R.string.private_viewer_zoom)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().testTag("private-viewer-photo")
            .pointerInput(source) {
                detectTransformGestures { _, pan, scale, _ ->
                    if (source.valid.value) {
                        zoom = (zoom * scale).coerceIn(1f, 16f)
                        val half = .5f / zoom
                        centerX = (centerX - pan.x / size.width.coerceAtLeast(1) / zoom).coerceIn(half, 1f - half)
                        centerY = (centerY - pan.y / size.height.coerceAtLeast(1) / zoom).coerceIn(half, 1f - half)
                    }
                }
            }, contentAlignment = Alignment.Center) {
            ownedBitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.private_viewer_photo),
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Text(stringResource(R.string.private_viewer_zoom_value, zoom))
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..16f,
            modifier = Modifier.testTag("private-viewer-zoom").semantics { contentDescription = zoomLabel })
        TextButton(onClick = { zoom = 1f; centerX = .5f; centerY = .5f }) { Text(stringResource(R.string.private_viewer_reset)) }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PrivateViewerVideo(source: PrivateViewerSource, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val failure by rememberUpdatedState(onFailure)
    val player = remember(source) { ExoPlayer.Builder(context).build() }
    val released = remember(player) { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun releasePlayer() {
        if (released.compareAndSet(false, true)) {
            player.pause(); player.clearVideoSurface(); player.release()
        }
    }
    var aspect by remember(source) { mutableFloatStateOf(
        source.metadata.width.toFloat().coerceAtLeast(1f) / source.metadata.height.coerceAtLeast(1)) }
    var playing by remember(source) { mutableStateOf(false) }
    var position by remember(source) { mutableLongStateOf(0) }
    var duration by remember(source) { mutableLongStateOf(source.metadata.durationMillis.coerceAtLeast(0)) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height.coerceAtLeast(1)
                if (ratio.isFinite() && ratio > 0f) aspect = ratio
            }
            override fun onPlayerError(error: PlaybackException) { player.pause(); failure() }
        }
        player.addListener(listener)
        try {
            check(source.valid.value)
            val factory = PrivateMediaDataSource.Factory(source)
            player.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(
                MediaItem.fromUri("ugallery-private://viewer/${source.metadata.mediaId}")))
            player.playWhenReady = false
            player.prepare()
        } catch (_: Exception) { player.pause(); failure() }
        onDispose { player.removeListener(listener); releasePlayer() }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                releasePlayer()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, source) {
        source.valid.collect { if (!it) releasePlayer() }
    }
    LaunchedEffect(player) {
        while (isActive) {
            if (!source.valid.value || released.get()) { releasePlayer(); break }
            position = player.currentPosition.coerceAtLeast(0)
            if (player.duration > 0) duration = player.duration
            delay(100)
        }
    }
    val seekLabel = stringResource(R.string.private_viewer_seek)
    val sourceValid by source.valid.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { SurfaceView(it).apply { setSecure(true); player.setVideoSurfaceView(this) } },
                modifier = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f).testTag("private-viewer-video"))
        }
        Text(stringResource(R.string.private_viewer_time, viewerTime(position), viewerTime(duration)), Modifier.testTag("private-viewer-position"))
        Slider(value = position.coerceAtMost(duration).toFloat(), valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = duration > 0 && sourceValid,
            onValueChange = { if (source.valid.value) { player.pause(); position = it.toLong(); player.seekTo(position) } },
            modifier = Modifier.testTag("private-viewer-seek").semantics { contentDescription = seekLabel })
        Button(onClick = {
            if (source.valid.value) {
                if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
            }
        }, modifier = Modifier.testTag("private-viewer-play")) {
            Text(stringResource(if (playing) R.string.private_viewer_pause else R.string.private_viewer_play))
        }
    }
}

private fun viewerTime(millis: Long): String = String.format(Locale.getDefault(), "%d:%02d.%03d", millis / 60_000, millis / 1000 % 60, millis % 1000)
private tailrec fun viewerActivity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> if (context.baseContext !== context) viewerActivity(context.baseContext) else null
    else -> null
}

/** Main-thread ownership: at most one pending and one presented bitmap, including conflated frames. */
internal class PrivateViewerFrameOwner<T : Any>(private val release: (T) -> Unit) : AutoCloseable {
    private var pending: T? = null
    private var presented: T? = null
    private var closed = false
    fun offer(value: T) {
        check(!closed)
        val old = pending
        pending = value
        if (old !== value && old !== presented) old?.let(release)
    }
    fun present(value: T) { check(!closed); presented = value }
    fun releasePresented(value: T) {
        if (presented === value) presented = null
        if (pending === value) pending = null
        release(value)
    }
    override fun close() {
        if (closed) return
        closed = true
        if (pending !== presented) pending?.let(release)
        pending = null
        // The committed composition effect still owns the presented frame until its disposal.
    }
}
