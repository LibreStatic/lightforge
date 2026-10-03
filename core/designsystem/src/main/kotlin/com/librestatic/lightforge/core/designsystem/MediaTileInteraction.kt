package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Keyboard modifiers held while a media tile was clicked with a pointer. */
@Immutable
data class MediaClickModifiers(
    /** Shift: extend the selection from the last clicked tile to this one. */
    val extend: Boolean = false,
    /** Ctrl (or Meta on a Mac keyboard): toggle this tile without opening it. */
    val toggle: Boolean = false,
)

/** What a click on a media tile should do. */
enum class MediaTileClick { Open, Toggle, ExtendRange }

/**
 * Desktop-style click rules shared by every selectable grid: Shift extends a range, Ctrl/Meta
 * toggles one tile, a plain click toggles while a selection exists and opens otherwise.
 */
fun mediaTileClick(selectionMode: Boolean, modifiers: MediaClickModifiers): MediaTileClick = when {
    modifiers.extend -> MediaTileClick.ExtendRange
    modifiers.toggle || selectionMode -> MediaTileClick.Toggle
    else -> MediaTileClick.Open
}

/**
 * The items between [from] and [to] (both inclusive, in either order) that [pick] accepts, in
 * grid order. Unloaded rows ([itemAt] returns null) and non-media rows such as day headers are
 * skipped, so a Shift-click range only touches media the user can see.
 */
fun <T, R : Any> selectionRange(from: Int, to: Int, itemAt: (Int) -> T?, pick: (T) -> R?): List<R> {
    if (from < 0 || to < 0) return emptyList()
    val range = if (from <= to) from..to else to..from
    return range.mapNotNull { index -> itemAt(index)?.let(pick) }
}

/**
 * Pointer input for a media tile: hover and press feedback through [interactionSource] (the
 * clickable reports hover itself), a click that
 * reports the keyboard modifiers held at press time, and a secondary (right) click that opens a
 * context menu at the pointer instead of activating the tile. Long-press stays with the grid's
 * drag-selection brush, so it is not handled here.
 */
fun Modifier.mediaTileInput(
    interactionSource: MutableInteractionSource,
    onClick: (MediaClickModifiers) -> Unit,
    onSecondaryClick: (Offset) -> Unit,
): Modifier = composed {
    val held = remember { MediaClickModifiersHolder() }
    val secondary by rememberUpdatedState(onSecondaryClick)
    val click by rememberUpdatedState(onClick)
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press) continue
                    val keys = event.keyboardModifiers
                    held.value = MediaClickModifiers(
                        extend = keys.isShiftPressed,
                        toggle = keys.isCtrlPressed || keys.isMetaPressed,
                    )
                    if (event.buttons.isSecondaryPressed) {
                        val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                        event.changes.forEach { it.consume() }
                        secondary(position)
                    }
                }
            }
        }
        .clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
            val modifiers = held.value
            held.value = MediaClickModifiers()
            click(modifiers)
        }
}

private class MediaClickModifiersHolder {
    var value = MediaClickModifiers()
}

/**
 * Accessibility for a media tile as one node: the full label, the selected state, the primary
 * action named for what it does, and custom actions to select/deselect and open, so TalkBack users
 * get the same choices as the long-press, the hover check and the context menu.
 */
@Composable
fun Modifier.mediaTileSemantics(
    description: String,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit,
    longPressLabel: String? = null,
): Modifier {
    val selectLabel = stringResource(R.string.media_tile_action_select)
    val deselectLabel = stringResource(R.string.media_tile_action_deselect)
    val openLabel = stringResource(R.string.media_tile_action_open)
    val toggleLabel = if (selected) deselectLabel else selectLabel
    return clearAndSetSemantics {
        contentDescription = description
        this.selected = selected
        onClick(label = if (selectionMode) toggleLabel else openLabel) {
            if (selectionMode) onToggleSelection() else onOpen()
            true
        }
        onLongClick(label = longPressLabel ?: toggleLabel) { onToggleSelection(); true }
        customActions = buildList {
            add(CustomAccessibilityAction(toggleLabel) { onToggleSelection(); true })
            if (selectionMode) add(CustomAccessibilityAction(openLabel) { onOpen(); true })
        }
    }
}

/** Localized medium date for a tile label, e.g. "Sep 28, 2026". */
@Composable
fun mediaTileDate(epochMillis: Long): String? {
    if (epochMillis <= 0L) return null
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochMillis, locale) {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

/**
 * The selection affordance on a tile that is not selected: a hollow check circle at the top end
 * over a short scrim. Shown on every tile while a selection exists, and on hover otherwise so a
 * mouse user can start a selection without a long press. [onToggle] makes the circle itself a
 * click target (hover case); pass null when the whole tile already toggles.
 */
@Composable
fun MediaSelectionAffordance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (() -> Unit)? = null,
) {
    if (!visible) return
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.35f)
                .background(GalleryScrims.top()),
        )
        val circle = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .size(36.dp)
            .clip(CircleShape)
            .testTag("media_selection_affordance")
        Box(
            if (onToggle != null) circle.clickable(onClick = onToggle) else circle,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GalleryIcons.Unchecked,
                contentDescription = null,
                tint = GalleryScrims.Content,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** A right-click menu for a media tile, opened at [offset] inside the tile. */
@Composable
fun MediaTileContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<GallerySelectionAction>,
    offset: DpOffset = DpOffset.Zero,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = { onDismiss(); action.onClick() },
                enabled = action.enabled,
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
        }
    }
}

/** Remembers whether a tile's context menu is open and where. */
class MediaTileMenuState {
    var expanded by mutableStateOf(false)
        private set
    var offset by mutableStateOf(DpOffset.Zero)
        private set

    fun open(at: DpOffset) {
        offset = at
        expanded = true
    }

    fun dismiss() {
        expanded = false
    }
}

@Composable
fun rememberMediaTileMenuState(): MediaTileMenuState = remember { MediaTileMenuState() }
