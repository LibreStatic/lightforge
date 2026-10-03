package com.librestatic.lightforge.feature.viewer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.view.WindowCompat
import com.librestatic.lightforge.core.designsystem.GalleryAnimatedVisibility
import com.librestatic.lightforge.core.designsystem.GalleryExpressiveIconButton
import com.librestatic.lightforge.core.designsystem.GalleryFoldInfo
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GalleryMotionEdge
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shared state of the viewer's Details surface. [ViewerDetailsScaffold] owns the layout; the
 * viewer's swipe gesture drives the compact sheet through it so the sheet follows the finger.
 */
@Stable
class ViewerDetailsState internal constructor(private val scope: CoroutineScope) {
    /** True while Details are requested open (mirrors the scaffold's `open`). */
    var isOpen by mutableStateOf(false)
        internal set

    /** Wide layouts show a side panel that opens and closes without following the finger. */
    var isSidePanel by mutableStateOf(false)
        internal set

    /** Pixels of the compact sheet currently on screen. */
    var visiblePx by mutableFloatStateOf(0f)
        private set

    internal var halfPx by mutableFloatStateOf(0f)
    internal var fullPx by mutableFloatStateOf(0f)
    internal var target by mutableStateOf(DetailsSheetValue.Hidden)
        private set
    internal var dragging by mutableStateOf(false)
        private set
    internal var requestOpen: () -> Unit = {}
    internal var requestClose: () -> Unit = {}
    private var settleJob: Job? = null

    /** Whether a viewer drag should move the sheet (compact layout that has been measured). */
    val followsFinger: Boolean get() = !isSidePanel && fullPx > 0f

    /** 0 while the sheet is at or below its half stop, 1 when it fills the screen. */
    val fullness: Float
        get() = if (fullPx <= halfPx) 0f else ((visiblePx - halfPx) / (fullPx - halfPx)).coerceIn(0f, 1f)

    fun open() {
        if (!isOpen) requestOpen()
    }

    fun close() {
        if (isOpen) requestClose()
    }

    fun beginDrag() {
        settleJob?.cancel()
        dragging = true
        open()
    }

    /** Moves the sheet by [upPx] (positive grows it). */
    fun dragBy(upPx: Float) {
        visiblePx = (visiblePx + upPx).coerceIn(0f, fullPx)
    }

    /** Settles after a drag released at [velocityUp] px/s (positive is upward). */
    fun endDrag(velocityUp: Float) {
        dragging = false
        val value = settleDetailsSheet(visiblePx, velocityUp, halfPx, fullPx)
        animateTo(value, velocityUp)
        if (value == DetailsSheetValue.Hidden) requestClose() else open()
    }

    internal fun animateTo(value: DetailsSheetValue, velocityUp: Float = 0f, instant: Boolean = false) {
        target = value
        val destination = when (value) {
            DetailsSheetValue.Hidden -> 0f
            DetailsSheetValue.Half -> halfPx
            DetailsSheetValue.Full -> fullPx
        }
        settleJob?.cancel()
        if (instant) {
            visiblePx = destination
            return
        }
        settleJob = scope.launch {
            animate(visiblePx, destination, initialVelocity = velocityUp) { current, _ -> visiblePx = current }
        }
    }
}

@Composable
fun rememberViewerDetailsState(): ViewerDetailsState {
    val scope = rememberCoroutineScope()
    return remember(scope) { ViewerDetailsState(scope) }
}

/**
 * Lays out the viewer with its Details. Compact windows get a sheet that rests at half height,
 * expands to full and follows the viewer's swipe; wide windows ([sidePanel]) get a side panel
 * of 320–400 dp next to the media, split at a vertical fold when there is one.
 *
 * Both surfaces respect the system bar and cutout insets, and while the sheet covers the status
 * bar the bar icons switch to match the sheet surface.
 */
@Composable
fun ViewerDetailsScaffold(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    sidePanel: Boolean,
    state: ViewerDetailsState,
    viewer: @Composable () -> Unit,
    details: @Composable (contentPadding: PaddingValues) -> Unit,
    modifier: Modifier = Modifier,
    foldInfo: GalleryFoldInfo? = null,
) {
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnClose by rememberUpdatedState(onClose)
    state.requestOpen = { latestOnOpen() }
    state.requestClose = { latestOnClose() }
    state.isOpen = open
    state.isSidePanel = sidePanel
    if (sidePanel) {
        SidePanelLayout(open, onClose, foldInfo, viewer, details, modifier)
    } else {
        SheetLayout(open, onClose, state, viewer, details, modifier)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidePanelLayout(
    open: Boolean,
    onClose: () -> Unit,
    foldInfo: GalleryFoldInfo?,
    viewer: @Composable () -> Unit,
    details: @Composable (PaddingValues) -> Unit,
    modifier: Modifier,
) {
    val verticalFold = foldInfo?.takeIf { it.enablesSideBySide }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        val panelWidth = if (verticalFold != null) {
            (maxWidth - verticalFold.right).coerceAtLeast(0.dp)
        } else {
            detailsPanelWidth(maxWidth)
        }
        val containerWidth = maxWidth
        Row(Modifier.fillMaxSize()) {
            Box(
                if (open && verticalFold != null) Modifier.width(verticalFold.left.coerceIn(0.dp, containerWidth)).fillMaxHeight()
                else Modifier.weight(1f).fillMaxHeight(),
            ) { viewer() }
            if (open && verticalFold != null) Spacer(Modifier.width(verticalFold.hingeWidth))
            GalleryAnimatedVisibility(visible = open, edge = GalleryMotionEdge.End) {
                // The strip above the panel stays dark so the light status-bar icons that the
                // viewer uses remain readable across the whole bar (bug 33/21).
                Box(
                    Modifier.width(panelWidth).fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 0.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            Modifier.fillMaxSize().windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom),
                            ),
                        ) {
                            DetailsHeader(onClose = onClose, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp))
                            Box(Modifier.weight(1f)) { details(PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** 30% of the window, kept between 320 and 400 dp and never leaving the media under 360 dp. */
internal fun detailsPanelWidth(windowWidth: Dp): Dp =
    (windowWidth * 0.3f).coerceIn(320.dp, 400.dp).coerceAtMost((windowWidth - 360.dp).coerceAtLeast(280.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetLayout(
    open: Boolean,
    onClose: () -> Unit,
    state: ViewerDetailsState,
    viewer: @Composable () -> Unit,
    details: @Composable (PaddingValues) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val reducedMotion = rememberGalleryReducedMotion()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heightPx = with(density) { maxHeight.toPx() }
        LaunchedEffect(heightPx) {
            state.halfPx = heightPx * 0.5f
            state.fullPx = heightPx
            // Keep a resting sheet at its stop when the window resizes or rotates.
            if (!state.dragging && state.target != DetailsSheetValue.Hidden) state.animateTo(state.target, instant = true)
        }
        LaunchedEffect(open, state.fullPx) {
            if (state.fullPx <= 0f || state.dragging) return@LaunchedEffect
            if (open && state.target == DetailsSheetValue.Hidden) state.animateTo(DetailsSheetValue.Half, instant = reducedMotion)
            if (!open && state.target != DetailsSheetValue.Hidden) state.animateTo(DetailsSheetValue.Hidden, instant = reducedMotion)
        }
        viewer()
        val showing by remember(state) { derivedStateOf { state.visiblePx > 0.5f } }
        if (showing || open) {
            SystemBarsMatchSheet(state)
            DetailsSheet(state, onClose, details, maxHeight)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    state: ViewerDetailsState,
    onClose: () -> Unit,
    details: @Composable (PaddingValues) -> Unit,
    height: Dp,
) {
    val fullness = state.fullness
    val corner = lerp(28f, 0f, fullness).dp
    val statusBarTop = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    val sheetConnection = remember(state) { DetailsSheetNestedScroll(state) }
    val dragState = rememberDraggableState { delta -> state.dragBy(-delta) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = corner, topEnd = corner),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .offset { IntOffset(0, (state.fullPx - state.visiblePx).roundToInt().coerceAtLeast(0)) }
            .nestedScroll(sheetConnection),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = { state.beginDrag() },
                        onDragStopped = { velocity -> state.endDrag(-velocity) },
                    )
                    .padding(top = statusBarTop * fullness),
            ) {
                BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                DetailsHeader(onClose = onClose, modifier = Modifier.padding(start = 20.dp, end = 8.dp))
            }
            Box(Modifier.weight(1f)) {
                details(PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp + bottomInset))
            }
        }
    }
}

/** Grows the sheet before its content scrolls up, and shrinks it once the content is at the top. */
private class DetailsSheetNestedScroll(private val state: ViewerDetailsState) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y < 0f && source == NestedScrollSource.UserInput && state.visiblePx < state.fullPx) {
            val before = state.visiblePx
            state.dragBy(-available.y)
            return Offset(0f, -(state.visiblePx - before))
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y > 0f && source == NestedScrollSource.UserInput) {
            val before = state.visiblePx
            state.dragBy(-available.y)
            return Offset(0f, before - state.visiblePx)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val atStop = state.visiblePx == state.fullPx || state.visiblePx == state.halfPx
        if (atStop && available.y < 0f) return Velocity.Zero
        if (state.visiblePx < state.fullPx) {
            state.endDrag(-available.y)
            return available
        }
        return Velocity.Zero
    }
}

@Composable
private fun DetailsHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.viewer_details),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        GalleryExpressiveIconButton(onClick = onClose) {
            Icon(GalleryIcons.Close, contentDescription = stringResource(R.string.viewer_close_details))
        }
    }
}

/**
 * While the sheet covers the status bar its icons follow the sheet surface; otherwise they stay
 * light over the dark viewer. The navigation bar follows the sheet whenever it is showing.
 */
@Composable
private fun SystemBarsMatchSheet(state: ViewerDetailsState) {
    val view = LocalView.current
    val activity = LocalContext.current.findViewerActivity()
    val lightSurface = MaterialTheme.colorScheme.surfaceContainerLow.luminance() > 0.5f
    val coversStatusBar by remember(state) { derivedStateOf { state.fullness > 0.9f } }
    val showing by remember(state) { derivedStateOf { state.visiblePx > 0.5f } }
    val controller = remember(activity, view) { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }
    LaunchedEffect(controller, coversStatusBar, showing, lightSurface) {
        controller ?: return@LaunchedEffect
        controller.isAppearanceLightStatusBars = coversStatusBar && lightSurface
        controller.isAppearanceLightNavigationBars = showing && lightSurface
    }
    androidx.compose.runtime.DisposableEffect(controller) {
        onDispose {
            controller?.isAppearanceLightStatusBars = false
            controller?.isAppearanceLightNavigationBars = false
        }
    }
}
