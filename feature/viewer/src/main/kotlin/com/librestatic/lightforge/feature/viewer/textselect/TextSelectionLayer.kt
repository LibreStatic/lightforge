package com.librestatic.lightforge.feature.viewer.textselect

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.librestatic.lightforge.core.model.RecognizedText
import com.librestatic.lightforge.feature.viewer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** On-demand recognizer supplied by the app; runs off the main thread. */
typealias ViewerTextRecognizer = suspend (Bitmap) -> RecognizedText

/**
 * Screen transform applied by the photo's graphicsLayer (pivot at the container center):
 * scale, then rotation, then translation.
 */
internal data class PhotoLayerTransform(
    val containerSize: IntSize,
    val scale: Float,
    val rotationDegrees: Float,
    val translation: Offset,
) {
    fun toScreen(local: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val scaled = (local - center) * scale
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val rotated = Offset(scaled.x * cosine - scaled.y * sine, scaled.x * sine + scaled.y * cosine)
        return center + rotated + translation
    }

    fun toLocal(screen: Offset): Offset {
        val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val shifted = screen - translation - center
        val radians = Math.toRadians(-rotationDegrees.toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val unrotated = Offset(shifted.x * cosine - shifted.y * sine, shifted.x * sine + shifted.y * cosine)
        return center + unrotated / scale.coerceAtLeast(0.0001f)
    }
}

/** FIT_CENTER placement of an image of [imageWidth]x[imageHeight] inside [container]. */
internal fun fitCenterRect(imageWidth: Float, imageHeight: Float, container: IntSize): Rect {
    if (imageWidth <= 0f || imageHeight <= 0f || container.width <= 0 || container.height <= 0) return Rect.Zero
    val fit = min(container.width / imageWidth, container.height / imageHeight)
    val width = imageWidth * fit
    val height = imageHeight * fit
    val left = (container.width - width) / 2f
    val top = (container.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

@Stable
internal class TextSelectionController(
    private val drawable: Drawable,
    private val recognizer: ViewerTextRecognizer,
) {
    var recognized by mutableStateOf<RecognizedText?>(null)
        private set
    var recognizing by mutableStateOf(false)
        private set
    var selection by mutableStateOf<TextSelection?>(null)
    var draggingHandle by mutableStateOf(false)
    private var job: Job? = null

    val active: Boolean get() = selection != null || recognizing

    fun clear() {
        job?.cancel()
        recognizing = false
        selection = null
        draggingHandle = false
    }

    /** Recognizes once per photo, then hands the result to [onReady] (null when it failed). */
    fun recognize(scope: CoroutineScope, onReady: (RecognizedText?) -> Unit) {
        recognized?.let { onReady(it); return }
        if (recognizing) return
        recognizing = true
        job = scope.launch {
            val result = try {
                recognizer(recognitionBitmap(drawable))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                recognizing = false
            }
            recognized = result
            onReady(result)
        }
    }
}

/**
 * Software bitmap of at most [maxSide] px for ML Kit. Must run on the main thread because it may
 * draw the drawable that the viewer's ImageView is also showing; its bounds are restored after.
 */
internal fun recognitionBitmap(drawable: Drawable, maxSide: Int = 2_048): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val scale = min(1f, maxSide.toFloat() / max(width, height))
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val source = (drawable as? BitmapDrawable)?.bitmap
    if (source != null && !source.isRecycled) {
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else source
        return if (scale < 1f) Bitmap.createScaledBitmap(software, targetWidth, targetHeight, true) else software
    }
    val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val previousBounds = drawable.copyBounds()
    drawable.setBounds(0, 0, targetWidth, targetHeight)
    drawable.draw(AndroidCanvas(output))
    drawable.bounds = previousBounds
    return output
}

/**
 * Pointer handling for text selection, in the photo layer's local (untransformed) coordinates.
 * Stays passive until a long press or a touch on an active selection, so pinch, pan, double-tap
 * and pager swipes keep working normally.
 */
internal suspend fun PointerInputScope.detectTextSelectionGestures(
    controller: TextSelectionController,
    imageRect: () -> Rect,
    layerScale: () -> Float,
    onLongPress: (Offset) -> Unit,
) {
    val handleRadius = 28.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        val rect = imageRect()
        val recognized = controller.recognized
        val current = controller.selection
        val scale = layerScale().coerceAtLeast(0.0001f)
        if (recognized != null && current != null && !rect.isEmpty) {
            val geometry = TextSelectionGeometry(recognized, rect.width, rect.height)
            val start = recognized.words[current.start]
            val end = recognized.words[current.end]
            val startHandle = Offset(rect.left + geometry.left(start), rect.top + geometry.bottom(start))
            val endHandle = Offset(rect.left + geometry.right(end), rect.top + geometry.bottom(end))
            val radius = handleRadius / scale
            val hitStart = (down.position - startHandle).getDistance() <= radius
            val hitEnd = (down.position - endHandle).getDistance() <= radius
            if (hitStart || hitEnd) {
                val dragsEnd = if (hitStart && hitEnd) {
                    (down.position - endHandle).getDistance() < (down.position - startHandle).getDistance()
                } else hitEnd
                val fixed = if (dragsEnd) current.start else current.end
                // Handles sit just below the text; aim the drag at the line the finger points to.
                val aim = Offset(0f, -((geometry.bottom(start) - geometry.top(start)) / 2f))
                down.consume()
                controller.draggingHandle = true
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (change.positionChanged()) {
                        val point = change.position + aim - rect.topLeft
                        geometry.nearest(point.x, point.y)?.let { moved ->
                            controller.selection = TextSelection(fixed, moved)
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
                controller.draggingHandle = false
                return@awaitEachGesture
            }
        }
        // Wait for a long press, a tap, or anything else that belongs to other gestures.
        var moved = false
        val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var outcome: Boolean? = null
            while (outcome == null) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                outcome = when {
                    event.changes.count { it.pressed } > 1 || change == null -> false
                    !change.pressed -> true
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> false
                    else -> null
                }
            }
            if (outcome == false) moved = true
            outcome
        }
        when {
            released == true -> {
                // A tap dismisses the selection instead of toggling the viewer chrome.
                if (controller.active) {
                    val insideSelection = recognized != null && current != null && !rect.isEmpty &&
                        TextSelectionGeometry(recognized, rect.width, rect.height)
                            .hitTest(down.position.x - rect.left, down.position.y - rect.top, 0f)
                            ?.let(current::contains) == true
                    if (!insideSelection) controller.clear()
                    currentEvent.changes.forEach { it.consume() }
                }
            }
            released == null && !moved -> {
                // Long press: select the word under the finger, then keep extending while held.
                onLongPress(down.position)
                var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                    last = change.position
                    val text = controller.recognized ?: continue
                    val selection = controller.selection ?: continue
                    if (rect.isEmpty) continue
                    val point = last - rect.topLeft
                    TextSelectionGeometry(text, rect.width, rect.height).nearest(point.x, point.y)?.let {
                        controller.selection = TextSelection(selection.anchor, it)
                    }
                }
            }
        }
    }
}

/** Highlights and handles, drawn inside the photo's transformed layer. */
@Composable
internal fun TextSelectionHighlights(
    controller: TextSelectionController,
    imageRect: Rect,
    layerScale: Float,
    modifier: Modifier = Modifier,
) {
    val recognized = controller.recognized ?: return
    val selection = controller.selection ?: return
    val highlight = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxSize().testTag("viewerTextSelection")) {
        if (imageRect.isEmpty) return@Canvas
        val geometry = TextSelectionGeometry(recognized, imageRect.width, imageRect.height)
        val words = recognized.words
        val inset = 2.dp.toPx() / layerScale
        for (index in selection.start..selection.end) {
            val word = words.getOrNull(index) ?: continue
            drawRect(
                color = highlight.copy(alpha = 0.32f),
                topLeft = Offset(imageRect.left + geometry.left(word) - inset, imageRect.top + geometry.top(word) - inset),
                size = Size(
                    geometry.right(word) - geometry.left(word) + inset * 2,
                    geometry.bottom(word) - geometry.top(word) + inset * 2,
                ),
            )
        }
        val radius = 10.dp.toPx() / layerScale
        val start = words[selection.start]
        val end = words[selection.end]
        fun handle(anchor: Offset, pointsLeft: Boolean) {
            // Teardrop: a circle hanging below the text with its square corner touching the anchor.
            val center = Offset(anchor.x + if (pointsLeft) -radius else radius, anchor.y + radius)
            drawCircle(highlight, radius, center)
            val corner = Path().apply {
                moveTo(anchor.x, anchor.y)
                lineTo(center.x, anchor.y)
                lineTo(center.x, center.y)
                lineTo(anchor.x, center.y)
                close()
            }
            drawPath(corner, highlight)
        }
        handle(Offset(imageRect.left + geometry.left(start), imageRect.top + geometry.bottom(start)), pointsLeft = true)
        handle(Offset(imageRect.left + geometry.right(end), imageRect.top + geometry.bottom(end)), pointsLeft = false)
    }
}

/** Floating action bar positioned in screen space above (or below) the selection. */
@Composable
internal fun TextSelectionToolbar(
    controller: TextSelectionController,
    imageRect: Rect,
    transform: PhotoLayerTransform,
    modifier: Modifier = Modifier,
) {
    val recognized = controller.recognized ?: return
    val selection = controller.selection ?: return
    if (controller.draggingHandle || imageRect.isEmpty) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val text = remember(recognized, selection) { selection.text(recognized) }
    val entities = remember(text) { SmartEntities.detect(text) }
    val canTranslate = remember(context) { TextSelectionActions.canTranslate(context) }
    val geometry = TextSelectionGeometry(recognized, imageRect.width, imageRect.height)
    val selected = recognized.words.subList(selection.start, selection.end + 1)
    val corners = listOf(
        Offset(selected.minOf(geometry::left), selected.minOf(geometry::top)),
        Offset(selected.maxOf(geometry::right), selected.minOf(geometry::top)),
        Offset(selected.minOf(geometry::left), selected.maxOf(geometry::bottom)),
        Offset(selected.maxOf(geometry::right), selected.maxOf(geometry::bottom)),
    ).map { transform.toScreen(it + imageRect.topLeft) }
    val bounds = Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    var toolbarSize by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(density) { 20.dp.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val container = transform.containerSize
    val above = bounds.top - gap - toolbarSize.height
    val below = bounds.bottom + gap * 1.5f
    val y = when {
        above >= margin * 6 -> above
        below + toolbarSize.height <= container.height - margin * 6 -> below
        else -> (container.height - toolbarSize.height) / 2f
    }
    val x = (bounds.center.x - toolbarSize.width / 2f)
        .coerceIn(margin, (container.width - toolbarSize.width - margin).coerceAtLeast(margin))
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    Box(modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .widthIn(max = with(density) { (container.width - margin * 2).coerceAtLeast(0f).toDp() })
                .onSizeChanged { toolbarSize = it }
                .testTag("viewerTextToolbar"),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                TextButton(onClick = { TextSelectionActions.copy(context, text); controller.clear() }, colors = buttonColors) {
                    Text(stringResource(R.string.viewer_text_copy))
                }
                if (selection.start != 0 || selection.end != recognized.words.lastIndex) {
                    TextButton(onClick = { controller.selection = TextSelection.all(recognized) }, colors = buttonColors) {
                        Text(stringResource(R.string.viewer_text_select_all))
                    }
                }
                if (canTranslate) {
                    TextButton(onClick = { TextSelectionActions.translate(context, text) }, colors = buttonColors) {
                        Text(stringResource(R.string.viewer_text_translate))
                    }
                }
                TextButton(onClick = { TextSelectionActions.share(context, text) }, colors = buttonColors) {
                    Text(stringResource(R.string.viewer_text_share))
                }
                entities.forEach { entity ->
                    val label = when (entity) {
                        is SmartEntity.Link -> R.string.viewer_text_open_link
                        is SmartEntity.Email -> R.string.viewer_text_email
                        is SmartEntity.Phone -> R.string.viewer_text_call
                        is SmartEntity.Address -> R.string.viewer_text_map
                    }
                    TextButton(onClick = { TextSelectionActions.open(context, entity) }, colors = buttonColors) {
                        Text(stringResource(label))
                    }
                }
                TextButton(onClick = { TextSelectionActions.searchWeb(context, text) }, colors = buttonColors) {
                    Text(stringResource(R.string.viewer_text_search_web))
                }
            }
        }
    }
}

/** Wires long-press recognition feedback (haptics, "no text" toast) and Back-to-dismiss. */
@Composable
internal fun rememberTextSelectionLongPress(
    controller: TextSelectionController?,
    scope: CoroutineScope,
    imageRect: () -> Rect,
): (Offset) -> Unit {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val noText = stringResource(R.string.viewer_text_none_found)
    BackHandler(enabled = controller?.active == true) { controller?.clear() }
    return remember(controller, scope, context, haptics, density) {
        { position ->
            controller?.let { selectionController ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                selectionController.recognize(scope) { recognized ->
                    val rect = imageRect()
                    if (recognized == null || recognized.isEmpty() || rect.isEmpty) {
                        Toast.makeText(context, noText, Toast.LENGTH_SHORT).show()
                        return@recognize
                    }
                    val geometry = TextSelectionGeometry(recognized, rect.width, rect.height)
                    val slop = with(density) { 24.dp.toPx() }
                    val hit = geometry.hitTest(position.x - rect.left, position.y - rect.top, slop)
                    if (hit == null) {
                        Toast.makeText(context, noText, Toast.LENGTH_SHORT).show()
                    } else {
                        selectionController.selection = TextSelection(hit, hit)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
        }
    }
}

/** Small progress pill shown while the photo is being read. */
@Composable
internal fun TextRecognitionProgress(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.viewer_text_recognizing)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(
            description,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
