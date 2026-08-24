package com.ugallery.feature.viewer

import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ugallery.core.model.MediaKind
import com.ugallery.core.designsystem.GalleryAnimatedVisibility
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryMotionEdge
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.ugallery.core.model.TimelineMedia
import com.ugallery.core.thumbnail.ThumbnailLoader
import com.ugallery.core.thumbnail.ThumbnailRequest
import com.ugallery.core.preferences.GestureSettings
import com.ugallery.core.preferences.VideoScrubbingMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.Brush
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

@Composable
fun ViewerContent(
    media: TimelineMedia,
    mediaItems: List<TimelineMedia>,
    photoState: PhotoLoadState?,
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onShareSanitized: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onOpenWith: () -> Unit,
    onSetAs: () -> Unit,
    onPrint: () -> Unit,
    onRepairDate: () -> Unit,
    onTrash: () -> Unit,
    onSelectMedia: (TimelineMedia) -> Unit,
    onContentTap: () -> Unit = {},
    slowMotionSession: HoldSlowMotionSession? = null,
    onSaveSlowMotionClip: (SlowMotionClip) -> Unit = {},
    slowMotionSaveProgress: Float? = null,
    slowMotionSaveCompletionGeneration: Long = 0,
    gestureSettings: GestureSettings = GestureSettings(),
    videoScrubbingMode: VideoScrubbingMode = VideoScrubbingMode.LegacySeekBar,
    modifier: Modifier = Modifier,
) {
    var chromeVisible by rememberSaveable(media.key) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var contentZoomed by remember(media.key) { mutableStateOf(false) }
    var slowHoldConsumed by remember(media.key) { mutableStateOf(false) }
    val slowMotionState by slowMotionSession?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<HoldSlowMotionState>(HoldSlowMotionState.Idle) }
    var zoomTapGeneration by remember(media.key) { mutableIntStateOf(0) }
    var chromeInteractionGeneration by remember(media.key) { mutableIntStateOf(0) }
    var zoomTapPosition by remember(media.key) { mutableStateOf(Offset.Zero) }
    var gestureFeedback by remember(media.key) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context.findActivity()
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val videoState = videoController?.state?.collectAsStateWithLifecycle()?.value
    val videoIsPlaying = (videoState as? VideoViewerState.Ready)?.isPlaying == true
    val videoDurationMillis = (videoState as? VideoViewerState.Ready)?.durationMillis ?: 0L
    var videoPositionMillis by remember(media.key) { mutableLongStateOf(0L) }
    var videoScrubPositionMillis by remember(media.key) { mutableLongStateOf(0L) }
    var videoScrubbing by remember(media.key) { mutableStateOf(false) }
    var resumeAfterVideoScrub by remember(media.key) { mutableStateOf(false) }
    var filmstripExpanded by rememberSaveable(media.key, videoScrubbingMode) {
        mutableStateOf(videoScrubbingMode == VideoScrubbingMode.Filmstrip)
    }
    var filmstripUnavailable by remember(media.key, media.generationModified) { mutableStateOf(false) }
    val filmstripFrameRequest = (videoState as? VideoViewerState.Ready)?.takeIf {
        videoScrubbingMode == VideoScrubbingMode.Filmstrip && it.durationMillis > 0L
    }
    val videoFramesState by produceState<VideoFramesState>(
        initialValue = VideoFramesState.Loading,
        filmstripFrameRequest?.uri,
        filmstripFrameRequest?.durationMillis,
        media.generationModified,
    ) {
        val request = filmstripFrameRequest
        if (request == null) {
            value = VideoFramesState.Loading
            return@produceState
        }
        value = try {
            val frames = VideoFrameExtractor.extract(context, request.uri, request.durationMillis)
            if (frames.isEmpty()) VideoFramesState.Unavailable else VideoFramesState.Ready(frames)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            VideoFramesState.Unavailable
        }
    }
    LaunchedEffect(videoFramesState) {
        if (videoFramesState == VideoFramesState.Unavailable) filmstripUnavailable = true
    }
    val cachedVideoFrames = (videoFramesState as? VideoFramesState.Ready)?.frames
    DisposableEffect(cachedVideoFrames) {
        onDispose {
            cachedVideoFrames.orEmpty().forEach { frame -> if (!frame.isRecycled) frame.recycle() }
        }
    }
    LaunchedEffect(videoController, media.key, videoDurationMillis, videoScrubbing) {
        val controller = videoController ?: return@LaunchedEffect
        if (videoDurationMillis <= 0L) return@LaunchedEffect
        while (true) {
            if (!videoScrubbing) {
                videoPositionMillis = controller.currentPositionMillis().coerceIn(0L, videoDurationMillis)
            }
            delay(VIDEO_POSITION_UPDATE_MILLIS)
        }
    }
    fun beginVideoScrub() {
        if (videoScrubbing) return
        resumeAfterVideoScrub = videoIsPlaying
        if (videoIsPlaying) videoController?.pause()
        videoScrubPositionMillis = videoPositionMillis
        videoScrubbing = true
        chromeInteractionGeneration++
    }
    fun seekVideoFromScrubber(positionMillis: Long) {
        if (!videoScrubbing) beginVideoScrub()
        val position = positionMillis.coerceIn(0L, videoDurationMillis.coerceAtLeast(0L))
        videoScrubPositionMillis = position
        videoPositionMillis = position
        videoController?.seekTo(position)
    }
    fun finishVideoScrub() {
        if (!videoScrubbing) return
        videoScrubbing = false
        if (resumeAfterVideoScrub) videoController?.play()
        resumeAfterVideoScrub = false
        chromeInteractionGeneration++
    }
    val displayedVideoPositionMillis = if (videoScrubbing) {
        videoScrubPositionMillis
    } else {
        videoPositionMillis
    }
    val expandedVideoFilmstrip = (videoState as? VideoViewerState.Ready)
        ?.takeIf {
            videoScrubbingMode == VideoScrubbingMode.Filmstrip &&
                filmstripExpanded &&
                !filmstripUnavailable &&
                it.durationMillis > 0L
        }
        ?.let { ready ->
            VideoFilmstripConfig(
                uri = ready.uri,
                durationMillis = ready.durationMillis,
                positionMillis = displayedVideoPositionMillis,
                framesState = videoFramesState,
                onScrubStart = ::beginVideoScrub,
                onScrub = ::seekVideoFromScrubber,
                onScrubFinished = ::finishVideoScrub,
                onClose = { filmstripExpanded = false },
            )
        }
    val systemBarsController = remember(activity, view) {
        activity?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    DisposableEffect(systemBarsController) {
        val controller = systemBarsController
        if (controller == null) return@DisposableEffect onDispose { }
        val previousBehavior = controller.systemBarsBehavior
        val previousLightStatusBars = controller.isAppearanceLightStatusBars
        val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
            controller.isAppearanceLightStatusBars = previousLightStatusBars
            controller.isAppearanceLightNavigationBars = previousLightNavigationBars
        }
    }
    LaunchedEffect(systemBarsController, chromeVisible) {
        systemBarsController?.let { controller ->
            if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(
        media.key,
        media.kind,
        videoIsPlaying,
        chromeVisible,
        menuExpanded,
        chromeInteractionGeneration,
    ) {
        if (media.kind != MediaKind.Video) return@LaunchedEffect
        if (!videoIsPlaying) {
            chromeVisible = true
            return@LaunchedEffect
        }
        if (chromeVisible && !menuExpanded) {
            delay(VIDEO_CHROME_TIMEOUT_MILLIS)
            chromeVisible = false
        }
    }
    val displayedItems = mediaItems.ifEmpty { listOf(media) }
    val selectedIndex = displayedItems.indexOfFirst { it.key == media.key }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex) { displayedItems.size }
    val latestDisplayedItems by rememberUpdatedState(displayedItems)
    val latestMediaKey by rememberUpdatedState(media.key)
    val latestOnSelectMedia by rememberUpdatedState(onSelectMedia)
    LaunchedEffect(media.key, selectedIndex) {
        if (pagerState.currentPage != selectedIndex) pagerState.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .filter { !it.second }
            .map { it.first }
            .distinctUntilChanged()
            .collect { page ->
                latestDisplayedItems.getOrNull(page)
                    ?.takeIf { it.key != latestMediaKey }
                    ?.let(latestOnSelectMedia)
            }
    }
    val dateLabel = remember(media.timelineSortMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.timelineSortMillis))
    }
    Box(
        modifier.fillMaxSize().background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(media.key, gestureSettings, contentZoomed) {
                    var start = Offset.Zero
                    var totalY = 0f
                    var initialBrightness = 0.5f
                    var initialVolume = 0
                    val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectVerticalDragGestures(
                        onDragStart = { position ->
                            start = position
                            totalY = 0f
                            initialBrightness = activity?.window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: 0.5f
                            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onVerticalDrag = { change, amount ->
                            totalY += amount
                            val fraction = (-totalY / size.height.coerceAtLeast(1)).coerceIn(-1f, 1f)
                            val leftSide = start.x < size.width / 3f
                            val rightSide = start.x > size.width * 2f / 3f
                            when {
                                leftSide && ((media.kind == MediaKind.Video && gestureSettings.videoBrightness) || (media.kind != MediaKind.Video && gestureSettings.photoBrightness)) -> {
                                    val value = (initialBrightness + fraction).coerceIn(0.01f, 1f)
                                    activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = value }
                                    gestureFeedback = "☀ ${(value * 100).toInt()}%"
                                    change.consume()
                                }
                                rightSide && media.kind == MediaKind.Video && gestureSettings.videoVolume -> {
                                    val value = (initialVolume + fraction * maximumVolume).toInt().coerceIn(0, maximumVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                    videoController?.unmute()
                                    gestureFeedback = "♪ ${value * 100 / maximumVolume}%"
                                    change.consume()
                                }
                            }
                        },
                        onDragEnd = {
                            val center = start.x in (size.width / 3f)..(size.width * 2f / 3f)
                            if (center && !contentZoomed && gestureSettings.swipeDownToClose && totalY > size.height * 0.16f) onBack()
                            gestureFeedback = null
                        },
                        onDragCancel = { gestureFeedback = null },
                    )
                }
                .pointerInput(onContentTap, slowMotionSession, videoController, media.key, gestureSettings) {
                    detectTapGestures(
                        onPress = {
                            if (media.kind != MediaKind.Video || slowMotionSession == null || videoController == null) {
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            tryAwaitRelease()
                            if (slowHoldConsumed) {
                                val clip = slowMotionSession.stop()
                                val resumeAt = clip?.endMillis ?: videoController.currentPositionMillis()
                                videoController.seekTo(resumeAt)
                                videoController.play()
                                slowHoldConsumed = false
                            }
                        },
                        onLongPress = {
                            if (media.kind == MediaKind.Video && slowMotionSession != null && videoController != null) {
                                slowHoldConsumed = true
                                videoController.pause()
                                slowMotionSession.start(videoController.currentPositionMillis())
                            }
                        },
                        onTap = {
                            chromeVisible = !chromeVisible
                            onContentTap()
                        },
                        onDoubleTap = { position ->
                            if (!gestureSettings.doubleTapZoom) return@detectTapGestures
                            if (media.kind == MediaKind.Video && videoController != null) {
                                val edge = size.width / 3f
                                when {
                                    position.x < edge -> videoController.seekBy(-gestureSettings.videoSkipSeconds * 1_000L)
                                    position.x > size.width - edge -> videoController.seekBy(gestureSettings.videoSkipSeconds * 1_000L)
                                    else -> { zoomTapPosition = position; zoomTapGeneration++ }
                                }
                            } else {
                                zoomTapPosition = position
                                zoomTapGeneration++
                            }
                        },
                    )
                },
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !contentZoomed,
                key = { displayedItems[it].key },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageMedia = displayedItems[page]
                if (pageMedia.key == media.key) {
                    if (media.kind == MediaKind.Video) {
                        VideoSurface(
                            controller = videoController,
                            state = videoState,
                            aspectRatio = if (media.width > 0 && media.height > 0) {
                                media.width.toFloat() / media.height.toFloat()
                            } else null,
                            settings = gestureSettings,
                            zoomTapPosition = zoomTapPosition,
                            zoomTapGeneration = zoomTapGeneration,
                            onZoomedChange = { contentZoomed = it },
                        )
                    } else {
                        PhotoSurface(
                            state = photoState,
                            settings = gestureSettings,
                            zoomTapPosition = zoomTapPosition,
                            zoomTapGeneration = zoomTapGeneration,
                            onZoomedChange = { contentZoomed = it },
                        )
                    }
                } else {
                    MediaThumbnail(pageMedia, thumbnailLoader, Modifier.fillMaxSize())
                }
            }
        }
        if (media.kind == MediaKind.Video) {
            ViewerChromeScrim(visible = chromeVisible)
        }
        if (media.kind == MediaKind.Video && videoController != null) {
            VideoPlaybackControl(
                controller = videoController,
                state = videoState,
                visible = chromeVisible,
                onInteraction = { chromeInteractionGeneration++ },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        gestureFeedback?.let { feedback ->
            Text(
                feedback,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        when (val slow = slowMotionState) {
            HoldSlowMotionState.Buffering -> Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text(stringResource(R.string.viewer_slow_motion_buffering), color = Color.White)
                }
            }
            is HoldSlowMotionState.Playing -> {
                Image(
                    bitmap = slow.frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.viewer_slow_motion_preview),
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    "0.25×",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(top = 72.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            is HoldSlowMotionState.ReadyToSave -> androidx.compose.material3.Button(
                onClick = { onSaveSlowMotionClip(slow.clip) },
                enabled = slowMotionSaveProgress == null,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 72.dp, start = 12.dp),
            ) {
                if (slowMotionSaveProgress != null) {
                    CircularProgressIndicator(
                        progress = { slowMotionSaveProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                    )
                } else Text(stringResource(R.string.viewer_save_slow_motion_clip))
            }
            is HoldSlowMotionState.Failure -> Text(
                slow.message,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 72.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            )
            HoldSlowMotionState.Idle -> Unit
        }
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(viewerTopInsets())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(GalleryIcons.Back, contentDescription = stringResource(R.string.viewer_back), tint = Color.White)
                }
                Text(dateLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(GalleryIcons.More, contentDescription = stringResource(R.string.viewer_more), tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_details)) },
                            onClick = { menuExpanded = false; onDetails() },
                            leadingIcon = { Icon(GalleryIcons.Info, contentDescription = null) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_share_private)) },
                            onClick = { menuExpanded = false; onShareSanitized() },
                            leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_print)) },
                            enabled = media.kind == MediaKind.Image,
                            onClick = { menuExpanded = false; onPrint() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_rename)) },
                            onClick = { menuExpanded = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_copy_to)) },
                            onClick = { menuExpanded = false; onCopy() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_move_to)) },
                            onClick = { menuExpanded = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_set_as)) },
                            onClick = { menuExpanded = false; onSetAs() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_open_with)) },
                            onClick = { menuExpanded = false; onOpenWith() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_repair_date)) },
                            onClick = { menuExpanded = false; onRepairDate() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_trash), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onTrash() },
                            leadingIcon = { Icon(GalleryIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        )
                    }
                }
            }
        }
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(viewerBottomInsets())
            ) {
                if (
                    media.kind == MediaKind.Video &&
                    videoDurationMillis > 0L &&
                    (videoScrubbingMode == VideoScrubbingMode.LegacySeekBar || filmstripUnavailable)
                ) {
                    LegacyVideoSeekBar(
                        positionMillis = displayedVideoPositionMillis,
                        durationMillis = videoDurationMillis,
                        onScrub = ::seekVideoFromScrubber,
                        onScrubFinished = ::finishVideoScrub,
                    )
                }
                ViewerFilmstrip(
                    items = displayedItems,
                    selectedIndex = selectedIndex,
                    thumbnailLoader = thumbnailLoader,
                    onSelectMedia = onSelectMedia,
                    expandedVideo = expandedVideoFilmstrip,
                    onSelectedVideoTap = if (
                        media.kind == MediaKind.Video &&
                        videoScrubbingMode == VideoScrubbingMode.Filmstrip &&
                        !filmstripUnavailable
                    ) {
                        { filmstripExpanded = true }
                    } else null,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction(onClick = onShare, icon = GalleryIcons.Share, label = stringResource(R.string.viewer_share), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onEdit, icon = GalleryIcons.Edit, label = stringResource(R.string.viewer_edit), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onToggleFavorite, icon = GalleryIcons.Heart, label = stringResource(if (isFavorite) R.string.viewer_unfavorite else R.string.viewer_favorite), modifier = Modifier.weight(1f))
                    ViewerAction(onClick = onDetails, icon = GalleryIcons.Info, label = stringResource(R.string.viewer_details), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.heightIn(min = 72.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private data class VideoFilmstripConfig(
    val uri: Uri,
    val durationMillis: Long,
    val positionMillis: Long,
    val framesState: VideoFramesState,
    val onScrubStart: () -> Unit,
    val onScrub: (Long) -> Unit,
    val onScrubFinished: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
private fun LegacyVideoSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
) {
    if (durationMillis <= 0L) return
    val position = positionMillis.coerceIn(0L, durationMillis)
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(VIDEO_LEGACY_SEEK_BAR_TEST_TAG),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatVideoTime(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(formatVideoTime(durationMillis), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = position.toFloat() / durationMillis.toFloat(),
            onValueChange = { fraction -> onScrub((durationMillis.toFloat() * fraction).toLong()) },
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        )
    }
}

@Composable
private fun ViewerFilmstrip(
    items: List<TimelineMedia>,
    selectedIndex: Int,
    thumbnailLoader: ThumbnailLoader?,
    onSelectMedia: (TimelineMedia) -> Unit,
    expandedVideo: VideoFilmstripConfig? = null,
    onSelectedVideoTap: (() -> Unit)? = null,
) {
    if (items.size <= 1 && expandedVideo == null && onSelectedVideoTap == null) return
    if (thumbnailLoader == null && expandedVideo == null) return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            val selected = index == selectedIndex
            val position = stringResource(R.string.viewer_thumbnail_position, index + 1, items.size)
            if (selected && expandedVideo != null) {
                VideoFrameScrubber(
                    config = expandedVideo,
                    modifier = Modifier.width(280.dp).heightIn(min = 66.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(if (selected) 66.dp else 58.dp)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .background(Color.DarkGray, RoundedCornerShape(8.dp))
                        .pointerInput(item.key, selected, onSelectedVideoTap) {
                            detectTapGestures {
                                if (selected && item.kind == MediaKind.Video && onSelectedVideoTap != null) {
                                    onSelectedVideoTap()
                                } else {
                                    onSelectMedia(item)
                                }
                            }
                        }
                        .semantics { contentDescription = position },
                ) {
                    MediaThumbnail(item, thumbnailLoader, Modifier.fillMaxSize())
                    if (item.kind == MediaKind.Video) {
                        Icon(
                            GalleryIcons.Play,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface VideoFramesState {
    data object Loading : VideoFramesState
    data class Ready(val frames: List<android.graphics.Bitmap>) : VideoFramesState
    data object Unavailable : VideoFramesState
}

@Composable
private fun VideoFrameScrubber(
    config: VideoFilmstripConfig,
    modifier: Modifier = Modifier,
) {
    val latestConfig by rememberUpdatedState(config)
    val timelineDescription = stringResource(
        R.string.viewer_video_timeline_position,
        formatVideoTime(config.positionMillis),
        formatVideoTime(config.durationMillis),
    )
    val closeDescription = stringResource(R.string.viewer_close_video_timeline)
    Box(
        modifier
            .border(3.dp, Color.White, RoundedCornerShape(8.dp))
            .background(Color.DarkGray, RoundedCornerShape(8.dp))
            .pointerInput(config.uri, config.durationMillis) {
                fun seekAt(horizontalPosition: Float) {
                    val fraction = (horizontalPosition / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    latestConfig.onScrub((latestConfig.durationMillis.toFloat() * fraction).toLong())
                }
                detectDragGestures(
                    onDragStart = { start ->
                        latestConfig.onScrubStart()
                        seekAt(start.x)
                    },
                    onDragEnd = { latestConfig.onScrubFinished() },
                    onDragCancel = { latestConfig.onScrubFinished() },
                ) { change, _ ->
                    seekAt(change.position.x)
                    change.consume()
                }
            }
            .semantics { contentDescription = timelineDescription }
            .testTag(VIDEO_FRAME_SCRUBBER_TEST_TAG),
    ) {
        Row(Modifier.fillMaxSize()) {
            when (val frames = config.framesState) {
                VideoFramesState.Loading -> repeat(10) { index ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().background(
                            if (index % 2 == 0) Color.DarkGray else Color.Gray,
                        ),
                    )
                }
                is VideoFramesState.Ready -> frames.frames.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                VideoFramesState.Unavailable -> Unit
            }
        }
        val fraction = if (config.durationMillis <= 0L) 0f else {
            config.positionMillis.toFloat() / config.durationMillis.toFloat()
        }.coerceIn(0f, 1f)
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * fraction
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
        }
        Text(
            "${formatVideoTime(config.positionMillis)} / ${formatVideoTime(config.durationMillis)}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        IconButton(
            onClick = config.onClose,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                .semantics { contentDescription = closeDescription },
        ) {
            Icon(GalleryIcons.Close, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: TimelineMedia,
    thumbnailLoader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
) {
    val request = remember(media.key, media.generationModified) {
        ThumbnailRequest(media.key, media.generationModified, 320, 320)
    }
    val bitmap by produceState(
        initialValue = thumbnailLoader?.cached(request),
        media.key,
        media.generationModified,
        thumbnailLoader,
    ) {
        if (value == null && thumbnailLoader != null) {
            value = runCatching { thumbnailLoader.load(request) }.getOrNull()
        }
    }
    val current = bitmap
    if (current == null) {
        Box(modifier.background(Color.DarkGray))
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
private fun PhotoSurface(
    state: PhotoLoadState?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
) {
    when (state) {
        is PhotoLoadState.Ready -> {
            val scope = rememberCoroutineScope()
            var containerSize by remember(state.drawable) { mutableStateOf(IntSize.Zero) }
            val description = stringResource(R.string.viewer_photo_description)
            val density = LocalDensity.current
            val zoom = remember(state.drawable) {
                ZoomPanState(
                    density = density,
                    maxOffsets = { candidateScale ->
                        val intrinsicWidth = state.drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
                        val intrinsicHeight = state.drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
                        val width = containerSize.width.toFloat().coerceAtLeast(1f)
                        val height = containerSize.height.toFloat().coerceAtLeast(1f)
                        val fit = min(width / intrinsicWidth, height / intrinsicHeight)
                        val displayedWidth = intrinsicWidth * fit * candidateScale
                        val displayedHeight = intrinsicHeight * fit * candidateScale
                        Offset(
                            ((displayedWidth - width) / 2f).coerceAtLeast(0f),
                            ((displayedHeight - height) / 2f).coerceAtLeast(0f),
                        )
                    },
                )
            }
            LaunchedEffect(zoomTapGeneration) {
                if (zoomTapGeneration == 0) return@LaunchedEffect
                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                if (zoom.currentScale > ZoomPanState.ZOOMED_THRESHOLD) {
                    // Keep pager locked until the settle-back finishes; an interruption freezes
                    // the scale above identity and the flag must stay consistent with it.
                    zoom.zoomTo(center, 1f, null, settings.photoMaxZoom)
                    onZoomedChange(false)
                } else {
                    onZoomedChange(true)
                    zoom.zoomTo(center, minOf(2f, settings.photoMaxZoom), zoomTapPosition, settings.photoMaxZoom)
                }
            }
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { it.setImageDrawable(state.drawable) },
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .graphicsLayer(
                        scaleX = zoom.scale.value,
                        scaleY = zoom.scale.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                    )
                    .pointerInput(state.drawable, settings.pinchZoom, settings.photoMaxZoom) {
                        detectViewerTransformGestures(
                            isZoomed = { zoom.isZoomed },
                            allowPinch = settings.pinchZoom,
                            onGestureStart = { scope.launch { zoom.stopTransitions() } },
                            onGesture = { centroid, pan, factor ->
                                val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                scope.launch {
                                    zoom.applyGesture(center, centroid, pan, factor, 1f, settings.photoMaxZoom)
                                    onZoomedChange(zoom.isZoomed)
                                }
                            },
                            onGestureEnd = { velocity ->
                                if (!zoom.isZoomed && zoom.currentScale > 1f) {
                                    // Pinch/drag ended just above identity: settle smoothly
                                    // to 1x instead of freezing at a sub-threshold zoom.
                                    scope.launch {
                                        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                        zoom.zoomTo(center, 1f, null, settings.photoMaxZoom)
                                        onZoomedChange(false)
                                    }
                                } else {
                                    zoom.fling(velocity, scope)
                                }
                            },
                        )
                    }.semantics { contentDescription = description },
            )
            DisposableEffect(state.drawable) {
                (state.drawable as? AnimatedImageDrawable)?.start()
                onDispose { (state.drawable as? AnimatedImageDrawable)?.stop() }
            }
        }
        is PhotoLoadState.Error -> Text(
            stringResource(R.string.viewer_unsupported),
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.error,
        )
        else -> CircularProgressIndicator(Modifier.padding(24.dp))
    }
}

private suspend fun PointerInputScope.detectViewerTransformGestures(
    isZoomed: () -> Boolean,
    allowPinch: Boolean = true,
    onGestureStart: () -> Unit = {},
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: (velocity: Offset) -> Unit = {},
) {
    awaitEachGesture {
        var transforming = false
        var started = false
        var pendingSinglePointerPan = Offset.Zero
        var trackedPointerId: PointerId? = null
        val velocityTracker = VelocityTracker()
        do {
            val event = awaitPointerEvent()
            val pressedPointers = event.changes.count { it.pressed }
            val rawZoom = event.calculateZoom()
            val pan = event.calculatePan()
            var gesturePan = pan
            if (!transforming) {
                when {
                    allowPinch && pressedPointers >= 2 -> transforming = true
                    isZoomed() && pressedPointers == 1 -> {
                        pendingSinglePointerPan += pan
                        if (pendingSinglePointerPan.getDistance() > viewConfiguration.touchSlop) {
                            transforming = true
                            gesturePan = pendingSinglePointerPan
                        }
                    }
                }
            }
            if (transforming) {
                if (!started) {
                    started = true
                    onGestureStart()
                }
                // Feed the tracker from ONE stable pointer so pinch velocity stays sane;
                // when that pointer lifts we stop feeding rather than jump to another
                // finger's position, which would fabricate a velocity spike.
                if (trackedPointerId == null) {
                    trackedPointerId = event.changes.firstOrNull { it.pressed }?.id
                }
                event.changes
                    .firstOrNull { it.id == trackedPointerId && it.pressed }
                    ?.let { change ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    }
                if (rawZoom != 1f || gesturePan != Offset.Zero) {
                    // Honor the pinch-zoom setting even while already zoomed: without it a
                    // two-finger gesture contributes pan only, never scale.
                    onGesture(event.calculateCentroid(), gesturePan, if (allowPinch) rawZoom else 1f)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (started) {
            val velocity = velocityTracker.calculateVelocity()
            onGestureEnd(Offset(velocity.x, velocity.y))
        }
    }
}

@Composable
private fun VideoSurface(
    controller: VideoViewerController?,
    state: VideoViewerState?,
    aspectRatio: Float?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
) {
    if (controller == null) {
        CircularProgressIndicator(Modifier.padding(24.dp))
        return
    }
    val description = stringResource(R.string.viewer_video_description)
    val scope = rememberCoroutineScope()
    var videoContainerSize by remember(controller) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val zoom = remember(controller) {
        ZoomPanState(
            density = density,
            maxOffsets = { candidateScale ->
                val halfSpan = (candidateScale - 1f) / 2f
                Offset(videoContainerSize.width * halfSpan, videoContainerSize.height * halfSpan)
            },
        )
    }
    LaunchedEffect(zoomTapGeneration) {
        if (zoomTapGeneration == 0) return@LaunchedEffect
        val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
        if (zoom.currentScale > ZoomPanState.ZOOMED_THRESHOLD) {
            // Keep pager locked until the settle-back finishes; an interruption freezes
            // the scale above identity and the flag must stay consistent with it.
            zoom.zoomTo(center, 1f, null, settings.videoMaxZoom)
            onZoomedChange(false)
        } else {
            onZoomedChange(true)
            zoom.zoomTo(center, minOf(2f, settings.videoMaxZoom), zoomTapPosition, settings.videoMaxZoom)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state !is VideoViewerState.Failure) {
            AndroidView(
                factory = { context -> android.view.SurfaceView(context).also(controller::attachSurface) },
                modifier = (aspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                    .onSizeChanged { videoContainerSize = it }
                    .graphicsLayer(
                        scaleX = zoom.scale.value,
                        scaleY = zoom.scale.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                    )
                    .pointerInput(controller, settings.pinchZoom, settings.videoMaxZoom) {
                        detectViewerTransformGestures(
                            isZoomed = { zoom.isZoomed },
                            allowPinch = settings.pinchZoom,
                            onGestureStart = { scope.launch { zoom.stopTransitions() } },
                            onGesture = { centroid, pan, factor ->
                                val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
                                scope.launch {
                                    zoom.applyGesture(center, centroid, pan, factor, 1f, settings.videoMaxZoom)
                                    onZoomedChange(zoom.isZoomed)
                                }
                            },
                            onGestureEnd = { velocity ->
                                if (!zoom.isZoomed && zoom.currentScale > 1f) {
                                    // Pinch/drag ended just above identity: settle smoothly
                                    // to 1x instead of freezing at a sub-threshold zoom.
                                    scope.launch {
                                        val center = Offset(videoContainerSize.width / 2f, videoContainerSize.height / 2f)
                                        zoom.zoomTo(center, 1f, null, settings.videoMaxZoom)
                                        onZoomedChange(false)
                                    }
                                } else {
                                    zoom.fling(velocity, scope)
                                }
                            },
                        )
                    }
                    .semantics { contentDescription = description },
            )
        }
        when (state) {
            is VideoViewerState.Ready -> Unit
            is VideoViewerState.Failure -> Text(
                stringResource(R.string.viewer_unsupported),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            VideoViewerState.Idle,
            is VideoViewerState.Loading -> CircularProgressIndicator()
            VideoViewerState.Released -> Unit
            null -> CircularProgressIndicator()
        }
    }
    DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
}

@Composable
private fun VideoPlaybackControl(
    controller: VideoViewerController,
    state: VideoViewerState?,
    visible: Boolean,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state as? VideoViewerState.Ready ?: return
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CHROME_FADE_MILLIS)),
        exit = fadeOut(tween(CHROME_FADE_MILLIS)),
        modifier = modifier,
    ) {
        FilledIconButton(
            onClick = {
                onInteraction()
                when {
                    current.isMuted -> controller.unmute()
                    current.isPlaying -> controller.pause()
                    else -> controller.play()
                }
            },
            modifier = Modifier.size(56.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
            ),
        ) {
            Icon(
                imageVector = if (current.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                contentDescription = stringResource(
                    when {
                        current.isMuted -> R.string.viewer_unmute
                        current.isPlaying -> R.string.viewer_pause
                        else -> R.string.viewer_play
                    },
                ),
            )
        }
    }
}

@Composable
private fun ViewerChromeScrim(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CHROME_FADE_MILLIS)),
        exit = fadeOut(tween(CHROME_FADE_MILLIS)),
        modifier = Modifier.fillMaxSize().testTag(VIEWER_CHROME_SCRIM_TEST_TAG),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.65f),
                            0.28f to Color.Transparent,
                            0.62f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun viewerTopInsets() = WindowInsets.statusBarsIgnoringVisibility
    .union(WindowInsets.displayCutout)
    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun viewerBottomInsets() = WindowInsets.navigationBarsIgnoringVisibility
    .union(WindowInsets.displayCutout)
    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatVideoTime(positionMillis: Long): String {
    val totalSeconds = (positionMillis.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private const val VIDEO_CHROME_TIMEOUT_MILLIS = 3_000L
private const val VIDEO_POSITION_UPDATE_MILLIS = 200L
private const val CHROME_FADE_MILLIS = 150
internal const val VIEWER_CHROME_SCRIM_TEST_TAG = "viewer_chrome_scrim"
internal const val VIDEO_LEGACY_SEEK_BAR_TEST_TAG = "video_legacy_seek_bar"
internal const val VIDEO_FRAME_SCRUBBER_TEST_TAG = "video_frame_scrubber"
