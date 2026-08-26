package com.ugallery.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    modifier: Modifier = Modifier,
) {
    Surface(modifier.width(120.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailItem(GalleryIcons.Image, stringResource(R.string.nav_photos), route == SurfaceRoute.Root && selectedRoot == RootTab.Photos) { onRoot(RootTab.Photos) }
            RailItem(GalleryIcons.Collections, stringResource(R.string.nav_collections), route == SurfaceRoute.Root && selectedRoot == RootTab.Collections) { onRoot(RootTab.Collections) }
            RailItem(GalleryIcons.Plus, stringResource(R.string.nav_create), false, onCreate)
            HorizontalDivider(Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
            RailItem(GalleryIcons.Ask, stringResource(R.string.nav_ask), route == SurfaceRoute.Root && selectedRoot == RootTab.Search) { onRoot(RootTab.Search) }
            RailItem(GalleryIcons.Notifications, stringResource(R.string.nav_updates), route == SurfaceRoute.Updates) { onRoute(SurfaceRoute.Updates) }
            RailItem(GalleryIcons.Folder, stringResource(R.string.nav_on_device), route == SurfaceRoute.DeviceFolders) { onRoute(SurfaceRoute.DeviceFolders) }
            RailItem(GalleryIcons.Archive, stringResource(R.string.nav_archive), route == SurfaceRoute.Archive) { onRoute(SurfaceRoute.Archive) }
            RailItem(GalleryIcons.Trash, stringResource(R.string.nav_trash), route == SurfaceRoute.Trash) { onRoute(SurfaceRoute.Trash) }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).semantics { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp))
        }
        Text(
            label,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
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
