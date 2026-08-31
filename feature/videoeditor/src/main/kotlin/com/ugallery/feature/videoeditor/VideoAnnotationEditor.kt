package com.ugallery.feature.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcons
import com.ugallery.core.designsystem.GalleryProgressIndicator
import com.ugallery.core.designsystem.GallerySpacing
import com.ugallery.core.editing.video.NormalizedPoint
import com.ugallery.core.editing.video.VideoAnnotationAppearance
import com.ugallery.core.editing.video.VideoAnnotationLayer
import com.ugallery.core.editing.video.VideoAnnotationKeyframe
import com.ugallery.core.editing.video.VideoAnnotationShape
import com.ugallery.core.editing.video.VideoAnnotationStyle
import com.ugallery.core.editing.video.VideoAnnotationTrackingMode

internal data class VideoAnnotationToolState(
    val shape: VideoAnnotationShape = VideoAnnotationShape.Freehand,
    val appearance: VideoAnnotationAppearance = VideoAnnotationAppearance.Pen,
    val color: Color = Color(0xFFFF3B30),
    val strokeWidth: Float = 0.012f,
    val opacity: Float = 1f,
    val filled: Boolean = false,
    val intensity: Float = 0.55f,
    val eraser: Boolean = false,
)

@Composable
internal fun VideoAnnotationGestureLayer(
    enabled: Boolean,
    tool: VideoAnnotationToolState,
    startMillis: Long,
    endMillis: Long,
    selectedLayer: VideoAnnotationLayer?,
    currentMillis: Long,
    onAdd: (VideoAnnotationLayer) -> Unit,
    onErase: (List<NormalizedPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var points by remember(enabled, tool) { mutableStateOf<List<Offset>>(emptyList()) }
    val input = if (enabled) Modifier.pointerInput(tool, startMillis, endMillis) {
        detectDragGestures(
            onDragStart = { points = listOf(it) },
            onDrag = { change, _ ->
                change.consume()
                points = if (tool.shape == VideoAnnotationShape.Freehand) points + change.position
                else listOf(points.firstOrNull() ?: change.position, change.position)
            },
            onDragCancel = { points = emptyList() },
            onDragEnd = {
                val captured = points
                points = emptyList()
                if (captured.size >= 2 && size.width > 0 && size.height > 0 && endMillis > startMillis) {
                    val normalized = captured.map { point ->
                        NormalizedPoint(point.x / size.width, point.y / size.height)
                    }
                    if (tool.eraser) {
                        onErase(normalized)
                    } else onAdd(VideoAnnotationLayer(
                        shape = tool.shape,
                        points = normalized,
                        style = VideoAnnotationStyle(
                            appearance = tool.appearance,
                            colorArgb = tool.color.toArgb(),
                            strokeWidth = tool.strokeWidth,
                            opacity = tool.opacity,
                            filled = tool.filled,
                            intensity = tool.intensity,
                        ),
                        startMillis = startMillis,
                        endMillis = endMillis,
                    ))
                }
            },
        )
    } else Modifier
    val redactionPreviewColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val mosaicPreviewColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val eraserPreviewColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    val selectionColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxSize().then(input)) {
        selectedLayer?.takeIf { currentMillis in it.startMillis until it.endMillis }?.let { layer ->
            val transform = layer.transformAt(currentMillis)
            val left = layer.points.minOf(NormalizedPoint::x) * size.width
            val top = layer.points.minOf(NormalizedPoint::y) * size.height
            val width = (layer.points.maxOf(NormalizedPoint::x) - layer.points.minOf(NormalizedPoint::x)) * size.width
            val height = (layer.points.maxOf(NormalizedPoint::y) - layer.points.minOf(NormalizedPoint::y)) * size.height
            val center = Offset(
                left + width / 2f + transform.translationX * size.width,
                top + height / 2f + transform.translationY * size.height,
            )
            val scaledWidth = width.coerceAtLeast(24f) * transform.scaleX
            val scaledHeight = height.coerceAtLeast(24f) * transform.scaleY
            withTransform({ rotate(transform.rotationDegrees, center) }) {
                val origin = Offset(center.x - scaledWidth / 2f, center.y - scaledHeight / 2f)
                drawRect(selectionColor, origin, androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight), style = Stroke(2.dp.toPx()))
                listOf(
                    origin,
                    Offset(origin.x + scaledWidth, origin.y),
                    Offset(origin.x, origin.y + scaledHeight),
                    Offset(origin.x + scaledWidth, origin.y + scaledHeight),
                ).forEach { drawCircle(selectionColor, 5.dp.toPx(), it) }
            }
        }
        if (points.size < 2) return@Canvas
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            if (tool.shape == VideoAnnotationShape.Freehand) {
                points.drop(1).forEach { lineTo(it.x, it.y) }
            } else lineTo(points.last().x, points.last().y)
        }
        val previewColor = if (tool.eraser) eraserPreviewColor else when (tool.appearance) {
            VideoAnnotationAppearance.Blur -> redactionPreviewColor
            VideoAnnotationAppearance.Mosaic -> mosaicPreviewColor
            VideoAnnotationAppearance.Highlighter -> tool.color.copy(alpha = tool.opacity * 0.38f)
            VideoAnnotationAppearance.Pen -> tool.color.copy(alpha = tool.opacity)
        }
        drawPath(path, previewColor, style = Stroke(tool.strokeWidth * minOf(size.width, size.height)))
    }
}

@Composable
internal fun VideoAnnotationControls(
    state: VideoEditorContentState,
    currentMillis: Long,
    tool: VideoAnnotationToolState,
    onToolChange: (VideoAnnotationToolState) -> Unit,
    onSelect: (String?) -> Unit,
    onUpdate: (VideoAnnotationLayer) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddKeyframe: (String, Long) -> Unit,
    onTrack: (String, Long) -> Unit,
    onCancelTracking: () -> Unit,
) {
    val selected = state.annotations.firstOrNull { it.id == state.selectedAnnotationId }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoAnnotationAppearance.entries.forEach { appearance ->
                FilterChip(
                    selected = tool.appearance == appearance,
                    onClick = { onToolChange(tool.copy(appearance = appearance, eraser = false)) },
                    label = { Text(stringResource(appearance.labelResource())) },
                )
            }
            FilterChip(
                selected = tool.eraser,
                onClick = { onToolChange(tool.copy(eraser = true)) },
                label = { Text(stringResource(R.string.video_editor_annotation_eraser)) },
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoAnnotationShape.entries.forEach { shape ->
                FilterChip(
                    selected = tool.shape == shape,
                    onClick = { onToolChange(tool.copy(shape = shape)) },
                    label = { Text(stringResource(shape.labelResource())) },
                )
            }
        }
        if (tool.appearance in setOf(VideoAnnotationAppearance.Pen, VideoAnnotationAppearance.Highlighter)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759),
                    Color(0xFF0A84FF), Color(0xFFAF52DE), Color.White, Color.Black,
                ).forEach { color ->
                    Box(
                        Modifier
                            .background(color, CircleShape)
                            .clickable { onToolChange(tool.copy(color = color, eraser = false)) }
                            .padding(12.dp),
                    )
                }
            }
            Text(stringResource(R.string.video_editor_annotation_width))
            Slider(tool.strokeWidth, { onToolChange(tool.copy(strokeWidth = it)) }, valueRange = 0.003f..0.08f)
            Text(stringResource(R.string.video_editor_annotation_opacity))
            Slider(tool.opacity, { onToolChange(tool.copy(opacity = it)) }, valueRange = 0.1f..1f)
            FilterChip(
                selected = tool.filled,
                onClick = { onToolChange(tool.copy(filled = !tool.filled)) },
                label = { Text(stringResource(R.string.video_editor_annotation_fill)) },
            )
        } else {
            Text(stringResource(R.string.video_editor_annotation_intensity))
            Slider(tool.intensity, { onToolChange(tool.copy(intensity = it)) }, valueRange = 0.1f..1f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onUndo) { Icon(GalleryIcons.Undo, stringResource(R.string.video_editor_undo)) }
            IconButton(onClick = onRedo) { Icon(GalleryIcons.Redo, stringResource(R.string.video_editor_redo)) }
            TextButton(onClick = onClear, enabled = state.annotations.isNotEmpty()) {
                Text(stringResource(R.string.video_editor_clear_annotations))
            }
        }
        if (state.annotations.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.annotations.forEachIndexed { index, layer ->
                    FilterChip(
                        selected = layer.id == state.selectedAnnotationId,
                        onClick = { onSelect(layer.id) },
                        label = { Text(stringResource(R.string.video_editor_annotation_layer, index + 1)) },
                    )
                }
            }
        }
        selected?.let { layer ->
            val duration = state.durationMillis.coerceAtLeast(1)
            Text(stringResource(R.string.video_editor_annotation_timing))
            RangeSlider(
                value = layer.startMillis.toFloat()..layer.endMillis.toFloat(),
                onValueChangeFinished = {},
                onValueChange = { range ->
                    val start = range.start.toLong().coerceIn(state.trimStartMillis, layer.endMillis - 1)
                    val end = range.endInclusive.toLong().coerceIn(start + 1, state.trimEndMillis)
                    onUpdate(layer.copy(
                        startMillis = start,
                        endMillis = end,
                        keyframes = layer.keyframes.filter { it.timeMillis in start..end },
                    ))
                },
                valueRange = 0f..duration.toFloat(),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoAnnotationTrackingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = layer.trackingMode == mode,
                        onClick = { onUpdate(layer.copy(trackingMode = mode)) },
                        label = { Text(stringResource(mode.labelResource())) },
                    )
                }
            }
            val transform = layer.transformAt(currentMillis)
            fun updateTransform(updated: com.ugallery.core.editing.video.VideoAnnotationTransform) {
                val safeTime = if (layer.trackingMode == VideoAnnotationTrackingMode.Fixed) {
                    layer.startMillis
                } else currentMillis.coerceIn(layer.startMillis, layer.endMillis)
                val retained = if (layer.trackingMode == VideoAnnotationTrackingMode.Fixed) emptyList()
                else layer.keyframes.filterNot { it.timeMillis == safeTime }
                val keyframes = (retained +
                    VideoAnnotationKeyframe(safeTime, updated)).sortedBy(VideoAnnotationKeyframe::timeMillis)
                onUpdate(layer.copy(keyframes = keyframes))
            }
            Text(stringResource(R.string.video_editor_annotation_horizontal_position))
            Slider(transform.translationX, { updateTransform(transform.copy(translationX = it)) }, valueRange = -1f..1f)
            Text(stringResource(R.string.video_editor_annotation_vertical_position))
            Slider(transform.translationY, { updateTransform(transform.copy(translationY = it)) }, valueRange = -1f..1f)
            Text(stringResource(R.string.video_editor_annotation_scale))
            Slider(transform.scaleX, { updateTransform(transform.copy(scaleX = it, scaleY = it)) }, valueRange = 0.1f..3f)
            Text(stringResource(R.string.video_editor_annotation_rotation))
            Slider(transform.rotationDegrees, { updateTransform(transform.copy(rotationDegrees = it)) }, valueRange = -180f..180f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onMove(layer.id, -1) }) {
                    Text(stringResource(R.string.video_editor_annotation_move_back))
                }
                TextButton(onClick = { onMove(layer.id, 1) }) {
                    Text(stringResource(R.string.video_editor_annotation_move_forward))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (layer.trackingMode == VideoAnnotationTrackingMode.Keyframes) {
                    OutlinedButton(onClick = { onAddKeyframe(layer.id, currentMillis) }) {
                        Text(stringResource(R.string.video_editor_add_keyframe))
                    }
                }
                if (layer.trackingMode == VideoAnnotationTrackingMode.Automatic) {
                    OutlinedButton(onClick = { onTrack(layer.id, currentMillis) }) {
                        Text(stringResource(R.string.video_editor_start_tracking))
                    }
                }
                TextButton(onClick = { onDelete(layer.id) }) {
                    Text(stringResource(R.string.video_editor_delete_annotation))
                }
            }
        }
        state.annotationTrackingProgress?.let { progress ->
            GalleryProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onCancelTracking) { Text(stringResource(R.string.video_editor_cancel_tracking)) }
        }
    }
}

private fun VideoAnnotationAppearance.labelResource() = when (this) {
    VideoAnnotationAppearance.Pen -> R.string.video_editor_annotation_pen
    VideoAnnotationAppearance.Highlighter -> R.string.video_editor_annotation_highlighter
    VideoAnnotationAppearance.Blur -> R.string.video_editor_annotation_blur
    VideoAnnotationAppearance.Mosaic -> R.string.video_editor_annotation_mosaic
}

private fun VideoAnnotationShape.labelResource() = when (this) {
    VideoAnnotationShape.Freehand -> R.string.video_editor_annotation_freehand
    VideoAnnotationShape.Line -> R.string.video_editor_annotation_line
    VideoAnnotationShape.Arrow -> R.string.video_editor_annotation_arrow
    VideoAnnotationShape.Rectangle -> R.string.video_editor_annotation_rectangle
    VideoAnnotationShape.Oval -> R.string.video_editor_annotation_oval
    VideoAnnotationShape.Triangle -> R.string.video_editor_annotation_triangle
    VideoAnnotationShape.Star -> R.string.video_editor_annotation_star
    VideoAnnotationShape.SpeechBubble -> R.string.video_editor_annotation_bubble
}

private fun VideoAnnotationTrackingMode.labelResource() = when (this) {
    VideoAnnotationTrackingMode.Fixed -> R.string.video_editor_tracking_fixed
    VideoAnnotationTrackingMode.Keyframes -> R.string.video_editor_tracking_keyframes
    VideoAnnotationTrackingMode.Automatic -> R.string.video_editor_tracking_automatic
}
