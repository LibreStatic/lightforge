package com.ugallery.feature.videoeditor

import android.view.SurfaceView
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.ugallery.feature.viewer.VideoViewerController
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.designsystem.GalleryExpressiveChoiceGroup
import com.ugallery.core.designsystem.GalleryExpressiveButton
import com.ugallery.core.designsystem.GalleryLoadingIndicator
import com.ugallery.core.designsystem.GalleryProgressIndicator
import com.ugallery.core.designsystem.GalleryMonoTypography
import com.ugallery.core.designsystem.GalleryTopAppBar
import com.ugallery.feature.viewer.VideoViewerState
import com.ugallery.core.editing.video.BuiltInLook
import com.ugallery.core.editing.video.CubeLut
import com.ugallery.core.editing.video.CustomLutOption
import com.ugallery.core.editing.video.HueBand
import com.ugallery.core.editing.video.LogInputProfile
import com.ugallery.core.editing.video.LutReference
import com.ugallery.core.editing.video.RealtimeColorLut
import com.ugallery.core.editing.video.VideoColorGrade
import com.ugallery.core.editing.video.VideoColorGradeEffects
import com.ugallery.core.editing.video.VideoOutputQuality
import com.ugallery.core.editing.video.SlowMotionAudioMode
import com.ugallery.core.editing.video.SlowMotionSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PreviewCubeSize = 17

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
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val colorGrade: VideoColorGrade = VideoColorGrade(),
    val customLuts: List<CustomLutOption> = emptyList(),
    val activeCustomLut: CubeLut? = null,
    val outputQuality: VideoOutputQuality = VideoOutputQuality.H264Compatible,
    val logDetectionMessage: String? = null,
    val isHevcMain10Available: Boolean = false,
    val slowMotionSegments: List<SlowMotionSegment> = emptyList(),
    val selectedSlowMotionSegmentId: String? = null,
    val slowMotionMarkInMillis: Long? = null,
    val exportProgress: Float? = null,
)

@Composable
fun VideoEditorContent(
    state: VideoEditorContentState,
    controller: VideoViewerController?,
    onBack: () -> Unit,
    onSaveCopy: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit = {},
    onOutputQualityChange: (VideoOutputQuality) -> Unit = {},
    onImportLut: () -> Unit = {},
    onMarkSlowMotionIn: (Long) -> Unit = {},
    onMarkSlowMotionOut: (Long) -> Unit = {},
    onSelectSlowMotionSegment: (String) -> Unit = {},
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit = {},
    onDeleteSlowMotionSegment: (String) -> Unit = {},
    onCancelExport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var previewPositionMillis by remember(controller) { mutableLongStateOf(state.currentMillis) }
    LaunchedEffect(controller) {
        controller?.setLooping(true)
    }
    LaunchedEffect(controller) {
        while (true) {
            previewPositionMillis = controller?.currentPositionMillis() ?: state.currentMillis
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
    LaunchedEffect(controller, realtimeColorLut) {
        if (controller != null && realtimeColorLut != null) {
            controller.setVideoEffects(listOf(realtimeColorLut))
        }
    }
    val gradePreviewRequests = remember(controller) {
        Channel<VideoGradePreviewRequest>(Channel.CONFLATED)
    }
    DisposableEffect(gradePreviewRequests) {
        onDispose { gradePreviewRequests.close() }
    }
    // LUT generation is CPU-bound and cannot be cancelled mid-cube. A single conflated consumer
    // prevents rapid slider events from creating stale work while still rendering during a drag.
    LaunchedEffect(state.colorGrade, state.activeCustomLut) {
        gradePreviewRequests.trySend(
            VideoGradePreviewRequest(state.colorGrade, state.activeCustomLut),
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
    Scaffold(modifier = modifier, topBar = {
        GalleryTopAppBar(
            title = stringResource(R.string.video_editor_title),
            onBack = onBack,
            navigationContentDescription = stringResource(R.string.video_editor_cancel),
            actions = {
                TextButton(onClick = onSaveCopy, enabled = !state.isExporting) {
                    Text(stringResource(R.string.video_editor_save_copy))
                }
            },
        )
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val previewWeight = if (maxWidth >= 600.dp) 1.7f else 1.25f
            val landscape = maxWidth > maxHeight
            val availableHeight = maxHeight
            var landscapePreviewFraction by rememberSaveable {
                mutableFloatStateOf(DefaultLandscapePreviewFraction)
            }
            Column(Modifier.fillMaxSize()) {
                if (landscape) {
                    val resizeHandleHeight = 48.dp
                    val resizableHeight = (availableHeight - resizeHandleHeight).coerceAtLeast(1.dp)
                    val resizableHeightPx = with(LocalDensity.current) { resizableHeight.toPx() }
                    VideoPreview(
                        controller,
                        Modifier
                            .fillMaxWidth()
                            .height(resizableHeight * landscapePreviewFraction),
                    )
                    VideoPanelResizeHandle(
                        fraction = landscapePreviewFraction,
                        onDragDelta = { deltaPx ->
                            landscapePreviewFraction = (
                                landscapePreviewFraction +
                                    deltaPx / resizableHeightPx.coerceAtLeast(1f)
                                ).coerceIn(
                                MinLandscapePreviewFraction,
                                MaxLandscapePreviewFraction,
                            )
                        },
                        onFractionChange = { landscapePreviewFraction = it },
                        modifier = Modifier.height(resizeHandleHeight),
                    )
                    VideoEditingPanel(
                        state = state,
                        currentMillis = previewPositionMillis,
                        onSeek = { position ->
                            previewPositionMillis = position
                            onSeek(position)
                        },
                        onTrimChange = onTrimChange,
                        onSpeedChange = onSpeedChange,
                        onOriginalVolumeChange = onOriginalVolumeChange,
                        onChooseMusic = onChooseMusic,
                        onRemoveMusic = onRemoveMusic,
                        onColorGradeChange = onColorGradeChange,
                        onOutputQualityChange = onOutputQualityChange,
                        onImportLut = onImportLut,
                        onMarkSlowMotionIn = onMarkSlowMotionIn,
                        onMarkSlowMotionOut = onMarkSlowMotionOut,
                        onSelectSlowMotionSegment = onSelectSlowMotionSegment,
                        onUpdateSlowMotionSegment = onUpdateSlowMotionSegment,
                        onDeleteSlowMotionSegment = onDeleteSlowMotionSegment,
                        onCancelExport = onCancelExport,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    VideoPreview(
                        controller,
                        Modifier.weight(previewWeight).fillMaxWidth(),
                    )
                    VideoEditingPanel(
                        state = state,
                        currentMillis = previewPositionMillis,
                        onSeek = { position ->
                            previewPositionMillis = position
                            onSeek(position)
                        },
                        onTrimChange = onTrimChange,
                        onSpeedChange = onSpeedChange,
                        onOriginalVolumeChange = onOriginalVolumeChange,
                        onChooseMusic = onChooseMusic,
                        onRemoveMusic = onRemoveMusic,
                        onColorGradeChange = onColorGradeChange,
                        onOutputQualityChange = onOutputQualityChange,
                        onImportLut = onImportLut,
                        onMarkSlowMotionIn = onMarkSlowMotionIn,
                        onMarkSlowMotionOut = onMarkSlowMotionOut,
                        onSelectSlowMotionSegment = onSelectSlowMotionSegment,
                        onUpdateSlowMotionSegment = onUpdateSlowMotionSegment,
                        onDeleteSlowMotionSegment = onDeleteSlowMotionSegment,
                        onCancelExport = onCancelExport,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPanelResizeHandle(
    fraction: Float,
    onDragDelta: (Float) -> Unit,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.video_editor_resize_panels)
    val dragState = rememberDraggableState(onDelta = onDragDelta)
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .draggable(dragState, Orientation.Vertical)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    fraction,
                    MinLandscapePreviewFraction..MaxLandscapePreviewFraction,
                )
                setProgress { requested ->
                    onFractionChange(
                        requested.coerceIn(
                            MinLandscapePreviewFraction,
                            MaxLandscapePreviewFraction,
                        ),
                    )
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(52.dp)
                .height(4.dp)
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
    currentMillis: Long,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit,
    onOutputQualityChange: (VideoOutputQuality) -> Unit,
    onImportLut: () -> Unit,
    onMarkSlowMotionIn: (Long) -> Unit,
    onMarkSlowMotionOut: (Long) -> Unit,
    onSelectSlowMotionSegment: (String) -> Unit,
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit,
    onDeleteSlowMotionSegment: (String) -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        VideoTimeline(
                    state = state,
                    currentMillis = currentMillis,
                    onSeek = onSeek,
                    onTrimChange = onTrimChange,
                )
        VideoControls(
            state, currentMillis, onSpeedChange, onOriginalVolumeChange, onChooseMusic, onRemoveMusic,
            onColorGradeChange, onOutputQualityChange, onImportLut, onMarkSlowMotionIn,
            onMarkSlowMotionOut, onSelectSlowMotionSegment, onUpdateSlowMotionSegment,
            onDeleteSlowMotionSegment, onCancelExport, Modifier.weight(1f),
        )
    }
}

@Composable
private fun VideoPreview(controller: VideoViewerController?, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (controller == null) {
            Text(stringResource(R.string.video_editor_preview_unavailable), color = Color.White)
            return
        }
        val description = stringResource(R.string.video_editor_preview_description)
        val viewerState by controller.state.collectAsState()
        key(controller) {
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val videoAspectRatio = (viewerState as? VideoViewerState.Ready)?.aspectRatio
                val surfaceModifier = if (videoAspectRatio != null && videoAspectRatio > 0f) {
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
                AndroidView(
                    factory = { context -> SurfaceView(context).also(controller::attachSurface) },
                    modifier = surfaceModifier.semantics { contentDescription = description },
                )
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
            is VideoViewerState.Failure -> Text(
                stringResource(R.string.video_editor_preview_failed),
                color = MaterialTheme.colorScheme.error,
            )
            VideoViewerState.Idle, is VideoViewerState.Loading -> GalleryLoadingIndicator()
            VideoViewerState.Released -> Unit
        }
        DisposableEffect(controller) { onDispose { controller.attachSurface(null) } }
    }
}

private const val DefaultLandscapePreviewFraction = 0.45f
private const val MinLandscapePreviewFraction = 0.2f
private const val MaxLandscapePreviewFraction = 0.7f

@Composable
private fun VideoTimeline(
    state: VideoEditorContentState,
    currentMillis: Long,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
) {
    val duration = state.durationMillis.coerceAtLeast(1)
    val position = currentMillis.coerceIn(0, duration)
    Column(Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMillis(position), style = GalleryMonoTypography)
            Text(formatMillis(duration), style = GalleryMonoTypography)
        }
        Slider(
            value = position.toFloat(),
            onValueChange = { onSeek(it.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "${formatMillis(position)} / ${formatMillis(duration)}"
            },
        )
        val trimStart = state.trimStartMillis.coerceIn(0, duration)
        val trimEnd = (state.trimEndMillis.takeIf { it > 0 } ?: duration).coerceIn(trimStart + 1, duration)
        val trimDescription = stringResource(
            R.string.video_editor_trim_description,
            formatMillis(trimStart),
            formatMillis(trimEnd),
        )
        RangeSlider(
            value = trimStart.toFloat()..trimEnd.toFloat(),
            onValueChange = { range -> onTrimChange(range.start.toLong(), range.endInclusive.toLong()) },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = trimDescription
            },
        )
    }
}

@Composable
private fun VideoControls(
    state: VideoEditorContentState,
    currentMillis: Long,
    onSpeedChange: (Float) -> Unit,
    onOriginalVolumeChange: (Float) -> Unit,
    onChooseMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onColorGradeChange: (VideoColorGrade) -> Unit,
    onOutputQualityChange: (VideoOutputQuality) -> Unit,
    onImportLut: () -> Unit,
    onMarkSlowMotionIn: (Long) -> Unit,
    onMarkSlowMotionOut: (Long) -> Unit,
    onSelectSlowMotionSegment: (String) -> Unit,
    onUpdateSlowMotionSegment: (SlowMotionSegment) -> Unit,
    onDeleteSlowMotionSegment: (String) -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth().padding(bottom = GallerySpacing.Sm)) {
        GalleryExpressiveChoiceGroup(
            labels = listOf(
                stringResource(R.string.video_editor_speed),
                stringResource(R.string.video_editor_audio),
                stringResource(R.string.video_editor_music),
                stringResource(R.string.video_editor_color),
                stringResource(R.string.video_editor_export),
            ),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            icons = listOf(
                GalleryIcons.Speed,
                GalleryIcons.Volume,
                GalleryIcons.Music,
                GalleryIcons.Palette,
                GalleryIcons.Edit,
            ),
            modifier = Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
        )
        when (selectedTab) {
            0 -> SlowMotionControls(
                state = state,
                currentMillis = currentMillis,
                onSpeedChange = onSpeedChange,
                onMarkIn = onMarkSlowMotionIn,
                onMarkOut = onMarkSlowMotionOut,
                onSelect = onSelectSlowMotionSegment,
                onUpdate = onUpdateSlowMotionSegment,
                onDelete = onDeleteSlowMotionSegment,
            )
            1 -> Column(Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg)) {
                Text(stringResource(R.string.video_editor_original_audio))
                val audioDescription = stringResource(R.string.video_editor_audio_description)
                Slider(
                    value = state.originalAudioVolume,
                    onValueChange = onOriginalVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = audioDescription },
                )
            }
            2 -> Column(
                Modifier.fillMaxWidth().padding(GallerySpacing.Md),
                verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
            ) {
                if (state.selectedMusicName == null) {
                    OutlinedButton(onClick = onChooseMusic) {
                        Icon(GalleryIcons.Music, contentDescription = null)
                        Text(stringResource(R.string.video_editor_choose_music))
                    }
                } else {
                    Text(state.selectedMusicName)
                    TextButton(onClick = onRemoveMusic) { Text(stringResource(R.string.video_editor_remove_music)) }
                }
            }
            3 -> ColorControls(state, onColorGradeChange, onImportLut, Modifier.weight(1f))
            4 -> ExportControls(state.outputQuality, state.isHevcMain10Available, onOutputQualityChange)
        }
        state.statusMessage?.let {
            Text(it, Modifier.padding(horizontal = GallerySpacing.Lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isExporting && state.exportProgress != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg)) {
                GalleryProgressIndicator(
                    progress = { state.exportProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onCancelExport) { Text(stringResource(R.string.video_editor_cancel_export)) }
            }
        }
    }
}

@Composable
private fun SlowMotionControls(
    state: VideoEditorContentState,
    currentMillis: Long,
    onSpeedChange: (Float) -> Unit,
    onMarkIn: (Long) -> Unit,
    onMarkOut: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onUpdate: (SlowMotionSegment) -> Unit,
    onDelete: (String) -> Unit,
) {
    val selected = state.slowMotionSegments.firstOrNull { it.id == state.selectedSlowMotionSegmentId }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        Text(stringResource(R.string.video_editor_base_speed), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            listOf(1f to R.string.video_editor_speed_normal, 2f to R.string.video_editor_speed_fast).forEach { (speed, label) ->
                FilterChip(state.speed == speed, { onSpeedChange(speed) }, label = { Text(stringResource(label)) })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            GalleryExpressiveButton(onClick = { onMarkIn(currentMillis) }) {
                Text(stringResource(R.string.video_editor_mark_in))
            }
            GalleryExpressiveButton(
                onClick = { onMarkOut(currentMillis) },
                enabled = state.slowMotionMarkInMillis != null,
            ) { Text(stringResource(R.string.video_editor_mark_out)) }
        }
        state.slowMotionMarkInMillis?.let {
            Text(stringResource(R.string.video_editor_marked_in_at, formatMillis(it)))
        }
        if (state.slowMotionSegments.isNotEmpty()) {
            Text(stringResource(R.string.video_editor_slow_segments), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
            ) {
                state.slowMotionSegments.forEachIndexed { index, segment ->
                    FilterChip(
                        selected = segment.id == state.selectedSlowMotionSegmentId,
                        onClick = { onSelect(segment.id) },
                        label = { Text("${index + 1}: ${formatMillis(segment.startMillis)}–${formatMillis(segment.endMillis)}") },
                    )
                }
            }
        }
        selected?.let { segment ->
            Text(stringResource(R.string.video_editor_segment_speed), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                listOf(0.5f, 0.25f, 0.125f).forEach { speed ->
                    FilterChip(
                        selected = segment.speed == speed,
                        onClick = { onUpdate(segment.copy(speed = speed)) },
                        label = { Text("${speed}×") },
                    )
                }
            }
            Text(stringResource(R.string.video_editor_segment_audio), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
            ) {
                listOf(
                    SlowMotionAudioMode.PreservePitch to R.string.video_editor_audio_preserve_pitch,
                    SlowMotionAudioMode.Muted to R.string.video_editor_audio_muted,
                    SlowMotionAudioMode.Varispeed to R.string.video_editor_audio_varispeed,
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = segment.audioMode == mode,
                        onClick = { onUpdate(segment.copy(audioMode = mode)) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            TextButton(onClick = { onDelete(segment.id) }) {
                Text(stringResource(R.string.video_editor_delete_segment))
            }
        }
    }
}

@Composable
private fun ColorControls(
    state: VideoEditorContentState,
    onChange: (VideoColorGrade) -> Unit,
    onImportLut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val grade = state.colorGrade
    var palette by remember { mutableIntStateOf(0) }
    var selectedBand by remember { mutableIntStateOf(0) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        state.logDetectionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilterChip(
                selected = grade.bypass,
                onClick = { onChange(grade.copy(bypass = !grade.bypass)) },
                label = { Text(stringResource(R.string.video_editor_bypass_grade)) },
            )
            TextButton(onClick = {
                onChange(VideoColorGrade(
                    inputProfile = grade.inputProfile,
                    profileWasAutoDetected = grade.profileWasAutoDetected,
                ))
            }) { Text(stringResource(R.string.video_editor_reset_grade)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                R.string.video_editor_camera to 0,
                R.string.video_editor_primaries to 1,
                R.string.video_editor_log_wheels to 2,
                R.string.video_editor_color_bands to 3,
                R.string.video_editor_luts to 4,
            ).forEach { (label, index) ->
                FilterChip(selected = palette == index, onClick = { palette = index }, label = { Text(stringResource(label)) })
            }
        }
        when (palette) {
            0 -> {
                Text(stringResource(R.string.video_editor_input_profile), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogInputProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = grade.inputProfile == profile,
                            onClick = { onChange(grade.copy(inputProfile = profile, profileWasAutoDetected = false)) },
                            label = { Text(profile.displayName) },
                        )
                    }
                }
            }
            1 -> {
                GradeSlider(stringResource(R.string.video_editor_exposure), grade.exposureEv, -5f..5f) { onChange(grade.copy(exposureEv = it)) }
                GradeSlider(stringResource(R.string.video_editor_temperature), grade.temperature, -1f..1f) { onChange(grade.copy(temperature = it)) }
                GradeSlider(stringResource(R.string.video_editor_tint), grade.tint, -1f..1f) { onChange(grade.copy(tint = it)) }
                GradeSlider(stringResource(R.string.video_editor_contrast), grade.contrast, -1f..1f) { onChange(grade.copy(contrast = it)) }
                GradeSlider(stringResource(R.string.video_editor_pivot), grade.pivot, 0.05f..0.95f) { onChange(grade.copy(pivot = it)) }
                GradeSlider(stringResource(R.string.video_editor_saturation), grade.saturation, -1f..1f) { onChange(grade.copy(saturation = it)) }
            }
            2 -> {
                listOf(
                    R.string.video_editor_shadows to grade.logWheels.shadows,
                    R.string.video_editor_midtones to grade.logWheels.midtones,
                    R.string.video_editor_highlights to grade.logWheels.highlights,
                ).forEachIndexed { index, (label, wheel) ->
                    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                    listOf(
                        R.string.video_editor_level to wheel.level,
                        R.string.video_editor_band_red to wheel.red,
                        R.string.video_editor_band_green to wheel.green,
                        R.string.video_editor_band_blue to wheel.blue,
                    ).forEachIndexed { channel, (channelLabel, channelValue) ->
                        GradeSlider(stringResource(channelLabel), channelValue, -1f..1f) { value ->
                            val changedWheel = when (channel) {
                                0 -> wheel.copy(level = value)
                                1 -> wheel.copy(red = value)
                                2 -> wheel.copy(green = value)
                                else -> wheel.copy(blue = value)
                            }
                            val updated = when (index) {
                                0 -> grade.logWheels.copy(shadows = changedWheel)
                                1 -> grade.logWheels.copy(midtones = changedWheel)
                                else -> grade.logWheels.copy(highlights = changedWheel)
                            }
                            onChange(grade.copy(logWheels = updated))
                        }
                    }
                }
            }
            3 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HueBand.entries.forEachIndexed { index, band ->
                        FilterChip(selected = selectedBand == index, onClick = { selectedBand = index }, label = { Text(stringResource(band.labelResource())) })
                    }
                }
                val adjustment = grade.hueBands.first { it.band == HueBand.entries[selectedBand] }
                GradeSlider(stringResource(R.string.video_editor_hue), adjustment.hueShiftDegrees, -45f..45f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(hueShiftDegrees = value) else it }))
                }
                GradeSlider(stringResource(R.string.video_editor_saturation), adjustment.saturation, -1f..1f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(saturation = value) else it }))
                }
                GradeSlider(stringResource(R.string.video_editor_luminance), adjustment.luminance, -1f..1f) { value ->
                    onChange(grade.copy(hueBands = grade.hueBands.map { if (it.band == adjustment.band) it.copy(luminance = value) else it }))
                }
            }
            4 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuiltInLook.entries.forEach { look ->
                        FilterChip(
                            selected = grade.lut.builtIn == look && grade.lut.customId == null,
                            onClick = { onChange(grade.copy(lut = LutReference(builtIn = look, intensity = grade.lut.intensity))) },
                            label = { Text(stringResource(look.labelResource())) },
                        )
                    }
                    state.customLuts.forEach { lut ->
                        FilterChip(
                            selected = grade.lut.customId == lut.id,
                            onClick = { onChange(grade.copy(lut = LutReference(customId = lut.id, intensity = grade.lut.intensity))) },
                            label = { Text(lut.displayName) },
                        )
                    }
                }
                GradeSlider(stringResource(R.string.video_editor_lut_intensity), grade.lut.intensity, 0f..1f) {
                    onChange(grade.copy(lut = grade.lut.copy(intensity = it)))
                }
                OutlinedButton(onClick = onImportLut) { Text(stringResource(R.string.video_editor_import_lut)) }
            }
        }
    }
}

@Composable
private fun GradeSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
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

@Composable
private fun ExportControls(selected: VideoOutputQuality, isHevcMain10Available: Boolean, onSelected: (VideoOutputQuality) -> Unit) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.video_editor_output_quality), style = MaterialTheme.typography.titleSmall)
        VideoOutputQuality.entries.forEach { quality ->
            FilterChip(
                selected = selected == quality,
                onClick = { onSelected(quality) },
                enabled = quality != VideoOutputQuality.HevcMain10 || isHevcMain10Available,
                label = { Text(stringResource(if (quality == VideoOutputQuality.HevcMain10) R.string.video_editor_hevc_10bit else R.string.video_editor_h264)) },
            )
        }
        if (!isHevcMain10Available) Text(
            stringResource(R.string.video_editor_hevc_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatMillis(value: Long): String {
    val seconds = (value / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@StringRes
private fun HueBand.labelResource(): Int = when (this) {
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
private fun BuiltInLook.labelResource(): Int = when (this) {
    BuiltInLook.None -> R.string.video_editor_look_none
    BuiltInLook.Clean709 -> R.string.video_editor_look_clean
    BuiltInLook.WarmFilm -> R.string.video_editor_look_warm
    BuiltInLook.CoolFilm -> R.string.video_editor_look_cool
    BuiltInLook.Bleach -> R.string.video_editor_look_bleach
    BuiltInLook.TealOrange -> R.string.video_editor_look_teal_orange
    BuiltInLook.Monochrome -> R.string.video_editor_look_monochrome
}
