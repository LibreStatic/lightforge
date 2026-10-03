package com.librestatic.lightforge

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.GalleryFloatingNavigation
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryNavItem
import com.librestatic.lightforge.core.designsystem.GalleryNavigationRail

/**
 * Compact-window root navigation: the floating pill (Photos · Collections · Create) and the
 * separate Search button. Only root screens show it; [createMenu] is composed anchored to the
 * Create entry so a popover can open from it.
 */
@Composable
internal fun GalleryShellFloatingNavigation(
    selectedRoot: RootTab,
    onRoot: (RootTab) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    createMenu: (@Composable () -> Unit)? = null,
) {
    GalleryFloatingNavigation(
        items = listOf(
            GalleryNavItem(
                label = stringResource(R.string.nav_photos),
                icon = GalleryIcons.Image,
                selected = selectedRoot == RootTab.Photos,
                testTag = "nav-photos",
            ) { onRoot(RootTab.Photos) },
            GalleryNavItem(
                label = stringResource(R.string.nav_collections),
                icon = GalleryIcons.Collections,
                selected = selectedRoot == RootTab.Collections,
                testTag = "nav-collections",
            ) { onRoot(RootTab.Collections) },
            GalleryNavItem(
                label = stringResource(R.string.nav_create),
                icon = GalleryIcons.Plus,
                isAction = true,
                testTag = "nav-create",
                anchoredContent = createMenu,
                onClick = onCreate,
            ),
        ),
        search = GalleryNavItem(
            label = stringResource(R.string.nav_search),
            icon = GalleryIcons.Search,
            selected = selectedRoot == RootTab.Search,
            testTag = "nav-search",
        ) { onRoot(RootTab.Search) },
        modifier = modifier,
    )
}

/**
 * Navigation rail for windows of at least 600 x 480 dp. It mirrors the floating navigation's
 * destinations (Photos, Collections, Create, Search) and adds Updates/Settings in the footer;
 * secondary shortcuts live in Collections, not here. [createMenu] is anchored to Create.
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
    createMenu: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val onRootRoute = route == SurfaceRoute.Root
    GalleryNavigationRail(
        destinations = listOf(
            GalleryNavItem(
                label = stringResource(R.string.nav_photos),
                icon = GalleryIcons.Image,
                selected = onRootRoute && selectedRoot == RootTab.Photos,
                testTag = "rail-photos",
            ) { onRoot(RootTab.Photos) },
            GalleryNavItem(
                label = stringResource(R.string.nav_collections),
                icon = GalleryIcons.Collections,
                selected = onRootRoute && selectedRoot == RootTab.Collections,
                testTag = "rail-collections",
            ) { onRoot(RootTab.Collections) },
            GalleryNavItem(
                label = stringResource(R.string.nav_create),
                icon = GalleryIcons.Plus,
                isAction = true,
                testTag = "rail-create",
                anchoredContent = createMenu,
                onClick = onCreate,
            ),
            GalleryNavItem(
                label = stringResource(R.string.nav_search),
                icon = GalleryIcons.Search,
                selected = onRootRoute && selectedRoot == RootTab.Search,
                testTag = "rail-search",
            ) { onRoot(RootTab.Search) },
        ),
        utilities = listOf(
            GalleryNavItem(
                label = stringResource(R.string.nav_updates),
                icon = GalleryIcons.Notifications,
                selected = route == SurfaceRoute.Updates,
                testTag = "rail-updates",
                badgeCount = activeExportCount,
                progress = activeExportProgress,
                contentDescription = activeExportDescription,
            ) {
                if (activeExportCount > 0) onOpenExportQueue() else onRoute(SurfaceRoute.Updates)
            },
            GalleryNavItem(
                label = stringResource(R.string.nav_settings),
                icon = GalleryIcons.Settings,
                selected = route == SurfaceRoute.Settings,
                testTag = "rail-settings",
            ) { onRoute(SurfaceRoute.Settings) },
        ),
        modifier = modifier,
    )
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
