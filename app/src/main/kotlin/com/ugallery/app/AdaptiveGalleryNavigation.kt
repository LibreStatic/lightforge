package com.ugallery.app

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
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

/**
 * Expanded-width navigation rail. Per Material 3 adaptive guidance it mirrors the bottom bar's
 * root destinations (Photos, Collections, Search) and adds Create as the header action plus
 * Updates/Settings in the footer; secondary shortcuts live in Collections, not here.
 */
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
        // Scroll is a safety net for short windows; the footer sits at the bottom otherwise.
        BoxWithConstraints(Modifier.fillMaxHeight()) {
        val viewportHeight = maxHeight
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val createLabel = stringResource(R.string.nav_create)
            FloatingActionButton(
                onClick = onCreate,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = 12.dp).testTag("rail-create")
                    .semantics { testTagsAsResourceId = true },
            ) {
                Icon(GalleryIcons.Plus, contentDescription = createLabel)
            }
            RailItem(GalleryIcons.Image, stringResource(R.string.nav_photos), route == SurfaceRoute.Root && selectedRoot == RootTab.Photos, tag = "rail-photos") { onRoot(RootTab.Photos) }
            RailItem(GalleryIcons.Collections, stringResource(R.string.nav_collections), route == SurfaceRoute.Root && selectedRoot == RootTab.Collections, tag = "rail-collections") { onRoot(RootTab.Collections) }
            RailItem(GalleryIcons.Search, stringResource(R.string.nav_search), route == SurfaceRoute.Root && selectedRoot == RootTab.Search, tag = "rail-search") { onRoot(RootTab.Search) }
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            HorizontalDivider(Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
            RailItem(
                icon = GalleryIcons.Notifications,
                label = stringResource(R.string.nav_updates),
                isSelected = route == SurfaceRoute.Updates,
                tag = "rail-updates",
                badgeCount = activeExportCount,
                progress = activeExportProgress,
                contentDescription = activeExportDescription,
                onClick = {
                    if (activeExportCount > 0) onOpenExportQueue()
                    else onRoute(SurfaceRoute.Updates)
                },
            )
            RailItem(GalleryIcons.Settings, stringResource(R.string.nav_settings), route == SurfaceRoute.Settings, tag = "rail-settings") { onRoute(SurfaceRoute.Settings) }
        }
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
        Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics {
                testTagsAsResourceId = true
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
