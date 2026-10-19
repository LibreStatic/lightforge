package com.librestatic.lightforge.feature.folders

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius

private const val ExplorerDragMime = "application/x-lightforge-explorer-drag"

/**
 * The drag in flight. A drag stays inside the app, so the payload is kept here and the system only
 * carries a marker that tells the explorer's drop targets it is theirs.
 */
class ExplorerDragState {
    var payload: ExplorerDragPayload? by mutableStateOf(null)
        internal set

    /** A drag is in flight, so drop targets show whether they accept it. */
    val dragging: Boolean get() = payload != null
}

/**
 * Makes the item a drag source. A mouse or stylus drags at once; touch drags after a long press, and only
 * an item that is already ticked ([touchArmed]), because the long press of an unticked one ticks it and
 * a swipe on it scrolls. [payload] is read when the drag starts and is null when the item cannot be dragged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.explorerDragSource(
    state: ExplorerDragState,
    touchArmed: Boolean,
    payload: () -> ExplorerDragPayload?,
): Modifier {
    val currentPayload = rememberUpdatedState(payload)
    val armed = rememberUpdatedState(touchArmed)
    val decoration = MaterialTheme.colorScheme.primaryContainer
    return dragAndDropSource(
        drawDragDecoration = { drawDecoration(decoration) },
        block = {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointer = when {
                    down.type == PointerType.Mouse || down.type == PointerType.Stylus ->
                        awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    armed.value -> awaitLongPressOrCancellation(down.id)
                    else -> null
                }
                if (pointer != null) {
                    val carried = currentPayload.value()
                    if (carried != null) {
                        state.payload = carried
                        startTransfer(
                            DragAndDropTransferData(
                                ClipData(ClipDescription("explorer", arrayOf(ExplorerDragMime)), ClipData.Item("explorer")),
                            ),
                        )
                    }
                }
            }
        },
    )
}

private fun DrawScope.drawDecoration(color: Color) {
    val side = 48.dp.toPx()
    drawRoundRect(color, size = androidx.compose.ui.geometry.Size(side, side), cornerRadius = CornerRadius(12.dp.toPx()))
}

/**
 * Makes the item a drop target for the drag in [state] when that drag [accepts] its [destination]; while a
 * drag it accepts is over it, it gets a primary outline. A drop calls [onDrop] with the payload.
 */
@Composable
internal fun Modifier.explorerDropTarget(
    state: ExplorerDragState,
    destination: FolderLocation,
    accepts: (ExplorerDragPayload) -> Boolean,
    onDrop: (ExplorerDragPayload, FolderLocation) -> Unit,
): Modifier {
    var over by remember { mutableStateOf(false) }
    val currentAccepts = rememberUpdatedState(accepts)
    val currentDrop = rememberUpdatedState(onDrop)
    val target = remember(state, destination) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { over = true }

            override fun onExited(event: DragAndDropEvent) { over = false }

            override fun onEnded(event: DragAndDropEvent) {
                over = false
                state.payload = null
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val carried = state.payload ?: return false
                if (!currentAccepts.value(carried)) return false
                state.payload = null
                currentDrop.value(carried, destination)
                return true
            }
        }
    }
    val outline = MaterialTheme.colorScheme.primary
    return this
        .dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.mimeTypes().contains(ExplorerDragMime) && state.payload?.let(currentAccepts.value) == true
            },
            target = target,
        )
        .then(if (over) Modifier.border(2.dp, outline, MaterialTheme.shapes.medium) else Modifier)
}
