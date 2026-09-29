package com.librestatic.lightforge.feature.videoeditor

import android.view.SurfaceView
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.feature.viewer.VideoViewerController
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryFoldOrientation
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveChoiceGroup
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveButton
import com.librestatic.lightforge.core.designsystem.GalleryIndeterminateProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryLoadingIndicator
import com.librestatic.lightforge.core.designsystem.GalleryProgressIndicator
import com.librestatic.lightforge.core.designsystem.GalleryMonoTypography
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.galleryWindowClass
import com.librestatic.lightforge.feature.viewer.VideoFrameExtractor
import com.librestatic.lightforge.feature.viewer.VideoViewerState
import com.librestatic.lightforge.core.editing.video.BuiltInLook
import com.librestatic.lightforge.core.editing.video.CubeLut
import com.librestatic.lightforge.core.editing.video.CustomLutOption
import com.librestatic.lightforge.core.editing.video.HueBand
import com.librestatic.lightforge.core.editing.video.LogInputProfile
import com.librestatic.lightforge.core.editing.video.LogWheel
import com.librestatic.lightforge.core.editing.video.LutReference
import com.librestatic.lightforge.core.editing.video.RealtimeColorLut
import com.librestatic.lightforge.core.editing.video.VideoColorGrade
import com.librestatic.lightforge.core.editing.video.VideoColorGradeEffects
import com.librestatic.lightforge.core.editing.video.VideoOutputQuality
import com.librestatic.lightforge.core.editing.video.VideoDynamicRange
import com.librestatic.lightforge.core.editing.video.VideoGeometry
import com.librestatic.lightforge.core.editing.video.SlowMotionAudioMode
import com.librestatic.lightforge.core.editing.video.SlowMotionSegment
import com.librestatic.lightforge.core.editing.video.VideoAnnotationEffect
import com.librestatic.lightforge.core.editing.video.VideoAnnotationLayer
import com.librestatic.lightforge.core.editing.video.VideoExportPhase
import com.librestatic.lightforge.core.editing.video.NormalizedPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PreviewCubeSize = 17
private const val FilmstripFrameCount = 8
private const val GeometryPreviewDebounceMillis = 50L
internal val EditorChipModifier = Modifier.widthIn(min = 80.dp).heightIn(min = 48.dp)

private data class VideoGradePreviewRequest(
    val grade: VideoColorGrade,
    val customLut: CubeLut?,
)

data class VideoEditorContentState(
    val currentMillis: Long = 0,
    val durationMillis: Long = 0,
    val trimStartMillis: Long = 0,
    val trimEndMillis: Long = 0,
    val speed: Float = 1f,
    val originalAudioVolume: Float = 1f,
    val selectedMusicName: String? = null,
    val selectedMusicUri: Uri? = null,
    val musicVolume: Float = 0.6f,
    val isExporting: Boolean = false,
    val isDirty: Boolean = false,
    val statusMessage: String? = null,
    val colorGrade: VideoColorGrade = VideoColorGrade(),
    val customLuts: List<CustomLutOption> = emptyList(),
    val activeCustomLut: CubeLut? = null,
    val outputQuality: VideoOutputQuality = VideoOutputQuality.H264Compatible,
    val dynamicRange: VideoDynamicRange = VideoDynamicRange.SdrRec709,
    val geometry: VideoGeometry = VideoGeometry(),
    val logDetectionMessage: String? = null,
    val isHevcMain10Available: Boolean = false,
    val isHlgExportAvailable: Boolean = false,
    val isHdr10ExportAvailable: Boolean = false,
    val slowMotionSegments: List<SlowMotionSegment> = emptyList(),
    val selectedSlowMotionSegmentId: String? = null,
    val slowMotionMarkInMillis: Long? = null,
    val exportProgress: Float? = null,
    val exportPhase: VideoExportPhase? = null,
    val usedSoftwareCodec: Boolean = false,
    val annotations: List<VideoAnnotationLayer> = emptyList(),
    val selectedAnnotationId: String? = null,
    val annotationTrackingProgress: Float? = null,
    val annotationTrackingCorrectionMillis: Long? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

private data class VideoAnnotationActions(
    val add: (VideoAnnotationLayer) -> Unit,
    val update: (VideoAnnotationLayer) -> Unit,
    val erase: (List<NormalizedPoint>, Long) -> Unit,
    val select: (String?) -> Unit,
    val delete: (String) -> Unit,
    val move: (String, Int) -> Unit,
    val clear: () -> Unit,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val addKeyframe: (String, Long) -> Unit,
    val track: (String, Long) -> Unit,
    val cancelTracking: () -> Unit,
)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun VideoEditorContent(
    sessionId: String,
    state: VideoEditorContentState,
    controller: VideoViewerController?,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit = {},
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit = {},
    onOutputQualityChange: (VideoOutputQuality) -> Unit = {},
    onDynamicRangeChange: (VideoDynamicRange) -> Unit = {},
    onGeometryChange: (VideoGeometry) -> Unit = {},
    onImportLut: () -> Unit = {},
    onMarkSlowMotionIn: (Long) -> Unit = {},
    onMarkSlowMotionOut: (Long) -> Unit = {},
    onSelectSlowMotionSegment: (String) -> Unit = {},
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit = {},
    onDeleteSlowMotionSegment: (String) -> Unit = {},
    onAddAnnotation: (VideoAnnotationLayer) -> Unit = {},
    onUpdateAnnotation: (VideoAnnotationLayer) -> Unit = {},
    onEraseAnnotations: (List<NormalizedPoint>, Long) -> Unit = { _, _ -> },
    onSelectAnnotation: (String?) -> Unit = {},
    onDeleteAnnotation: (String) -> Unit = {},
    onMoveAnnotation: (String, Int) -> Unit = { _, _ -> },
    onClearAnnotations: () -> Unit = {},
    onUndoAnnotation: () -> Unit = {},
    onRedoAnnotation: () -> Unit = {},
    onAddAnnotationKeyframe: (String, Long) -> Unit = { _, _ -> },
    onTrackAnnotation: (String, Long) -> Unit = { _, _ -> },
    onCancelAnnotationTracking: () -> Unit = {},
    onCancelExport: () -> Unit = {},
    onPositionCheckpoint: (Long) -> Unit = {},
    foldInfo: GalleryFoldInfo? = null,
    modifier: Modifier = Modifier,
) {
    require(sessionId.isNotBlank())
    key(sessionId) {
    var previewPositionMillis by rememberSaveable(sessionId) { mutableLongStateOf(state.currentMillis) }
    var selectedTab by rememberSaveable(sessionId) { mutableIntStateOf(0) }
    var showExportSheet by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showDiscardDialog by rememberSaveable(sessionId) { mutableStateOf(false) }
    // Held on the preview to see the original: it bypasses the grade without touching the recipe.
    var comparingOriginal by remember(sessionId) { mutableStateOf(false) }
    // Crop editing shows the whole, unrotated frame so the rectangle maps 1:1 onto the source.
    var cropEditing by rememberSaveable(sessionId) { mutableStateOf(false) }
    val cropActive = cropEditing && VideoEditorTool.fromIndex(selectedTab) == VideoEditorTool.Transform
    val previewGeometry = if (cropActive) VideoGeometry() else state.geometry
    var annotationTool by rememberSaveable(sessionId, stateSaver = VideoAnnotationToolStateSaver) {
        mutableStateOf(VideoAnnotationToolState())
    }
    val annotationActions = VideoAnnotationActions(
        onAddAnnotation,
        onUpdateAnnotation,
        onEraseAnnotations,
        onSelectAnnotation,
        onDeleteAnnotation,
        onMoveAnnotation,
        onClearAnnotations,
        onUndoAnnotation,
        onRedoAnnotation,
        onAddAnnotationKeyframe,
        onTrackAnnotation,
        onCancelAnnotationTracking,
    )
    val context = LocalContext.current
    val musicController = remember(sessionId, state.selectedMusicUri) {
        state.selectedMusicUri?.let { uri ->
            VideoViewerController(context, initialLooping = true).also {
                it.select(uri, autoplay = false)
                it.setVolume(state.musicVolume)
            }
        }
    }
    DisposableEffect(musicController) { onDispose { musicController?.close() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestState by rememberUpdatedState(state)
    val checkpoint by rememberUpdatedState(onPositionCheckpoint)
    DisposableEffect(controller, lifecycle) {
        controller?.pause()
        controller?.setLooping(true)
        previewPositionMillis = videoEditorDraftPosition(
            previewPositionMillis, state.trimStartMillis, state.trimEndMillis, state.durationMillis,
        )
        controller?.seekTo(previewPositionMillis)
        onDispose { controller?.pause() }
    }
    DisposableEffect(controller, musicController, lifecycle) {
        fun pauseAndCheckpoint() {
            controller?.pause()
            musicController?.pause()
            val current = if (controller?.state?.value is VideoViewerState.Ready) {
                controller.currentPositionMillis()
            } else null
            previewPositionMillis = videoEditorCheckpointPosition(
                current, previewPositionMillis, latestState.trimStartMillis,
                latestState.trimEndMillis, latestState.durationMillis,
            )
            checkpoint(previewPositionMillis)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                pauseAndCheckpoint()
            }
        }
        lifecycle.addObserver(observer)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pauseAndCheckpoint()
        onDispose {
            lifecycle.removeObserver(observer)
            pauseAndCheckpoint()
        }
    }
    LaunchedEffect(controller, state.annotationTrackingCorrectionMillis) {
        state.annotationTrackingCorrectionMillis?.let { position ->
            previewPositionMillis = position
            onSeek(position)
            controller?.seekTo(position)
            controller?.pause()
        }
    }
    LaunchedEffect(
        controller,
        state.trimStartMillis,
        state.trimEndMillis,
        state.speed,
        state.originalAudioVolume,
        state.musicVolume,
        musicController,
        state.slowMotionSegments,
    ) {
        var appliedSpeed = Float.NaN
        var appliedVolume = Float.NaN
        while (true) {
            val controllerReady = controller?.state?.value as? VideoViewerState.Ready
            val position = if (controllerReady != null) controller?.currentPositionMillis() ?: previewPositionMillis
                else previewPositionMillis
            val trimEnd = state.trimEndMillis.takeIf { it > state.trimStartMillis }
                ?: state.durationMillis
            if (position < state.trimStartMillis || position >= trimEnd) {
                controller?.seekTo(state.trimStartMillis)
                previewPositionMillis = state.trimStartMillis
            } else {
                previewPositionMillis = position
            }
            val slowSegment = state.slowMotionSegments.firstOrNull {
                previewPositionMillis in it.startMillis until it.endMillis
            }
            val desiredSpeed = slowSegment?.speed ?: state.speed
            val desiredVolume = if (slowSegment?.audioMode == SlowMotionAudioMode.Muted) {
                0f
            } else state.originalAudioVolume
            if (controller != null && desiredSpeed != appliedSpeed) {
                controller.setPlaybackSpeed(desiredSpeed)
                appliedSpeed = desiredSpeed
            }
            if (controller != null && desiredVolume != appliedVolume) {
                controller.setVolume(desiredVolume)
                appliedVolume = desiredVolume
            }
            if (musicController != null) {
                musicController.setVolume(state.musicVolume)
                val musicPosition = musicController.currentPositionMillis()
                val desiredPosition = editedTimelinePosition(
                    previewPositionMillis,
                    state.trimStartMillis,
                    state.speed,
                    state.slowMotionSegments,
                )
                if (kotlin.math.abs(musicPosition - desiredPosition) > 350) {
                    musicController.seekTo(desiredPosition)
                }
                val mainReady = controller?.state?.value as? VideoViewerState.Ready
                if (mainReady?.isPlaying == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    musicController.play()
                } else musicController.pause()
            }
            delay(100)
        }
    }
    val realtimeColorLut = remember(controller) {
        controller?.let {
            RealtimeColorLut(
                VideoColorGradeEffects.buildPreviewCube(
                    VideoColorGrade(),
                    size = PreviewCubeSize,
                ),
            )
        }
    }
    val realtimeAnnotations = remember(controller) { controller?.let { VideoAnnotationEffect(emptyList()) } }
    var lastAppliedGeometry by remember(controller) { mutableStateOf<VideoGeometry?>(null) }
    LaunchedEffect(controller, realtimeColorLut, realtimeAnnotations, previewGeometry) {
        if (controller != null && realtimeColorLut != null && realtimeAnnotations != null) {
            // Installing Media3 effects rebuilds the preview chain. Apply the initial geometry
            // immediately, then conflate rapid straighten/crop drags through coroutine cancellation.
            if (lastAppliedGeometry != null && lastAppliedGeometry != previewGeometry) {
                delay(GeometryPreviewDebounceMillis)
            }
            controller.setVideoEffects(
                VideoColorGradeEffects.geometryEffects(previewGeometry) + realtimeColorLut + realtimeAnnotations,
            )
            lastAppliedGeometry = previewGeometry
        }
    }
    LaunchedEffect(controller, realtimeAnnotations, state.annotations) {
        realtimeAnnotations?.updateLayers(state.annotations)
        controller?.refreshVideoFrame()
    }
    val gradePreviewRequests = remember(controller) {
        Channel<VideoGradePreviewRequest>(Channel.CONFLATED)
    }
    DisposableEffect(gradePreviewRequests) {
        onDispose { gradePreviewRequests.close() }
    }
    // LUT generation is CPU-bound and cannot be cancelled mid-cube. A single conflated consumer
    // prevents rapid slider events from creating stale work while still rendering during a drag.
    LaunchedEffect(state.colorGrade, state.activeCustomLut, comparingOriginal) {
        gradePreviewRequests.trySend(
            VideoGradePreviewRequest(
                if (comparingOriginal) state.colorGrade.copy(bypass = true) else state.colorGrade,
                state.activeCustomLut,
            ),
        )
    }
    LaunchedEffect(controller, gradePreviewRequests, realtimeColorLut) {
        for (request in gradePreviewRequests) {
            if (controller == null || realtimeColorLut == null) continue
            runCatching {
                val cube = withContext(Dispatchers.Default) {
                    VideoColorGradeEffects.buildPreviewCube(
                        request.grade,
                        request.customLut,
                        size = PreviewCubeSize,
                    )
                }
                realtimeColorLut.updateCube(cube)
                controller.refreshVideoFrame()
            }
        }
    }
    val sourceUri = (controller?.state?.collectAsState()?.value).let { viewerState ->
        when (viewerState) {
            is VideoViewerState.Ready -> viewerState.uri
            is VideoViewerState.Loading -> viewerState.uri
            else -> null
        }
    }
    val filmstripFrames by produceState<List<android.graphics.Bitmap>?>(
        initialValue = null,
        sourceUri,
        state.durationMillis,
    ) {
        val uri = sourceUri
        if (uri == null) {
            value = null
            return@produceState
        }
        value = try {
            VideoFrameExtractor.extract(context, uri, state.durationMillis, FilmstripFrameCount)
                .takeIf { it.isNotEmpty() }
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            null
        }
    }
    DisposableEffect(filmstripFrames) {
        val loaded = filmstripFrames
        onDispose { loaded?.forEach { if (!it.isRecycled) it.recycle() } }
    }
    val requestBack: () -> Unit = { if (state.isDirty) showDiscardDialog = true else onBack() }
    BackHandler(enabled = state.isDirty && !showExportSheet) { showDiscardDialog = true }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.video_editor_discard_title)) },
            text = { Text(stringResource(R.string.video_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) {
                    Text(stringResource(R.string.video_editor_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.video_editor_keep_editing))
                }
            },
        )
    }
    if (showExportSheet) {
        VideoExportSheet(
            state = state,
            onDismiss = { showExportSheet = false },
            onOutputQualityChange = onOutputQualityChange,
            onDynamicRangeChange = onDynamicRangeChange,
            onSaveCopy = {
                showExportSheet = false
                onSaveCopy()
            },
        )
    }
    Scaffold(
        modifier = modifier.testTag("video-editor-screen").semantics { testTagsAsResourceId = true },
        topBar = {
            VideoEditorTopBar(
                foldInfo = foldInfo,
                isExporting = state.isExporting,
                onBack = requestBack,
                onExport = { showExportSheet = true },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            val leftInset = padding.calculateLeftPadding(LayoutDirection.Ltr)
            val topInset = padding.calculateTopPadding()
            val localFoldInfo = foldInfo?.takeIf(GalleryFoldInfo::isSeparating)?.let { fold ->
                val localLeft = (fold.left - leftInset).coerceIn(0.dp, maxWidth)
                val localRight = (fold.right - leftInset).coerceIn(localLeft, maxWidth)
                val localTop = (fold.top - topInset).coerceIn(0.dp, maxHeight)
                val localBottom = (fold.bottom - topInset).coerceIn(localTop, maxHeight)
                fold.copy(
                    left = localLeft,
                    right = localRight,
                    top = localTop,
                    bottom = localBottom,
                )
            }
            val preview: @Composable (Modifier) -> Unit = { previewModifier ->
                VideoPreview(
                    controller = controller,
                    annotationsActive = VideoEditorTool.fromIndex(selectedTab) == VideoEditorTool.Draw,
                    annotationTool = annotationTool,
                    state = state,
                    currentMillis = previewPositionMillis,
                    compareEnabled = VideoEditorTool.fromIndex(selectedTab) == VideoEditorTool.Color &&
                        state.colorGrade.hasChanges,
                    onCompareChange = { comparingOriginal = it },
                    cropActive = cropActive,
                    onGeometryChange = onGeometryChange,
                    onAddAnnotation = annotationActions.add,
                    onEraseAnnotations = { points ->
                        annotationActions.erase(points, previewPositionMillis)
                    },
                    modifier = previewModifier,
                )
            }
            val editingPanel: @Composable (Modifier) -> Unit = { panelModifier ->
                Surface(
                    modifier = panelModifier
                        .testTag(VideoEditorPanelTag)
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = 1f
                        },
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    VideoEditingPanel(
                        state = state,
                        cropEditing = cropEditing,
                        onCropEditingChange = { cropEditing = it },
                        frames = filmstripFrames,
                        currentMillis = previewPositionMillis,
                        onSeek = { position ->
                            previewPositionMillis = videoEditorDraftPosition(
                                position, state.trimStartMillis, state.trimEndMillis, state.durationMillis,
                            )
                            checkpoint(previewPositionMillis)
                            onSeek(previewPositionMillis)
                        },
                        onTrimChange = onTrimChange,
                        onSpeedChange = onSpeedChange,
                        onOriginalVolumeChange = onOriginalVolumeChange,
                        onChooseMusic = onChooseMusic,
                        onRemoveMusic = onRemoveMusic,
                        onMusicVolumeChange = onMusicVolumeChange,
                        onColorGradeChange = onColorGradeChange,
                        onGeometryChange = onGeometryChange,
                        onImportLut = onImportLut,
                        onMarkSlowMotionIn = onMarkSlowMotionIn,
                        onMarkSlowMotionOut = onMarkSlowMotionOut,
                        onSelectSlowMotionSegment = onSelectSlowMotionSegment,
                        onUpdateSlowMotionSegment = onUpdateSlowMotionSegment,
                        onDeleteSlowMotionSegment = onDeleteSlowMotionSegment,
                        onCancelExport = onCancelExport,
                        selectedTab = selectedTab,
                        onTabChange = { selectedTab = it },
                        annotationTool = annotationTool,
                        onAnnotationToolChange = { annotationTool = it },
                        annotationActions = annotationActions,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            var stackedPreviewFraction by rememberSaveable {
                mutableFloatStateOf(DefaultLandscapePreviewFraction)
            }
            var sidePreviewFraction by rememberSaveable {
                mutableFloatStateOf(DefaultExpandedPreviewFraction)
            }
            val density = LocalDensity.current
            val availableHeight = maxHeight
            when (videoEditorLayoutMode(maxWidth, maxHeight, localFoldInfo)) {
                VideoEditorLayoutMode.ExpandedSideBySide -> {
                    val resizableWidth = (maxWidth - ResizeHandleThickness).coerceAtLeast(1.dp)
                    val resizableWidthPx = with(density) { resizableWidth.toPx() }
                    Row(Modifier.fillMaxSize()) {
                        preview(
                            Modifier
                                .width(resizableWidth * sidePreviewFraction)
                                .fillMaxHeight(),
                        )
                        VideoPanelResizeHandle(
                            fraction = sidePreviewFraction,
                            orientation = Orientation.Horizontal,
                            valueRange = MinExpandedPreviewFraction..MaxExpandedPreviewFraction,
                            onDragDelta = { deltaPx ->
                                sidePreviewFraction = (
                                    sidePreviewFraction +
                                        deltaPx / resizableWidthPx.coerceAtLeast(1f)
                                    ).coerceIn(
                                    MinExpandedPreviewFraction,
                                    MaxExpandedPreviewFraction,
                                )
                            },
                            onFractionChange = { sidePreviewFraction = it },
                            modifier = Modifier.width(ResizeHandleThickness).fillMaxHeight(),
                        )
                        editingPanel(
                            Modifier
                                .width(resizableWidth * (1f - sidePreviewFraction))
                                .fillMaxHeight(),
                        )
                    }
                }

                VideoEditorLayoutMode.SeparatingHorizontalFold -> {
                    val fold = requireNotNull(localFoldInfo)
                    Column(Modifier.fillMaxSize()) {
                        preview(Modifier.fillMaxWidth().height(fold.top))
                        Spacer(Modifier.fillMaxWidth().height(fold.hingeHeight))
                        editingPanel(
                            Modifier
                                .fillMaxWidth()
                                .height((availableHeight - fold.bottom).coerceAtLeast(0.dp)),
                        )
                    }
                }

                VideoEditorLayoutMode.SeparatingVerticalFold -> {
                    val fold = requireNotNull(localFoldInfo)
                    val pane = widestVerticalFoldPane(maxWidth, fold)
                    Box(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .width(pane.width)
                                .fillMaxHeight()
                                .align(if (pane.useStart) Alignment.CenterStart else Alignment.CenterEnd),
                        ) {
                            val resizableHeight = (availableHeight - ResizeHandleThickness).coerceAtLeast(1.dp)
                            val resizableHeightPx = with(density) { resizableHeight.toPx() }
                            preview(
                                Modifier
                                    .fillMaxWidth()
                                    .height(resizableHeight * stackedPreviewFraction),
                            )
                            VideoPanelResizeHandle(
                                fraction = stackedPreviewFraction,
                                orientation = Orientation.Vertical,
                                valueRange = MinLandscapePreviewFraction..MaxLandscapePreviewFraction,
                                onDragDelta = { deltaPx ->
                                    stackedPreviewFraction = (
                                        stackedPreviewFraction +
                                            deltaPx / resizableHeightPx.coerceAtLeast(1f)
                                        ).coerceIn(
                                        MinLandscapePreviewFraction,
                                        MaxLandscapePreviewFraction,
                                    )
                                },
                                onFractionChange = { stackedPreviewFraction = it },
                                modifier = Modifier.fillMaxWidth().height(ResizeHandleThickness),
                            )
                            editingPanel(Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }

                VideoEditorLayoutMode.StackedResizable -> {
                    val resizableHeight = (maxHeight - ResizeHandleThickness).coerceAtLeast(1.dp)
                    val resizableHeightPx = with(density) { resizableHeight.toPx() }
                    Column(Modifier.fillMaxSize()) {
                        preview(
                            Modifier
                                .fillMaxWidth()
                                .height(resizableHeight * stackedPreviewFraction),
                        )
                        VideoPanelResizeHandle(
                            fraction = stackedPreviewFraction,
                            orientation = Orientation.Vertical,
                            valueRange = MinLandscapePreviewFraction..MaxLandscapePreviewFraction,
                            onDragDelta = { deltaPx ->
                                stackedPreviewFraction = (
                                    stackedPreviewFraction +
                                        deltaPx / resizableHeightPx.coerceAtLeast(1f)
                                    ).coerceIn(
                                    MinLandscapePreviewFraction,
                                    MaxLandscapePreviewFraction,
                                )
                            },
                            onFractionChange = { stackedPreviewFraction = it },
                            modifier = Modifier.fillMaxWidth().height(ResizeHandleThickness),
                        )
                        editingPanel(Modifier.fillMaxWidth().weight(1f))
                    }
                }

                VideoEditorLayoutMode.Stacked -> {
                    val previewWeight = if (maxWidth >= 600.dp) 1.1f else 0.55f
                    Column(Modifier.fillMaxSize()) {
                        preview(Modifier.fillMaxWidth().weight(previewWeight))
                        editingPanel(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}
}

@Composable
private fun VideoEditorTopBar(
    foldInfo: GalleryFoldInfo?,
    isExporting: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val topBar: @Composable (Modifier) -> Unit = { barModifier ->
        GalleryTopAppBar(
            title = stringResource(R.string.video_editor_title),
            modifier = barModifier,
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.video_editor_cancel),
            actions = {
                TextButton(onClick = onExport, enabled = !isExporting) {
                    Text(stringResource(R.string.video_editor_export))
                }
            },
        )
    }
    val verticalFold = foldInfo?.takeIf {
        it.isSeparating && it.orientation == GalleryFoldOrientation.Vertical
    }
    if (verticalFold == null) {
        topBar(Modifier.fillMaxWidth())
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pane = widestVerticalFoldPane(maxWidth, verticalFold)
            Box(Modifier.fillMaxWidth()) {
                topBar(
                    Modifier
                        .width(pane.width)
                        .align(if (pane.useStart) Alignment.CenterStart else Alignment.CenterEnd),
                )
            }
        }
    }
}

private enum class VideoEditorLayoutMode {
    ExpandedSideBySide,
    SeparatingHorizontalFold,
    SeparatingVerticalFold,
    StackedResizable,
    Stacked,
}

private data class VerticalFoldPane(
    val width: androidx.compose.ui.unit.Dp,
    val useStart: Boolean,
)

private val VideoAnnotationToolStateSaver = listSaver(
    save = { tool: VideoAnnotationToolState ->
        listOf(
            tool.shape.name,
            tool.appearance.name,
            tool.color.toArgb(),
            tool.strokeWidth,
            tool.opacity,
            tool.filled,
            tool.intensity,
            tool.eraser,
        )
    },
    restore = { values ->
        VideoAnnotationToolState(
            shape = com.librestatic.lightforge.core.editing.video.VideoAnnotationShape.valueOf(values[0] as String),
            appearance = com.librestatic.lightforge.core.editing.video.VideoAnnotationAppearance.valueOf(values[1] as String),
            color = Color(values[2] as Int),
            strokeWidth = values[3] as Float,
            opacity = values[4] as Float,
            filled = values[5] as Boolean,
            intensity = values[6] as Float,
            eraser = values[7] as Boolean,
        )
    },
)

private fun videoEditorLayoutMode(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    foldInfo: GalleryFoldInfo?,
): VideoEditorLayoutMode {
    if (foldInfo?.isSeparating == true) {
        return when (foldInfo.orientation) {
            GalleryFoldOrientation.Horizontal -> VideoEditorLayoutMode.SeparatingHorizontalFold
            GalleryFoldOrientation.Vertical -> VideoEditorLayoutMode.SeparatingVerticalFold
        }
    }
    if (
        galleryWindowClass(width) == GalleryWindowClass.Expanded &&
        width >= height * ExpandedSidePanelAspectRatio
    ) {
        return VideoEditorLayoutMode.ExpandedSideBySide
    }
    return if (width > height) {
        VideoEditorLayoutMode.StackedResizable
    } else {
        VideoEditorLayoutMode.Stacked
    }
}

private fun widestVerticalFoldPane(
    width: androidx.compose.ui.unit.Dp,
    foldInfo: GalleryFoldInfo,
): VerticalFoldPane {
    val leftWidth = foldInfo.left.coerceIn(0.dp, width)
    val rightWidth = (width - foldInfo.right.coerceIn(0.dp, width)).coerceAtLeast(0.dp)
    return if (leftWidth >= rightWidth) {
        VerticalFoldPane(width = leftWidth, useStart = true)
    } else {
        VerticalFoldPane(width = rightWidth, useStart = false)
    }
}

private fun editedTimelinePosition(
    sourcePositionMillis: Long,
    trimStartMillis: Long,
    baseSpeed: Float,
    slowSegments: List<SlowMotionSegment>,
): Long {
    val target = sourcePositionMillis.coerceAtLeast(trimStartMillis)
    var cursor = trimStartMillis
    var outputMillis = 0.0
    slowSegments.forEach { segment ->
        if (target <= cursor) return outputMillis.toLong()
        val normalEnd = minOf(target, segment.startMillis)
        outputMillis += (normalEnd - cursor).coerceAtLeast(0) / baseSpeed.toDouble()
        if (target <= segment.startMillis) return outputMillis.toLong()
        val slowEnd = minOf(target, segment.endMillis)
        outputMillis += (slowEnd - segment.startMillis).coerceAtLeast(0) / segment.speed.toDouble()
        if (target <= segment.endMillis) return outputMillis.toLong()
        cursor = segment.endMillis
    }
    outputMillis += (target - cursor).coerceAtLeast(0) / baseSpeed.toDouble()
    return outputMillis.toLong()
}

@Composable
private fun VideoPanelResizeHandle(
    fraction: Float,
    orientation: Orientation,
    valueRange: ClosedFloatingPointRange<Float>,
    onDragDelta: (Float) -> Unit,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.video_editor_resize_panels)
    val dragState = rememberDraggableState(onDelta = onDragDelta)
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .draggable(dragState, orientation)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    fraction,
                    valueRange,
                )
                setProgress { requested ->
                    onFractionChange(requested.coerceIn(valueRange))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (orientation == Orientation.Vertical) {
                Modifier.width(52.dp).height(4.dp)
            } else {
                Modifier.width(4.dp).height(52.dp)
            })
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    MaterialTheme.shapes.extraSmall,
                ),
        )
    }
}

@Composable
private fun VideoEditingPanel(
    state: VideoEditorContentState,
    cropEditing: Boolean,
    onCropEditingChange: (Boolean) -> Unit,
    frames: List<android.graphics.Bitmap>?,
    currentMillis: Long,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit,
    onGeometryChange: (VideoGeometry) -> Unit,
    onImportLut: () -> Unit,
    onMarkSlowMotionIn: (Long) -> Unit,
    onMarkSlowMotionOut: (Long) -> Unit,
    onSelectSlowMotionSegment: (String) -> Unit,
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit,
    onDeleteSlowMotionSegment: (String) -> Unit,
    onCancelExport: () -> Unit,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    annotationTool: VideoAnnotationToolState,
    onAnnotationToolChange: (VideoAnnotationToolState) -> Unit,
    annotationActions: VideoAnnotationActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        VideoFilmstripTimeline(
            frames = frames,
            durationMillis = state.durationMillis,
            trimStartMillis = state.trimStartMillis,
            trimEndMillis = state.trimEndMillis,
            positionMillis = currentMillis,
            onSeek = onSeek,
            onTrimChange = onTrimChange,
            slowMotionSegments = state.slowMotionSegments,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onUndo = annotationActions.undo,
            onRedo = annotationActions.redo,
        )
        VideoControls(
            state = state,
            thumbnailFrame = frames?.let { it.getOrNull(it.size / 2) },
            cropEditing = cropEditing,
            onCropEditingChange = onCropEditingChange,
            currentMillis = currentMillis,
            onSpeedChange = onSpeedChange,
            onOriginalVolumeChange = onOriginalVolumeChange,
            onChooseMusic = onChooseMusic,
            onRemoveMusic = onRemoveMusic,
            onMusicVolumeChange = onMusicVolumeChange,
            onColorGradeChange = onColorGradeChange,
            onGeometryChange = onGeometryChange,
            onImportLut = onImportLut,
            onMarkSlowMotionIn = onMarkSlowMotionIn,
            onMarkSlowMotionOut = onMarkSlowMotionOut,
            onSelectSlowMotionSegment = onSelectSlowMotionSegment,
            onUpdateSlowMotionSegment = onUpdateSlowMotionSegment,
            onDeleteSlowMotionSegment = onDeleteSlowMotionSegment,
            onCancelExport = onCancelExport,
            selectedTab = selectedTab,
            annotationTool = annotationTool,
            onAnnotationToolChange = onAnnotationToolChange,
            annotationActions = annotationActions,
            modifier = Modifier.weight(1f),
        )
        VideoEditorToolBar(
            selected = VideoEditorTool.fromIndex(selectedTab),
            onSelect = { onTabChange(it.index) },
        )
    }
}

@Composable
private fun VideoPreview(
    controller: VideoViewerController?,
    annotationsActive: Boolean,
    annotationTool: VideoAnnotationToolState,
    state: VideoEditorContentState,
    currentMillis: Long,
    compareEnabled: Boolean,
    onCompareChange: (Boolean) -> Unit,
    cropActive: Boolean,
    onGeometryChange: (VideoGeometry) -> Unit,
    onAddAnnotation: (VideoAnnotationLayer) -> Unit,
    onEraseAnnotations: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .testTag(VideoEditorPreviewTag)
            .semantics {
                isTraversalGroup = true
                traversalIndex = 0f
            }
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (controller == null) {
            Text(stringResource(R.string.video_editor_preview_unavailable), color = Color.White)
            return
        }
        val description = stringResource(R.string.video_editor_preview_description)
        val viewerState by controller.state.collectAsState()
        key(controller) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val videoAspectRatio = (viewerState as? VideoViewerState.Ready)?.aspectRatio
                val baseSurfaceModifier = if (videoAspectRatio != null && videoAspectRatio > 0f) {
                    val containerAspectRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                    if (videoAspectRatio >= containerAspectRatio) {
                        Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                    } else {
                        Modifier.fillMaxHeight().aspectRatio(
                            videoAspectRatio,
                            matchHeightConstraintsFirst = true,
                        )
                    }
                } else {
                    Modifier.fillMaxSize()
                }
                // While cropping, inset the frame so its edge handles are not under the system back
                // gesture zone; the overlay shares this modifier, so it stays aligned with the video.
                val surfaceModifier = if (cropActive) {
                    Modifier.padding(horizontal = 24.dp).then(baseSurfaceModifier)
                } else {
                    baseSurfaceModifier
                }
                AndroidView(
                    factory = { context -> SurfaceView(context).also(controller::attachSurface) },
                    modifier = surfaceModifier.semantics { contentDescription = description },
                )
                VideoAnnotationGestureLayer(
                    enabled = annotationsActive,
                    tool = annotationTool,
                    startMillis = state.trimStartMillis,
                    endMillis = state.trimEndMillis.takeIf { it > state.trimStartMillis }
                        ?: state.durationMillis,
                    selectedLayer = state.annotations.firstOrNull { it.id == state.selectedAnnotationId },
                    currentMillis = currentMillis,
                    onAdd = {
                        controller.pause()
                        onAddAnnotation(it)
                    },
                    onErase = onEraseAnnotations,
                    modifier = surfaceModifier,
                )
                if (cropActive) {
                    VideoCropOverlay(
                        geometry = state.geometry,
                        onGeometryChange = onGeometryChange,
                        modifier = surfaceModifier,
                    )
                }
            }
        }
        when (val current = viewerState) {
            is VideoViewerState.Ready -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = { if (current.isPlaying) controller.pause() else controller.play() },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        if (current.isPlaying) GalleryIcons.Pause else GalleryIcons.Play,
                        contentDescription = stringResource(
                            if (current.isPlaying) R.string.video_editor_pause else R.string.video_editor_play,
                        ),
                    )
                }
                FilledIconToggleButton(
                    checked = current.isLooping,
                    onCheckedChange = controller::setLooping,
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(
                        GalleryIcons.Repeat,
                        contentDescription = stringResource(
                            if (current.isLooping) {
                                R.string.video_editor_disable_loop
                            } else {
                                R.string.video_editor_enable_loop
                            },
                        ),
                    )
                }
            }
            is VideoViewerState.Failure -> Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    stringResource(R.string.video_editor_preview_failed),
                    Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
                )
            }
            VideoViewerState.Idle, is VideoViewerState.Loading -> GalleryLoadingIndicator()
            VideoViewerState.Released -> Unit
        }
        if ((viewerState as? VideoViewerState.Ready)?.usedSoftwareDecoder == true) {
            Text(
                text = stringResource(R.string.video_editor_software_decoder_warning),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(GallerySpacing.Md)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
            )
        }
        if (compareEnabled) {
            CompareOriginalButton(
                onCompareChange = onCompareChange,
                modifier = Modifier.align(Alignment.BottomStart).padding(GallerySpacing.Md),
            )
        }
        DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
    }
}

/** Shows the original while pressed: hold to compare, release to return to the graded video. */
@Composable
private fun CompareOriginalButton(onCompareChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onCompareChange(pressed) }
    DisposableEffect(Unit) { onDispose { onCompareChange(false) } }
    Surface(
        onClick = {},
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = 48.dp).testTag("video-editor-compare"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Row(
            Modifier.padding(horizontal = GallerySpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            Icon(GalleryIcons.Palette, contentDescription = null)
            Text(stringResource(R.string.video_editor_hold_compare), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val DefaultLandscapePreviewFraction = 0.45f
private const val DefaultExpandedPreviewFraction = 0.5f
private const val MinLandscapePreviewFraction = 0.2f
private const val MaxLandscapePreviewFraction = 0.7f
private const val MinExpandedPreviewFraction = 0.42f
private const val MaxExpandedPreviewFraction = 0.68f
private const val ExpandedSidePanelAspectRatio = 1.2f
private val ResizeHandleThickness = 48.dp
internal val WideColorControlsBreakpoint = 480.dp
internal val WideLogWheelsBreakpoint = 600.dp
private const val VideoEditorPreviewTag = "video-editor-preview"
private const val VideoEditorPanelTag = "video-editor-panel"
private const val VideoExportSheetTag = "video-export-sheet"
private const val VideoExportSaveCopyTag = "video-export-save-copy"
private const val VideoExportProgressCardTag = "video-export-progress-card"
private const val VideoExportProgressIndicatorTag = "video-export-progress-indicator"

@Composable
private fun VideoControls(
    state: VideoEditorContentState,
    thumbnailFrame: android.graphics.Bitmap?,
    cropEditing: Boolean,
    onCropEditingChange: (Boolean) -> Unit,
    currentMillis: Long,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onMusicVolumeChange: (Float) -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit,
    onGeometryChange: (VideoGeometry) -> Unit,
    onImportLut: () -> Unit,
    onMarkSlowMotionIn: (Long) -> Unit,
    onMarkSlowMotionOut: (Long) -> Unit,
    onSelectSlowMotionSegment: (String) -> Unit,
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit,
    onDeleteSlowMotionSegment: (String) -> Unit,
    onCancelExport: () -> Unit,
    selectedTab: Int,
    annotationTool: VideoAnnotationToolState,
    onAnnotationToolChange: (VideoAnnotationToolState) -> Unit,
    annotationActions: VideoAnnotationActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = GallerySpacing.Xs)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (VideoEditorTool.fromIndex(selectedTab)) {
                VideoEditorTool.Speed -> SpeedControls(
                    state = state,
                    currentMillis = currentMillis,
                    onSpeedChange = onSpeedChange,
                    onMarkIn = onMarkSlowMotionIn,
                    onMarkOut = onMarkSlowMotionOut,
                    onSelect = onSelectSlowMotionSegment,
                    onUpdate = onUpdateSlowMotionSegment,
                    onDelete = onDeleteSlowMotionSegment,
                    modifier = Modifier.fillMaxSize(),
                )
                VideoEditorTool.Audio -> AudioControls(state, onOriginalVolumeChange, Modifier.fillMaxSize())
                VideoEditorTool.Music -> MusicControls(
                    state = state,
                    onChooseMusic = onChooseMusic,
                    onRemoveMusic = onRemoveMusic,
                    onMusicVolumeChange = onMusicVolumeChange,
                    modifier = Modifier.fillMaxSize(),
                )
                VideoEditorTool.Color -> ColorControls(state, thumbnailFrame, onColorGradeChange, onImportLut, Modifier.fillMaxSize())
                VideoEditorTool.Transform -> TransformControls(state.geometry, cropEditing, onCropEditingChange, onGeometryChange, Modifier.fillMaxSize())
                VideoEditorTool.Draw -> VideoAnnotationControls(
                    state = state,
                    currentMillis = currentMillis,
                    tool = annotationTool,
                    onToolChange = onAnnotationToolChange,
                    onSelect = annotationActions.select,
                    onUpdate = annotationActions.update,
                    onDelete = annotationActions.delete,
                    onMove = annotationActions.move,
                    onClear = annotationActions.clear,
                    onUndo = annotationActions.undo,
                    onRedo = annotationActions.redo,
                    onAddKeyframe = annotationActions.addKeyframe,
                    onTrack = annotationActions.track,
                    onCancelTracking = annotationActions.cancelTracking,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        state.statusMessage?.let {
            Surface(
                modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(it, Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm))
            }
        }
        if (state.isExporting) {
            VideoExportProgressCard(
                progress = state.exportProgress,
                phase = state.exportPhase,
                onCancel = onCancelExport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Md),
            )
        }
    }
}

@Composable
internal fun VideoExportProgressCard(
    progress: Float?,
    phase: VideoExportPhase?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag(VideoExportProgressCardTag),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(GallerySpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.video_editor_export_in_progress),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(phase.exportLabel()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                progress?.let { value ->
                    Text(
                        stringResource(
                            R.string.video_editor_export_progress_percent,
                            (value.coerceIn(0f, 1f) * 100f).toInt(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = GallerySpacing.Xs)
                    .testTag(VideoExportProgressIndicatorTag),
            ) {
                progress?.let { value ->
                    GalleryProgressIndicator(
                        progress = { value.coerceIn(0f, 1f) },
                    )
                } ?: GalleryIndeterminateProgressIndicator()
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.video_editor_cancel_export))
            }
        }
    }
}

@StringRes
private fun VideoExportPhase?.exportLabel(): Int = when (this) {
    VideoExportPhase.GeneratingFrames -> R.string.video_editor_export_generating_frames
    VideoExportPhase.Rendering -> R.string.video_editor_export_rendering
    VideoExportPhase.Publishing -> R.string.video_editor_export_publishing
    VideoExportPhase.Verifying -> R.string.video_editor_export_verifying
    VideoExportPhase.Completed -> R.string.video_editor_copy_saved
    VideoExportPhase.Preparing, null -> R.string.video_editor_export_preparing
}

@Composable
internal fun LogWheelControls(
    @StringRes label: Int,
    wheel: LogWheel,
    onChange: (LogWheel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(GallerySpacing.Md),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            GradeSlider(stringResource(R.string.video_editor_level), wheel.level, -1f..1f) {
                onChange(wheel.copy(level = it))
            }
            GradeSlider(stringResource(R.string.video_editor_band_red), wheel.red, -1f..1f) {
                onChange(wheel.copy(red = it))
            }
            GradeSlider(stringResource(R.string.video_editor_band_green), wheel.green, -1f..1f) {
                onChange(wheel.copy(green = it))
            }
            GradeSlider(stringResource(R.string.video_editor_band_blue), wheel.blue, -1f..1f) {
                onChange(wheel.copy(blue = it))
            }
        }
    }
}

@Composable
internal fun GradeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    var liveValue by remember(label) { mutableFloatStateOf(value) }
    LaunchedEffect(value) { liveValue = value }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("%.2f".format(liveValue), style = GalleryMonoTypography)
        }
        Slider(
            value = liveValue,
            onValueChange = {
                liveValue = it
                onChange(it)
            },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoExportSheet(
    state: VideoEditorContentState,
    onDismiss: () -> Unit,
    onOutputQualityChange: (VideoOutputQuality) -> Unit,
    onDynamicRangeChange: (VideoDynamicRange) -> Unit,
    onSaveCopy: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully so the primary Save copy action is never hidden below a half-height sheet.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(VideoExportSheetTag),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = GallerySpacing.Lg)) {
            ExportControls(
                state = state,
                onQualitySelected = onOutputQualityChange,
                onDynamicRangeSelected = onDynamicRangeChange,
                modifier = Modifier.weight(1f, fill = false),
            )
            Button(
                onClick = onSaveCopy,
                enabled = !state.isExporting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GallerySpacing.Lg)
                    .heightIn(min = 48.dp)
                    .testTag(VideoExportSaveCopyTag),
            ) {
                Text(stringResource(R.string.video_editor_save_copy))
            }
        }
    }
}

@Composable
private fun ExportControls(
    state: VideoEditorContentState,
    onQualitySelected: (VideoOutputQuality) -> Unit,
    onDynamicRangeSelected: (VideoDynamicRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val qualityLabel: (VideoOutputQuality) -> Int = { quality ->
        if (quality == VideoOutputQuality.HevcMain10) R.string.video_editor_hevc_10bit else R.string.video_editor_h264
    }
    val rangeLabel: (VideoDynamicRange) -> Int = { dynamicRange ->
        when (dynamicRange) {
            VideoDynamicRange.SdrRec709 -> R.string.video_editor_dynamic_range_sdr
            VideoDynamicRange.HdrHlg -> R.string.video_editor_dynamic_range_hlg
            VideoDynamicRange.Hdr10Pq -> R.string.video_editor_dynamic_range_hdr10
        }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        Text(stringResource(R.string.video_editor_output_quality), style = MaterialTheme.typography.titleSmall)
        val qualities = VideoOutputQuality.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            qualities.forEachIndexed { index, quality ->
                SegmentedButton(
                    selected = state.outputQuality == quality,
                    onClick = { onQualitySelected(quality) },
                    enabled = quality != VideoOutputQuality.HevcMain10 || state.isHevcMain10Available,
                    shape = SegmentedButtonDefaults.itemShape(index, qualities.size),
                    label = { Text(stringResource(qualityLabel(quality)), maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        if (!state.isHevcMain10Available) Text(
            stringResource(R.string.video_editor_hevc_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.video_editor_dynamic_range), style = MaterialTheme.typography.titleSmall)
        val ranges = VideoDynamicRange.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ranges.forEachIndexed { index, dynamicRange ->
                SegmentedButton(
                    selected = state.dynamicRange == dynamicRange,
                    onClick = { onDynamicRangeSelected(dynamicRange) },
                    enabled = when (dynamicRange) {
                        VideoDynamicRange.SdrRec709 -> true
                        VideoDynamicRange.HdrHlg -> state.isHlgExportAvailable
                        VideoDynamicRange.Hdr10Pq -> state.isHdr10ExportAvailable
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ranges.size),
                    label = { Text(stringResource(rangeLabel(dynamicRange)), maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Column(Modifier.fillMaxWidth().padding(GallerySpacing.Md), verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                val trimEnd = state.trimEndMillis.takeIf { it > state.trimStartMillis } ?: state.durationMillis
                val length = ((trimEnd - state.trimStartMillis) / state.speed).toLong().coerceAtLeast(0)
                Text(
                    stringResource(
                        R.string.video_editor_export_summary,
                        formatVideoEditorShortTime(length),
                        stringResource(qualityLabel(state.outputQuality)),
                        stringResource(rangeLabel(state.dynamicRange)),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        if (state.dynamicRange == VideoDynamicRange.SdrRec709) {
                            R.string.video_editor_dynamic_range_sdr_description
                        } else {
                            R.string.video_editor_dynamic_range_hdr_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (!state.isHlgExportAvailable && !state.isHdr10ExportAvailable) Text(
            stringResource(R.string.video_editor_hdr_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransformControls(
    geometry: VideoGeometry,
    cropEditing: Boolean,
    onCropEditingChange: (Boolean) -> Unit,
    onChange: (VideoGeometry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fineRotation = ((geometry.rotationDegrees + 45f) % 90f + 90f) % 90f - 45f
    val quarterRotation = geometry.rotationDegrees - fineRotation
    val horizontalCropDescription = stringResource(R.string.video_editor_crop_horizontal)
    val verticalCropDescription = stringResource(R.string.video_editor_crop_vertical)
    val straightenDescription = stringResource(R.string.video_editor_straighten)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        // Quick actions first: they are what most edits need, and the sliders are fine-tuning.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            FilterChip(
                selected = cropEditing,
                onClick = { onCropEditingChange(!cropEditing) },
                label = { Text(stringResource(R.string.video_editor_crop)) },
                leadingIcon = { Icon(GalleryIcons.Crop, contentDescription = null) },
                modifier = EditorChipModifier,
            )
            OutlinedButton(onClick = {
                val rotated = normalizeVideoRotation(geometry.rotationDegrees + 90f)
                onChange(geometry.copy(rotationDegrees = rotated))
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(GalleryIcons.RotateRight, contentDescription = null)
                Text(stringResource(R.string.video_editor_rotate_90), modifier = Modifier.padding(start = GallerySpacing.Xs))
            }
            FilterChip(
                selected = geometry.flipHorizontal,
                onClick = { onChange(geometry.copy(flipHorizontal = !geometry.flipHorizontal)) },
                label = { Text(stringResource(R.string.video_editor_flip_horizontal)) },
                leadingIcon = { Icon(GalleryIcons.SwapHoriz, contentDescription = null) },
                modifier = EditorChipModifier,
            )
            TextButton(
                onClick = { onChange(VideoGeometry()) },
                enabled = geometry != VideoGeometry(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.video_editor_reset_transform)) }
        }
        if (cropEditing) Text(
            stringResource(R.string.video_editor_crop_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.video_editor_crop_horizontal), style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = geometry.left..geometry.right,
            onValueChange = {
                val left = it.start.coerceAtMost(it.endInclusive - 0.02f).coerceAtLeast(0f)
                val right = it.endInclusive.coerceAtLeast(left + 0.02f).coerceAtMost(1f)
                onChange(geometry.copy(left = left, right = right))
            },
            valueRange = 0f..1f,
            modifier = Modifier.semantics {
                contentDescription = horizontalCropDescription
            },
        )
        Text(stringResource(R.string.video_editor_crop_vertical), style = MaterialTheme.typography.labelLarge)
        RangeSlider(
            value = geometry.top..geometry.bottom,
            onValueChange = {
                val top = it.start.coerceAtMost(it.endInclusive - 0.02f).coerceAtLeast(0f)
                val bottom = it.endInclusive.coerceAtLeast(top + 0.02f).coerceAtMost(1f)
                onChange(geometry.copy(top = top, bottom = bottom))
            },
            valueRange = 0f..1f,
            modifier = Modifier.semantics {
                contentDescription = verticalCropDescription
            },
        )
        Text(stringResource(R.string.video_editor_straighten), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = fineRotation,
            onValueChange = {
                onChange(geometry.copy(rotationDegrees = normalizeVideoRotation(quarterRotation + it)))
            },
            valueRange = -45f..45f,
            modifier = Modifier.semantics {
                contentDescription = straightenDescription
            },
        )
    }
}

private fun normalizeVideoRotation(value: Float): Float {
    var result = value
    while (result > 315f) result -= 360f
    while (result < -45f) result += 360f
    return result
}

/** Slow-motion marker positions, rounded to the nearest second like the duration badge. */
internal fun formatMillis(value: Long): String {
    val seconds = ((value + 500) / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@StringRes
internal fun HueBand.labelResource(): Int = when (this) {
    HueBand.Red -> R.string.video_editor_band_red
    HueBand.Orange -> R.string.video_editor_band_orange
    HueBand.Yellow -> R.string.video_editor_band_yellow
    HueBand.Green -> R.string.video_editor_band_green
    HueBand.Cyan -> R.string.video_editor_band_cyan
    HueBand.Blue -> R.string.video_editor_band_blue
    HueBand.Purple -> R.string.video_editor_band_purple
    HueBand.Magenta -> R.string.video_editor_band_magenta
}

@StringRes
internal fun BuiltInLook.labelResource(): Int = when (this) {
    BuiltInLook.None -> R.string.video_editor_look_none
    BuiltInLook.Clean709 -> R.string.video_editor_look_clean
    BuiltInLook.WarmFilm -> R.string.video_editor_look_warm
    BuiltInLook.CoolFilm -> R.string.video_editor_look_cool
    BuiltInLook.Bleach -> R.string.video_editor_look_bleach
    BuiltInLook.TealOrange -> R.string.video_editor_look_teal_orange
    BuiltInLook.Monochrome -> R.string.video_editor_look_monochrome
}

@StringRes
fun LogInputProfile.labelResource(): Int = when (this) {
    LogInputProfile.Standard -> R.string.video_editor_profile_standard
    LogInputProfile.AppleLog -> R.string.video_editor_profile_apple_log
    LogInputProfile.SonySLog2 -> R.string.video_editor_profile_sony_slog2
    LogInputProfile.SonySLog3 -> R.string.video_editor_profile_sony_slog3
    LogInputProfile.CanonLog2 -> R.string.video_editor_profile_canon_log2
    LogInputProfile.CanonLog3 -> R.string.video_editor_profile_canon_log3
    LogInputProfile.PanasonicVLog -> R.string.video_editor_profile_panasonic_vlog
    LogInputProfile.DjiDLog -> R.string.video_editor_profile_dji_dlog
    LogInputProfile.FujifilmFLog -> R.string.video_editor_profile_fujifilm_flog
    LogInputProfile.FujifilmFLog2 -> R.string.video_editor_profile_fujifilm_flog2
    LogInputProfile.NikonNLog -> R.string.video_editor_profile_nikon_nlog
    LogInputProfile.BlackmagicFilmGen5 -> R.string.video_editor_profile_blackmagic_film_gen5
    LogInputProfile.ArriLogC3 -> R.string.video_editor_profile_arri_logc3
    LogInputProfile.ArriLogC4 -> R.string.video_editor_profile_arri_logc4
    LogInputProfile.RedLog3G10 -> R.string.video_editor_profile_red_log3g10
}
