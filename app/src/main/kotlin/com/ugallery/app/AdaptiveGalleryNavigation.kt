package com.ugallery.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ugallery.core.designsystem.GalleryIcons

@Composable
internal fun GalleryBottomDock(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        indicatorColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = selected == RootTab.Photos,
            onClick = { onSelect(RootTab.Photos) },
            icon = { Icon(GalleryIcons.Image, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_photos), maxLines = 1) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == RootTab.Collections,
            onClick = { onSelect(RootTab.Collections) },
            icon = { Icon(GalleryIcons.Collections, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_collections), maxLines = 1) },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selected == RootTab.Search,
            onClick = { onSelect(RootTab.Search) },
            icon = { Icon(GalleryIcons.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_search), maxLines = 1) },
            colors = itemColors,
        )
    }
}

@Composable
internal fun GalleryExpandedRail(
    route: SurfaceRoute,
    selectedRoot: RootTab,
    onRoot: (RootTab) -> Unit,
    onCreate: () -> Unit,
    onRoute: (SurfaceRoute) -> Unit,
    activeExportCount: Int = 0,
    activeExportProgress: Float? = null,
    activeExportDescription: String? = null,
    onOpenExportQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier.width(120.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailItem(GalleryIcons.Image, stringResource(R.string.nav_photos), route == SurfaceRoute.Root && selectedRoot == RootTab.Photos) { onRoot(RootTab.Photos) }
            RailItem(GalleryIcons.Collections, stringResource(R.string.nav_collections), route == SurfaceRoute.Root && selectedRoot == RootTab.Collections) { onRoot(RootTab.Collections) }
            RailItem(GalleryIcons.Plus, stringResource(R.string.nav_create), false, onClick = onCreate)
            RailItem(GalleryIcons.Collections, stringResource(R.string.publication_recoveries_title), route == SurfaceRoute.PublicationRecoveries, tag = "rail-publication-recoveries") { onRoute(SurfaceRoute.PublicationRecoveries) }
            HorizontalDivider(Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
            RailItem(GalleryIcons.Ask, stringResource(R.string.nav_ask), route == SurfaceRoute.Root && selectedRoot == RootTab.Search) { onRoot(RootTab.Search) }
            RailItem(
                icon = GalleryIcons.Notifications,
                label = stringResource(R.string.nav_updates),
                isSelected = route == SurfaceRoute.Updates,
                badgeCount = activeExportCount,
                progress = activeExportProgress,
                contentDescription = activeExportDescription,
                onClick = {
                    if (activeExportCount > 0) onOpenExportQueue()
                    else onRoute(SurfaceRoute.Updates)
                },
            )
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.collections.R.string.memory_controls_title), route == SurfaceRoute.MemoryControls, tag = "rail-memory-controls") { onRoute(SurfaceRoute.MemoryControls) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.collections.R.string.smart_title), route == SurfaceRoute.SmartAlbums, tag = "rail-smart-albums") { onRoute(SurfaceRoute.SmartAlbums) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.collections.R.string.stacks_title), route == SurfaceRoute.Stacks) { onRoute(SurfaceRoute.Stacks) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.collections.R.string.documents_title), route == SurfaceRoute.Documents) { onRoute(SurfaceRoute.Documents) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.pdfstudio.R.string.pdf_studio), route == SurfaceRoute.PdfStudio) { onRoute(SurfaceRoute.PdfStudio) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.places.R.string.places_title), route == SurfaceRoute.OfflinePlaces, tag = "rail-offline-places") { onRoute(SurfaceRoute.OfflinePlaces) }
            RailItem(GalleryIcons.Folder, stringResource(com.ugallery.feature.ownsync.R.string.own_sync_title), route == SurfaceRoute.OwnSync, tag = "rail-own-sync") { onRoute(SurfaceRoute.OwnSync) }
            RailItem(GalleryIcons.Folder, stringResource(com.ugallery.feature.localsharing.R.string.peer_title), route == SurfaceRoute.LocalSharing, tag = "rail-local-sharing") { onRoute(SurfaceRoute.LocalSharing) }
            RailItem(GalleryIcons.Collections, stringResource(com.ugallery.feature.petrecognition.R.string.pet_title), route == SurfaceRoute.PetIdentity, tag = "rail-pet-identity") { onRoute(SurfaceRoute.PetIdentity) }
            RailItem(GalleryIcons.Folder, stringResource(R.string.nav_on_device), route == SurfaceRoute.DeviceFolders) { onRoute(SurfaceRoute.DeviceFolders) }
            RailItem(GalleryIcons.Archive, stringResource(R.string.nav_archive), route == SurfaceRoute.Archive) { onRoute(SurfaceRoute.Archive) }
            RailItem(GalleryIcons.Trash, stringResource(R.string.nav_trash), route == SurfaceRoute.Trash) { onRoute(SurfaceRoute.Trash) }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun RailItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    tag: String? = null,
    badgeCount: Int = 0,
    progress: Float? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier).clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).semantics {
                testTagsAsResourceId = true
                selected = isSelected
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ) {
            BadgedBox(
                badge = {
                    if (badgeCount > 0) Badge {
                        Text(if (badgeCount > 99) "99+" else badgeCount.toString())
                    }
                },
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (badgeCount > 0) {
                        if (progress == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.size(28.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
        }
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 3,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun Modifier.rootTabSwipe(selected: RootTab, onSelect: (RootTab) -> Unit): Modifier =
    pointerInput(selected) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { change, amount ->
                distance += amount
                if (kotlin.math.abs(distance) > 24.dp.toPx()) change.consume()
            },
            onDragEnd = {
                val threshold = 72.dp.toPx()
                when {
                    selected == RootTab.Photos && distance < -threshold -> onSelect(RootTab.Collections)
                    selected == RootTab.Collections && distance > threshold -> onSelect(RootTab.Photos)
                }
            },
            onDragCancel = { distance = 0f },
        )
    }
