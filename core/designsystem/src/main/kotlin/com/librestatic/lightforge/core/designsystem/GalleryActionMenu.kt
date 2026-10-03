package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** One action of a [GalleryActionMenuContent]: a tonal icon badge, a title and a description. */
class GalleryActionMenuEntry(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val testTag: String,
    val onClick: () -> Unit,
)

/** A titled group of entries; groups after the first are separated by a divider. */
class GalleryActionMenuSection(
    val title: String?,
    val entries: List<GalleryActionMenuEntry>,
)

object GalleryActionMenuDefaults {
    /** Width of the anchored popover used on rail (tablet, desktop) and short windows. */
    val PopoverWidth: Dp = 360.dp
    val EntryMinHeight: Dp = 64.dp

    /** Room kept above and below the popover; matches the menu's own placement margin. */
    val PopoverWindowMargin: Dp = 48.dp
}

/**
 * Grouped action list shared by the bottom sheet (compact windows) and the anchored popover
 * (rail and short windows), so both show every entry with the same rows. Set [scrollable] when
 * the container does not scroll its content itself.
 */
@Composable
fun GalleryActionMenuContent(
    title: String?,
    sections: List<GalleryActionMenuSection>,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    Column(
        modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        sections.forEachIndexed { index, section ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            section.title?.let { sectionTitle ->
                Text(
                    sectionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp).semantics { heading() },
                )
            }
            section.entries.forEach { GalleryActionMenuRow(it) }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun GalleryActionMenuRow(entry: GalleryActionMenuEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = entry.onClick)
            .semantics { testTagsAsResourceId = true }
            .testTag(entry.testTag)
            .heightIn(min = GalleryActionMenuDefaults.EntryMinHeight)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(entry.icon, contentDescription = null) }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The action menu as a popover anchored to the composable that hosts it (the Create entry of the
 * rail or of the floating navigation), instead of a sheet centred on the window. The menu scrolls
 * on its own and flips above the anchor when there is no room below.
 */
@Composable
fun GalleryActionMenuPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String?,
    sections: List<GalleryActionMenuSection>,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .width(GalleryActionMenuDefaults.PopoverWidth)
            .heightIn(max = popoverMaxHeight())
            .testTag("action-menu-popover"),
    ) {
        GalleryActionMenuContent(title = title, sections = sections, scrollable = false)
    }
}

/**
 * Keeps the popover inside the window with a margin above and below. Without it a tall menu in a
 * short window (phone landscape) fills the whole height, runs under the status bar and loses its
 * rounded corners; with it the menu scrolls instead.
 */
@Composable
private fun popoverMaxHeight(): Dp {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    return (windowHeight - GalleryActionMenuDefaults.PopoverWindowMargin * 2).coerceAtLeast(GalleryActionMenuDefaults.EntryMinHeight * 2)
}
