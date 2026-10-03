package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// They drive the real GalleryFloatingNavigation, GalleryNavigationRail and action menu with the
// shell's W x H policy, over a placeholder grid instead of the app's library.

/**
 * The shell's navigation for whatever window the driver renders: floating below 600 x 480 dp,
 * rail otherwise. Tabs are selectable and Create (tag nav-create / rail-create) opens the grouped
 * Create popover.
 */
@Preview
@Composable
fun GalleryShellNavigationPreview() = ShellFrame()

/** The floating cluster with the labels of every shipped locale, to check fit at font scale 1.3. */
@Preview
@Composable
fun GalleryFloatingNavigationLocalesPreview() {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LocaleLabels.forEach { (locale, labels) ->
                    Text(locale, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 16.dp))
                    // The longest label selected is the worst case for the selected-only icon.
                    GalleryFloatingNavigation(
                        items = listOf(
                            GalleryNavItem(labels[0], GalleryIcons.Image, testTag = "nav-photos-$locale") {},
                            GalleryNavItem(labels[1], GalleryIcons.Collections, selected = true, testTag = "nav-collections-$locale") {},
                            GalleryNavItem(labels[2], GalleryIcons.Plus, isAction = true, testTag = "nav-create-$locale") {},
                        ),
                        search = GalleryNavItem(labels[3], GalleryIcons.Search, testTag = "nav-search-$locale") {},
                    )
                }
            }
        }
    }
}

/** The grouped Create list as the compact bottom sheet shows it. */
@Preview
@Composable
fun GalleryCreateMenuPreview() {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            GalleryActionMenuContent(title = "Create", sections = createSections {})
        }
    }
}

private val LocaleLabels = listOf(
    "en" to listOf("Photos", "Collections", "Create", "Search"),
    "es" to listOf("Fotos", "Colecciones", "Crear", "Buscar"),
    "fr" to listOf("Photos", "Collections", "Créer", "Rechercher"),
    "pt" to listOf("Fotos", "Coleções", "Criar", "Pesquisar"),
    "it" to listOf("Foto", "Raccolte", "Crea", "Cerca"),
    "de" to listOf("Fotos", "Sammlungen", "Erstellen", "Suchen"),
)

private fun createSections(onClick: () -> Unit) = listOf(
    GalleryActionMenuSection(
        "Organize",
        listOf(
            GalleryActionMenuEntry("New album", "Group photos and videos you pick", GalleryIcons.Album, "create-album", onClick),
            GalleryActionMenuEntry("New memory", "Pick photos and tell their story", GalleryIcons.PhotoLibrary, "create-memory", onClick),
        ),
    ),
    GalleryActionMenuSection(
        "From your photos",
        listOf(
            GalleryActionMenuEntry("Memory video", "Turn photos into a short video", GalleryIcons.Video, "create-memory-video", onClick),
            GalleryActionMenuEntry("Animated GIF", "Loop a burst or a few photos", GalleryIcons.Repeat, "create-gif", onClick),
            GalleryActionMenuEntry("Collage", "Lay out several photos together", GalleryIcons.GridView, "create-collage", onClick),
            GalleryActionMenuEntry("PDF Studio", "Combine scans into a PDF", GalleryIcons.PictureAsPdf, "create-pdf", onClick),
        ),
    ),
    GalleryActionMenuSection(
        "More",
        listOf(
            GalleryActionMenuEntry("Publication recoveries", "Resume or clean up interrupted saves", GalleryIcons.History, "publication-recoveries-entry", onClick),
        ),
    ),
)

@Composable
private fun ShellFrame() {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val info = galleryAdaptiveLayoutInfo(maxWidth, height = maxHeight)
                var selected by remember { mutableStateOf(0) }
                var createOpen by remember { mutableStateOf(false) }
                val menu: @Composable () -> Unit = {
                    GalleryActionMenuPopover(
                        expanded = createOpen,
                        onDismissRequest = { createOpen = false },
                        title = "Create",
                        sections = createSections { createOpen = false },
                        offset = if (info.navigationType == GalleryNavigationType.Rail) {
                            DpOffset(GalleryNavigationRailWidth, (-56).dp)
                        } else DpOffset(0.dp, 0.dp),
                    )
                }
                val labels = LocaleLabels.first().second
                val destinations = listOf(
                    GalleryNavItem(labels[0], GalleryIcons.Image, selected = selected == 0, testTag = "nav-photos") { selected = 0 },
                    GalleryNavItem(labels[1], GalleryIcons.Collections, selected = selected == 1, testTag = "nav-collections") { selected = 1 },
                    GalleryNavItem(
                        labels[2], GalleryIcons.Plus, isAction = true, testTag = "nav-create",
                        anchoredContent = menu,
                    ) { createOpen = true },
                )
                val search = GalleryNavItem(labels[3], GalleryIcons.Search, selected = selected == 2, testTag = "nav-search") { selected = 2 }
                if (info.navigationType == GalleryNavigationType.Rail) {
                    Row(Modifier.fillMaxSize()) {
                        GalleryNavigationRail(
                            destinations = listOf(
                                destinations[0].copyTag("rail-photos"),
                                destinations[1].copyTag("rail-collections"),
                                destinations[2].copyTag("rail-create"),
                                search.copyTag("rail-search"),
                            ),
                            utilities = listOf(
                                GalleryNavItem("Updates", GalleryIcons.Notifications, badgeCount = 2, progress = 0.4f, testTag = "rail-updates") {},
                                GalleryNavItem("Settings", GalleryIcons.Settings, testTag = "rail-settings") {},
                            ),
                        )
                        PlaceholderGrid(info, PaddingValues(info.gutter))
                    }
                } else {
                    val clearance = GalleryFloatingNavigationDefaults.contentBottomPadding(0.dp)
                    Box(Modifier.fillMaxSize()) {
                        PlaceholderGrid(info, PaddingValues(start = info.gutter, end = info.gutter, top = info.gutter, bottom = clearance))
                        GalleryFloatingNavigation(
                            items = destinations,
                            search = search,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = GalleryFloatingNavigationDefaults.BottomGap),
                        )
                    }
                }
            }
        }
    }
}

private fun GalleryNavItem.copyTag(tag: String) = GalleryNavItem(
    label = label, icon = icon, selected = selected, isAction = isAction, testTag = tag,
    badgeCount = badgeCount, progress = progress, contentDescription = contentDescription,
    anchoredContent = anchoredContent, onClick = onClick,
)

@Composable
private fun PlaceholderGrid(info: GalleryAdaptiveLayoutInfo, padding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(galleryGridCellSize(info.contentWidth)),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(60) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(
                        if (index % 3 == 0) MaterialTheme.colorScheme.surfaceContainerHighest
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            )
        }
    }
}
