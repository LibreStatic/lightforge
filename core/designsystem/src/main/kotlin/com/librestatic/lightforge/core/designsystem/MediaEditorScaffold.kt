package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

/**
 * Size rules shared by the photo, video, collage and GIF editors (UI review, "MediaEditorScaffold").
 *
 * The layout follows the window's width and height only, never the device orientation: a square
 * foldable gets the same arrangement whichever way it is held.
 */
object MediaEditorLayoutTokens {
    /** Width at which an editor switches to media pane plus inspector. */
    val TwoPaneEnterWidth = 800.dp

    /** A two-pane editor only stacks again below this width, so a resize near 800 dp does not flicker. */
    val TwoPaneExitWidth = 776.dp

    /** Short landscape windows (phones) split once both minimum panes fit: 440 + 300. */
    val ShortWindowTwoPaneWidth = 740.dp
    val MinMediaWidth = 440.dp
    val MinInspectorWidth = 300.dp
    val InspectorWidthMin = 360.dp
    val InspectorWidthMax = 440.dp

    /** Each side of a separating vertical hinge must be this wide to hold a pane. */
    val BookPaneMinWidth = 320.dp

    /** Each side of a separating horizontal hinge must be this tall to hold a region. */
    val TabletopRegionMinHeight = 200.dp

    /** Windows narrower than this fraction of their height are portrait-like and always stack. */
    const val PortraitAspect = 0.8f

    /** Above this width/height ratio a window is landscape-like; between the two it is "square". */
    const val LandscapeAspect = 1.25f
    const val SquareInspectorFraction = 0.4f
    const val WideInspectorFraction = 0.32f
    const val StackedMediaFractionMin = 0.2f
    const val StackedMediaFractionMax = 0.7f

    /** Media at least this wide (width/height) may stack in a square window to show it larger. */
    const val WideMediaAspect = 1.5f

    /** Stacking must show wide media at least this much larger (by area) than the media pane would. */
    const val WideMediaStackGain = 1.15f

    /** Upper bound of the initial preview share when a stacked body fits wide media to its width. */
    const val WideMediaInitialFractionMax = 0.5f
    val ResizeHandleThickness = 24.dp
}

enum class MediaEditorLayoutMode {
    /** Media on top; timeline, tools and the active tool's panel below it. */
    Stacked,

    /** Media pane (with the timeline under the preview) beside an inspector of 300–440 dp. */
    TwoPane,

    /** Half-open, horizontal hinge: media above the hinge, controls below it. */
    Tabletop,

    /** Half-open, vertical hinge: media in the start pane, inspector in the end pane. */
    Book,
}

/**
 * The resolved arrangement for an editor body.
 *
 * @property inspectorWidth preferred inspector width for [MediaEditorLayoutMode.TwoPane].
 * @property hingeStart start of a separating hinge in body coordinates (x for [MediaEditorLayoutMode.Book],
 *   y for [MediaEditorLayoutMode.Tabletop]); zero otherwise.
 */
@Immutable
data class MediaEditorLayout(
    val mode: MediaEditorLayoutMode,
    val inspectorWidth: Dp = 0.dp,
    val minInspectorWidth: Dp = 0.dp,
    val maxInspectorWidth: Dp = 0.dp,
    val hingeStart: Dp = 0.dp,
    val hingeEnd: Dp = 0.dp,
) {
    /** True when the inspector sits beside the media, so tool chips can wrap instead of scrolling. */
    val isSideBySide: Boolean
        get() = mode == MediaEditorLayoutMode.TwoPane || mode == MediaEditorLayoutMode.Book
}

/**
 * Picks the editor arrangement for a body of [width] × [height].
 *
 * @param fold a separating hinge in the body's own coordinates, if any.
 * @param previous the mode chosen for the previous size, for the 800/776 dp hysteresis.
 * @param mediaAspect width/height of the media, if known. The window still decides the mode; in a
 *   square window, wide media (16:9 video on an unfolded foldable) stacks when a full-width
 *   preview shows it clearly larger than the media pane beside an inspector would.
 */
fun mediaEditorLayout(
    width: Dp,
    height: Dp,
    fold: GalleryFoldInfo? = null,
    previous: MediaEditorLayoutMode? = null,
    mediaAspect: Float? = null,
): MediaEditorLayout {
    val tokens = MediaEditorLayoutTokens
    if (fold != null && fold.isSeparating) {
        when (fold.orientation) {
            GalleryFoldOrientation.Horizontal -> if (
                fold.top >= tokens.TabletopRegionMinHeight &&
                height - fold.bottom >= tokens.TabletopRegionMinHeight
            ) {
                return MediaEditorLayout(MediaEditorLayoutMode.Tabletop, hingeStart = fold.top, hingeEnd = fold.bottom)
            }
            GalleryFoldOrientation.Vertical -> if (
                fold.left >= tokens.BookPaneMinWidth &&
                width - fold.right >= tokens.BookPaneMinWidth
            ) {
                return MediaEditorLayout(MediaEditorLayoutMode.Book, hingeStart = fold.left, hingeEnd = fold.right)
            }
        }
    }
    if (height <= 0.dp || width <= 0.dp) return MediaEditorLayout(MediaEditorLayoutMode.Stacked)
    val aspect = width / height
    if (aspect < tokens.PortraitAspect) return MediaEditorLayout(MediaEditorLayoutMode.Stacked)
    val enterWidth = if (previous == MediaEditorLayoutMode.TwoPane) tokens.TwoPaneExitWidth else tokens.TwoPaneEnterWidth
    val twoPane = width >= enterWidth ||
        (aspect > tokens.LandscapeAspect && width >= tokens.ShortWindowTwoPaneWidth)
    if (!twoPane) return MediaEditorLayout(MediaEditorLayoutMode.Stacked)
    val square = aspect <= tokens.LandscapeAspect
    val maxInspector = max(min(tokens.InspectorWidthMax, width - tokens.MinMediaWidth), tokens.MinInspectorWidth)
    val minInspector = min(tokens.InspectorWidthMin, maxInspector)
    val preferred = width * if (square) tokens.SquareInspectorFraction else tokens.WideInspectorFraction
    val inspectorWidth = preferred.coerceIn(minInspector, maxInspector)
    if (square && mediaAspect != null && mediaAspect >= tokens.WideMediaAspect) {
        val besideArea = fittedArea(width - inspectorWidth, height, mediaAspect)
        val stackedArea = fittedArea(width, height * tokens.WideMediaInitialFractionMax, mediaAspect)
        if (stackedArea >= besideArea * tokens.WideMediaStackGain) {
            return MediaEditorLayout(MediaEditorLayoutMode.Stacked)
        }
    }
    return MediaEditorLayout(
        mode = MediaEditorLayoutMode.TwoPane,
        inspectorWidth = inspectorWidth,
        minInspectorWidth = minInspector,
        maxInspectorWidth = maxInspector,
    )
}

/** Area (dp²) of media with [aspect] fitted inside [width] × [height]. */
private fun fittedArea(width: Dp, height: Dp, aspect: Float): Float {
    val w = minOf(width.value, height.value * aspect).coerceAtLeast(0f)
    return w * (w / aspect)
}

/**
 * Initial preview share of a resizable stacked body: the height that fits [mediaAspect] at full
 * width, so wide media has no side bars, or [fallback] when the aspect is unknown.
 */
internal fun initialStackedMediaFraction(bodyWidth: Dp, resizableHeight: Dp, mediaAspect: Float?, fallback: Float): Float {
    val tokens = MediaEditorLayoutTokens
    if (mediaAspect == null || mediaAspect <= 0f || resizableHeight <= 0.dp) return fallback
    return (bodyWidth.value / mediaAspect / resizableHeight.value)
        .coerceIn(tokens.StackedMediaFractionMin, tokens.WideMediaInitialFractionMax)
}

/** Moves a window-coordinate [fold] into a body placed at [origin] (dp) and sized [width] × [height]. */
fun GalleryFoldInfo.toLocal(originX: Dp, originY: Dp, width: Dp, height: Dp): GalleryFoldInfo {
    val localLeft = (left - originX).coerceIn(0.dp, width)
    val localTop = (top - originY).coerceIn(0.dp, height)
    return copy(
        left = localLeft,
        right = (right - originX).coerceIn(localLeft, width),
        top = localTop,
        bottom = (bottom - originY).coerceIn(localTop, height),
    )
}

/**
 * One adaptive frame for every media editor.
 *
 * The body is padded by the safe-drawing insets that the [topBar] does not already cover, so
 * bottom actions never sit under the navigation bar or a desktop taskbar. Regions:
 * - [media]: the preview (draw it with `ContentScale.Fit`).
 * - [mediaSupport]: transport and timeline; under the preview inside the media pane when side by
 *   side, between media and inspector when stacked.
 * - [inspector]: tool chips and the active tool's panel; it owns its own scrolling.
 *
 * @param resizeDescription when set, a draggable, accessible handle lets people resize the panes.
 * @param stackedInspectorWeight weight of the inspector in a stacked body; null lets it wrap its
 *   content up to [stackedInspectorMaxFraction] of the height.
 * @param mediaAspect width/height of the media, if known; see [mediaEditorLayout].
 */
@Composable
fun MediaEditorScaffold(
    topBar: @Composable (MediaEditorLayout) -> Unit,
    media: @Composable (Modifier) -> Unit,
    inspector: @Composable (Modifier, MediaEditorLayout) -> Unit,
    modifier: Modifier = Modifier,
    mediaSupport: (@Composable (Modifier, MediaEditorLayout) -> Unit)? = null,
    foldInfo: GalleryFoldInfo? = null,
    stackedMediaWeight: Float = 1f,
    stackedInspectorWeight: Float? = 1f,
    stackedInspectorMaxFraction: Float = 0.5f,
    resizeDescription: String? = null,
    bodyWindowInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    mediaAspect: Float? = null,
) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bodyOrigin by remember { mutableStateOf(Offset.Zero) }
    var previousMode by remember { mutableStateOf<MediaEditorLayoutMode?>(null) }
    var inspectorWidthOverride by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var stackedMediaFraction by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The mode follows the whole editor window, before the top bar takes its height, so the
            // same window always gets the same arrangement.
            val windowFold = foldInfo?.takeIf(GalleryFoldInfo::isSeparating)?.let { fold ->
                with(density) { fold.toLocal(origin.x.toDp(), origin.y.toDp(), maxWidth, maxHeight) }
            }
            val windowLayout = mediaEditorLayout(maxWidth, maxHeight, windowFold, previousMode, mediaAspect)
            SideEffect { previousMode = windowLayout.mode }
            Column(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { origin = it.positionInWindow() },
            ) {
                topBar(windowLayout)
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(bodyWindowInsets)
                        .onGloballyPositioned { bodyOrigin = it.positionInWindow() },
                ) {
                    val bodyHeight = maxHeight
                    val bodyWidth = maxWidth
                    // Hinge positions move into body coordinates: the top bar and insets shift them.
                    val bodyFold = foldInfo?.takeIf(GalleryFoldInfo::isSeparating)?.let { fold ->
                        with(density) { fold.toLocal(bodyOrigin.x.toDp(), bodyOrigin.y.toDp(), maxWidth, maxHeight) }
                    }
                    val layout = when (windowLayout.mode) {
                        MediaEditorLayoutMode.Tabletop -> windowLayout.copy(
                            hingeStart = bodyFold?.top ?: windowLayout.hingeStart,
                            hingeEnd = bodyFold?.bottom ?: windowLayout.hingeEnd,
                        )
                        MediaEditorLayoutMode.Book -> windowLayout.copy(
                            hingeStart = bodyFold?.left ?: windowLayout.hingeStart,
                            hingeEnd = bodyFold?.right ?: windowLayout.hingeEnd,
                        )
                        else -> windowLayout
                    }
                    val mediaPane: @Composable (Modifier) -> Unit = { paneModifier ->
                        Column(paneModifier.semantics { isTraversalGroup = true; traversalIndex = 0f }) {
                            media(Modifier.fillMaxWidth().weight(1f))
                            mediaSupport?.invoke(Modifier.fillMaxWidth(), layout)
                        }
                    }
                    val inspectorPane: @Composable (Modifier) -> Unit = { paneModifier ->
                        inspector(
                            paneModifier.semantics { isTraversalGroup = true; traversalIndex = 1f },
                            layout,
                        )
                    }
                    when (layout.mode) {
                        MediaEditorLayoutMode.TwoPane -> {
                            val width = inspectorWidthOverride
                                .takeUnless(Float::isNaN)
                                ?.let { it.dp.coerceIn(layout.minInspectorWidth, layout.maxInspectorWidth) }
                                ?: layout.inspectorWidth
                            Row(Modifier.fillMaxSize()) {
                                mediaPane(Modifier.weight(1f).fillMaxHeight())
                                if (resizeDescription != null && layout.maxInspectorWidth > layout.minInspectorWidth) {
                                    val range = layout.minInspectorWidth.value..layout.maxInspectorWidth.value
                                    MediaEditorResizeHandle(
                                        description = resizeDescription,
                                        orientation = Orientation.Horizontal,
                                        // Reported as the media share so "increase" grows the preview.
                                        progress = 1f - (width.value - range.start) / (range.endInclusive - range.start),
                                        onDragDelta = { deltaPx ->
                                            val next = width.value - with(density) { deltaPx.toDp().value }
                                            inspectorWidthOverride = next.coerceIn(range)
                                        },
                                        onProgressChange = { progress ->
                                            inspectorWidthOverride =
                                                range.endInclusive - progress * (range.endInclusive - range.start)
                                        },
                                        modifier = Modifier.width(MediaEditorLayoutTokens.ResizeHandleThickness).fillMaxHeight(),
                                    )
                                }
                                inspectorPane(Modifier.width(width).fillMaxHeight())
                            }
                        }
                        MediaEditorLayoutMode.Book -> Row(Modifier.fillMaxSize()) {
                            mediaPane(Modifier.width(layout.hingeStart).fillMaxHeight())
                            Spacer(Modifier.width(layout.hingeEnd - layout.hingeStart).fillMaxHeight())
                            inspectorPane(Modifier.weight(1f).fillMaxHeight())
                        }
                        MediaEditorLayoutMode.Tabletop -> Column(Modifier.fillMaxSize()) {
                            media(
                                Modifier
                                    .fillMaxWidth()
                                    .height(layout.hingeStart)
                                    .semantics { isTraversalGroup = true; traversalIndex = 0f },
                            )
                            Spacer(Modifier.fillMaxWidth().height(layout.hingeEnd - layout.hingeStart))
                            mediaSupport?.invoke(Modifier.fillMaxWidth(), layout)
                            inspectorPane(Modifier.fillMaxWidth().weight(1f))
                        }
                        MediaEditorLayoutMode.Stacked -> Column(Modifier.fillMaxSize()) {
                            val mediaSemantics = Modifier.semantics { isTraversalGroup = true; traversalIndex = 0f }
                            // Portrait bodies keep a fixed split; a short, wide body lets people trade
                            // preview height for controls.
                            val resizableStack = resizeDescription != null && bodyWidth > bodyHeight
                            if (resizableStack) {
                                val tokens = MediaEditorLayoutTokens
                                val resizable = (bodyHeight - tokens.ResizeHandleThickness).coerceAtLeast(1.dp)
                                val fraction = stackedMediaFraction.takeUnless(Float::isNaN)
                                    ?: initialStackedMediaFraction(
                                        bodyWidth = bodyWidth,
                                        resizableHeight = resizable,
                                        mediaAspect = mediaAspect,
                                        fallback = (stackedMediaWeight / (stackedMediaWeight + (stackedInspectorWeight ?: 1f)))
                                            .coerceIn(tokens.StackedMediaFractionMin, tokens.StackedMediaFractionMax),
                                    )
                                val span = tokens.StackedMediaFractionMax - tokens.StackedMediaFractionMin
                                media(Modifier.fillMaxWidth().height(resizable * fraction).then(mediaSemantics))
                                MediaEditorResizeHandle(
                                    description = resizeDescription,
                                    orientation = Orientation.Vertical,
                                    progress = (fraction - tokens.StackedMediaFractionMin) / span,
                                    onDragDelta = { deltaPx ->
                                        val current = stackedMediaFraction.takeUnless(Float::isNaN) ?: fraction
                                        stackedMediaFraction = (current + with(density) { deltaPx.toDp() } / resizable)
                                            .coerceIn(tokens.StackedMediaFractionMin, tokens.StackedMediaFractionMax)
                                    },
                                    onProgressChange = { progress ->
                                        stackedMediaFraction = tokens.StackedMediaFractionMin + progress * span
                                    },
                                    modifier = Modifier.fillMaxWidth().height(tokens.ResizeHandleThickness),
                                )
                            } else {
                                media(Modifier.fillMaxWidth().weight(stackedMediaWeight).then(mediaSemantics))
                            }
                            mediaSupport?.invoke(Modifier.fillMaxWidth(), layout)
                            val inspectorModifier = when {
                                resizableStack -> Modifier.fillMaxWidth().weight(1f)
                                stackedInspectorWeight != null -> Modifier.fillMaxWidth().weight(stackedInspectorWeight)
                                else -> Modifier.fillMaxWidth().heightIn(max = bodyHeight * stackedInspectorMaxFraction)
                            }
                            inspectorPane(inspectorModifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaEditorResizeHandle(
    description: String,
    orientation: Orientation,
    progress: Float,
    onDragDelta: (Float) -> Unit,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragState = rememberDraggableState(onDelta = onDragDelta)
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .draggable(dragState, orientation)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
                setProgress { requested ->
                    onProgressChange(requested.coerceIn(0f, 1f))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (orientation == Orientation.Vertical) Modifier.width(40.dp).height(4.dp) else Modifier.width(4.dp).height(40.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.shapes.extraSmall),
        )
    }
}

/** Undo/redo for [MediaEditorTopBar]; labels come from the editor's own strings. */
@Immutable
data class MediaEditorHistory(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val undoLabel: String,
    val redoLabel: String,
    val undoTestTag: String = "media-editor-undo",
    val redoTestTag: String = "media-editor-redo",
)

/**
 * The common editor top bar: cancel, title, undo/redo and the primary action
 * ("Save copy", "Export").
 *
 * Undo and redo are tonal buttons on the `secondaryContainer`/`onSecondaryContainer` pair. When
 * unavailable they keep a readable `surfaceContainerHighest`/`onSurfaceVariant` pair instead of a
 * faded icon, so the state reads from the container while the glyph stays legible (bug 29).
 */
@Composable
fun MediaEditorTopBar(
    title: String,
    onCancel: () -> Unit,
    cancelLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    history: MediaEditorHistory? = null,
    actionTestTag: String? = null,
    windowInsets: WindowInsets = LocalGalleryTopBarWindowInsets.current ?: TopAppBarDefaults.windowInsets,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .heightIn(min = 64.dp)
                .padding(horizontal = GallerySpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) {
            GalleryExpressiveIconButton(onClick = onCancel, modifier = Modifier.testTag("media-editor-cancel")) {
                Icon(GalleryIcons.Close, contentDescription = cancelLabel)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            history?.let { MediaEditorHistoryButtons(it) }
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .padding(start = GallerySpacing.Xs, end = GallerySpacing.Sm)
                    .then(actionTestTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Text(actionLabel, maxLines = 1)
            }
        }
    }
}

@Composable
fun MediaEditorHistoryButtons(history: MediaEditorHistory, modifier: Modifier = Modifier) {
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        FilledTonalIconButton(
            onClick = history.onUndo,
            enabled = history.canUndo,
            colors = colors,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.testTag(history.undoTestTag),
        ) { Icon(GalleryIcons.Undo, contentDescription = history.undoLabel) }
        FilledTonalIconButton(
            onClick = history.onRedo,
            enabled = history.canRedo,
            colors = colors,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.testTag(history.redoTestTag),
        ) { Icon(GalleryIcons.Redo, contentDescription = history.redoLabel) }
    }
}

/** A tool entry for [MediaEditorToolChips]. */
@Immutable
data class MediaEditorToolChip(
    val key: String,
    val label: String,
    val icon: ImageVector? = null,
    val testTag: String? = null,
)

/**
 * Tool chips: wrapped on as many rows as needed beside the media ([wrap]), or one scrolling row
 * with end padding in a stacked layout so the last chip is never flush against the edge.
 */
@Composable
fun MediaEditorToolChips(
    tools: List<MediaEditorToolChip>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    wrap: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = GallerySpacing.Md),
) {
    val chip: @Composable (MediaEditorToolChip) -> Unit = { tool ->
        FilterChip(
            selected = tool.key == selectedKey,
            onClick = { onSelect(tool.key) },
            label = { Text(tool.label, maxLines = 1) },
            leadingIcon = tool.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .then(tool.testTag?.let { Modifier.testTag(it) } ?: Modifier),
        )
    }
    if (wrap) {
        FlowRow(
            modifier = modifier.fillMaxWidth().padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
        ) { tools.forEach { chip(it) } }
    } else {
        LazyRow(
            modifier = modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        ) { items(tools, key = { it.key }) { chip(it) } }
    }
}
