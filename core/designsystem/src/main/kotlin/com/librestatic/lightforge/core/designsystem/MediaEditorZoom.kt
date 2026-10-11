package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Default pinch ceiling for editor previews. */
const val MEDIA_EDITOR_MAX_ZOOM = 5f

/** Scale a double tap zooms to in editor previews. */
const val MEDIA_EDITOR_DOUBLE_TAP_ZOOM = 2.5f

/**
 * Max absolute pan on each axis at [scale] so the picture never leaves empty letterbox in view:
 * `max(0, (content * scale - viewport) / 2)`. A zero component locks that axis.
 */
fun mediaEditorMaxOffsets(contentSize: Size, viewportSize: Size, scale: Float): Offset = Offset(
    max(0f, (contentSize.width * scale - viewportSize.width) / 2f),
    max(0f, (contentSize.height * scale - viewportSize.height) / 2f),
)

/**
 * Zoom/pan state for an editor preview stage. The owner keeps [updateSizes] current with the
 * fitted picture size ([contentSize]) and the stage size ([viewportSize]), both in stage pixels.
 */
class MediaEditorZoomState internal constructor(
    density: Density,
    internal val scope: CoroutineScope,
) {
    var contentSize: Size = Size.Zero
        private set
    var viewportSize: Size = Size.Zero
        private set

    val zoom = ZoomPanState(density) { candidate -> mediaEditorMaxOffsets(contentSize, viewportSize, candidate) }

    val isZoomed: Boolean get() = zoom.isZoomed
    val currentScale: Float get() = zoom.currentScale
    internal val center: Offset get() = Offset(viewportSize.width / 2f, viewportSize.height / 2f)

    fun updateSizes(contentSize: Size, viewportSize: Size) {
        this.contentSize = contentSize
        this.viewportSize = viewportSize
    }

    /** Animates back to 1x. */
    fun animateReset() {
        scope.launch { zoom.zoomTo(center, 1f, null, MEDIA_EDITOR_MAX_ZOOM) }
    }

    /** Jumps back to 1x without animation. */
    suspend fun resetImmediately() {
        zoom.stopTransitions()
        zoom.scale.snapTo(1f)
        zoom.offsetX.snapTo(0f)
        zoom.offsetY.snapTo(0f)
    }
}

/** Remembers a [MediaEditorZoomState]; a change in any of [keys] starts over at 1x. */
@Composable
fun rememberMediaEditorZoomState(vararg keys: Any?): MediaEditorZoomState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(density, scope, *keys) { MediaEditorZoomState(density, scope) }
}

/** Applies the zoom as a graphics layer; put the picture and everything aligned to it inside. */
fun Modifier.mediaEditorZoomLayer(state: MediaEditorZoomState): Modifier = graphicsLayer {
    scaleX = state.zoom.scale.value
    scaleY = state.zoom.scale.value
    translationX = state.zoom.offsetX.value
    translationY = state.zoom.offsetY.value
}

/**
 * Pinch-zoom, pan and double-tap zoom for an editor stage. Attach it to the parent of the zoom
 * layer (screen coordinates). Events are observed in [PointerEventPass.Initial] so a transform is
 * consumed before the tool overlays inside the layer see it; they then observe consumed changes
 * and cancel whatever one-finger action the first finger of a pinch had started.
 */
fun Modifier.mediaEditorZoomGestures(
    state: MediaEditorZoomState,
    enabled: Boolean,
    singleFingerPan: Boolean,
    doubleTapZoom: Boolean,
    maxScale: Float = MEDIA_EDITOR_MAX_ZOOM,
): Modifier = pointerInput(state, enabled, singleFingerPan, doubleTapZoom, maxScale) {
    if (enabled) detectEditorZoom(state, singleFingerPan, doubleTapZoom, maxScale)
}

private suspend fun PointerInputScope.detectEditorZoom(
    state: MediaEditorZoomState,
    singleFingerPan: Boolean,
    doubleTapZoom: Boolean,
    maxScale: Float,
) {
    val scope = state.scope
    val zoom = state.zoom
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var transforming = false
        var maxPointers = 1
        var pendingPan = Offset.Zero
        var trackedPointerId: PointerId? = null
        var wandered = false
        var lastUptime = down.uptimeMillis
        val velocityTracker = VelocityTracker()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            maxPointers = max(maxPointers, pressed)
            event.changes.firstOrNull()?.let { lastUptime = it.uptimeMillis }
            val pan = event.calculatePan()
            var gesturePan = pan
            if (!transforming) {
                when {
                    pressed >= 2 -> transforming = true
                    pressed == 1 && maxPointers == 1 -> {
                        event.changes.firstOrNull()?.let {
                            if ((it.position - down.position).getDistance() > viewConfiguration.touchSlop) wandered = true
                        }
                        if (singleFingerPan && zoom.isZoomed) {
                            pendingPan += pan
                            if (pendingPan.getDistance() > viewConfiguration.touchSlop) {
                                transforming = true
                                gesturePan = pendingPan
                            }
                        }
                    }
                }
                if (transforming) scope.launch { zoom.stopTransitions() }
            }
            if (transforming) {
                // One stable pointer feeds the velocity so a lifting finger cannot fake a spike.
                if (trackedPointerId == null) trackedPointerId = event.changes.firstOrNull { it.pressed }?.id
                event.changes.firstOrNull { it.id == trackedPointerId && it.pressed }
                    ?.let { velocityTracker.addPosition(it.uptimeMillis, it.position) }
                val factor = event.calculateZoom()
                if (factor != 1f || gesturePan != Offset.Zero) {
                    val centroid = event.calculateCentroid()
                    scope.launch {
                        zoom.applyGesture(state.center, centroid, gesturePan, factor, 1f, maxScale)
                    }
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })

        if (transforming) {
            val velocity = velocityTracker.calculateVelocity()
            if (!zoom.isZoomed && zoom.currentScale > 1f) {
                scope.launch { zoom.zoomTo(state.center, 1f, null, maxScale) }
            } else {
                zoom.fling(Offset(velocity.x, velocity.y), scope)
            }
        } else if (doubleTapZoom && maxPointers == 1 && !wandered &&
            lastUptime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        ) {
            val isSecondTap = lastTapTime != 0L &&
                down.uptimeMillis - lastTapTime <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPosition).getDistance() < 100.dp.toPx()
            if (isSecondTap) {
                lastTapTime = 0L
                scope.launch {
                    if (zoom.isZoomed) {
                        zoom.zoomTo(state.center, 1f, null, maxScale)
                    } else {
                        zoom.zoomTo(
                            state.center,
                            min(MEDIA_EDITOR_DOUBLE_TAP_ZOOM, maxScale),
                            down.position,
                            maxScale,
                        )
                    }
                }
            } else {
                lastTapTime = lastUptime
                lastTapPosition = down.position
            }
        } else {
            lastTapTime = 0L
        }
    }
}

/** Chip showing the current factor while zoomed; tapping it animates back to 1x. */
@Composable
fun MediaEditorZoomResetChip(state: MediaEditorZoomState, modifier: Modifier = Modifier) {
    if (!state.isZoomed) return
    val description = stringResource(R.string.editor_zoom_reset)
    Surface(
        onClick = state::animateReset,
        modifier = modifier.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            String.format(LocalConfiguration.current.locales[0], "%.1f×", state.currentScale),
            Modifier.padding(horizontal = GallerySpacing.Md, vertical = GallerySpacing.Sm),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
