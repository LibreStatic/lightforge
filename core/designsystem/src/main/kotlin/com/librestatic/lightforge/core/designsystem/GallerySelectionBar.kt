package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One action offered while media is selected. */
@Immutable
data class GallerySelectionAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /**
     * True for everyday actions that should sit in the bar while there is room (they move to the
     * overflow menu, in order, when there is not); false keeps the action in the overflow menu.
     */
    val inline: Boolean = true,
    val testTag: String? = null,
)

/** Layout constants of [GallerySelectionBar]. */
object GallerySelectionBarDefaults {
    /** The bar never grows wider than this, so on desktop it stays next to the media. */
    val MaxWidth: Dp = 720.dp
    val ActionWidth: Dp = 48.dp

    /** Close button, a typical count label ("12 selected"), divider and paddings. */
    val FixedWidth: Dp = 48.dp + 96.dp + 13.dp + 16.dp

    /** The action pill alone (stacked layout): just its paddings. */
    val StackedFixedWidth: Dp = 16.dp
}

/** How [GallerySelectionBar] arranges itself in a given width. */
data class SelectionBarArrangement(
    /** True puts close + count in their own pill above the actions, freeing the bar for them. */
    val stacked: Boolean,
    val inlineCount: Int,
)

/**
 * One pill when every inline action fits next to close + count; otherwise close + count move to
 * a pill of their own above the actions, which is what keeps the everyday actions visible on a
 * 360 dp phone at large font scales.
 */
fun selectionBarArrangement(
    availableWidth: Dp,
    inlineCandidates: Int,
    hasMenuOnlyActions: Boolean,
): SelectionBarArrangement {
    val single = selectionBarInlineCount(availableWidth, inlineCandidates, hasMenuOnlyActions)
    if (single >= inlineCandidates) return SelectionBarArrangement(stacked = false, inlineCount = single)
    val stacked = selectionBarInlineCount(
        availableWidth,
        inlineCandidates,
        hasMenuOnlyActions,
        fixedWidth = GallerySelectionBarDefaults.StackedFixedWidth,
    )
    return SelectionBarArrangement(stacked = true, inlineCount = stacked)
}

/**
 * How many of [inlineCandidates] actions fit inline in a bar of [availableWidth]. When some do
 * not fit, or when [hasMenuOnlyActions], one slot goes to the overflow button.
 */
fun selectionBarInlineCount(
    availableWidth: Dp,
    inlineCandidates: Int,
    hasMenuOnlyActions: Boolean,
    fixedWidth: Dp = GallerySelectionBarDefaults.FixedWidth,
    actionWidth: Dp = GallerySelectionBarDefaults.ActionWidth,
): Int {
    val width = minOf(availableWidth, GallerySelectionBarDefaults.MaxWidth)
    val slots = ((width - fixedWidth) / actionWidth).toInt().coerceAtLeast(0)
    if (!hasMenuOnlyActions && inlineCandidates <= slots) return inlineCandidates
    // One slot is the overflow button.
    return (slots - 1).coerceIn(0, inlineCandidates)
}

/**
 * The contextual bar shown while media is selected: close, count, the everyday actions and an
 * overflow menu, in one floating pill. It replaces the floating navigation (or sits above the
 * bottom of a rail layout) and is centred in whatever container hosts it, so place it in the
 * content pane, never the window, and it stays next to the media it acts on.
 *
 * Actions that do not fit move into the overflow menu in order, so the bar works from a 360 dp
 * phone at 130 % font scale up to a desktop window. Inline actions show their label as a
 * tooltip on hover and long-press.
 *
 * Colors follow the "raised" role of [GalleryColorRoles]: surfaceContainerHigh with onSurface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySelectionBar(
    count: Long,
    onClear: () -> Unit,
    actions: List<GallerySelectionAction>,
    modifier: Modifier = Modifier,
    countLabel: String = pluralStringResource(
        R.plurals.gallery_selection_count,
        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        count,
    ),
    clearLabel: String = stringResource(R.string.gallery_selection_clear),
    clearTestTag: String = "gallery_selection_clear",
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val inlineActions = actions.filter { it.inline }
        val menuOnly = actions.filterNot { it.inline }
        val arrangement = selectionBarArrangement(maxWidth - GallerySpacing.Md * 2, inlineActions.size, menuOnly.isNotEmpty())
        val shown = inlineActions.take(arrangement.inlineCount)
        val overflow = inlineActions.drop(arrangement.inlineCount) + menuOnly
        val clear: @Composable RowScope.() -> Unit = {
            SelectionBarIcon(
                GallerySelectionAction(label = clearLabel, icon = GalleryIcons.Close, onClick = onClear, testTag = clearTestTag),
            )
            Text(
                countLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Shrinks (and ellipsizes) before any action is squeezed out of the bar.
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = GallerySpacing.Xs, end = GallerySpacing.Md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val tools: @Composable RowScope.() -> Unit = {
            shown.forEach { action -> SelectionBarIcon(action) }
            if (overflow.isNotEmpty()) SelectionBarOverflow(overflow)
        }
        Column(
            Modifier
                .padding(GallerySpacing.Md)
                .widthIn(max = GallerySelectionBarDefaults.MaxWidth)
                .semantics { testTagsAsResourceId = true }
                .testTag("gallery_selection_bar"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) {
            if (arrangement.stacked) {
                SelectionBarPill { clear() }
                if (shown.isNotEmpty() || overflow.isNotEmpty()) SelectionBarPill { tools() }
            } else {
                SelectionBarPill {
                    clear()
                    if (shown.isNotEmpty() || overflow.isNotEmpty()) {
                        VerticalDivider(
                            Modifier.height(GallerySpacing.Xxl).padding(end = GallerySpacing.Xs),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    tools()
                }
            }
        }
    }
}

/** One floating pill of the bar, in the "raised" color role. */
@Composable
private fun SelectionBarPill(content: @Composable RowScope.() -> Unit) {
    val roles = GalleryColorRoles.current.raised
    Surface(
        color = roles.container,
        contentColor = roles.content,
        shape = GalleryShapes.Pill,
        shadowElevation = 6.dp,
        modifier = Modifier.heightIn(min = GalleryHeights.FloatingBar),
    ) {
        Row(
            Modifier.height(GalleryHeights.FloatingBar).padding(horizontal = GallerySpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBarIcon(action: GallerySelectionAction) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(action.label) } },
        state = rememberTooltipState(),
    ) {
        GalleryExpressiveIconButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
        ) {
            Icon(action.icon, contentDescription = action.label)
        }
    }
}

@Composable
private fun SelectionBarOverflow(actions: List<GallerySelectionAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SelectionBarIcon(
            GallerySelectionAction(
                label = stringResource(R.string.gallery_selection_more),
                icon = GalleryIcons.More,
                onClick = { expanded = true },
                testTag = "gallery_selection_more",
            ),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = { expanded = false; action.onClick() },
                    enabled = action.enabled,
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    modifier = action.testTag?.let {
                        Modifier.semantics { testTagsAsResourceId = true }.testTag(it)
                    } ?: Modifier,
                )
            }
        }
    }
}
