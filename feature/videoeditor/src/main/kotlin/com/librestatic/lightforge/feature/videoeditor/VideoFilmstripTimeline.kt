package com.librestatic.lightforge.feature.videoeditor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shortest trim the timeline lets the user create, so a handle can never cross the other. */
private const val MinTrimSpanMillis = 200L

private val StripHeight = 64.dp
private val HandleWidth = 16.dp
private val HandleTouchSlop = 32.dp

private enum class TimelineTarget { Seek, TrimStart, TrimEnd }

/**
 * One visual timeline replacing the separate position and trim sliders: frame thumbnails, a
 * playhead, and draggable trim handles. Touching the strip seeks; dragging near either end moves
 * that trim handle. Every gesture also has a semantics action so TalkBack users are not left out.
 *
 * [frames] are evenly spaced samples of the source video, or null while they load / when the
 * source cannot be sampled (the strip then shows a plain tonal track).
 */
@Composable
internal fun VideoFilmstripTimeline(
    frames: List<Bitmap>?,
    durationMillis: Long,
    trimStartMillis: Long,
    trimEndMillis: Long,
    positionMillis: Long,
    onSeek: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMillis.coerceAtLeast(1)
    val trimStart = trimStartMillis.coerceIn(0, (duration - 1).coerceAtLeast(0))
    val trimEnd = (trimEndMillis.takeIf { it > trimStart } ?: duration).coerceIn(trimStart + 1, duration)
    val position = positionMillis.coerceIn(trimStart, trimEnd)

    val positionDescription = stringResource(
        R.string.video_editor_position_description,
        formatVideoEditorDraftTime(position),
        formatVideoEditorDraftTime(trimEnd),
    )
    val trimDescription = stringResource(
        R.string.video_editor_trim_description,
        formatVideoEditorDraftTime(trimStart),
        formatVideoEditorDraftTime(trimEnd),
    )
    val startDescription = stringResource(
        R.string.video_editor_trim_start_description,
        formatVideoEditorDraftTime(trimStart),
    )
    val endDescription = stringResource(
        R.string.video_editor_trim_end_description,
        formatVideoEditorDraftTime(trimEnd),
    )

    var stripWidthPx by remember { mutableIntStateOf(0) }
    val latest = rememberUpdatedState(TimelineSnapshot(duration, trimStart, trimEnd, onSeek, onTrimChange))

    Column(modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.video_editor_timeline_time,
                    formatVideoEditorShortTime(position),
                    formatVideoEditorShortTime(duration),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("video-editor-position-value"),
            )
            Box(Modifier.weight(1f))
            Text(
                stringResource(
                    R.string.video_editor_trim_range,
                    formatVideoEditorShortTime(trimStart),
                    formatVideoEditorShortTime(trimEnd),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("video-editor-trim-value"),
            )
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag("video-editor-undo")) {
                Icon(GalleryIcons.Undo, stringResource(R.string.video_editor_undo_edit))
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag("video-editor-redo")) {
                Icon(GalleryIcons.Redo, stringResource(R.string.video_editor_redo_edit))
            }
        }
        Box(
            Modifier
                .padding(top = GallerySpacing.Sm)
                .fillMaxWidth()
                .height(StripHeight)
                // The start handle rests on the screen edge; keep it out of the back gesture.
                .systemGestureExclusion()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .onSizeChanged { stripWidthPx = it.width }
                .testTag("video-editor-timeline")
                .pointerInput(Unit) {
                    val touchSlopPx = HandleTouchSlop.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val snapshot = latest.value
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val startX = snapshot.trimStart.toFloat() / snapshot.duration * width
                        val endX = snapshot.trimEnd.toFloat() / snapshot.duration * width
                        val distanceToStart = abs(down.position.x - startX)
                        val distanceToEnd = abs(down.position.x - endX)
                        val target = when {
                            distanceToStart <= touchSlopPx && distanceToStart <= distanceToEnd -> TimelineTarget.TrimStart
                            distanceToEnd <= touchSlopPx -> TimelineTarget.TrimEnd
                            else -> TimelineTarget.Seek
                        }
                        fun apply(x: Float) {
                            val current = latest.value
                            val millis = ((x / width) * current.duration).toLong().coerceIn(0, current.duration)
                            when (target) {
                                TimelineTarget.Seek -> current.onSeek(millis.coerceIn(current.trimStart, current.trimEnd))
                                TimelineTarget.TrimStart -> current.onTrimChange(
                                    millis.coerceIn(0, current.trimEnd - MinTrimSpanMillis.coerceAtMost(current.trimEnd)),
                                    current.trimEnd,
                                )
                                TimelineTarget.TrimEnd -> current.onTrimChange(
                                    current.trimStart,
                                    millis.coerceIn(
                                        (current.trimStart + MinTrimSpanMillis).coerceAtMost(current.duration),
                                        current.duration,
                                    ),
                                )
                            }
                        }
                        apply(down.position.x)
                        down.consume()
                        drag(down.id) { change ->
                            apply(change.position.x)
                            change.consume()
                        }
                    }
                },
        ) {
            FilmstripFrames(frames, Modifier.fillMaxSize())
            TimelineOverlay(
                duration = duration,
                trimStart = trimStart,
                trimEnd = trimEnd,
                position = position,
                modifier = Modifier.fillMaxSize(),
            )
            // Semantics-only nodes: the whole strip is the playhead slider, and each handle is a slider.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("video-editor-position")
                    .semantics {
                        contentDescription = positionDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            position.toFloat(),
                            trimStart.toFloat()..trimEnd.toFloat(),
                        )
                        setProgress { value ->
                            onSeek(value.toLong())
                            true
                        }
                    },
            )
            HandleSemantics(
                stripWidthPx = stripWidthPx,
                fraction = trimStart.toFloat() / duration,
                testTag = "video-editor-trim-start",
                description = startDescription,
                value = trimStart.toFloat(),
                range = 0f..(trimEnd - MinTrimSpanMillis).coerceAtLeast(0).toFloat(),
                onChange = { onTrimChange(it.toLong(), trimEnd) },
            )
            HandleSemantics(
                stripWidthPx = stripWidthPx,
                fraction = trimEnd.toFloat() / duration,
                testTag = "video-editor-trim-end",
                description = endDescription,
                value = trimEnd.toFloat(),
                range = (trimStart + MinTrimSpanMillis).coerceAtMost(duration).toFloat()..duration.toFloat(),
                onChange = { onTrimChange(trimStart, it.toLong()) },
            )
            Box(Modifier.fillMaxSize().testTag("video-editor-trim").semantics { contentDescription = trimDescription })
        }
    }
}

private data class TimelineSnapshot(
    val duration: Long,
    val trimStart: Long,
    val trimEnd: Long,
    val onSeek: (Long) -> Unit,
    val onTrimChange: (Long, Long) -> Unit,
)

@Composable
private fun FilmstripFrames(frames: List<Bitmap>?, modifier: Modifier) {
    if (frames.isNullOrEmpty()) {
        Box(modifier)
        return
    }
    val images = remember(frames) { frames.map { it.asImageBitmap() } }
    Row(modifier) {
        images.forEach { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TimelineOverlay(
    duration: Long,
    trimStart: Long,
    trimEnd: Long,
    position: Long,
    modifier: Modifier,
) {
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    val handle = MaterialTheme.colorScheme.primary
    val grip = MaterialTheme.colorScheme.onPrimary
    val playhead = MaterialTheme.colorScheme.inverseSurface
    val playheadOutline = MaterialTheme.colorScheme.inverseOnSurface
    val density = LocalDensity.current
    val handleWidthPx = with(density) { HandleWidth.toPx() }
    val borderPx = with(density) { 3.dp.toPx() }
    val playheadPx = with(density) { 3.dp.toPx() }
    Canvas(modifier) {
        val startX = trimStart.toFloat() / duration * size.width
        val endX = trimEnd.toFloat() / duration * size.width
        // Dim what the trim removes.
        if (startX > 0f) drawRect(scrim, Offset.Zero, Size(startX, size.height))
        if (endX < size.width) drawRect(scrim, Offset(endX, 0f), Size(size.width - endX, size.height))
        // Frame between the handles.
        drawRect(handle, Offset(startX, 0f), Size(endX - startX, borderPx))
        drawRect(handle, Offset(startX, size.height - borderPx), Size(endX - startX, borderPx))
        // Handles sit inside the trimmed range so they stay reachable at the very edges.
        val radius = CornerRadius(handleWidthPx / 2)
        drawRoundRect(handle, Offset(startX, 0f), Size(handleWidthPx, size.height), radius)
        drawRoundRect(handle, Offset(endX - handleWidthPx, 0f), Size(handleWidthPx, size.height), radius)
        val gripHeight = size.height * 0.36f
        val gripTop = (size.height - gripHeight) / 2
        val gripWidth = 3.dp.toPx()
        drawRoundRect(grip, Offset(startX + (handleWidthPx - gripWidth) / 2, gripTop), Size(gripWidth, gripHeight), CornerRadius(gripWidth / 2))
        drawRoundRect(grip, Offset(endX - handleWidthPx + (handleWidthPx - gripWidth) / 2, gripTop), Size(gripWidth, gripHeight), CornerRadius(gripWidth / 2))
        // Playhead: an outlined line so it reads over any frame color.
        val playheadX = position.toFloat() / duration * size.width
        drawRect(playheadOutline, Offset(playheadX - playheadPx, 0f), Size(playheadPx * 2, size.height))
        drawRect(playhead, Offset(playheadX - playheadPx / 2, 0f), Size(playheadPx, size.height))
    }
}

@Composable
private fun HandleSemantics(
    stripWidthPx: Int,
    fraction: Float,
    testTag: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val touchPx = with(LocalDensity.current) { (HandleTouchSlop * 2).roundToPx() }
    val x = (fraction * stripWidthPx - touchPx / 2f).roundToInt().coerceIn(0, (stripWidthPx - touchPx).coerceAtLeast(0))
    Box(
        Modifier
            .offset { IntOffset(x, 0) }
            .size(HandleTouchSlop * 2, StripHeight)
            .testTag(testTag)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                setProgress { target ->
                    onChange(target.coerceIn(range))
                    true
                }
            },
    )
}
