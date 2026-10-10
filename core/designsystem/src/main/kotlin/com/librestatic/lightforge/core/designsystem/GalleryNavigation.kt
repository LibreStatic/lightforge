package com.librestatic.lightforge.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One entry of the root navigation, shared by [GalleryFloatingNavigation] and
 * [GalleryNavigationRail]. A destination is a tab that can be [selected]; an [isAction] entry
 * (Create) runs [onClick] and is never shown as selected. [anchoredContent] is composed next to
 * the entry, so a `DropdownMenu` placed there opens anchored to it.
 */
class GalleryNavItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean = false,
    val isAction: Boolean = false,
    val testTag: String? = null,
    val badgeCount: Int = 0,
    val progress: Float? = null,
    val contentDescription: String? = null,
    val anchoredContent: (@Composable () -> Unit)? = null,
    val onClick: () -> Unit,
)

object GalleryFloatingNavigationDefaults {
    /** Minimum height of the pill; it grows with the font scale. */
    val MinHeight = 56.dp
    val SearchButtonSize = 56.dp

    /** Gap between the cluster and the bottom window inset. */
    val BottomGap = 8.dp

    /** Extra space between the last row of content and the top of the cluster. */
    val ContentClearance = 24.dp
    val SideMargin = 12.dp
    val SearchGap = 8.dp
    val PillPadding = 4.dp
    val ItemMinHeight = 48.dp
    val IconSize = 24.dp
    val IconGap = 8.dp
    val ItemPadding = 16.dp
    val Elevation = 3.dp

    /** Bottom content padding under the cluster: inset + measured cluster height + 24 dp. */
    fun contentBottomPadding(bottomInset: Dp, clusterHeight: Dp = MinHeight): Dp =
        bottomInset + clusterHeight + ContentClearance

    /** Distance from the window bottom to the top of the cluster, for overlays stacked above it. */
    fun occupiedHeight(bottomInset: Dp, clusterHeight: Dp = MinHeight): Dp =
        bottomInset + BottomGap + clusterHeight
}

/** Which pill entries show their icon. */
enum class GalleryFloatingNavIcons { All, SelectedOnly, None }

/** How the pill fits its labels into the width it has. */
data class GalleryFloatingNavLayout(
    val icons: GalleryFloatingNavIcons,
    val itemPadding: Dp,
    /** Nothing fits even at the tightest padding: entries share the width and ellipsize. */
    val ellipsize: Boolean,
)

/**
 * Degrades the pill step by step until its labels fit [availablePillWidth]: an icon on every entry
 * (landscape phones), then the icon on the selected entry only (portrait phones, like Google
 * Photos), then tighter item padding, then labels only (the tonal indicator still marks the
 * selection), and only as a last resort ellipsized labels. Every step budgets the same icons
 * whichever entry is selected, so moving the selection never changes the chosen step.
 */
fun galleryFloatingNavLayout(labelWidths: List<Dp>, availablePillWidth: Dp): GalleryFloatingNavLayout {
    val d = GalleryFloatingNavigationDefaults
    val labels = labelWidths.fold(0.dp) { sum, width -> sum + width }
    val candidates = listOf(
        GalleryFloatingNavIcons.All to d.ItemPadding,
        GalleryFloatingNavIcons.SelectedOnly to d.ItemPadding,
        GalleryFloatingNavIcons.SelectedOnly to 12.dp,
        GalleryFloatingNavIcons.SelectedOnly to 8.dp,
        GalleryFloatingNavIcons.None to 8.dp,
    )
    for ((icons, padding) in candidates) {
        val iconCount = when (icons) {
            GalleryFloatingNavIcons.All -> labelWidths.size
            GalleryFloatingNavIcons.SelectedOnly -> 1
            GalleryFloatingNavIcons.None -> 0
        }
        val required = d.PillPadding * 2 + labels + padding * 2 * labelWidths.size +
            (d.IconSize + d.IconGap) * iconCount
        if (required <= availablePillWidth) return GalleryFloatingNavLayout(icons, padding, ellipsize = false)
    }
    return GalleryFloatingNavLayout(GalleryFloatingNavIcons.None, 8.dp, ellipsize = true)
}

/**
 * Floating root navigation for compact windows: a pill with Photos · Collections · Create on
 * `surfaceContainerHigh`, the selected destination on `secondaryContainer`, and Search as a
 * separate circular button. The caller places it [GalleryFloatingNavigationDefaults.BottomGap]
 * above the bottom inset and pads content by
 * [GalleryFloatingNavigationDefaults.contentBottomPadding].
 */
@Composable
fun GalleryFloatingNavigation(
    items: List<GalleryNavItem>,
    search: GalleryNavItem,
    modifier: Modifier = Modifier,
) {
    val d = GalleryFloatingNavigationDefaults
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = d.SideMargin),
        contentAlignment = Alignment.Center,
    ) {
        val pillMaxWidth = (maxWidth - d.SearchButtonSize - d.SearchGap).coerceAtLeast(0.dp)
        val style = MaterialTheme.typography.labelLarge
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labels = items.map(GalleryNavItem::label)
        val layout = remember(labels, style, density, pillMaxWidth) {
            val widths = labels.map { label ->
                with(density) {
                    // +1 dp absorbs sub-pixel rounding between measuring here and laying out.
                    measurer.measure(label, style, maxLines = 1, softWrap = false).size.width.toDp() + 1.dp
                }
            }
            galleryFloatingNavLayout(widths, pillMaxWidth)
        }
        Row(
            Modifier.selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.SearchGap),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shadowElevation = d.Elevation,
                modifier = Modifier.widthIn(max = pillMaxWidth).testTag("floating-navigation"),
            ) {
                Row(
                    Modifier.defaultMinSize(minHeight = d.MinHeight).padding(d.PillPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEach { item ->
                        FloatingNavEntry(
                            item = item,
                            showIcon = when (layout.icons) {
                                GalleryFloatingNavIcons.All -> true
                                GalleryFloatingNavIcons.SelectedOnly -> item.selected && !item.isAction
                                GalleryFloatingNavIcons.None -> false
                            },
                            horizontalPadding = layout.itemPadding,
                            modifier = if (layout.ellipsize) Modifier.weight(1f, fill = false) else Modifier,
                        )
                    }
                }
            }
            FloatingSearchButton(search)
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun FloatingNavEntry(
    item: GalleryNavItem,
    showIcon: Boolean,
    horizontalPadding: Dp,
    modifier: Modifier,
) {
    val d = GalleryFloatingNavigationDefaults
    val selected = item.selected && !item.isAction
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "floatingNavIndicator",
    )
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = d.ItemMinHeight)
                .clip(CircleShape)
                .background(container)
                .then(navItemInteraction(item, selected))
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides content) {
                if (showIcon) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(d.IconSize))
                    Spacer(Modifier.width(d.IconGap))
                }
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.anchoredContent?.invoke()
    }
}

@Composable
private fun FloatingSearchButton(item: GalleryNavItem) {
    val d = GalleryFloatingNavigationDefaults
    val selected = item.selected && !item.isAction
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "floatingSearchContainer",
    )
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = d.Elevation,
    ) {
        Box(
            Modifier
                .size(d.SearchButtonSize)
                .then(navItemInteraction(item, selected))
                .semantics { contentDescription = item.contentDescription ?: item.label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null)
            item.anchoredContent?.invoke()
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun navItemInteraction(item: GalleryNavItem, selected: Boolean): Modifier {
    val tag = item.testTag
    val tagged = if (tag != null) Modifier.semantics { testTagsAsResourceId = true }.testTag(tag) else Modifier
    return tagged.then(
        if (item.isAction) Modifier.clickable(role = Role.Button, onClick = item.onClick)
        else Modifier.selectable(selected = selected, role = Role.Tab, onClick = item.onClick),
    )
}

/** Below this rail height the utilities drop their labels so every entry stays on screen. */
val GalleryCompactRailHeight: Dp = 560.dp

/**
 * Side navigation rail for windows of at least 600 × 480 dp: [destinations] (and the Create
 * action) at the top and [utilities] (Updates, Settings) at the bottom after a divider. The
 * selected destination uses the `secondaryContainer` indicator, like the floating navigation.
 * In short windows the utilities become icon-only; scrolling stays only as a safety net.
 */
@Composable
fun GalleryNavigationRail(
    destinations: List<GalleryNavItem>,
    utilities: List<GalleryNavItem>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.width(GalleryNavigationRailWidth).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val viewportHeight = maxHeight
            val compact = viewportHeight < GalleryCompactRailHeight
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight)
                    .padding(vertical = if (compact) 8.dp else 12.dp)
                    .selectableGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
            ) {
                destinations.forEach { RailEntry(it, showLabel = true, compact = compact) }
                Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
                HorizontalDivider(Modifier.padding(horizontal = 30.dp, vertical = if (compact) 4.dp else 8.dp))
                utilities.forEach { RailEntry(it, showLabel = !compact, compact = compact) }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun RailEntry(item: GalleryNavItem, showLabel: Boolean, compact: Boolean) {
    val selected = item.selected && !item.isAction
    val indicator by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        label = "railIndicator",
    )
    val description = item.contentDescription ?: if (showLabel) null else item.label
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .then(navItemInteraction(item, selected))
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier.size(width = 56.dp, height = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Only the indicator is clipped to the pill: the badge sits partly outside it.
                Box(Modifier.matchParentSize().clip(CircleShape).background(indicator))
                CompositionLocalProvider(
                    LocalContentColor provides if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else LocalContentColor.current,
                ) {
                    BadgedBox(
                        badge = {
                            if (item.badgeCount > 0) Badge {
                                Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString())
                            }
                        },
                    ) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            if (item.badgeCount > 0) {
                                val progress = item.progress
                                if (progress == null) {
                                    GalleryCircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = LocalContentColor.current,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    GalleryCircularProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.size(28.dp),
                                        color = LocalContentColor.current,
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            Icon(
                                item.icon,
                                contentDescription = null,
                                modifier = Modifier.size(if (item.badgeCount > 0) 17.dp else 24.dp),
                            )
                        }
                    }
                }
            }
            if (showLabel) {
                Text(
                    item.label,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item.anchoredContent?.invoke()
    }
}
