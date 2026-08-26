package com.ugallery.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import com.ugallery.core.designsystem.GalleryIcons
import kotlin.math.roundToInt

@Composable
internal fun GalleryBottomDock(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val askLabel = stringResource(R.string.nav_ask)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(64.dp),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val layoutDirection = LocalLayoutDirection.current
                val selectedIndex = when (selected) {
                    RootTab.Photos -> 0
                    RootTab.Collections -> 1
                    RootTab.Search -> -1
                }
                val target by animateFloatAsState(
                    targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "root-navigation-indicator",
                )
                val segmentPx = with(density) { maxWidth.toPx() / 3f }
                val visualTarget = if (layoutDirection == LayoutDirection.Ltr) target else 2f - target
                Box(
                    Modifier
                        .offset { IntOffset((segmentPx * visualTarget).roundToInt(), 0) }
                        .width(maxWidth / 3)
                        .fillMaxHeight()
                        .padding(4.dp)
                        .graphicsLayer { alpha = if (selectedIndex >= 0) 1f else 0f }
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(28.dp)),
                )
                Row(Modifier.fillMaxWidth()) {
                    DockItem(
                        GalleryIcons.Image,
                        stringResource(R.string.nav_photos),
                        selected == RootTab.Photos,
                        { onSelect(RootTab.Photos) },
                        Modifier.weight(1f),
                    )
                    DockItem(
                        GalleryIcons.Collections,
                        stringResource(R.string.nav_collections),
                        selected == RootTab.Collections,
                        { onSelect(RootTab.Collections) },
                        Modifier.weight(1f),
                    )
                    DockItem(
                        GalleryIcons.Plus,
                        stringResource(R.string.nav_create),
                        false,
                        onCreate,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.size(64.dp).clickable(role = Role.Tab) { onSelect(RootTab.Search) }
                .semantics { this.selected = selected == RootTab.Search },
            shape = RoundedCornerShape(32.dp),
            color = if (selected == RootTab.Search) {
                MaterialTheme.colorScheme.secondaryContainer
            } else MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(GalleryIcons.Ask, contentDescription = askLabel)
            }
        }
    }
}

@Composable
private fun DockItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clickable(role = Role.Tab, onClick = onClick).semantics { selected = isSelected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
