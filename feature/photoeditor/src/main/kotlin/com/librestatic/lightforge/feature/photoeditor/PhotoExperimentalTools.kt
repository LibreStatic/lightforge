package com.librestatic.lightforge.feature.photoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.feature.objecteraser.ObjectEraser
import com.librestatic.lightforge.feature.subjectclip.SubjectClipper
import kotlin.math.min
import kotlin.math.roundToInt

/** A point on the edited image, as fractions (0..1) of its width and height. */
data class PhotoPoint(val x: Float, val y: Float)

/** Eraser brush radius as a fraction of the image's shorter side. */
const val PHOTO_ERASE_BRUSH_RADIUS = 0.03f

/** The rectangle a [ContentScale.Fit] image of [image] size occupies inside [container]. */
internal fun fittedImageBounds(container: IntSize, image: IntSize): Rect {
    if (container.width <= 0 || container.height <= 0 || image.width <= 0 || image.height <= 0) return Rect.Zero
    val scale = min(container.width.toFloat() / image.width, container.height.toFloat() / image.height)
    val width = image.width * scale
    val height = image.height * scale
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

/** Maps a touch inside the fitted image to image fractions; null when it lands in the letterbox. */
internal fun imagePointAt(position: Offset, bounds: Rect): PhotoPoint? {
    if (bounds.isEmpty || !bounds.contains(position)) return null
    return PhotoPoint(
        ((position.x - bounds.left) / bounds.width).coerceIn(0f, 1f),
        ((position.y - bounds.top) / bounds.height).coerceIn(0f, 1f),
    )
}

/** Square brush dabs, in pixels of a [width] x [height] bitmap, for the object eraser. */
fun eraseRegionsFor(points: List<PhotoPoint>, width: Int, height: Int): List<ObjectEraser.EraseRegion> {
    if (width <= 0 || height <= 0) return emptyList()
    val radius = (min(width, height) * PHOTO_ERASE_BRUSH_RADIUS).roundToInt().coerceAtLeast(1)
    return points.map { point ->
        val centerX = (point.x * width).roundToInt()
        val centerY = (point.y * height).roundToInt()
        ObjectEraser.EraseRegion(centerX - radius, centerY - radius, radius * 2, radius * 2)
    }
}

/** The recipe operations that move pixels, in order; eraser marks are projected through these. */
fun photoGeometryOperations(operations: List<EditOperation>): List<EditOperation> =
    operations.filter {
        it is EditOperation.Crop || it is EditOperation.Rotate || it is EditOperation.Flip ||
            (it is EditOperation.Straighten && it.degrees != 0f)
    }

/**
 * Whether marks can move between source and edited coordinates through [geometry]. Straighten
 * crops by the intermediate aspect ratio, so marks drawn under it stay in edited coordinates.
 */
fun photoGeometryProjectable(geometry: List<EditOperation>): Boolean =
    geometry.none { it is EditOperation.Straighten }

/** Maps an upright-source point to the edited image; null when a crop leaves it out of frame. */
fun projectToEdited(point: PhotoPoint, geometry: List<EditOperation>): PhotoPoint? {
    var x = point.x
    var y = point.y
    geometry.forEach { operation ->
        when (operation) {
            is EditOperation.Crop -> {
                val left = operation.leftPermille / 1_000f
                val top = operation.topPermille / 1_000f
                x = (x - left) / ((operation.rightPermille - operation.leftPermille) / 1_000f)
                y = (y - top) / ((operation.bottomPermille - operation.topPermille) / 1_000f)
                if (x !in 0f..1f || y !in 0f..1f) return null
            }
            is EditOperation.Rotate -> repeat(((operation.degrees / 90) % 4 + 4) % 4) {
                val rotatedX = 1f - y
                y = x
                x = rotatedX
            }
            is EditOperation.Flip -> if (operation.horizontal) x = 1f - x else y = 1f - y
            is EditOperation.Straighten -> return null
            else -> Unit
        }
    }
    return PhotoPoint(x, y)
}

/** Inverse of [projectToEdited]: maps an edited-image point back to the upright source. */
fun projectToSource(point: PhotoPoint, geometry: List<EditOperation>): PhotoPoint? {
    var x = point.x
    var y = point.y
    geometry.asReversed().forEach { operation ->
        when (operation) {
            is EditOperation.Crop -> {
                x = operation.leftPermille / 1_000f + x * (operation.rightPermille - operation.leftPermille) / 1_000f
                y = operation.topPermille / 1_000f + y * (operation.bottomPermille - operation.topPermille) / 1_000f
            }
            is EditOperation.Rotate -> repeat(((operation.degrees / 90) % 4 + 4) % 4) {
                val sourceX = y
                y = 1f - x
                x = sourceX
            }
            is EditOperation.Flip -> if (operation.horizontal) x = 1f - x else y = 1f - y
            is EditOperation.Straighten -> return null
            else -> Unit
        }
    }
    return PhotoPoint(x, y)
}

/**
 * Moves edited-image [points] drawn under geometry [from] onto the image edited by [to], keeping
 * them over the same content; points cropped out of frame are dropped. Marks cannot follow a
 * straighten, so they are cleared when either side has one.
 */
fun reprojectEditedPoints(
    points: List<PhotoPoint>,
    from: List<EditOperation>,
    to: List<EditOperation>,
): List<PhotoPoint> {
    if (points.isEmpty() || from == to) return points
    if (!photoGeometryProjectable(from) || !photoGeometryProjectable(to)) return emptyList()
    return points.mapNotNull { point -> projectToSource(point, from)?.let { projectToEdited(it, to) } }
}

/** Drops dabs closer than half a brush radius to the previous one, keeping masks bounded. */
internal fun appendBrushPoint(points: List<PhotoPoint>, point: PhotoPoint, aspect: Float): List<PhotoPoint> {
    val last = points.lastOrNull() ?: return points + point
    val shortSide = min(1f, aspect)
    val dx = (point.x - last.x) * aspect / shortSide
    val dy = (point.y - last.y) / shortSide
    val spacing = PHOTO_ERASE_BRUSH_RADIUS / 2f
    return if (dx * dx + dy * dy < spacing * spacing) points else points + point
}

internal class ExperimentalToolActions(
    val eraseStrokes: List<PhotoPoint>,
    val onEraseStrokesChange: (List<PhotoPoint>) -> Unit,
    val onApplyErase: () -> Unit,
    val onClearErase: () -> Unit,
    val onPreviewSubjectClip: (PhotoPoint) -> Unit,
    val onSaveSubjectClip: () -> Unit,
)

@Composable
internal fun ExperimentalPreviewOverlay(
    bitmapSize: IntSize,
    erasing: Boolean,
    eraseStrokes: List<PhotoPoint>,
    onEraseStrokesChange: (List<PhotoPoint>) -> Unit,
    subjectClipSeed: PhotoPoint?,
    onSubjectTap: (PhotoPoint) -> Unit,
    modifier: Modifier,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val bounds = fittedImageBounds(containerSize, bitmapSize)
    val currentStrokes by rememberUpdatedState(eraseStrokes)
    val aspect = if (bitmapSize.height > 0) bitmapSize.width.toFloat() / bitmapSize.height else 1f
    val maskColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val markerOuter = MaterialTheme.colorScheme.primary
    val markerInner = MaterialTheme.colorScheme.onPrimary
    val maskDescription = stringResource(R.string.photo_editor_eraser_mask_description)
    Canvas(
        modifier
            .onSizeChanged { containerSize = it }
            .semantics { if (erasing) contentDescription = maskDescription }
            .pointerInput(erasing, bounds) {
                if (erasing) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var strokes = currentStrokes
                        imagePointAt(down.position, bounds)?.let { strokes = appendBrushPoint(strokes, it, aspect) }
                        onEraseStrokesChange(strokes)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    imagePointAt(change.position, bounds)?.let {
                                        strokes = appendBrushPoint(strokes, it, aspect)
                                    }
                                    change.consume()
                                }
                            }
                            onEraseStrokesChange(strokes)
                        } while (event.changes.any { it.pressed })
                    }
                } else {
                    detectTapGestures { offset -> imagePointAt(offset, bounds)?.let(onSubjectTap) }
                }
            },
    ) {
        if (bounds.isEmpty) return@Canvas
        val radius = min(bounds.width, bounds.height) * PHOTO_ERASE_BRUSH_RADIUS
        if (erasing) {
            eraseStrokes.forEach { point ->
                drawCircle(
                    maskColor,
                    radius,
                    Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height),
                )
            }
        } else subjectClipSeed?.let { point ->
            val center = Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)
            drawCircle(markerOuter, 12.dp.toPx(), center, style = Stroke(4.dp.toPx()))
            drawCircle(markerInner, 8.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun ExperimentalFallbackNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(GallerySpacing.Sm), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ObjectEraserControls(state: PhotoEditorContentState, actions: ExperimentalToolActions) {
    val method = state.eraseMethod ?: ObjectEraser.EraseMethod.NEIGHBOR_INTERPOLATION_FALLBACK
    ExperimentalFallbackNotice(stringResource(R.string.photo_editor_eraser_fallback, method.name))
    Text(stringResource(R.string.photo_editor_eraser_hint), style = MaterialTheme.typography.bodyMedium)
}

/** Apply/Clear for the eraser; pinned below the scrolling tool panel. */
@Composable
internal fun ObjectEraserActions(state: PhotoEditorContentState, actions: ExperimentalToolActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        TextButton(
            onClick = actions.onClearErase,
            enabled = actions.eraseStrokes.isNotEmpty() || state.erasePreview != null,
            modifier = Modifier.weight(1f).testTag("photo-editor-eraser-clear"),
        ) { Text(stringResource(R.string.photo_editor_eraser_clear), maxLines = 1) }
        Button(
            onClick = actions.onApplyErase,
            enabled = actions.eraseStrokes.isNotEmpty() && !state.isExperimentalProcessing,
            modifier = Modifier.weight(1f).testTag("photo-editor-eraser-apply"),
        ) { Text(stringResource(R.string.photo_editor_eraser_apply), maxLines = 1) }
    }
}

@Composable
internal fun SubjectClipControls(state: PhotoEditorContentState, actions: ExperimentalToolActions) {
    val method = state.subjectClipMethod ?: SubjectClipper.ClipMethod.COLOR_DISTANCE_FALLBACK
    ExperimentalFallbackNotice(stringResource(R.string.photo_editor_clip_fallback, method.name))
    Text(stringResource(R.string.photo_editor_clip_hint), style = MaterialTheme.typography.bodyMedium)
}

/** Save cut-out; pinned below the scrolling tool panel. */
@Composable
internal fun SubjectClipActions(state: PhotoEditorContentState, actions: ExperimentalToolActions) {
    Button(
        onClick = actions.onSaveSubjectClip,
        enabled = state.subjectClipPreview != null && !state.isExperimentalProcessing && !state.isExporting,
        modifier = Modifier.fillMaxWidth().testTag("photo-editor-clip-save"),
    ) { Text(stringResource(R.string.photo_editor_clip_save), maxLines = 1) }
}
