package com.librestatic.lightforge.feature.viewer

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
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.abs
import kotlin.math.roundToInt
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.designsystem.GalleryAnimatedVisibility
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GalleryMotionEdge
import com.librestatic.lightforge.core.designsystem.GalleryOverlayTokens
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.librestatic.lightforge.core.model.ViewerMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailRequest
import com.librestatic.lightforge.core.preferences.GestureSettings
import com.librestatic.lightforge.core.preferences.VideoScrubbingMode
import kotlinx.coroutines.flow.first
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
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
import com.librestatic.lightforge.feature.viewer.textselect.PhotoLayerTransform
import com.librestatic.lightforge.feature.viewer.textselect.TextRecognitionProgress
import com.librestatic.lightforge.feature.viewer.textselect.TextSelectionController
import com.librestatic.lightforge.feature.viewer.textselect.TextSelectionHighlights
import com.librestatic.lightforge.feature.viewer.textselect.TextSelectionToolbar
import com.librestatic.lightforge.feature.viewer.textselect.ViewerTextRecognizer
import com.librestatic.lightforge.feature.viewer.textselect.detectTextSelectionGestures
import com.librestatic.lightforge.feature.viewer.textselect.fitCenterRect
import com.librestatic.lightforge.feature.viewer.textselect.rememberTextSelectionLongPress

@Composable
fun ViewerContent(
    media: ViewerMedia,
    mediaItems: List<ViewerMedia>,
    photoState: PhotoLoadState?,
    adjacentPhotoStates: Map<MediaKey, PhotoLoadState.Ready> = emptyMap(),
    videoController: VideoViewerController?,
    thumbnailLoader: ThumbnailLoader?,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onShareSanitized: (() -> Unit)?,
    onDetails: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onRename: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onOpenWith: (() -> Unit)?,
    onSetAs: (() -> Unit)?,
    onPrint: (() -> Unit)?,
    onRepairDate: (() -> Unit)?,
    onTrash: (() -> Unit)?,
    onSelectMedia: (ViewerMedia) -> Unit,
    onArchive: (() -> Unit)? = null,
    archiveActionLabel: String? = null,
    onMoveToPrivate: (() -> Unit)? = null,
    trashActionLabel: String? = null,
    onDelete: (() -> Unit)? = null,
    deleteActionLabel: String? = null,
    onMotionPhoto: (() -> Unit)? = null,
    motionPhotoLabel: String? = null,
    onContentTap: () -> Unit = {},
    slowMotionSession: HoldSlowMotionSession? = null,
    onSaveSlowMotionClip: (SlowMotionClip) -> Unit = {},
    slowMotionSaveProgress: Float? = null,
    slowMotionSaveCompletionGeneration: Long = 0,
    onCancelSlowMotionSave: () -> Unit = {},
    gestureSettings: GestureSettings = GestureSettings(),
    onMuteToggle: (Boolean) -> Unit = {},
    videoScrubbingMode: VideoScrubbingMode = VideoScrubbingMode.LegacySeekBar,
    textRecognizer: ViewerTextRecognizer? = null,
    /** Details surface driven by the swipe-up gesture; null opens Details through [onDetails]. */
    detailsState: ViewerDetailsState? = null,
    modifier: Modifier = Modifier,
) {
    // Held for the whole viewer route (photos and videos share the pager), not per page.
    WideColorGamutWindowEffect(enabled = true)
    var textSelectionActive by remember(media.viewerId) { mutableStateOf(false) }
    var chromeVisible by rememberSaveable(media.viewerId) { mutableStateOf(true) }
    var menuExpanded by remember { mutableStateOf(false) }
    var contentZoomed by remember(media.viewerId) { mutableStateOf(false) }
    var slowHoldConsumed by remember(media.viewerId) { mutableStateOf(false) }
    val slowMotionState by slowMotionSession?.state?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<HoldSlowMotionState>(HoldSlowMotionState.Idle) }
    var zoomTapGeneration by remember(media.viewerId) { mutableIntStateOf(0) }
    var chromeInteractionGeneration by remember(media.viewerId) { mutableIntStateOf(0) }
    // Auto-hide fades the video chrome out while it stays in place and hit-testable, so a tap
    // that lands during the fade still reaches its action (F-E2E-08).
    var chromeAutoHiding by remember(media.viewerId) { mutableStateOf(false) }
    val chromeAutoHideAlpha by animateFloatAsState(
        targetValue = if (chromeAutoHiding) 0f else 1f,
        animationSpec = tween(if (chromeAutoHiding) VIDEO_CHROME_AUTO_HIDE_FADE_MILLIS else CHROME_FADE_MILLIS),
        label = "videoChromeAutoHide",
    )
    var zoomTapPosition by remember(media.viewerId) { mutableStateOf(Offset.Zero) }
    var gestureFeedback by remember(media.viewerId) { mutableStateOf<String?>(null) }
    // A finished save dismisses the Save button and confirms once; the counter is process-wide,
    // so only a change seen while this viewer is open counts.
    val slowMotionSavedMessage = stringResource(R.string.viewer_slow_motion_saved)
    var seenSlowMotionSaveGeneration by remember(media.viewerId) {
        mutableLongStateOf(slowMotionSaveCompletionGeneration)
    }
    LaunchedEffect(slowMotionSaveCompletionGeneration) {
        if (slowMotionSaveCompletionGeneration == seenSlowMotionSaveGeneration) return@LaunchedEffect
        seenSlowMotionSaveGeneration = slowMotionSaveCompletionGeneration
        slowMotionSession?.discardSavedClip()
        gestureFeedback = slowMotionSavedMessage
        delay(2_000L)
        gestureFeedback = null
    }
    // An unsaved clip offer or a failure message fades out on its own instead of sticking around;
    // a save in progress keeps the offer up so Cancel stays reachable.
    val slowMotionOfferIdle = (slowMotionState is HoldSlowMotionState.ReadyToSave && slowMotionSaveProgress == null) ||
        slowMotionState is HoldSlowMotionState.Failure
    LaunchedEffect(slowMotionState, slowMotionOfferIdle) {
        if (!slowMotionOfferIdle) return@LaunchedEffect
        delay(SLOW_MOTION_OFFER_TIMEOUT_MILLIS)
        slowMotionSession?.discardSavedClip()
    }
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context.findViewerActivity()
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // The brightness gesture overrides the window brightness; give it back when the viewer closes
    // so the rest of the app does not stay dimmed or brightened.
    DisposableEffect(activity) {
        val window = activity?.window
        val original = window?.attributes?.screenBrightness
        onDispose {
            if (window != null && original != null) {
                window.attributes = window.attributes.apply { screenBrightness = original }
            }
        }
    }
    val videoState = videoController?.state?.collectAsStateWithLifecycle()?.value
    val videoIsPlaying = (videoState as? VideoViewerState.Ready)?.isPlaying == true
    val videoDurationMillis = (videoState as? VideoViewerState.Ready)?.durationMillis ?: 0L
    var videoPositionMillis by remember(media.viewerId) { mutableLongStateOf(0L) }
    var videoScrubPositionMillis by remember(media.viewerId) { mutableLongStateOf(0L) }
    var videoScrubbing by remember(media.viewerId) { mutableStateOf(false) }
    var filmstripExpanded by rememberSaveable(media.viewerId, videoScrubbingMode) {
        mutableStateOf(videoScrubbingMode == VideoScrubbingMode.Filmstrip)
    }
    var filmstripUnavailable by remember(media.viewerId, media.generationModified) { mutableStateOf(false) }
    // Video keeps the media unobstructed: its strip starts collapsed and the choice sticks while browsing.
    var videoStripCollapsed by rememberSaveable { mutableStateOf(true) }
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
    LaunchedEffect(videoController, media.viewerId, videoDurationMillis, videoScrubbing) {
        val controller = videoController ?: return@LaunchedEffect
        if (videoDurationMillis <= 0L) return@LaunchedEffect
        while (true) {
            if (!videoScrubbing) {
                videoPositionMillis = controller.currentPositionMillis().coerceIn(0L, videoDurationMillis)
            }
            delay(VIDEO_POSITION_UPDATE_MILLIS)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, videoController, media.viewerId) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                videoController?.onBackground()
                videoScrubbing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            videoController?.onBackground()
        }
    }
    fun beginVideoScrub() {
        if (videoScrubbing) return
        videoController?.beginScrubbing()
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
        if (!videoScrubbing) {
            videoController?.endScrubbing()
            return
        }
        videoController?.seekTo(videoScrubPositionMillis)
        videoController?.endScrubbing()
        videoScrubbing = false
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
        media.viewerId,
        media.kind,
        videoIsPlaying,
        chromeVisible,
        menuExpanded,
        chromeInteractionGeneration,
    ) {
        if (chromeVisible) chromeAutoHiding = false
        if (media.kind != MediaKind.Video) return@LaunchedEffect
        if (!videoIsPlaying) {
            chromeAutoHiding = false
            chromeVisible = true
            return@LaunchedEffect
        }
        if (chromeVisible && !menuExpanded) {
            delay(VIDEO_CHROME_TIMEOUT_MILLIS)
            chromeAutoHiding = true
            delay(VIDEO_CHROME_AUTO_HIDE_FADE_MILLIS.toLong())
            chromeVisible = false
        }
    }
    val displayedItems = mediaItems.ifEmpty { listOf(media) }
    val selectedIndex = displayedItems.indexOfFirst { it.viewerId == media.viewerId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex) { displayedItems.size }
    val latestDisplayedItems by rememberUpdatedState(displayedItems)
    val latestMediaKey by rememberUpdatedState(media.viewerId)
    val latestOnSelectMedia by rememberUpdatedState(onSelectMedia)
    LaunchedEffect(media.viewerId, selectedIndex) {
        if (pagerState.currentPage != selectedIndex) pagerState.scrollToPage(selectedIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
            .filter { !it.second }
            .map { it.first }
            .distinctUntilChanged()
            .collect { page ->
                latestDisplayedItems.getOrNull(page)
                    ?.takeIf { it.viewerId != latestMediaKey }
                    ?.let(latestOnSelectMedia)
            }
    }
    val dateLabel = remember(media.timelineSortMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(media.timelineSortMillis))
    }
    // The bottom chrome (scrubber, strip, actions) keeps its own drags: a swipe that starts on it
    // never opens Details or closes the viewer.
    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
    val latestBottomExcludedPx by rememberUpdatedState(if (chromeVisible) bottomChromeHeightPx.toFloat() else 0f)
    val latestDetailsState by rememberUpdatedState(detailsState)
    val latestOnDetails by rememberUpdatedState(onDetails)
    val latestOnBack by rememberUpdatedState(onBack)
    // Lift the media by half the compact sheet so it stays centred in the space left above it.
    val mediaLiftPx by remember(detailsState) {
        androidx.compose.runtime.derivedStateOf {
            val state = detailsState ?: return@derivedStateOf 0f
            if (state.isSidePanel) 0f else min(state.visiblePx, state.halfPx) / 2f
        }
    }
    // Keyboard: ←/→ page, Esc closes Details then the viewer, I toggles Details, Space plays or
    // pauses, +/- zoom, Delete asks before trashing, F favourites.
    val keyboardFocus = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }
    var deleteKeyConfirmVisible by remember(media.viewerId) { mutableStateOf(false) }
    val deleteKeyLabel = when {
        onDelete != null && deleteActionLabel != null -> deleteActionLabel
        onTrash != null && trashActionLabel == null -> stringResource(R.string.viewer_trash)
        else -> null
    }
    val deleteKeyAction = when {
        onDelete != null && deleteActionLabel != null -> onDelete
        onTrash != null && trashActionLabel == null -> onTrash
        else -> null
    }
    LaunchedEffect(media.viewerId) { runCatching { keyboardFocus.requestFocus() } }
    fun runShortcut(shortcut: ViewerShortcut): Boolean {
        when (shortcut) {
            ViewerShortcut.Previous, ViewerShortcut.Next -> {
                if (contentZoomed || textSelectionActive) return false
                val forward = (shortcut == ViewerShortcut.Next) != (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl)
                val target = pagerState.currentPage + if (forward) 1 else -1
                if (target !in displayedItems.indices) return false
                coroutineScope.launch { pagerState.animateScrollToPage(target) }
            }
            ViewerShortcut.Close -> if (detailsState?.isOpen == true) detailsState.close() else onBack()
            ViewerShortcut.Details -> when {
                detailsState?.isOpen == true -> detailsState.close()
                detailsState != null -> detailsState.open()
                onDetails != null -> onDetails()
                else -> return false
            }
            ViewerShortcut.PlayPause -> {
                val controller = videoController?.takeIf { media.kind == MediaKind.Video } ?: return false
                if (videoIsPlaying) controller.pause() else controller.play()
                chromeInteractionGeneration++
            }
            ViewerShortcut.ZoomIn, ViewerShortcut.ZoomOut -> {
                if ((shortcut == ViewerShortcut.ZoomIn) == contentZoomed) return false
                zoomTapPosition = Offset(viewerSize.width / 2f, viewerSize.height / 2f)
                zoomTapGeneration++
            }
            ViewerShortcut.Trash -> {
                if (deleteKeyAction == null) return false
                deleteKeyConfirmVisible = true
            }
            ViewerShortcut.Favorite -> onToggleFavorite?.invoke() ?: return false
        }
        return true
    }
    if (deleteKeyConfirmVisible && deleteKeyAction != null && deleteKeyLabel != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteKeyConfirmVisible = false },
            title = {
                Text(stringResource(if (onDelete != null) R.string.viewer_delete_confirm_title else R.string.viewer_trash_confirm_title))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { deleteKeyConfirmVisible = false; deleteKeyAction() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(deleteKeyLabel) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteKeyConfirmVisible = false }) {
                    Text(stringResource(R.string.viewer_cancel))
                }
            },
        )
    }
    Box(
        modifier.fillMaxSize().background(Color.Black)
            .onSizeChanged { viewerSize = it }
            .focusRequester(keyboardFocus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val key = event.nativeKeyEvent
                val shortcut = viewerShortcutFor(key.keyCode, key.isCtrlPressed, key.isAltPressed, key.isMetaPressed)
                shortcut != null && runShortcut(shortcut)
            }
            .focusable(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(media.viewerId, gestureSettings, contentZoomed, textSelectionActive) {
                    var start = Offset.Zero
                    var totalY = 0f
                    var mode = ViewerDragMode.None
                    var modeChosen = false
                    var sheetFollowing = false
                    val velocityTracker = VelocityTracker()
                    var initialBrightness = 0.5f
                    var initialVolume = 0
                    val maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectVerticalDragGestures(
                        onDragStart = { position ->
                            start = position
                            totalY = 0f
                            mode = ViewerDragMode.None
                            modeChosen = false
                            sheetFollowing = false
                            velocityTracker.resetTracking()
                            initialBrightness = activity?.window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: 0.5f
                            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        },
                        onVerticalDrag = { change, amount ->
                            totalY += amount
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (!modeChosen && amount != 0f) {
                                modeChosen = true
                                val sheet = latestDetailsState
                                val eligible = isViewerSwipeStart(
                                    startX = start.x,
                                    startY = start.y,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    bottomExcludedPx = latestBottomExcludedPx,
                                    zoomed = contentZoomed,
                                    textSelecting = textSelectionActive,
                                )
                                mode = viewerDragMode(
                                    eligible = eligible,
                                    firstDeltaY = amount,
                                    detailsOpen = sheet?.isOpen == true,
                                    swipeUpForDetails = gestureSettings.swipeUpForDetails && (sheet != null || latestOnDetails != null),
                                    swipeDownToClose = gestureSettings.swipeDownToClose,
                                )
                                if (mode == ViewerDragMode.Details && sheet != null && sheet.followsFinger) {
                                    sheetFollowing = true
                                    sheet.beginDrag()
                                }
                            }
                            val fraction = (-totalY / size.height.coerceAtLeast(1)).coerceIn(-1f, 1f)
                            val leftSide = start.x < size.width / 3f
                            val rightSide = start.x > size.width * 2f / 3f
                            when {
                                sheetFollowing -> {
                                    latestDetailsState?.dragBy(-amount)
                                    change.consume()
                                }
                                mode != ViewerDragMode.None -> change.consume()
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
                            val velocityY = velocityTracker.calculateVelocity().y
                            val sheet = latestDetailsState
                            if (sheetFollowing) {
                                sheet?.endDrag(-velocityY)
                            } else {
                                val result = viewerSwipeResult(mode, totalY, velocityY, size.height.toFloat(), sheet?.isOpen == true)
                                when (result) {
                                    ViewerSwipeResult.OpenDetails -> if (sheet != null) sheet.open() else latestOnDetails?.invoke()
                                    ViewerSwipeResult.CloseDetails -> sheet?.close()
                                    ViewerSwipeResult.CloseViewer -> latestOnBack()
                                    ViewerSwipeResult.None -> Unit
                                }
                            }
                            sheetFollowing = false
                            gestureFeedback = null
                        },
                        onDragCancel = {
                            if (sheetFollowing) latestDetailsState?.endDrag(0f)
                            sheetFollowing = false
                            gestureFeedback = null
                        },
                    )
                }
                .pointerInput(onContentTap, slowMotionSession, videoController, media.viewerId, gestureSettings) {
                    detectTapGestures(
                        onPress = {
                            if (media.kind != MediaKind.Video || slowMotionSession == null || videoController == null) {
                                tryAwaitRelease()
                                return@detectTapGestures
                            }
                            slowMotionSession.warmUp(videoController.currentPositionMillis())
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
                            // A tap during the auto-hide fade keeps the chrome instead of hiding it.
                            if (chromeAutoHiding && chromeVisible) chromeInteractionGeneration++ else chromeVisible = !chromeVisible
                            onContentTap()
                        },
                        onDoubleTap = { position ->
                            val edge = size.width / 3f
                            val skipSide = media.kind == MediaKind.Video && videoController != null &&
                                gestureSettings.videoSeek && (position.x < edge || position.x > size.width - edge)
                            when {
                                // The skip gesture has its own switch; it must not depend on double-tap zoom.
                                skipSide && position.x < edge ->
                                    videoController?.seekBy(-gestureSettings.videoSkipSeconds * 1_000L)
                                skipSide -> videoController?.seekBy(gestureSettings.videoSkipSeconds * 1_000L)
                                gestureSettings.doubleTapZoom -> { zoomTapPosition = position; zoomTapGeneration++ }
                            }
                        },
                    )
                },
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !contentZoomed && !textSelectionActive,
                key = { displayedItems[it].viewerId },
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -mediaLiftPx },
            ) { page ->
                val pageMedia = displayedItems[page]
                if (pageMedia.viewerId == media.viewerId) {
                    if (media.kind == MediaKind.Video) {
                        VideoSurface(
                            controller = videoController,
                            state = videoState,
                            poster = {
                                MediaThumbnail(
                                    media = media,
                                    thumbnailLoader = thumbnailLoader,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    backgroundColor = Color.Black,
                                )
                            },
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
                            textRecognizer = textRecognizer,
                            onTextSelectionActiveChange = { textSelectionActive = it },
                        )
                    }
                } else {
                    val adjacentPhoto = pageMedia.mediaKey?.let(adjacentPhotoStates::get)
                    if (pageMedia.kind == MediaKind.Image && adjacentPhoto != null) {
                        AdjacentPhotoSurface(adjacentPhoto)
                    } else {
                        MediaThumbnail(
                            media = pageMedia,
                            thumbnailLoader = thumbnailLoader,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            backgroundColor = Color.Black,
                        )
                    }
                }
            }
        }
        if (media.kind == MediaKind.Video) {
            ViewerChromeScrim(visible = chromeVisible, modifier = Modifier.graphicsLayer { alpha = chromeAutoHideAlpha })
        }
        if (media.kind == MediaKind.Video && videoController != null) {
            VideoPlaybackControl(
                controller = videoController,
                state = videoState,
                visible = chromeVisible,
                onInteraction = { chromeInteractionGeneration++ },
                modifier = Modifier.align(Alignment.Center).graphicsLayer {
                    alpha = chromeAutoHideAlpha
                    translationY = -mediaLiftPx
                },
            )
        }
        gestureFeedback?.let { feedback ->
            Text(
                feedback,
                color = GalleryOverlayTokens.Content,
                modifier = Modifier.align(Alignment.Center)
                    .background(GalleryOverlayTokens.FeedbackSurface, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        SlowMotionOverlay(
            state = slowMotionState,
            saveProgress = slowMotionSaveProgress,
            onSave = { clip ->
                // The export needs its own 4K decoder and encoder; a buffer still rendering beside the
                // player exhausts the hardware codecs and the export dies with NO_MEMORY.
                slowMotionSession?.suspendBuffering()
                onSaveSlowMotionClip(clip)
            },
            onDismiss = { slowMotionSession?.discardSavedClip() },
            onCancelSave = onCancelSlowMotionSave,
        )
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Top,
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { alpha = chromeAutoHideAlpha },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .viewerTopScrim()
                    .windowInsetsPadding(viewerTopInsets())
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val backLabel = stringResource(R.string.viewer_back)
                ViewerTooltip(backLabel, ViewerShortcutKeys.Back) {
                    GalleryExpressiveIconButton(onClick = onBack) {
                        Icon(GalleryIcons.Back, contentDescription = backLabel, tint = GalleryOverlayTokens.Content)
                    }
                }
                Text(
                    dateLabel,
                    Modifier.weight(1f).padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = GalleryOverlayTokens.Content,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Icon-only so a long localized label cannot squeeze the date.
                if (onMotionPhoto != null && motionPhotoLabel != null) {
                    ViewerTooltip(motionPhotoLabel, null) {
                        androidx.compose.material3.FilledTonalIconButton(onClick = onMotionPhoto) {
                            Icon(GalleryIcons.Play, contentDescription = motionPhotoLabel)
                        }
                    }
                }
                onDetails?.let { openDetails ->
                    val detailsLabel = stringResource(R.string.viewer_details)
                    ViewerTooltip(detailsLabel, ViewerShortcutKeys.Details) {
                        GalleryExpressiveIconButton(onClick = openDetails) {
                            Icon(GalleryIcons.Info, contentDescription = detailsLabel, tint = GalleryOverlayTokens.Content)
                        }
                    }
                }
                Box {
                    GalleryExpressiveIconButton(onClick = { menuExpanded = true }) {
                        Icon(GalleryIcons.More, contentDescription = stringResource(R.string.viewer_more), tint = GalleryOverlayTokens.Content)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        onDetails?.let { action -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_details)) },
                            onClick = { menuExpanded = false; action() },
                            leadingIcon = { Icon(GalleryIcons.Info, contentDescription = null) },
                        ) }
                        onShareSanitized?.let { action -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_share_private)) },
                            onClick = { menuExpanded = false; action() },
                            leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null) },
                        ) }
                        onPrint?.takeIf { media.kind == MediaKind.Image }?.let { action -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_print)) },
                            onClick = { menuExpanded = false; action() },
                        ) }
                        onRename?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_rename)) }, onClick = { menuExpanded = false; action() }) }
                        onCopy?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_copy_to)) }, onClick = { menuExpanded = false; action() }) }
                        onMove?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_move_to)) }, onClick = { menuExpanded = false; action() }) }
                        onSetAs?.takeIf { media.kind == MediaKind.Image }?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_set_as)) }, onClick = { menuExpanded = false; action() }) }
                        onOpenWith?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_open_with)) }, onClick = { menuExpanded = false; action() }) }
                        onRepairDate?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.viewer_repair_date)) }, onClick = { menuExpanded = false; action() }) }
                        onMoveToPrivate?.let { action -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_move_to_private)) },
                            onClick = { menuExpanded = false; action() },
                            leadingIcon = { Icon(GalleryIcons.Lock, contentDescription = null) },
                        ) }
                        if (onArchive != null && archiveActionLabel != null) {
                            DropdownMenuItem(
                                text = { Text(archiveActionLabel) },
                                onClick = { menuExpanded = false; onArchive() },
                                leadingIcon = { Icon(GalleryIcons.Archive, contentDescription = null) },
                            )
                        }
                    }
                }
            }
        }
        GalleryAnimatedVisibility(
            visible = chromeVisible,
            edge = GalleryMotionEdge.Bottom,
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chromeAutoHideAlpha },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .onSizeChanged { bottomChromeHeightPx = it.height }
                    .viewerBottomScrim()
                    .windowInsetsPadding(viewerBottomInsets())
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (media.kind == MediaKind.Video && videoDurationMillis > 0L && videoController != null) {
                    VideoScrubberRow(
                        positionMillis = displayedVideoPositionMillis,
                        durationMillis = videoDurationMillis,
                        showSeekBar = videoScrubbingMode == VideoScrubbingMode.LegacySeekBar || filmstripUnavailable,
                        onScrub = ::seekVideoFromScrubber,
                        onScrubFinished = ::finishVideoScrub,
                        muteButton = {
                            VideoMuteButton(
                                controller = videoController,
                                state = videoState,
                                onInteraction = { chromeInteractionGeneration++ },
                                onMuteToggle = onMuteToggle,
                            )
                        },
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
                    collapsed = media.kind == MediaKind.Video && videoStripCollapsed,
                    onToggleCollapsed = if (media.kind == MediaKind.Video) ({ videoStripCollapsed = !videoStripCollapsed }) else null,
                )
                ViewerActionPill(
                    onShare = onShare,
                    onEdit = onEdit,
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    // Trash and delete sit in the pill, as in Google Photos, not in the ⋮ menu.
                    onRestore = onTrash.takeIf { trashActionLabel != null },
                    restoreLabel = trashActionLabel,
                    onDelete = deleteKeyAction,
                    deleteLabel = deleteKeyLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
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

/**
 * The immersive video row: elapsed time, a thin seek bar, total time and mute. With
 * [showSeekBar] false (the frame filmstrip scrubs instead) only the times and mute remain.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VideoScrubberRow(
    positionMillis: Long,
    durationMillis: Long,
    showSeekBar: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    muteButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationMillis <= 0L) return
    val position = positionMillis.coerceIn(0L, durationMillis)
    val timeStyle = MaterialTheme.typography.labelMedium.copy(
        fontFeatureSettings = "tnum",
        color = GalleryOverlayTokens.Content,
    )
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatVideoTime(position), style = timeStyle)
        if (showSeekBar) {
            val colors = SliderDefaults.colors(
                thumbColor = GalleryOverlayTokens.Content,
                activeTrackColor = GalleryOverlayTokens.Content,
                inactiveTrackColor = GalleryOverlayTokens.Content.copy(alpha = 0.35f),
            )
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Slider(
                value = position.toFloat() / durationMillis.toFloat(),
                onValueChange = { fraction -> onScrub((durationMillis.toFloat() * fraction).toLong()) },
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..1f,
                colors = colors,
                interactionSource = interactionSource,
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        colors = colors,
                        thumbSize = androidx.compose.ui.unit.DpSize(4.dp, 20.dp),
                    )
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(4.dp),
                        colors = colors,
                        drawStopIndicator = null,
                        thumbTrackGapSize = 4.dp,
                    )
                },
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp)
                    .heightIn(min = 40.dp)
                    .testTag(VIDEO_LEGACY_SEEK_BAR_TEST_TAG),
            )
        } else {
            Text(" / ", style = timeStyle)
        }
        Text(formatVideoTime(durationMillis), style = timeStyle)
        if (!showSeekBar) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        muteButton()
    }
}

@Composable
private fun ViewerFilmstrip(
    items: List<ViewerMedia>,
    selectedIndex: Int,
    thumbnailLoader: ThumbnailLoader?,
    onSelectMedia: (ViewerMedia) -> Unit,
    expandedVideo: VideoFilmstripConfig? = null,
    onSelectedVideoTap: (() -> Unit)? = null,
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null,
) {
    if (items.size <= 1 && expandedVideo == null && onSelectedVideoTap == null) return
    if (thumbnailLoader == null && expandedVideo == null) return
    // Only a window around the selection is composed, so a scope of thousands of items costs the
    // same as a short one; a collapsed (video) strip keeps just the neighbours.
    val window = stripWindow(items.size, selectedIndex, if (collapsed) 1 else VIEWER_STRIP_RADIUS)
    val windowItems = if (window.isEmpty()) emptyList() else items.subList(window.first, window.last + 1)
    val localSelected = (selectedIndex - window.first).coerceIn(0, (windowItems.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (localSelected - 2).coerceAtLeast(0))
    val density = LocalDensity.current
    var stripCentered by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, window.first, collapsed) {
        // Wait for the first layout so the selection can be centred in the real viewport.
        val viewport = snapshotFlow { listState.layoutInfo.viewportSize.width }.first { it > 0 }
        val offset = -((viewport - with(density) { 66.dp.roundToPx() }) / 2)
        if (stripCentered) {
            listState.animateScrollToItem(localSelected, offset)
        } else {
            listState.scrollToItem(localSelected, offset)
            stripCentered = true
        }
    }
    Row(
        Modifier.widthIn(max = VIEWER_STRIP_MAX_WIDTH).fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    LazyRow(
        state = listState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, if (collapsed) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        itemsIndexed(windowItems, key = { _, item -> item.viewerId }) { localIndex, item ->
            val index = window.first + localIndex
            val selected = index == selectedIndex
            // The filmstrip only holds a paged window of the scope, so an "N of M" total would be
            // misleading (R-05); announce the kind and date, plus selected state, instead.
            val position = viewerThumbnailDescription(LocalContext.current.resources, item)
            if (selected && expandedVideo != null) {
                VideoFrameScrubber(
                    config = expandedVideo,
                    modifier = Modifier.width(280.dp).heightIn(min = 66.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(if (selected) 66.dp else 58.dp)
                        .then(
                            if (selected) {
                                Modifier
                            } else {
                                Modifier.border(
                                    width = 3.dp,
                                    color = GalleryOverlayTokens.FilmstripHalo,
                                    shape = RoundedCornerShape(8.dp),
                                )
                            },
                        )
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) GalleryOverlayTokens.Content else GalleryOverlayTokens.Content.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .pointerInput(item.viewerId, selected, onSelectedVideoTap) {
                            detectTapGestures {
                                if (selected && item.kind == MediaKind.Video && onSelectedVideoTap != null) {
                                    onSelectedVideoTap()
                                } else {
                                    onSelectMedia(item)
                                }
                            }
                        }
                        .semantics {
                            contentDescription = position
                            this.selected = selected
                            onClick(label = position) {
                                if (selected && item.kind == MediaKind.Video && onSelectedVideoTap != null) {
                                    onSelectedVideoTap()
                                } else {
                                    onSelectMedia(item)
                                }
                                true
                            }
                        },
                ) {
                    MediaThumbnail(item, thumbnailLoader, Modifier.fillMaxSize())
                    if (item.kind == MediaKind.Video) {
                        Box(
                            Modifier.align(Alignment.Center)
                                .size(34.dp)
                                .background(GalleryOverlayTokens.ControlSurface, RoundedCornerShape(17.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                GalleryIcons.Play,
                                contentDescription = null,
                                tint = GalleryOverlayTokens.Content,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    onToggleCollapsed?.let { toggle ->
        val label = stringResource(if (collapsed) R.string.viewer_show_more_thumbnails else R.string.viewer_show_fewer_thumbnails)
        ViewerTooltip(label, shortcut = null, above = true) {
            GalleryExpressiveIconButton(onClick = toggle, modifier = Modifier.padding(end = 4.dp)) {
                Icon(
                    if (collapsed) Icons.Filled.UnfoldMore else Icons.Filled.UnfoldLess,
                    contentDescription = label,
                    tint = GalleryOverlayTokens.Content,
                    modifier = Modifier.graphicsLayer { rotationZ = 90f },
                )
            }
        }
    }
    }
}

/** Thumbnails composed on each side of the selected one. */
private const val VIEWER_STRIP_RADIUS = 30

/** Keeps the strip next to the media on wide windows instead of stretching edge to edge. */
private val VIEWER_STRIP_MAX_WIDTH = 840.dp

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
            .border(5.dp, GalleryOverlayTokens.FilmstripHalo, RoundedCornerShape(8.dp))
            .border(3.dp, GalleryOverlayTokens.Content, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
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
            .semantics {
                contentDescription = timelineDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = config.positionMillis.toFloat(),
                    range = 0f..config.durationMillis.toFloat(),
                )
                setProgress { target ->
                    val clamped = target.coerceIn(0f, config.durationMillis.toFloat())
                    latestConfig.onScrub(clamped.toLong())
                    true
                }
            }
            .testTag(VIDEO_FRAME_SCRUBBER_TEST_TAG),
    ) {
        when (val frames = config.framesState) {
            VideoFramesState.Loading -> Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                GalleryLoadingIndicator(color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is VideoFramesState.Ready -> Row(Modifier.fillMaxSize()) {
                frames.frames.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            VideoFramesState.Unavailable -> Unit
        }
        val fraction = if (config.durationMillis <= 0L) 0f else {
            config.positionMillis.toFloat() / config.durationMillis.toFloat()
        }.coerceIn(0f, 1f)
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width * fraction
            val haloWidth = 7.dp.toPx()
            drawLine(GalleryOverlayTokens.FilmstripHalo, Offset(x, 0f), Offset(x, size.height), strokeWidth = haloWidth)
            drawLine(GalleryOverlayTokens.Content, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
        }
        Text(
            "${formatVideoTime(config.positionMillis)} / ${formatVideoTime(config.durationMillis)}",
            color = GalleryOverlayTokens.Content,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter)
                .background(GalleryOverlayTokens.TimelineSurface, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Box(
            Modifier.align(Alignment.TopEnd).size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            GalleryExpressiveIconButton(
                onClick = config.onClose,
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = closeDescription },
            ) {
                Box(
                    Modifier.size(32.dp)
                        .background(GalleryOverlayTokens.StrongSurface, RoundedCornerShape(16.dp))
                        .border(1.dp, GalleryOverlayTokens.Border, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GalleryIcons.Close, contentDescription = null, tint = GalleryOverlayTokens.Content)
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnail(
    media: ViewerMedia,
    thumbnailLoader: ThumbnailLoader?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundColor: Color? = null,
) {
    val resolvedBackgroundColor = backgroundColor ?: MaterialTheme.colorScheme.surfaceVariant
    val request = remember(media.mediaKey, media.generationModified) {
        media.mediaKey?.let { ThumbnailRequest(it, media.generationModified, 320, 320) }
    }
    val bitmap by produceState(
        initialValue = request?.let { thumbnailLoader?.cached(it) },
        media.mediaKey,
        media.generationModified,
        thumbnailLoader,
    ) {
        if (value == null && thumbnailLoader != null && request != null) {
            value = runCatching { thumbnailLoader.load(request) }.getOrNull()
        }
    }
    val current = bitmap
    if (current == null) {
        Box(modifier.background(resolvedBackgroundColor))
    } else {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier.background(resolvedBackgroundColor),
        )
    }
}

@Composable
private fun AdjacentPhotoSurface(state: PhotoLoadState.Ready) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { imageView -> imageView.setImageDrawable(state.drawable) },
        modifier = Modifier.fillMaxSize().background(Color.Black),
    )
}

@Composable
private fun PhotoSurface(
    state: PhotoLoadState?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
    textRecognizer: ViewerTextRecognizer?,
    onTextSelectionActiveChange: (Boolean) -> Unit,
) {
    val reducedMotion = rememberGalleryReducedMotion()
    val motionScheme = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = state,
        contentKey = { current ->
            when (current) {
                is PhotoLoadState.Thumbnail -> "thumbnail"
                is PhotoLoadState.Ready -> "ready"
                is PhotoLoadState.Error -> "error"
                null -> "loading"
            }
        },
        transitionSpec = {
            val immediateThumbnailUpgrade = initialState is PhotoLoadState.Thumbnail &&
                (targetState as? PhotoLoadState.Ready)?.thumbnailTransition == PhotoPreviewTransition.Immediate
            if (reducedMotion || immediateThumbnailUpgrade) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(motionScheme.fastEffectsSpec())
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { current ->
        PhotoSurfaceState(
            state = current,
            settings = settings,
            zoomTapPosition = zoomTapPosition,
            zoomTapGeneration = zoomTapGeneration,
            onZoomedChange = onZoomedChange,
            textRecognizer = textRecognizer,
            onTextSelectionActiveChange = onTextSelectionActiveChange,
        )
    }
}

@Composable
private fun PhotoSurfaceState(
    state: PhotoLoadState?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
    textRecognizer: ViewerTextRecognizer?,
    onTextSelectionActiveChange: (Boolean) -> Unit,
) {
    when (state) {
        is PhotoLoadState.Thumbnail -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.viewer_photo_description),
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentScale = ContentScale.Fit,
        )
        is PhotoLoadState.Ready -> {
            val scope = rememberCoroutineScope()
            var containerSize by remember(state.drawable) { mutableStateOf(IntSize.Zero) }
            val description = stringResource(R.string.viewer_photo_description)
            val density = LocalDensity.current
            // "Rotate photos": a view-only two-finger turn that snaps to quarter turns; the
            // snapped photo is refitted to the screen with its sides swapped.
            val rotation = remember(state.drawable) { Animatable(0f) }
            var snappedRotation by remember(state.drawable) { mutableFloatStateOf(0f) }
            val rotationFit = remember(state.drawable) { Animatable(1f) }
            val zoom = remember(state.drawable) {
                ZoomPanState(
                    density = density,
                    maxOffsets = { candidateScale ->
                        // After a quarter turn the photo shows with swapped sides, refitted.
                        val quarter = isQuarterTurn(snappedRotation)
                        val drawableWidth = state.drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
                        val drawableHeight = state.drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
                        val intrinsicWidth = if (quarter) drawableHeight else drawableWidth
                        val intrinsicHeight = if (quarter) drawableWidth else drawableHeight
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
                    zoom.zoomTo(center, minOf(DOUBLE_TAP_ZOOM, settings.photoMaxZoom), zoomTapPosition, settings.photoMaxZoom)
                }
            }
            val selection = remember(state.drawable, textRecognizer) {
                textRecognizer?.let { TextSelectionController(state.drawable, it) }
            }
            val selectionActive = selection?.active == true
            LaunchedEffect(selectionActive) { onTextSelectionActiveChange(selectionActive) }
            DisposableEffect(selection) { onDispose { onTextSelectionActiveChange(false) } }
            val imageRect = fitCenterRect(
                state.drawable.intrinsicWidth.toFloat(),
                state.drawable.intrinsicHeight.toFloat(),
                containerSize,
            )
            val latestImageRect by rememberUpdatedState(imageRect)
            val layerScale = { zoom.scale.value * rotationFit.value }
            val onTextLongPress = rememberTextSelectionLongPress(selection, scope) { latestImageRect }
            Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    // Transform gestures sit outside the layer so pan, centroid and velocity are
                    // in screen pixels; inside it they would be divided by the zoom, leaving the
                    // finger sliding over the photo and flings 1/scale as strong.
                    .pointerInput(state.drawable, settings.pinchZoom, settings.photoMaxZoom, settings.rotatePhotos) {
                        detectViewerTransformGestures(
                            isZoomed = { zoom.isZoomed },
                            allowPinch = settings.pinchZoom,
                            onRotate = if (settings.rotatePhotos) {
                                { degrees -> scope.launch { rotation.snapTo(snappedRotation + degrees) } }
                            } else null,
                            onRotateEnd = {
                                val target = snapRotationDegrees(rotation.value)
                                snappedRotation = target
                                val fitTarget = quarterTurnFitScale(
                                    state.drawable.intrinsicWidth.toFloat(),
                                    state.drawable.intrinsicHeight.toFloat(),
                                    containerSize.width.toFloat(),
                                    containerSize.height.toFloat(),
                                    target,
                                )
                                scope.launch { rotation.animateTo(target) }
                                scope.launch { rotationFit.animateTo(fitTarget) }
                                // Back to fit-to-screen so pan limits follow the new orientation.
                                scope.launch {
                                    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
                                    zoom.zoomTo(center, 1f, null, settings.photoMaxZoom)
                                    onZoomedChange(false)
                                }
                            },
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
                    }
                    .graphicsLayer(
                        scaleX = zoom.scale.value * rotationFit.value,
                        scaleY = zoom.scale.value * rotationFit.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                        rotationZ = rotation.value,
                    )
                    .then(
                        if (selection == null) Modifier else Modifier.pointerInput(selection) {
                            detectTextSelectionGestures(
                                controller = selection,
                                imageRect = { latestImageRect },
                                layerScale = layerScale,
                                onLongPress = onTextLongPress,
                            )
                        },
                    ),
            ) {
                AndroidView(
                    factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = {
                        it.setImageDrawable(state.drawable)
                        // AndroidView's native accessibility child must expose the same localized label.
                        it.contentDescription = description
                    },
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
                )
                if (selection != null) {
                    TextSelectionHighlights(selection, imageRect, layerScale())
                }
            }
            if (selection != null) {
                if (selection.recognizing) {
                    TextRecognitionProgress(Modifier.align(Alignment.Center))
                }
                TextSelectionToolbar(
                    controller = selection,
                    imageRect = imageRect,
                    transform = PhotoLayerTransform(
                        containerSize = containerSize,
                        scale = layerScale(),
                        rotationDegrees = rotation.value,
                        translation = Offset(zoom.offsetX.value, zoom.offsetY.value),
                    ),
                )
            }
            }
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
        else -> GalleryLoadingIndicator(Modifier.padding(24.dp))
    }
}

private suspend fun PointerInputScope.detectViewerTransformGestures(
    isZoomed: () -> Boolean,
    allowPinch: Boolean = true,
    onRotate: ((degrees: Float) -> Unit)? = null,
    onRotateEnd: () -> Unit = {},
    onGestureStart: () -> Unit = {},
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onGestureEnd: (velocity: Offset) -> Unit = {},
) {
    awaitEachGesture {
        var rotationTotal = 0f
        var rotating = false
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
                    (allowPinch || onRotate != null) && pressedPointers >= 2 -> transforming = true
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
                if (onRotate != null && pressedPointers >= 2) {
                    rotationTotal += event.calculateRotation()
                    // A slop keeps an ordinary pinch from tilting the photo.
                    if (!rotating && abs(rotationTotal) > ROTATE_SLOP_DEGREES) rotating = true
                    if (rotating) onRotate(rotationTotal)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (started) {
            val velocity = velocityTracker.calculateVelocity()
            onGestureEnd(Offset(velocity.x, velocity.y))
        }
        if (rotating) onRotateEnd()
    }
}

internal fun isQuarterTurn(degrees: Float): Boolean = (degrees / 90f).roundToInt() % 2 != 0

/**
 * Extra scale that refits a FIT_CENTER photo after a view rotation of [degrees]: on a quarter turn
 * the rotated sides must fit the container swapped; half and full turns keep the original fit.
 */
internal fun quarterTurnFitScale(
    imageWidth: Float,
    imageHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    degrees: Float,
): Float {
    if (!isQuarterTurn(degrees) || imageWidth <= 0f || imageHeight <= 0f ||
        containerWidth <= 0f || containerHeight <= 0f
    ) return 1f
    val fit = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val rotatedFit = min(containerWidth / imageHeight, containerHeight / imageWidth)
    return rotatedFit / fit
}

/** Snaps a free rotation to the nearest quarter turn. */
internal fun snapRotationDegrees(degrees: Float): Float = (degrees / 90f).roundToInt() * 90f

private const val ROTATE_SLOP_DEGREES = 15f

/** Double-tap zoom level, matching Google Photos (measured 2.5x in both orientations). */
private const val DOUBLE_TAP_ZOOM = 2.5f

@Composable
private fun VideoSurface(
    controller: VideoViewerController?,
    state: VideoViewerState?,
    aspectRatio: Float?,
    settings: GestureSettings,
    zoomTapPosition: Offset,
    zoomTapGeneration: Int,
    onZoomedChange: (Boolean) -> Unit,
    poster: @Composable () -> Unit = {},
) {
    // The player is created only once the open transition settles; until then (and until its
    // first decoded frame) the thumbnail stands in, so opening a video morphs instead of flashing
    // black. A spinner appears only when loading is genuinely slow.
    var slowLoading by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, state is VideoViewerState.Ready) {
        slowLoading = false
        if (state !is VideoViewerState.Ready) {
            delay(600)
            slowLoading = true
        }
    }
    if (controller == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            poster()
            if (slowLoading) GalleryLoadingIndicator()
        }
        return
    }
    val description = stringResource(R.string.viewer_video_description)
    val scope = rememberCoroutineScope()
    var videoContainerSize by remember(controller) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val resolvedAspectRatio = (state as? VideoViewerState.Ready)?.aspectRatio ?: aspectRatio
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
            zoom.zoomTo(center, minOf(DOUBLE_TAP_ZOOM, settings.videoMaxZoom), zoomTapPosition, settings.videoMaxZoom)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (state !is VideoViewerState.Failure) {
            AndroidView(
                factory = { context -> android.view.SurfaceView(context).also(controller::attachSurface) },
                modifier = (resolvedAspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize())
                    .onSizeChanged { videoContainerSize = it }
                    // Outside the layer: gestures must see screen pixels, not zoomed ones.
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
                    .graphicsLayer(
                        scaleX = zoom.scale.value,
                        scaleY = zoom.scale.value,
                        translationX = zoom.offsetX.value,
                        translationY = zoom.offsetY.value,
                    )
                    .semantics { contentDescription = description },
            )
        }
        AnimatedVisibility(
            visible = state !is VideoViewerState.Failure &&
                (state as? VideoViewerState.Ready)?.firstFrameRendered != true,
            enter = EnterTransition.None,
            exit = fadeOut(tween(220)),
        ) { poster() }
        when (state) {
            is VideoViewerState.Ready -> Unit
            is VideoViewerState.Failure -> Text(
                stringResource(R.string.viewer_unsupported),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            VideoViewerState.Idle,
            is VideoViewerState.Loading,
            null -> if (slowLoading) GalleryLoadingIndicator()
            VideoViewerState.Released -> Unit
        }
        if ((state as? VideoViewerState.Ready)?.usedSoftwareDecoder == true) {
            Text(
                text = stringResource(R.string.viewer_software_decoder_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
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
        // One large play/pause target in the middle; mute sits next to the scrubber.
        val label = stringResource(if (current.isPlaying) R.string.viewer_pause else R.string.viewer_play)
        ViewerTooltip(label, ViewerShortcutKeys.PlayPause) {
            FilledIconButton(
                onClick = {
                    onInteraction()
                    if (current.isPlaying) controller.pause() else controller.play()
                },
                shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
                modifier = Modifier.size(72.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = GalleryOverlayTokens.ControlSurface,
                    contentColor = GalleryOverlayTokens.Content,
                ),
            ) {
                Icon(
                    imageVector = if (current.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                    contentDescription = label,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/** Mute toggle for the scrubber row. */
@Composable
private fun VideoMuteButton(
    controller: VideoViewerController,
    state: VideoViewerState?,
    onInteraction: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
) {
    val current = state as? VideoViewerState.Ready ?: return
    GalleryExpressiveIconButton(
        onClick = {
            onInteraction()
            if (current.isMuted) {
                controller.unmute()
                onMuteToggle(false)
            } else {
                controller.mute()
                onMuteToggle(true)
            }
        },
    ) {
        Icon(
            imageVector = if (current.isMuted) GalleryIcons.VolumeOff else GalleryIcons.Volume,
            contentDescription = stringResource(if (current.isMuted) R.string.viewer_unmute else R.string.viewer_mute),
            tint = GalleryOverlayTokens.Content,
        )
    }
}

@Composable
private fun ViewerChromeScrim(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(CHROME_FADE_MILLIS)),
        exit = fadeOut(tween(CHROME_FADE_MILLIS)),
        modifier = modifier.fillMaxSize().testTag(VIEWER_CHROME_SCRIM_TEST_TAG),
    ) {
        Box(Modifier.fillMaxSize().background(GalleryOverlayTokens.ScrimBase)) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to GalleryOverlayTokens.ScrimTop,
                            0.20f to GalleryOverlayTokens.ScrimMiddle,
                            0.80f to GalleryOverlayTokens.ScrimMiddle,
                            1f to GalleryOverlayTokens.ScrimBottom,
                        ),
                    ),
                ),
            )
        }
    }
}


/** Hold-to-slow-motion layers: buffering veil, the 0.25× preview, the save offer and failures. */
@Composable
internal fun BoxScope.SlowMotionOverlay(
    state: HoldSlowMotionState,
    saveProgress: Float?,
    onSave: (SlowMotionClip) -> Unit,
    onDismiss: () -> Unit,
    onCancelSave: () -> Unit,
) {
    when (val slow = state) {
        HoldSlowMotionState.Buffering -> Box(
            Modifier.fillMaxSize().background(GalleryOverlayTokens.SoftVeil),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GalleryLoadingIndicator(color = GalleryOverlayTokens.Content)
                Text(stringResource(R.string.viewer_slow_motion_buffering), color = GalleryOverlayTokens.Content)
            }
        }
        is HoldSlowMotionState.Playing -> {
            Image(
                bitmap = slow.frame.asImageBitmap(),
                contentDescription = stringResource(R.string.viewer_slow_motion_preview),
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentScale = ContentScale.Fit,
            )
            Row(
                modifier = Modifier.align(Alignment.TopCenter)
                    .slowMotionOverlayTopPadding()
                    .background(GalleryOverlayTokens.TimelineSurface, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    GalleryIcons.SlowMotion,
                    contentDescription = null,
                    tint = GalleryOverlayTokens.Content,
                    modifier = Modifier.size(20.dp),
                )
                Text("0.25×", color = GalleryOverlayTokens.Content, style = MaterialTheme.typography.titleMedium)
            }
        }
        is HoldSlowMotionState.ReadyToSave -> Row(
            modifier = Modifier.align(Alignment.TopCenter).slowMotionOverlayTopPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val saving = saveProgress != null
            GalleryExpressiveButton(
                onClick = { onSave(slow.clip) },
                enabled = !saving,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                if (saving) {
                    GalleryLoadingIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        GalleryIcons.SlowMotion,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.viewer_save_slow_motion_clip))
            }
            if (saving) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = onCancelSave,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.viewer_cancel))
                }
            } else {
                val dismissLabel = stringResource(R.string.viewer_dismiss_slow_motion_clip)
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = onDismiss,
                    shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
                ) {
                    Icon(GalleryIcons.Close, contentDescription = dismissLabel)
                }
            }
        }
        is HoldSlowMotionState.Failure -> Text(
            slow.message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.align(Alignment.TopCenter)
                .slowMotionOverlayTopPadding()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
        HoldSlowMotionState.Idle -> Unit
    }
}

/** Places slow-motion overlays just under the top bar so they never cover Back, Details or More. */
@Composable
private fun Modifier.slowMotionOverlayTopPadding() =
    windowInsetsPadding(viewerTopInsets()).padding(top = 76.dp, start = 16.dp, end = 16.dp)

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

internal tailrec fun Context.findViewerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findViewerActivity()
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
/** Auto-hide fade; the chrome keeps its position and stays tappable until it ends. */
private const val VIDEO_CHROME_AUTO_HIDE_FADE_MILLIS = 700
private const val SLOW_MOTION_OFFER_TIMEOUT_MILLIS = 8_000L
internal const val VIEWER_CHROME_SCRIM_TEST_TAG = "viewer_chrome_scrim"
internal const val VIDEO_LEGACY_SEEK_BAR_TEST_TAG = "video_legacy_seek_bar"
internal const val VIDEO_FRAME_SCRUBBER_TEST_TAG = "video_frame_scrubber"

/** Accessibility label for a filmstrip thumbnail: media kind and capture time, no window total. */
internal fun viewerThumbnailDescription(resources: android.content.res.Resources, item: ViewerMedia): String {
    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(item.timelineSortMillis))
    return resources.getString(
        if (item.kind == MediaKind.Video) R.string.viewer_thumbnail_video else R.string.viewer_thumbnail_photo,
        date,
    )
}
