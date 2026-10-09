package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.preferences.ThemePalette

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// They render the shared foundations and components so a change to a token is visible at once.

/** Color roles (with live contrast ratios), shapes, the type scale and the structural components. */
@Preview
@Composable
fun DesignSystemCataloguePreview() = Frame {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = GallerySpacing.Xxl),
    ) {
        GallerySectionHeader(
            title = "Color roles",
            actionLabel = "See all",
            onAction = {},
            supportingText = "Container / content pairs and their WCAG contrast",
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
        )
        ColorRoles()
        GallerySectionHeader(
            title = "Type scale",
            actionLabel = null,
            onAction = null,
            supportingText = "Nothing below 12 sp; controls at 14 sp",
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
        )
        TypeScale()
        GallerySectionHeader(
            title = "Settings rows",
            actionLabel = null,
            onAction = null,
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
        )
        var checked by remember { mutableStateOf(true) }
        GallerySwitchSettingRow(
            title = "Show hidden folders",
            supportingText = "Folders that start with a dot",
            checked = checked,
            onCheckedChange = { checked = it },
            icon = GalleryIcons.Folder,
            modifier = Modifier.testTag("catalogue_switch_row"),
        )
        GallerySettingRow(
            title = "Storage",
            supportingText = "2.1 GB used by previews",
            icon = GalleryIcons.Cleanup,
            onClick = {},
            trailing = { androidx.compose.material3.Icon(GalleryIcons.ChevronForward, contentDescription = null) },
        )
        GallerySettingRow(title = "Cloud backup", supportingText = "Not available offline", icon = GalleryIcons.Lock, enabled = false)
        GallerySectionHeader(
            title = "Shapes",
            actionLabel = null,
            onAction = null,
            modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
        )
        Shapes()
    }
}

/** The empty, loading, error and permission states side by side (stacked on a phone). */
@OptIn(ExperimentalLayoutApi::class)
@Preview
@Composable
fun DesignSystemStatesPreview() = Frame {
    FlowRow(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(GallerySpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Lg),
    ) {
        val cell = Modifier.width(340.dp).height(360.dp)
        GalleryEmptyState("No photos yet", "Photos you take or download appear here.", cell, actionLabel = "Open camera", onAction = {})
        GalleryLoadingState("Reading your library", "Large libraries take a minute the first time.", cell)
        GalleryErrorState("Couldn't load albums", "Your photos are safe. Try again in a moment.", cell, retryLabel = "Try again", onRetry = {})
        GalleryPermissionState(
            title = "Allow photo access",
            body = "Lightforge needs access to show the photos on this device.",
            grantLabel = "Allow access",
            onGrant = {},
            modifier = cell,
            settingsLabel = "Open settings",
            onOpenSettings = {},
        )
    }
}

/** A full-screen empty state with the shape illustration. Driver: `GalleryHeroEmptyStatePreview`. */
@Preview
@Composable
fun GalleryHeroEmptyStatePreview() = Frame {
    GalleryEmptyState(
        title = "No exports yet",
        body = "PDFs you export from your projects show up here, ready to open, share or save again.",
        icon = GalleryIcons.PictureAsPdf,
        hero = true,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * The selection bar over an adaptive grid of placeholder cells. Tap cells to change the count;
 * resize (`--qualifiers`) to see actions move into the overflow menu.
 */
@Preview
@Composable
fun SelectionBarPreview() = Frame {
    val selected = remember { mutableStateOf(setOf(1, 2, 5)) }
    Box(Modifier.fillMaxSize()) {
        AdaptiveMediaGrid(itemCount = null, bottomPadding = 96.dp) { layout ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Sat, Sep 28 · ${layout.columns} columns",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = GallerySpacing.Md),
                )
            }
            items(40, key = { it }) { index ->
                val isSelected = index in selected.value
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(GalleryShapes.Thumbnail)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .mediaTileSemantics(
                            description = "Photo $index",
                            selected = isSelected,
                            selectionMode = true,
                            onOpen = {},
                            onToggleSelection = {
                                selected.value = if (isSelected) selected.value - index else selected.value + index
                            },
                        )
                        .testTag("cell_$index"),
                ) {
                    MediaSelectionOverlay(isSelected, shape = GalleryShapes.Thumbnail)
                    MediaSelectionAffordance(visible = !isSelected)
                }
            }
        }
        if (selected.value.isNotEmpty()) {
            GallerySelectionBar(
                count = selected.value.size.toLong(),
                onClear = { selected.value = emptySet() },
                actions = demoActions(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun demoActions() = listOf(
    GallerySelectionAction("Share", GalleryIcons.Share, {}, testTag = "demo_share"),
    GallerySelectionAction("Add to album", GalleryIcons.Album, {}),
    GallerySelectionAction("Favorite", GalleryIcons.Heart, {}),
    GallerySelectionAction("Create PDF", GalleryIcons.PictureAsPdf, {}),
    GallerySelectionAction("Move to trash", GalleryIcons.Trash, {}),
    GallerySelectionAction("Archive", GalleryIcons.Archive, {}, inline = false),
    GallerySelectionAction("Select all", GalleryIcons.SelectAll, {}, inline = false),
)

/**
 * Every palette in light, dark and pure black, each with its role contrasts. Material You shows
 * the wallpaper colours of the host (the baseline scheme under Robolectric).
 */
@Composable
fun ThemePaletteCataloguePreview() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ThemePalette.entries.forEach { palette ->
            val variants = if (palette == ThemePalette.MinimalistBlack) {
                listOf("dark" to (true to true))
            } else {
                listOf("light" to (false to false), "dark" to (true to false), "pure black" to (true to true))
            }
            variants.forEach { (label, variant) ->
                LightforgeTheme(darkTheme = variant.first, palette = palette, pureBlack = variant.second) {
                    Surface(Modifier.fillMaxWidth().testTag("palette-${palette.name}-$label")) {
                        Column(Modifier.padding(vertical = GallerySpacing.Md)) {
                            Text(
                                "${palette.name} · $label",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = GallerySpacing.Lg),
                            )
                            ColorRoles()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRoles() {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
    ) {
        GalleryColorRoles.all(scheme).forEach { (name, pair) ->
            Surface(
                color = pair.container,
                contentColor = pair.content,
                shape = GalleryShapes.Plate,
                modifier = Modifier.width(144.dp),
            ) {
                Column(Modifier.padding(GallerySpacing.Md)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "%.1f:1".format(galleryContrastRatio(pair.content, pair.container)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeScale() {
    val t = MaterialTheme.typography
    Column(Modifier.padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm)) {
        listOf(
            "titleLarge" to t.titleLarge,
            "titleMedium" to t.titleMedium,
            "bodyLarge" to t.bodyLarge,
            "bodyMedium" to t.bodyMedium,
            "labelLarge (controls)" to t.labelLarge,
            "bodySmall" to t.bodySmall,
            "labelSmall (metadata floor)" to t.labelSmall,
        ).forEach { (name, style) ->
            Text("$name · ${style.fontSize.value.toInt()} sp", style = style)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shapes() {
    FlowRow(
        Modifier.padding(horizontal = GallerySpacing.Lg, vertical = GallerySpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
    ) {
        listOf(
            "Thumbnail" to GalleryShapes.Thumbnail,
            "Plate" to GalleryShapes.Plate,
            "Card" to GalleryShapes.Card,
            "Sheet" to GalleryShapes.Sheet,
            "Pill" to GalleryShapes.Pill,
        ).forEach { (name, shape) ->
            val role = GalleryColorRoles.current.highest
            Surface(color = role.container, contentColor = role.content, shape = shape, modifier = Modifier.size(96.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(name, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}
