package com.librestatic.lightforge.feature.pdfstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.AbsoluteAlignment
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.delay
import com.librestatic.lightforge.core.designsystem.GalleryCircularProgressIndicator

@Composable
internal fun PdfBitmap(
    file: File?,
    rotation: Int,
    fit: PdfFit,
    focusX: Double,
    focusY: Double,
    modifier: Modifier = Modifier,
    maxSide: Int = 1024,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retry by remember(file?.path, rotation, maxSide) { mutableIntStateOf(0) }
    var failure by remember(file?.path, rotation, maxSide) { mutableStateOf<Int?>(null) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(null, file?.path, rotation, maxSide, retry) {
            value = null
            failure = null
            try {
                value =
                    file?.let {
                        PdfBitmapStore.get(context).load(it, rotation, maxSide, immutable = true)
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = previewError(e)
            }
        }
    failure?.let { PdfPreviewProblem(it, maxSide > 192) { retry++ } }
    // Compose owns the displayed bitmap until this image leaves composition; GC releases its
    // allocation.
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = if (fit == PdfFit.Cover) ContentScale.Crop else ContentScale.Fit,
            alignment =
                androidx.compose.ui.BiasAlignment(
                    (focusX * 2 - 1).toFloat(),
                    (focusY * 2 - 1).toFloat(),
                ),
            modifier = modifier,
        )
    }
}

internal fun previewError(error: Exception): Int =
    PdfFailure.from(error).let {
        if (it == PdfFailure.Unknown) R.string.pdf_preview_error else it.message
    }

@Composable
internal fun PdfPreviewProblem(error: Int, retryable: Boolean, retry: () -> Unit) {
    Column {
        Text(stringResource(error), color = PdfPaperTokens.Ink)
        if (retryable)
            TextButton(
                onClick = retry,
                colors = ButtonDefaults.textButtonColors(contentColor = PdfPaperTokens.Ink),
            ) {
                Text(stringResource(R.string.pdf_retry))
            }
    }
}

@Composable
internal fun PdfPageBitmap(page: PdfPage, vm: PdfStudioViewModel, side: Int, modifier: Modifier) {
    var retry by
        remember(page.source, page.sourcePage, page.rotation, side) { mutableIntStateOf(0) }
    var failure by
        remember(page.source, page.sourcePage, page.rotation, side) { mutableStateOf<Int?>(null) }
    val bitmap by
        produceState<android.graphics.Bitmap?>(
            null,
            page.source,
            page.sourcePage,
            page.rotation,
            side,
            retry,
        ) {
            value = null
            failure = null
            try {
                value = vm.repository.previewBitmap(page, side)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = previewError(e)
            }
        }
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
    failure?.let { PdfPreviewProblem(it, side > 192) { retry++ } }
}

/** The delivery's photos, with the one that was rejected badged so the user can find it. */
@Composable
internal fun GallerySourceStrip(delivery: PdfGalleryDelivery) {
    val failed = delivery.failedSource() ?: return
    val sources = remember(delivery.uris) { delivery.sources() }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(sources.size) { index ->
            val number = index + 1
            val rejected = number == failed
            val description =
                if (rejected) stringResource(R.string.pdf_gallery_source_rejected, number)
                else stringResource(R.string.pdf_gallery_source, number)
            val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
            val uri = sources[index]
            val bitmap by
                produceState<android.graphics.Bitmap?>(null, uri) {
                    value =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                    resolver.loadThumbnail(uri, android.util.Size(144, 144), null)
                                }
                                .getOrNull()
                        }
                }
            Box(
                Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics { contentDescription = description }
            ) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (rejected) Badge(Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
    }
}

/** Ordered thumbnail strip for the non-blocking intake card (Phase E item 5): each source shows
 * done / in-progress state as [copied] advances. Copied items get a check mark, the one actively
 * being copied gets a small progress ring, and the rest are dimmed as pending. */
@Composable
internal fun PdfIntakeStrip(delivery: PdfGalleryDelivery, copied: Int) {
    val sources = remember(delivery.uris) { delivery.sources() }
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(sources.size) { index ->
            val number = index + 1
            val done = number <= copied
            val inProgress = number == copied + 1
            val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
            val uri = sources[index]
            val bitmap by
                produceState<android.graphics.Bitmap?>(null, uri) {
                    value =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                    resolver.loadThumbnail(uri, android.util.Size(144, 144), null)
                                }
                                .getOrNull()
                        }
                }
            val statusLabel =
                stringResource(
                    when {
                        done -> R.string.pdf_intake_item_done
                        inProgress -> R.string.pdf_intake_item_inprogress
                        else -> R.string.pdf_gallery_source
                    }
                )
            val description = if (!done && !inProgress) stringResource(R.string.pdf_gallery_source, number) else "$number: $statusLabel"
            Box(
                Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clearAndSetSemantics { contentDescription = description }
            ) {
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        modifier =
                            Modifier.fillMaxSize().let { m -> if (!done && !inProgress) m.alpha(0.4f) else m },
                        contentScale = ContentScale.Crop,
                    )
                }
                when {
                    done ->
                        Icon(
                            GalleryIcons.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).size(16.dp),
                        )
                    inProgress ->
                        GalleryCircularProgressIndicator(
                            Modifier.align(Alignment.BottomEnd).size(16.dp),
                            strokeWidth = 2.dp,
                        )
                }
            }
        }
    }
}

@Composable
internal fun PageThumbnail(page: PdfPage, vm: PdfStudioViewModel, modifier: Modifier) {
    BoxWithConstraints(
        modifier.background(PdfPaperTokens.Paper).clipToBounds(),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val width = maxWidth
        val height = maxHeight
        if (page.source != null) {
            PdfPageBitmap(page, vm, 192, Modifier.fillMaxSize())
        } else
            // Paint in stacking order (PdfLayers.order), like the canvas and the exported PDF —
            // not in list order, which ignores Layer forward/backward/front/back changes.
            PdfLayers.order(page).filterIsInstance<PdfLayers.Element.Img>().map { it.image }.forEach { i ->
                PdfBitmap(
                    vm.repository.file(i.asset),
                    i.rotation,
                    i.fit,
                    i.focusX,
                    i.focusY,
                    Modifier.absoluteOffset(
                            width * (i.x / page.width).toFloat(),
                            height * (i.y / page.height).toFloat(),
                        )
                        .size(
                            width * (i.width / page.width).toFloat(),
                            height * (i.height / page.height).toFloat(),
                        ),
                    PdfPreviewPolicy.side(page.images.size, thumbnail = true),
                )
            }
    }
}

/** Fixed-height band reserved at the bottom of the canvas workspace for the page/zoom badges (or
 * the contextual toolbar in their place) — shared by [PdfCanvas] and the ruler wrapper
 * ([PdfCanvasWithRulers]) in `PdfRulers.kt` so both agree on exactly how tall the page box is. */
internal val PdfCanvasBadgeBandHeight = 64.dp

/** The page box's on-screen size (before zoom/pan) for a canvas laid out in `maxWidth` x
 * `maxHeight`: fit within the available space above [PdfCanvasBadgeBandHeight], preserving the
 * page's aspect ratio, never smaller than 80dp wide. Pulled out of [PdfCanvas] so the ruler
 * wrapper can compute the exact same page box without duplicating (and risking drifting from) the
 * formula. */
internal fun pdfCanvasPageBoxSize(
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    page: PdfPage,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val width =
        minOf(
                maxWidth - 24.dp,
                (maxHeight - 24.dp - PdfCanvasBadgeBandHeight) * (page.width / page.height).toFloat(),
            )
            .coerceAtLeast(80.dp)
    val height = width * (page.height / page.width).toFloat()
    return width to height
}

private val ZOOM_LEVELS = listOf(.5f, 1f, 2f)

/** Maps the subset of [androidx.compose.ui.input.key.Key] the editor's shortcuts care about to
 * the plain string labels [PdfEditorCommands] matches against, so that mapping itself stays a
 * pure, JVM-testable function independent of Compose's Key type. */
private fun keyChordLabel(key: androidx.compose.ui.input.key.Key): String? =
    when (key) {
        androidx.compose.ui.input.key.Key.Z -> "Z"
        androidx.compose.ui.input.key.Key.D -> "D"
        androidx.compose.ui.input.key.Key.E -> "E"
        androidx.compose.ui.input.key.Key.Zero -> "0"
        androidx.compose.ui.input.key.Key.Equals -> "="
        androidx.compose.ui.input.key.Key.Minus -> "-"
        androidx.compose.ui.input.key.Key.NumPadAdd -> "NumPadAdd"
        androidx.compose.ui.input.key.Key.NumPadSubtract -> "NumPadSubtract"
        androidx.compose.ui.input.key.Key.Slash -> "/"
        androidx.compose.ui.input.key.Key.Delete -> "Delete"
        androidx.compose.ui.input.key.Key.Backspace -> "Backspace"
        else -> null
    }

@Composable
internal fun PdfCanvas(
    page: PdfPage,
    selected: Int,
    vm: PdfStudioViewModel,
    modifier: Modifier,
    busy: Boolean,
    pageIndex: Int = 0,
    pageCount: Int = 1,
    onAdjustImage: () -> Unit,
    onReplaceImage: () -> Unit = {},
    commands: PdfEditorCommandDispatcher? = null,
) {
    val current by vm.state.collectAsStateWithLifecycle()
    val viewport by rememberUpdatedState(current)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val canvasLabel = stringResource(R.string.pdf_canvas_label)
    // The text box currently in inline-editing mode (Phase G1b), or null. Local to the canvas
    // (not VM state) since it's pure UI/IME state, never persisted or undoable. Opening the
    // editor for a freshly-added text (vm.state.newTextId) is handled right below.
    var editingTextId by remember(page.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(current.newTextId) {
        current.newTextId?.let { id ->
            editingTextId = id
            vm.newTextOpened()
        }
    }
    // Bug fix (gesture arbitration): an explicit, single source of truth for "some element's own
    // drag/resize is in progress right now", read by the ancestor's detectTransformGestures below.
    // Compose's automatic pointer-event consumption (an inner pointerInput's change.consume() is
    // supposed to make an ancestor's own gesture detector see the change as already consumed and
    // stop) is fragile here: this subtree stacks combinedClickable (tap/long-press), a plain drag,
    // a long-press marquee drag, and two more independent drag detectors per selected element
    // (the bottom-right resize handle and the three PdfCornerHandle instances) all on nodes at
    // different depths, and the ancestor's detectTransformGestures itself starts on every down
    // (`requireUnconsumed = false`) before any child has had a chance to recognize its own
    // gesture. Reported symptom: dragging an image also pans/moves the whole page. This flag is
    // flipped true the moment ANY element drag/resize starts and false when it ends/cancels, and
    // the pan/zoom gesture below skips entirely while it's true — independent of and in addition
    // to each drag's own change.consume() call.
    val elementDragActiveState = remember(page.id) { mutableStateOf(false) }
    Surface(
        modifier.semantics { contentDescription = canvasLabel },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .clipToBounds()
                .onGloballyPositioned { PdfCanvasProbe.canvasCoordinates = it }
                .focusRequester(focus)
                .pointerInput(page.id, busy, editingTextId) {
                    if (!busy)
                        detectTapGestures(
                            // Item 3: "outside tap commits" — a tap on the page background
                            // (anywhere not already consumed by the editing text's own Box)
                            // while inline editing clears focus, which the BasicTextField's
                            // onFocusChanged then turns into a commit attempt.
                            onTap = { if (editingTextId != null) focusManager.clearFocus() },
                            onDoubleTap = { vm.viewport(1f, 0f, 0f) },
                        )
                }
                .onPreviewKeyEvent { event ->
                    // Item 3: every shortcut here (undo/duplicate/delete/nudge/...) is suppressed
                    // while the inline text editor has focus, so its own IME/cursor keys work
                    // normally instead of being intercepted by this tunneling handler first.
                    if (
                        busy ||
                            editingTextId != null ||
                            event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown
                    )
                        false
                    else {
                        val chordKey = keyChordLabel(event.key)
                        val command =
                            if (chordKey != null)
                                PdfEditorCommands.forChord(
                                    PdfKeyChord(chordKey, event.isCtrlPressed, event.isShiftPressed)
                                )
                                    ?: (if (!event.isCtrlPressed) PdfEditorCommands.forDeleteKey(chordKey)
                                        else null)
                            else null
                        if (command != null && commands != null) {
                            commands.dispatch(command)
                            true
                        } else {
                            // Arrow-key nudge (Phase F2 item C): always 1 mm, Shift+arrow 10 mm,
                            // regardless of the project's snap-to-grid setting — snap only governs
                            // pointer drag, not the keyboard's fine-grained nudge.
                            val step = if (event.isShiftPressed) 10.0 else 1.0
                            when (event.key) {
                                // Phase G2: a group nudges together (moveSelectionBy falls back to
                                // the single-element moveSelected itself when fewer than 2 are
                                // selected, so this one call covers both cases).
                                androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                    vm.moveSelectionBy(-step, 0.0)
                                    true
                                }
                                androidx.compose.ui.input.key.Key.DirectionRight -> {
                                    vm.moveSelectionBy(step, 0.0)
                                    true
                                }
                                androidx.compose.ui.input.key.Key.DirectionUp -> {
                                    vm.moveSelectionBy(0.0, -step)
                                    true
                                }
                                androidx.compose.ui.input.key.Key.DirectionDown -> {
                                    vm.moveSelectionBy(0.0, step)
                                    true
                                }
                                // Escape (Phase G2): exits a multi-select session (a no-op
                                // otherwise, per exitMultiSelect's own guard).
                                androidx.compose.ui.input.key.Key.Escape -> {
                                    vm.exitMultiSelect()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                }
                .focusable()
                .pointerInput(page.id, busy, density) {
                    if (!busy)
                        detectTransformGestures { _, offset, scale, _ ->
                            // Bug fix (gesture arbitration): never pan/zoom the whole page while an
                            // element's own drag or resize is in progress (elementDragActiveState,
                            // set by PdfImageElement/PdfTextElement/PdfCornerHandle below) — see the
                            // state's own doc comment above for why relying on consume() alone was
                            // not enough.
                            if (!elementDragActiveState.value)
                                vm.viewport(
                                    viewport.zoom * scale,
                                    viewport.panX + offset.x / density.density,
                                    viewport.panY + offset.y / density.density,
                                )
                        }
                },
            contentAlignment = Alignment.Center,
        ) {
            // A fixed-height band reserved at the bottom of the workspace — never over the paper
            // — for the page/zoom badges (or, while an image is selected, the contextual toolbar
            // in their place). Reserved unconditionally so switching selection never changes the
            // page's own fit size (no jump).
            val badgeBandHeight = PdfCanvasBadgeBandHeight
            val (width, height) = pdfCanvasPageBoxSize(maxWidth, maxHeight, page)
            // Bug fix (fit/viewport): a per-page viewport (Phase C item 6) persists zoom/pan
            // across sessions AND across layout changes (compact <-> expanded three-pane <->
            // hinge-split/tabletop). Those pan values are stored in dp relative to the canvas size
            // they were captured in; restoring them verbatim into a canvas of a DIFFERENT size (a
            // fold/rotation, or simply opening the project on a different window class) can leave
            // the page mostly or entirely off-screen — reported: the page clipped off the right
            // edge and the vertical ruler starting around -120 in the expanded three-pane layout.
            // Whenever the available canvas size changes (or on first composition), clamp the
            // current pan back to a range that keeps the page's center within the visible canvas,
            // so "Fit" (zoom=1/pan=0, already correct since the page box itself is sized to fit
            // maxWidth x maxHeight and centered) is never fought by a stale out-of-range pan.
            LaunchedEffect(page.id, maxWidth, maxHeight) {
                val maxPanXDp = (maxWidth / 2).value
                val maxPanYDp = (maxHeight / 2).value
                val snapshot = viewport
                val clampedX = snapshot.panX.coerceIn(-maxPanXDp, maxPanXDp)
                val clampedY = snapshot.panY.coerceIn(-maxPanYDp, maxPanYDp)
                if (clampedX != snapshot.panX || clampedY != snapshot.panY)
                    vm.viewport(snapshot.zoom, clampedX, clampedY)
            }
            // Shared across every image/text below: only one drag is ever active at a time, so a
            // single pair of hoisted states is enough to draw the guide overlay/chip for whichever
            // element is currently moving; hoisted here (not inside the elements branch) so the
            // overlay and badges drawn as siblings of the page box below can still read them. Kept
            // as explicit MutableState objects (not just `by remember` locals) so the same
            // instances can also be threaded into PdfImageElement/PdfTextElement below.
            val activeSnapState =
                remember(page.id) { mutableStateOf<PdfSnapGuides.SnapResult?>(null) }
            var activeSnap by activeSnapState
            val dragOffsetMmState =
                remember(page.id) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var dragOffsetMm by dragOffsetMmState
            // Fix-round item 1: the live pixel delta shared by every member of a 2+ group drag —
            // hoisted the same way as activeSnapState/dragOffsetMmState above, so whichever
            // member's pointer is actually moving updates the ONE state every member's own
            // graphicsLayer reads.
            val groupDragDeltaState =
                remember(page.id) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            // Recomputed on every recomposition (selection/page content changes) rather than
            // memoized: pages here top out at 24 elements, so this is cheap, and memoizing against
            // the right keys (selectedIds AND every member's own x/y/width/height) would be more
            // failure-prone than just recomputing.
            val groupDrag: PdfGroupDragContext? =
                if (current.groupSelected) {
                    val ids = current.selectedIds
                    PdfGroupDragContext(
                        memberBounds =
                            page.images.filter { it.id in ids }.map(PdfArrange::boundsOf) +
                                page.texts.filter { it.id in ids }.map(PdfArrange::boundsOf),
                        nonMemberImages = page.images.filterNot { it.id in ids },
                        nonMemberTexts = page.texts.filterNot { it.id in ids },
                        delta = groupDragDeltaState,
                    )
                } else null
            // Phase F item 3: the page box's own coordinates, captured so the drag & drop target
            // below can convert a drop's root-space position into this box's local (unscaled)
            // space regardless of the current zoom/pan graphicsLayer - windowToLocal accounts for
            // the full transform chain, matching how the pointer-drag handlers below already
            // treat local offsets as page pixels (pxPerMm).
            var pageBoxCoordinates by remember(page.id) { mutableStateOf<LayoutCoordinates?>(null) }
            val pxPerMmForDrop = with(density) { width.toPx() } / page.width
            // Marquee drag-select (Phase G2 item 2): a long-press + drag on EMPTY page area (an
            // element under the finger/pointer consumes its own long-press first via
            // PdfImageElement/PdfTextElement's combinedClickable, so this never fires over one)
            // selects every element whose bounds intersect the dragged rectangle. Long-press
            // rather than a plain drag, so it never fights the existing single-finger pan gesture
            // ([detectTransformGestures] below, which already owns plain drags on this same Box).
            var marqueeStart by remember(page.id) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            var marqueeEnd by remember(page.id) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
            // Physical print preview uses explicit white paper / black ink, a 21:1 contrast pair.
            // Shifted up by half the reserved badge band so the page is centered in the space
            // actually available above that band, not in the full (band-including) workspace.
            Box(
                Modifier.offset(y = -(badgeBandHeight / 2))
                    .size(width, height)
                    .graphicsLayer {
                        scaleX = current.zoom
                        scaleY = current.zoom
                        translationX = current.panX * density.density
                        translationY = current.panY * density.density
                    }
                    // A soft elevation shadow separates the paper from the surfaceContainer
                    // workspace behind it; the page keeps its rectangular clip afterward.
                    .shadow(elevation = 6.dp, shape = androidx.compose.ui.graphics.RectangleShape, clip = false)
                    .background(PdfPaperTokens.Paper)
                    .onGloballyPositioned {
                        pageBoxCoordinates = it
                        PdfCanvasProbe.pageBoxCoordinates = it
                    }
                    .pointerInput(page.id, busy) {
                        if (!busy)
                            detectTapGestures(
                                // Tap on empty page area (Phase G2): exits a multi-select session.
                                // Only reached when no element under the tap already consumed it.
                                onTap = { if (current.multiSelectMode) vm.exitMultiSelect() }
                            )
                    }
                    .pointerInput(page.id, busy) {
                        if (!busy)
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    marqueeStart = offset
                                    marqueeEnd = offset
                                },
                                onDragEnd = {
                                    val start = marqueeStart
                                    val end = marqueeEnd
                                    if (start != null && end != null) {
                                        val minXmm = minOf(start.x, end.x) / pxPerMmForDrop
                                        val maxXmm = maxOf(start.x, end.x) / pxPerMmForDrop
                                        val minYmm = minOf(start.y, end.y) / pxPerMmForDrop
                                        val maxYmm = maxOf(start.y, end.y) / pxPerMmForDrop
                                        val hitIds =
                                            (page.images.filter { i ->
                                                i.x < maxXmm &&
                                                    i.x + i.width > minXmm &&
                                                    i.y < maxYmm &&
                                                    i.y + i.height > minYmm
                                            }.map { it.id } +
                                                page.texts.filter { t ->
                                                    t.x < maxXmm &&
                                                        t.x + t.width > minXmm &&
                                                        t.y < maxYmm &&
                                                        t.y + t.height > minYmm
                                                }.map { it.id })
                                                .toSet()
                                        if (hitIds.isNotEmpty()) vm.setMultiSelection(hitIds)
                                    }
                                    marqueeStart = null
                                    marqueeEnd = null
                                },
                                onDragCancel = {
                                    marqueeStart = null
                                    marqueeEnd = null
                                },
                            ) { change, _ ->
                                change.consume()
                                marqueeEnd = change.position
                            }
                    }
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { !busy && page.source == null },
                        target =
                            remember(page.id) {
                                object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                                    override fun onDrop(
                                        event: androidx.compose.ui.draganddrop.DragAndDropEvent
                                    ): Boolean {
                                        val uri = pdfMediaDropUri(event) ?: return false
                                        val coords = pageBoxCoordinates ?: return false
                                        // The raw Android DragEvent's x/y are window-relative (the
                                        // ComposeView fills the window here), so windowToLocal
                                        // converts straight to this box's own space, correctly
                                        // accounting for the zoom/pan graphicsLayer above.
                                        val dragEvent = event.toAndroidDragEvent()
                                        val windowOffset =
                                            androidx.compose.ui.geometry.Offset(dragEvent.x, dragEvent.y)
                                        val local = coords.windowToLocal(windowOffset)
                                        vm.insertMedia(
                                            uri,
                                            local.x / pxPerMmForDrop,
                                            local.y / pxPerMmForDrop,
                                        )
                                        return true
                                    }
                                }
                            },
                    )
                    .clipToBounds(),
                // PDF coordinates are physical, not reading-direction relative.
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                if (page.source != null) {
                    PdfPageBitmap(page, vm, 1024, Modifier.fillMaxSize())
                } else {
                    if (page.images.isEmpty() && page.texts.isEmpty())
                        Text(
                            stringResource(R.string.pdf_empty),
                            Modifier.align(Alignment.Center).padding(16.dp),
                            color = PdfPaperTokens.Ink,
                        )
                    // Item 2: paint order follows PdfLayers.order (images and texts interleaved
                    // by z), so the editor's stacking always matches what the isolated PDFBox
                    // renderer exports — including after the Layer menu moves a text above/below
                    // an image.
                    PdfLayers.order(page).forEach { element ->
                        when (element) {
                            is PdfLayers.Element.Img -> {
                                val n = page.images.indexOfFirst { it.id == element.image.id }
                                PdfImageElement(
                                    page = page,
                                    i = element.image,
                                    n = n,
                                    selected = selected,
                                    vm = vm,
                                    width = width,
                                    height = height,
                                    busy = busy,
                                    snapEnabled = current.project?.snap == true,
                                    zoom = current.zoom,
                                    focus = focus,
                                    activeSnap = activeSnapState,
                                    dragOffsetMm = dragOffsetMmState,
                                    onAdjustImage = onAdjustImage,
                                    multiSelectMode = current.multiSelectMode,
                                    inGroupSelection = element.image.id in current.selectedIds,
                                    groupDrag = groupDrag,
                                    elementDragActive = elementDragActiveState,
                                )
                            }
                            is PdfLayers.Element.Txt -> {
                                PdfTextElement(
                                    page = page,
                                    t = element.text,
                                    selectedTextId = current.selectedTextId,
                                    editingTextId = editingTextId,
                                    onEditingTextChange = { editingTextId = it },
                                    vm = vm,
                                    width = width,
                                    height = height,
                                    busy = busy,
                                    snapEnabled = current.project?.snap == true,
                                    focus = focus,
                                    activeSnap = activeSnapState,
                                    dragOffsetMm = dragOffsetMmState,
                                    onAdjust = onAdjustImage,
                                    multiSelectMode = current.multiSelectMode,
                                    inGroupSelection = element.text.id in current.selectedIds,
                                    groupDrag = groupDrag,
                                    elementDragActive = elementDragActiveState,
                                )
                            }
                        }
                    }
                }
                // Phase G2: the marquee rectangle being dragged, and the persistent group
                // bounding outline once 2+ elements are selected — both drawn as an overlay ON
                // TOP of every element (last child = highest z), in the same local px space as
                // the elements above (this Box's own graphicsLayer already applies zoom/pan), so
                // no extra coordinate conversion is needed beyond page-mm -> px via pxPerMmForDrop.
                Canvas(Modifier.matchParentSize()) {
                    val start = marqueeStart
                    val end = marqueeEnd
                    if (start != null && end != null) {
                        val topLeft =
                            androidx.compose.ui.geometry.Offset(minOf(start.x, end.x), minOf(start.y, end.y))
                        val rectSize =
                            androidx.compose.ui.geometry.Size(
                                kotlin.math.abs(end.x - start.x),
                                kotlin.math.abs(end.y - start.y),
                            )
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)
                        drawRect(
                            PdfPaperTokens.GuideOuter,
                            topLeft,
                            rectSize,
                            style = Stroke(3.dp.toPx(), pathEffect = dash),
                        )
                        drawRect(
                            PdfPaperTokens.GuideInner,
                            topLeft,
                            rectSize,
                            style = Stroke(1.dp.toPx(), pathEffect = dash),
                        )
                    }
                    if (current.groupSelected) {
                        val imgBounds =
                            page.images.filter { it.id in current.selectedIds }.map(PdfArrange::boundsOf)
                        val txtBounds =
                            page.texts.filter { it.id in current.selectedIds }.map(PdfArrange::boundsOf)
                        if (imgBounds.isNotEmpty() || txtBounds.isNotEmpty()) {
                            val group = PdfArrange.groupBounds(imgBounds, txtBounds)
                            val out = 4.dp.toPx()
                            // Bug fix (outline/content desync during a group drag): this
                            // persistent bounding outline was computed only from the COMMITTED
                            // model bounds (PdfArrange.boundsOf reads i.x/i.y/t.x/t.y, which only
                            // change once vm.moveSelectionBy commits at drag end), while the
                            // members themselves are drawn live via groupDrag.delta during the
                            // drag. That left the outline static/stale while the group's content
                            // visibly moved away from it, then made it "jump" once the commit
                            // caught the outline up to the content on release. Adding the same
                            // live pixel delta every member's own graphicsLayer already reads
                            // keeps this overlay perfectly in sync with the dragged content.
                            val liveDelta =
                                groupDrag?.delta?.value ?: androidx.compose.ui.geometry.Offset.Zero
                            val topLeft =
                                androidx.compose.ui.geometry.Offset(
                                    (group.x * pxPerMmForDrop).toFloat() - out + liveDelta.x,
                                    (group.y * pxPerMmForDrop).toFloat() - out + liveDelta.y,
                                )
                            val rectSize =
                                androidx.compose.ui.geometry.Size(
                                    (group.width * pxPerMmForDrop).toFloat() + 2 * out,
                                    (group.height * pxPerMmForDrop).toFloat() + 2 * out,
                                )
                            // Fix-round item 6: dashed (visually distinct from every per-member
                            // solid outline, and from the marquee's own dashed rectangle above by
                            // its position — it only ever appears once the drag ends and members
                            // already have their own outlines) plus the same "reads on white paper
                            // too" outer black ring as the per-member outlines.
                            drawGroupOutline(
                                topLeft = topLeft,
                                boxSize = rectSize,
                                bandWidth = 3.dp,
                                centerlineWidth = 1.dp,
                                dashed = true,
                            )
                        }
                    }
                }
            }
            if (activeSnap != null)
                PdfSnapOverlay(current, density, width, page, badgeBandHeight, activeSnap)
            // The reserved band itself: badges (with the drag measurement chip floating just
            // above them while dragging) normally, or the contextual toolbar in their place while
            // an image or text is selected — never over the paper, and never changing the page's
            // fit size since the band's height is reserved unconditionally above.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(badgeBandHeight),
                contentAlignment = Alignment.Center,
            ) {
                dragOffsetMm?.let { offset ->
                    val project = current.project
                    if (project != null) {
                        val unitLabel = listOf("mm", "cm", "in", "px")[project.unit.ordinal]
                        val factor = project.unit.factor(project.dpi)
                        fun format(mm: Float) =
                            "%.1f %s".format(kotlin.math.abs(mm) / factor, unitLabel)
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-28).dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        ) {
                            Text(
                                stringResource(
                                    R.string.pdf_snap_measurement,
                                    format(offset.x),
                                    format(offset.y),
                                ),
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                if (current.groupSelected && !busy) {
                    // Phase G2: 2+ elements selected replaces the single-element toolbar with the
                    // group's own — same reserved band, never overlapping the canvas/handles.
                    PdfMultiSelectBar(vm = vm, count = current.selectedIds.size)
                } else if (selected in page.images.indices && !busy) {
                    PdfImageContextualToolbar(vm = vm, onReplace = onReplaceImage)
                } else if (current.selectedTextId != null && !busy) {
                    PdfTextContextualToolbar(
                        vm = vm,
                        onEdit = { editingTextId = current.selectedTextId },
                        onStyle = onAdjustImage,
                    )
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        val pageLabel =
                            stringResource(
                                R.string.pdf_page_indicator,
                                pageIndex + 1,
                                pageCount.coerceAtLeast(1),
                            )
                        Surface(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        ) {
                            Text(
                                pageLabel,
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics {
                                    contentDescription = pageLabel
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        PdfZoomBadge(
                            zoomPercent = (current.zoom * 100).toInt(),
                            busy = busy,
                            onZoom = { vm.viewport(it, current.panX, current.panY) },
                            onFit = { vm.viewport(1f, 0f, 0f) },
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Test-only observation seam (device-verification pass for the drag/pan/fit bug fixes): holds the
 * most recently laid-out [LayoutCoordinates] for the canvas workspace pane (the outer
 * `BoxWithConstraints` in [PdfCanvas]) and for the page box within it, so [PdfUiProbeActivity]
 * (androidTest-only) can report their real on-screen pixel rects — post three-pane layout, post
 * zoom/pan graphicsLayer transform — in its probe-state JSON without any production code needing
 * to know this exists. `boundsInWindow()` is computed fresh from the live coordinates at whatever
 * moment the reader calls it (graphicsLayer scale/translation are part of the coordinates'
 * transform chain, so this stays correct across zoom/pan changes that don't trigger a new layout
 * pass), so this object only ever needs to be updated when a LAYOUT (not a pan/zoom-only redraw)
 * actually occurs. Never read outside androidTest; production code only ever writes to it.
 */
internal object PdfCanvasProbe {
    @Volatile internal var canvasCoordinates: LayoutCoordinates? = null
    @Volatile internal var pageBoxCoordinates: LayoutCoordinates? = null
}

/**
 * Fix-round item 1: shared context for a RIGID group drag, hoisted once per recomposition (not
 * once per element) so every selected member reads the exact same live delta — dragging any one
 * member moves the whole group together, snapped/clamped against the group's own bounding box (as
 * a single rigid body), never each member independently. Non-null only while [PdfStudioState]
 * actually has a 2+ group ([PdfStudioState.groupSelected]); [PdfImageElement]/[PdfTextElement]
 * fall back to their pre-existing single-element drag entirely when this is null.
 */
internal class PdfGroupDragContext(
    val memberBounds: List<PdfArrange.Bounds>,
    val nonMemberImages: List<PdfImage>,
    val nonMemberTexts: List<PdfText>,
    val delta: MutableState<androidx.compose.ui.geometry.Offset?>,
)

/**
 * Snaps the GROUP's own bounding box (never an individual dragged member's bounds, which would
 * incorrectly show snap lines to the member's own edges) against [PdfGroupDragContext]'s non-
 * member elements, then clamps so every member stays on the page — shared by both
 * [PdfImageElement]'s and [PdfTextElement]'s group-drag path so a rigid group move can never
 * resolve differently depending on which member is actually being dragged. Returns the resolved
 * [PdfSnapGuides.SnapResult] (group-space x/y, for the guide overlay/measurement chip) alongside
 * the clamped mm delta every member should move by.
 */
private fun resolveGroupDrag(
    page: PdfPage,
    group: PdfGroupDragContext,
    rawDeltaMmX: Double,
    rawDeltaMmY: Double,
    gridMm: Double?,
    thresholdMm: Double,
): Pair<PdfSnapGuides.SnapResult, Pair<Double, Double>> {
    val bounds = PdfArrange.union(group.memberBounds)
    val candidates = PdfSnapGuides.candidatesFor(page, group.nonMemberImages, group.nonMemberTexts)
    val snap =
        PdfSnapGuides.resolveDrag(
            bounds.x + rawDeltaMmX,
            bounds.y + rawDeltaMmY,
            bounds.width,
            bounds.height,
            candidates,
            gridMm,
            thresholdMm,
        )
    val clamped =
        PdfArrange.clampGroupMove(snap.x - bounds.x, snap.y - bounds.y, group.memberBounds, page.width, page.height)
    return snap to clamped
}

/**
 * Fix-round item 6: draws a selection outline that reads against ANY content — plain white paper,
 * a black-ink text box, or a colored/dark photo — not just the latter two the plain white+black
 * double stroke ([PdfPaperTokens.GuideOuter]/[GuideInner]) alone is legible against. A pure white
 * stroke is invisible directly on white paper, so a THIRD, thin black ring is added at the OUTER
 * edge of the white band: black ring (visible on white paper) -> white band (visible on dark
 * content) -> black centerline (always visible, same as the pre-existing double stroke). Used by
 * the group-selection outlines (Fix-round item 1/6); the pre-existing single-element selection
 * outline is left exactly as it was; it isn't in scope here and already has its own tests/mockups.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroupOutline(
    topLeft: androidx.compose.ui.geometry.Offset,
    boxSize: androidx.compose.ui.geometry.Size,
    bandWidth: androidx.compose.ui.unit.Dp,
    centerlineWidth: androidx.compose.ui.unit.Dp,
    dashed: Boolean = false,
) {
    val dash =
        if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f) else null
    val bandPx = bandWidth.toPx()
    val ringOut = bandPx / 2
    val ringTopLeft = androidx.compose.ui.geometry.Offset(topLeft.x - ringOut, topLeft.y - ringOut)
    val ringSize = androidx.compose.ui.geometry.Size(boxSize.width + 2 * ringOut, boxSize.height + 2 * ringOut)
    drawRect(PdfPaperTokens.GuideInner, ringTopLeft, ringSize, style = Stroke(1.dp.toPx(), pathEffect = dash))
    drawRect(PdfPaperTokens.GuideOuter, topLeft, boxSize, style = Stroke(bandPx, pathEffect = dash))
    drawRect(PdfPaperTokens.GuideInner, topLeft, boxSize, style = Stroke(centerlineWidth.toPx(), pathEffect = dash))
}

/** As the 4-argument [drawGroupOutline], but for a [drawWithContent] whose content fills this
 * scope entirely (a per-element outline, [outsideBy] beyond the content's own bounds) rather than
 * an arbitrary rectangle within a larger canvas (the group bounding box uses the other overload
 * directly). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGroupOutline(
    outsideBy: androidx.compose.ui.unit.Dp,
    bandWidth: androidx.compose.ui.unit.Dp,
    centerlineWidth: androidx.compose.ui.unit.Dp,
    dashed: Boolean = false,
) {
    val out = outsideBy.toPx()
    drawGroupOutline(
        topLeft = androidx.compose.ui.geometry.Offset(-out, -out),
        boxSize = androidx.compose.ui.geometry.Size(size.width + 2 * out, size.height + 2 * out),
        bandWidth = bandWidth,
        centerlineWidth = centerlineWidth,
        dashed = dashed,
    )
}

/**
 * Fix-round item 3: tracks whether the pointer press that is about to produce a click was
 * accompanied by Shift or Ctrl, into [state], for [PdfImageElement]/[PdfTextElement]'s `onClick`
 * to read a moment later at release. `combinedClickable` doesn't expose keyboard modifiers itself,
 * so this is a second, non-consuming `pointerInput` alongside it (Compose dispatches the same
 * event to every `pointerInput` on a node, so this never blocks `combinedClickable`'s own tap/
 * long-press/drag recognition — it only ever reads, never calls `change.consume()`).
 */
private fun Modifier.trackClickModifiers(state: MutableState<Boolean>): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    val mods = event.keyboardModifiers
                    state.value = mods.isCtrlPressed || mods.isShiftPressed
                }
            }
        }
    }

/** One selectable/draggable/resizable image element on the canvas (Phase G1b: extracted out of
 * [PdfCanvas]'s single per-image loop so it can be interleaved with [PdfTextElement] in
 * [PdfLayers.order] — same body as before the extraction, unchanged). */
@Composable
private fun PdfImageElement(
    page: PdfPage,
    i: PdfImage,
    n: Int,
    selected: Int,
    vm: PdfStudioViewModel,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    busy: Boolean,
    snapEnabled: Boolean,
    zoom: Float,
    focus: androidx.compose.ui.focus.FocusRequester,
    activeSnap: MutableState<PdfSnapGuides.SnapResult?>,
    dragOffsetMm: MutableState<androidx.compose.ui.geometry.Offset?>,
    onAdjustImage: () -> Unit,
    multiSelectMode: Boolean = false,
    inGroupSelection: Boolean = false,
    groupDrag: PdfGroupDragContext? = null,
    elementDragActive: MutableState<Boolean>? = null,
) {
    var activeSnap by activeSnap
    var dragOffsetMm by dragOffsetMm
    val inGroupSelectionState = rememberUpdatedState(inGroupSelection)
    val groupDragState = rememberUpdatedState(groupDrag)
    run {
        val imageLabel = stringResource(R.string.pdf_image_label, n + 1)
                        val resizeLabel = stringResource(R.string.pdf_resize_label, n + 1)
                        val adjustLabel = stringResource(R.string.pdf_adjust)
                        val addToSelectionLabel = stringResource(R.string.pdf_multiselect_add)
                        val removeFromSelectionLabel = stringResource(R.string.pdf_multiselect_remove)
                        val imageSelected = selected == n
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val pxPerMm = with(density) { width.toPx() } / page.width
                        // A constant on-screen catch distance (~8dp) rather than a constant page-
                        // space one: zoomed in, guides should feel just as "sticky" in screen
                        // terms, not proportionally wider in mm.
                        val snapThresholdMm =
                            (with(density) { 8.dp.toPx() } / (pxPerMm * zoom))
                                .coerceIn(0.1, 50.0)
                        // Live geometry while a resize handle is held; keyed on the committed image
                        // so the VM's commit on release replaces it without a one-frame snap back.
                        var resizePreview by remember(i) { mutableStateOf<PdfImage?>(null) }
                        val shown = resizePreview ?: i
                        var delta by
                            remember(i.id, i.x, i.y) {
                                mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
                            }
                        // Phase F2 item C: a delayed hover outline/tooltip is pointer/mouse-only
                        // (hoverable only ever fires for non-touch pointers in Compose), so touch
                        // behavior is unaffected; double-click below is a plain elapsed-time check
                        // on the existing single-click handler rather than a second gesture
                        // detector, so it can never race the drag-to-move detector on the same
                        // Box.
                        val hoverSource = remember(i.id) { MutableInteractionSource() }
                        val hovered by hoverSource.collectIsHoveredAsState()
                        var showHoverTooltip by remember(i.id) { mutableStateOf(false) }
                        LaunchedEffect(hovered, busy) {
                            showHoverTooltip = false
                            if (hovered && !busy) {
                                delay(600)
                                showHoverTooltip = true
                            }
                        }
                        var lastClickAtMs by remember(i.id) { mutableStateOf(0L) }
                        val clickModifierHeld = remember(i.id) { mutableStateOf(false) }
                        Box(
                            Modifier.absoluteOffset(
                                    x = width * (shown.x / page.width).toFloat(),
                                    y = height * (shown.y / page.height).toFloat(),
                                )
                                .size(
                                    width * (shown.width / page.width).toFloat(),
                                    height * (shown.height / page.height).toFloat(),
                                )
                                .graphicsLayer {
                                    // Fix-round item 1: a group member (2+ selected) always
                                    // translates by the shared group delta, regardless of which
                                    // member's own finger/pointer is actually driving the drag —
                                    // this is what makes the whole selection move together as one
                                    // rigid body instead of only the dragged element.
                                    val groupDelta = if (inGroupSelection) groupDrag?.delta?.value else null
                                    if (groupDelta != null) {
                                        translationX = groupDelta.x
                                        translationY = groupDelta.y
                                    } else if (imageSelected) {
                                        // Bug fix (canvas drag desync/ghost-move): this branch must
                                        // be gated to the actually-selected/dragged image, exactly
                                        // like PdfTextElement's twin graphicsLayer below (`else if
                                        // (isSelected)`). Previously it applied unconditionally to
                                        // EVERY image on the page, so `activeSnap`/`delta` — both
                                        // page-level state shared by every element — bled into
                                        // every non-dragged image's own graphicsLayer too, moving
                                        // it in lockstep with whichever image was actually being
                                        // dragged (reported: "the other image moves together with
                                        // the dragged one" even with no multi-select group active).
                                        val snap = activeSnap
                                        if (snap != null) {
                                            translationX = ((snap.x - i.x) * pxPerMm).toFloat()
                                            translationY = ((snap.y - i.y) * pxPerMm).toFloat()
                                        } else {
                                            translationX = delta.x
                                            translationY = delta.y
                                        }
                                    }
                                }
                                .then(
                                    if (selected == n)
                                        Modifier.drawWithContent {
                                            drawContent()
                                            // Physical print canvas overlay: white/black provide
                                            // 21:1 contrast.
                                            // The outline is editor-only and is never included in
                                            // exported pages.
                                            drawRect(PdfPaperTokens.GuideOuter, style = Stroke(6.dp.toPx()))
                                            drawRect(PdfPaperTokens.GuideInner, style = Stroke(2.dp.toPx()))
                                        }
                                    else if (inGroupSelection && !imageSelected)
                                        // Per-member outline for a group member that isn't ALSO
                                        // the single-selection (i.e. groups of 2+): drawn OUTSIDE
                                        // the image bounds like every other selection outline
                                        // here, so it never covers content or the corner handles.
                                        // Fix-round item 6: drawGroupOutline's extra outer black
                                        // ring keeps this visible on plain white paper, not just on
                                        // dark/colored photos.
                                        Modifier.drawWithContent {
                                            drawContent()
                                            drawGroupOutline(
                                                outsideBy = 3.dp,
                                                bandWidth = 4.dp,
                                                centerlineWidth = 1.5.dp,
                                            )
                                        }
                                    else if (hovered && !busy)
                                        Modifier.drawWithContent {
                                            drawContent()
                                            // Thinner and dashed, so a hovered-but-unselected image
                                            // is never mistaken for the selection outline above.
                                            val dash =
                                                PathEffect.dashPathEffect(
                                                    floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                                                    0f,
                                                )
                                            drawRect(
                                                PdfPaperTokens.GuideOuter,
                                                style = Stroke(3.dp.toPx(), pathEffect = dash),
                                            )
                                            drawRect(
                                                PdfPaperTokens.GuideInner,
                                                style = Stroke(1.dp.toPx(), pathEffect = dash),
                                            )
                                        }
                                    else Modifier
                                )
                                .hoverable(hoverSource, enabled = !busy)
                                .trackClickModifiers(clickModifierHeld)
                                .semantics {
                                    contentDescription = imageLabel
                                    this.selected = imageSelected || inGroupSelection
                                    customActions =
                                        if (busy) emptyList()
                                        else
                                            buildList {
                                                add(
                                                    CustomAccessibilityAction(adjustLabel) {
                                                        vm.selectImage(n)
                                                        onAdjustImage()
                                                        true
                                                    }
                                                )
                                                // Phase G2 a11y: TalkBack equivalent of long-press
                                                // (enter/add) and tap-to-toggle while already in a
                                                // multi-select session.
                                                add(
                                                    CustomAccessibilityAction(
                                                        if (inGroupSelection) removeFromSelectionLabel
                                                        else addToSelectionLabel
                                                    ) {
                                                        if (multiSelectMode) vm.toggleMultiSelect(i.id)
                                                        else vm.enterMultiSelect(i.id)
                                                        true
                                                    }
                                                )
                                            }
                                }
                                .combinedClickable(
                                    enabled = !busy,
                                    onLongClick = {
                                        // Phase G2: long-press starts (or adds this element to) a
                                        // multi-select session instead of the ordinary single
                                        // selection.
                                        focus.requestFocus()
                                        if (multiSelectMode) vm.toggleMultiSelect(i.id)
                                        else vm.enterMultiSelect(i.id)
                                    },
                                    onClick = {
                                        focus.requestFocus()
                                        if (clickModifierHeld.value) {
                                            // Fix-round item 3: Shift/Ctrl+click toggles
                                            // membership — starting a group from whatever was
                                            // singly selected, if anything, when not already in a
                                            // multi-select session.
                                            vm.toggleSelectionWithModifier(i.id)
                                        } else if (multiSelectMode) {
                                            vm.toggleMultiSelect(i.id)
                                        } else {
                                            vm.selectImage(n)
                                            val now = android.os.SystemClock.uptimeMillis()
                                            // Double-click (pointer): select (above) + open
                                            // Adjust, matching the existing "Resize image n" ->
                                            // Adjust action.
                                            if (now - lastClickAtMs < 350) onAdjustImage()
                                            lastClickAtMs = now
                                        }
                                    },
                                )
                                .pointerInput(i, busy) {
                                    // Read through rememberUpdatedState, not as pointerInput keys: selecting the dragged
                                    // element in onDragStart flips inGroupSelection, which would restart this block
                                    // mid-gesture and cancel the drag without onDragCancel (stale guides, no commit).
                                    val inGroupSelection by inGroupSelectionState
                                    val groupDrag by groupDragState
                                    if (!busy)
                                        detectDragGestures(
                                            onDragStart = {
                                                focus.requestFocus()
                                                // Bug fix (gesture arbitration): tell the ancestor
                                                // pan/zoom gesture an element drag owns this pointer
                                                // now.
                                                elementDragActive?.value = true
                                                // Fix-round item 1: dragging a member of an
                                                // existing 2+ group must NOT collapse the
                                                // selection down to just this element — the whole
                                                // point is that the group moves together.
                                                if (!(inGroupSelection && groupDrag != null))
                                                    vm.selectImage(n)
                                            },
                                            onDragEnd = {
                                                val move = delta
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                                elementDragActive?.value = false
                                                val group = groupDrag
                                                if (inGroupSelection && group != null) {
                                                    val (_, clampedMm) =
                                                        resolveGroupDrag(
                                                            page,
                                                            group,
                                                            move.x / pxPerMm,
                                                            move.y / pxPerMm,
                                                            gridMm = 5.0.takeIf { snapEnabled },
                                                            thresholdMm = snapThresholdMm,
                                                        )
                                                    group.delta.value = null
                                                    activeSnap = null
                                                    dragOffsetMm = null
                                                    // One committed VM call = one undo step for
                                                    // the WHOLE group, not per member.
                                                    vm.moveSelectionBy(clampedMm.first, clampedMm.second)
                                                } else {
                                                    val others =
                                                        page.images.filterIndexed { m, _ -> m != n }
                                                    val candidateX = i.x + move.x / pxPerMm
                                                    val candidateY = i.y + move.y / pxPerMm
                                                    // Same resolution the live overlay/chip below
                                                    // already computed every frame, so the
                                                    // committed position always equals what was
                                                    // last shown.
                                                    val result =
                                                        PdfSnapGuides.resolveDrag(
                                                            candidateX,
                                                            candidateY,
                                                            i.width,
                                                            i.height,
                                                            PdfSnapGuides.candidatesFor(page, others, page.texts),
                                                            gridMm = 5.0.takeIf { snapEnabled },
                                                            thresholdMm = snapThresholdMm,
                                                        )
                                                    activeSnap = null
                                                    dragOffsetMm = null
                                                    vm.moveImageTo(result.x, result.y)
                                                }
                                            },
                                            onDragCancel = {
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                                elementDragActive?.value = false
                                                if (inGroupSelection) groupDrag?.delta?.value = null
                                                activeSnap = null
                                                dragOffsetMm = null
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            delta += drag
                                            val group = groupDrag
                                            if (inGroupSelection && group != null) {
                                                val (snap, clampedMm) =
                                                    resolveGroupDrag(
                                                        page,
                                                        group,
                                                        delta.x / pxPerMm,
                                                        delta.y / pxPerMm,
                                                        gridMm = 5.0.takeIf { snapEnabled },
                                                        thresholdMm = snapThresholdMm,
                                                    )
                                                activeSnap = snap
                                                group.delta.value =
                                                    androidx.compose.ui.geometry.Offset(
                                                        (clampedMm.first * pxPerMm).toFloat(),
                                                        (clampedMm.second * pxPerMm).toFloat(),
                                                    )
                                                dragOffsetMm =
                                                    androidx.compose.ui.geometry.Offset(
                                                        clampedMm.first.toFloat(),
                                                        clampedMm.second.toFloat(),
                                                    )
                                            } else {
                                                val others =
                                                    page.images.filterIndexed { m, _ -> m != n }
                                                val candidateX = i.x + delta.x / pxPerMm
                                                val candidateY = i.y + delta.y / pxPerMm
                                                activeSnap =
                                                    PdfSnapGuides.resolveDrag(
                                                        candidateX,
                                                        candidateY,
                                                        i.width,
                                                        i.height,
                                                        PdfSnapGuides.candidatesFor(page, others, page.texts),
                                                        gridMm = 5.0.takeIf { snapEnabled },
                                                        thresholdMm = snapThresholdMm,
                                                    )
                                                dragOffsetMm =
                                                    androidx.compose.ui.geometry.Offset(
                                                        (delta.x / pxPerMm).toFloat(),
                                                        (delta.y / pxPerMm).toFloat(),
                                                    )
                                            }
                                        }
                                }
                        ) {
                            PdfBitmap(
                                vm.repository.file(i.asset),
                                i.rotation,
                                i.fit,
                                i.focusX,
                                i.focusY,
                                Modifier.fillMaxSize(),
                                PdfPreviewPolicy.side(page.images.size, thumbnail = false),
                            )
                            // Delayed pointer-hover tooltip (Phase F2 item C): only for a
                            // non-selected image (the selected one already has the resize/corner
                            // handles for feedback), and never on touch (hovered is pointer-only).
                            if (showHoverTooltip && selected != n && !busy)
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).offset(y = (-28).dp),
                                    color = MaterialTheme.colorScheme.inverseSurface,
                                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.pdf_hover_tooltip),
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            // Every handle's 48dp touch target is CENTERED on its image vertex
                            // (overflowing the image bounds), not inset inside it — so it never
                            // covers the photo underneath, matching the other three below. Only
                            // this bottom-right one keeps "Resize image n" (pdf_resize_label),
                            // its click->Adjust action and its exact drag-resize math, since
                            // PdfAccessibleAdjustTest/PdfKeyboardPointerDeviceTest depend on it;
                            // (0.2, 0.2) of the image (that test's other click target) is well
                            // clear of this corner's 48dp target.
                            if (selected == n && !busy)
                                Box(
                                    Modifier.align(AbsoluteAlignment.BottomRight)
                                        .offset(x = 24.dp, y = 24.dp)
                                        .size(48.dp)
                                        .semantics { contentDescription = resizeLabel }
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = resizeLabel,
                                            onClick = onAdjustImage,
                                        )
                                        .pointerInput(i) {
                                            var resize = androidx.compose.ui.geometry.Offset.Zero
                                            detectDragGestures(
                                                onDragStart = {
                                                    resize =
                                                        androidx.compose.ui.geometry.Offset.Zero
                                                    elementDragActive?.value = true
                                                },
                                                onDragEnd = {
                                                    elementDragActive?.value = false
                                                    val w = max(.1, i.width + resize.x / pxPerMm)
                                                    val h = max(.1, i.height + resize.y / pxPerMm)
                                                    vm.imageEdit {
                                                        PdfGeometry.resize(it, page, w, h, true)
                                                    }
                                                },
                                                onDragCancel = {
                                                    elementDragActive?.value = false
                                                    resizePreview = null
                                                },
                                            ) { change, drag ->
                                                change.consume()
                                                resize += drag
                                                val w = max(.1, i.width + resize.x / pxPerMm)
                                                val h = max(.1, i.height + resize.y / pxPerMm)
                                                resizePreview = PdfGeometry.resize(i, page, w, h, true)
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PdfHandleDot()
                                }
                            // These three corners are pointer/mouse-only touch targets, with no
                            // contentDescription of their own (decorative, merged into this
                            // image's own semantics above): the accessible path to resizing is
                            // the bottom-right handle's "Resize image n" node above, whose click
                            // action opens Adjust, plus the numeric Width/Height fields there.
                            // Giving all four handles the same label would also make
                            // `find { it.contentDescription == "Resize image n" }` in
                            // PdfAccessibleAdjustTest/PdfKeyboardPointerDeviceTest ambiguous.
                            if (selected == n && !busy)
                                listOf(
                                        Triple(AbsoluteAlignment.TopLeft, PdfGeometry.Corner.TopLeft, (-24).dp to (-24).dp),
                                        Triple(AbsoluteAlignment.TopRight, PdfGeometry.Corner.TopRight, 24.dp to (-24).dp),
                                        Triple(AbsoluteAlignment.BottomLeft, PdfGeometry.Corner.BottomLeft, (-24).dp to 24.dp),
                                    )
                                    .forEach { (cornerAlignment, corner, cornerOffset) ->
                                        PdfCornerHandle(
                                            modifier =
                                                Modifier.align(cornerAlignment)
                                                    .offset(x = cornerOffset.first, y = cornerOffset.second),
                                            onResize = { dxMm, dyMm ->
                                                vm.imageEdit {
                                                    PdfGeometry.resizeFromCorner(
                                                        it,
                                                        page,
                                                        corner,
                                                        dxMm,
                                                        dyMm,
                                                    )
                                                }
                                            },
                                            onPreview = { delta ->
                                                resizePreview =
                                                    delta?.let { (dxMm, dyMm) ->
                                                        PdfGeometry.resizeFromCorner(i, page, corner, dxMm, dyMm)
                                                    }
                                            },
                                            pxPerMm = pxPerMm,
                                            elementDragActive = elementDragActive,
                                        )
                                    }
                        }
        }
}

/** Draws the live snap-guide lines (Phase C item 5) while a drag is in progress — a vertical
 * and/or horizontal line across the full page box wherever [activeSnap] currently guides to.
 * Extracted out of [PdfCanvas] (Phase G1b) so its call site can sit right after the unified
 * image/text paint loop instead of after a now-removed per-image-only loop. */
@Composable
private fun PdfSnapOverlay(
    current: PdfStudioState,
    density: androidx.compose.ui.unit.Density,
    width: androidx.compose.ui.unit.Dp,
    page: PdfPage,
    badgeBandHeight: androidx.compose.ui.unit.Dp,
    activeSnap: PdfSnapGuides.SnapResult?,
) {
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            scaleX = current.zoom
            scaleY = current.zoom
            translationX = current.panX * density.density
            translationY = current.panY * density.density
        }
    ) {
        val pageBoxWidth = width.toPx()
        val pageBoxHeight = pageBoxWidth * (page.height / page.width).toFloat()
        val left = (this.size.width - pageBoxWidth) / 2f
        // Matches the page box's own offset(y = -badgeBandHeight / 2).
        val top = (this.size.height - pageBoxHeight) / 2f - badgeBandHeight.toPx() / 2f
        activeSnap?.vertical?.let { g ->
            val x = left + (g.position / page.width).toFloat() * pageBoxWidth
            drawLine(
                PdfPaperTokens.GuideOuter,
                androidx.compose.ui.geometry.Offset(x, top),
                androidx.compose.ui.geometry.Offset(x, top + pageBoxHeight),
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                PdfPaperTokens.GuideInner,
                androidx.compose.ui.geometry.Offset(x, top),
                androidx.compose.ui.geometry.Offset(x, top + pageBoxHeight),
                strokeWidth = 1.dp.toPx(),
            )
        }
        activeSnap?.horizontal?.let { g ->
            val y = top + (g.position / page.height).toFloat() * pageBoxHeight
            drawLine(
                PdfPaperTokens.GuideOuter,
                androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Offset(left + pageBoxWidth, y),
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                PdfPaperTokens.GuideInner,
                androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Offset(left + pageBoxWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

/**
 * WYSIWYG text-layer renderer (Phase G1b): draws a [PdfText] with the exact math the isolated
 * PDFBox exporter uses (`PdfProcessingService.drawText`) — the SAME bundled TTF (loaded here via
 * `android.graphics.Typeface`, not Compose's own text layout), the SAME [PdfTextWrap] line-
 * breaking, a fixed 1.2x line height and an ascent-based first baseline, the same Start/Center/End
 * alignment. Using `Paint`/`Typeface` directly (rather than Compose's `TextMeasurer`/`Paragraph`)
 * keeps the measured glyph widths and ascent metric coming from the exact font file the exporter
 * embeds, instead of risking drift from Compose's own line-height/shaping defaults.
 */
private object PdfTextRenderer {
    private val typefaces =
        mutableMapOf<Pair<PdfFontFamily, PdfFontWeight>, android.graphics.Typeface>()

    private fun assetName(family: PdfFontFamily, weight: PdfFontWeight) =
        when (family to weight) {
            PdfFontFamily.Sans to PdfFontWeight.Regular -> "fonts/NotoSans-Regular.ttf"
            PdfFontFamily.Sans to PdfFontWeight.Bold -> "fonts/NotoSans-Bold.ttf"
            PdfFontFamily.Serif to PdfFontWeight.Regular -> "fonts/NotoSerif-Regular.ttf"
            PdfFontFamily.Serif to PdfFontWeight.Bold -> "fonts/NotoSerif-Bold.ttf"
            else -> error("Unreachable: every PdfFontFamily x PdfFontWeight combination is listed above")
        }

    fun typeface(
        context: android.content.Context,
        family: PdfFontFamily,
        weight: PdfFontWeight,
    ): android.graphics.Typeface =
        typefaces.getOrPut(family to weight) {
            android.graphics.Typeface.createFromAsset(context.assets, assetName(family, weight))
        }

    /** [sizePt] (1/72in) to px at the canvas's own mm->px scale ([pxPerMm]) — 1pt = 25.4/72 mm,
     * matching [PdfUnit.Inch]'s own mm-per-inch constant. */
    fun sizePx(sizePt: Double, pxPerMm: Double): Float = (sizePt * 25.4 / 72.0 * pxPerMm).toFloat()

    private fun paint(context: android.content.Context, t: PdfText, sizePxValue: Float) =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(context, t.font, t.weight)
            textSize = sizePxValue
            val (r, g, b) = PdfPaperTokens.rgb(t.ink)
            color =
                android.graphics.Color.rgb(
                    (r * 255).toInt().coerceIn(0, 255),
                    (g * 255).toInt().coerceIn(0, 255),
                    (b * 255).toInt().coerceIn(0, 255),
                )
        }

    data class TextLayout(
        val lines: List<String>,
        val paint: android.graphics.Paint,
        val lineHeightPx: Float,
        val firstBaselineY: Float,
    )

    /**
     * Word-wraps [t]'s text to [boxWidthPx]/[boxHeightPx] with the same [PdfTextWrap] the exporter
     * uses, plus the resolved [android.graphics.Paint] and the first line's baseline Y (px, top-
     * down). `paint.ascent()` is negative (the distance ABOVE the baseline); the box's own top
     * edge sits `-ascent` px above that first baseline, the same offset
     * `PdfProcessingService.drawText`'s `boxTop - ascent` computes (its y-axis points up instead,
     * but the magnitude is identical).
     */
    fun layout(
        context: android.content.Context,
        t: PdfText,
        pxPerMm: Double,
        boxWidthPx: Float,
        boxHeightPx: Float,
    ): TextLayout {
        val sizePxValue = sizePx(t.sizePt, pxPerMm)
        val paint = paint(context, t, sizePxValue)
        fun measure(s: String) = paint.measureText(s)
        val lineHeight = sizePxValue * 1.2f
        val maxLines = (boxHeightPx / lineHeight).toInt().coerceAtLeast(0)
        val lines = PdfTextWrap.wrap(t.text, boxWidthPx, maxLines, ::measure)
        return TextLayout(lines, paint, lineHeight, -paint.ascent())
    }
}

/** Paints [t] inside [modifier]'s bounds using [PdfTextRenderer] — the static (non-editing)
 * WYSIWYG display of a text element. */
@Composable
private fun PdfTextContent(t: PdfText, pxPerMm: Double, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Canvas(modifier) {
        val layout = PdfTextRenderer.layout(context, t, pxPerMm, size.width, size.height)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipRect(0f, 0f, size.width, size.height)
            var baseline = layout.firstBaselineY
            for (line in layout.lines) {
                val lineWidth = layout.paint.measureText(line)
                val x =
                    when (t.align) {
                        PdfTextAlign.Start -> 0f
                        PdfTextAlign.Center -> (size.width - lineWidth) / 2f
                        PdfTextAlign.End -> size.width - lineWidth
                    }
                native.drawText(line, x, baseline, layout.paint)
                baseline += layout.lineHeightPx
            }
            native.restore()
        }
    }
}

/**
 * One selectable/draggable/resizable text element on the canvas (Phase G1b), the text-layer twin
 * of [PdfImageElement]: the same 4 corner handles (resizing changes the box, never the font size),
 * the same snap-guided drag (against images AND other texts — [PdfSnapGuides.candidatesFor]), tap
 * to select, double-tap (or the "Edit" custom action) to enter inline editing
 * ([PdfInlineTextEditor]).
 */
@Composable
private fun PdfTextElement(
    page: PdfPage,
    t: PdfText,
    selectedTextId: String?,
    editingTextId: String?,
    onEditingTextChange: (String?) -> Unit,
    vm: PdfStudioViewModel,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    busy: Boolean,
    snapEnabled: Boolean,
    focus: androidx.compose.ui.focus.FocusRequester,
    activeSnap: MutableState<PdfSnapGuides.SnapResult?>,
    dragOffsetMm: MutableState<androidx.compose.ui.geometry.Offset?>,
    onAdjust: () -> Unit,
    multiSelectMode: Boolean = false,
    inGroupSelection: Boolean = false,
    groupDrag: PdfGroupDragContext? = null,
    elementDragActive: MutableState<Boolean>? = null,
) {
    var activeSnap by activeSnap
    var dragOffsetMm by dragOffsetMm
    val inGroupSelectionState = rememberUpdatedState(inGroupSelection)
    val groupDragState = rememberUpdatedState(groupDrag)
    val isSelected = selectedTextId == t.id
    val isEditing = editingTextId == t.id
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pxPerMm = with(density) { width.toPx() } / page.width
    val snapThresholdMm =
        (with(density) { 8.dp.toPx() } / pxPerMm).coerceIn(0.1, 50.0)
    val textLabel =
        stringResource(R.string.pdf_text_label, t.text.take(60).replace('\n', ' '))
    val editLabel = stringResource(R.string.pdf_edit)
    val adjustLabel = stringResource(R.string.pdf_adjust)
    val addToSelectionLabel = stringResource(R.string.pdf_multiselect_add)
    val removeFromSelectionLabel = stringResource(R.string.pdf_multiselect_remove)
    // Live geometry while a corner handle is held (see PdfImageElement's twin).
    var resizePreview by remember(t) { mutableStateOf<PdfText?>(null) }
    val shown = resizePreview ?: t
    var delta by remember(t.id, t.x, t.y) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var lastClickAtMs by remember(t.id) { mutableStateOf(0L) }
    val clickModifierHeld = remember(t.id) { mutableStateOf(false) }
    Box(
        Modifier.absoluteOffset(
                x = width * (shown.x / page.width).toFloat(),
                y = height * (shown.y / page.height).toFloat(),
            )
            .size(
                width * (shown.width / page.width).toFloat(),
                height * (shown.height / page.height).toFloat(),
            )
            .graphicsLayer {
                // Fix-round item 1: as PdfImageElement's twin above — a group member always
                // translates by the shared rigid-body delta, whichever member is actually being
                // dragged.
                val groupDelta = if (inGroupSelection) groupDrag?.delta?.value else null
                if (groupDelta != null) {
                    translationX = groupDelta.x
                    translationY = groupDelta.y
                } else if (isSelected) {
                    val snap = activeSnap
                    if (snap != null) {
                        translationX = ((snap.x - t.x) * pxPerMm).toFloat()
                        translationY = ((snap.y - t.y) * pxPerMm).toFloat()
                    } else {
                        translationX = delta.x
                        translationY = delta.y
                    }
                }
            }
            .then(
                if (isSelected && !isEditing)
                    Modifier.drawWithContent {
                        // Same 21:1-contrast double stroke as the image selection outline —
                        // editor-only, never exported — but centered 3dp OUTSIDE the box: a
                        // stroke centered on the edge would hide the top of the first line,
                        // which sits flush against the box's top edge.
                        val out = 3.dp.toPx()
                        val topLeft = androidx.compose.ui.geometry.Offset(-out, -out)
                        val outlined =
                            androidx.compose.ui.geometry.Size(size.width + 2 * out, size.height + 2 * out)
                        drawRect(PdfPaperTokens.GuideOuter, topLeft, outlined, style = Stroke(6.dp.toPx()))
                        drawRect(PdfPaperTokens.GuideInner, topLeft, outlined, style = Stroke(2.dp.toPx()))
                        // Drawn before the content so the corner handles (children) stay on top.
                        drawContent()
                    }
                else if (inGroupSelection && !isSelected)
                    // As the image twin above: a per-member outline, outside the box, for a group
                    // member that isn't ALSO the lone single-selection — drawGroupOutline's extra
                    // outer black ring (Fix-round item 6) keeps it visible on plain white paper.
                    Modifier.drawWithContent {
                        drawContent()
                        drawGroupOutline(outsideBy = 3.dp, bandWidth = 4.dp, centerlineWidth = 1.5.dp)
                    }
                else Modifier
            )
            .semantics {
                contentDescription = textLabel
                this.selected = isSelected || inGroupSelection
                customActions =
                    if (busy) emptyList()
                    else
                        listOf(
                            CustomAccessibilityAction(editLabel) {
                                vm.selectText(t.id)
                                onEditingTextChange(t.id)
                                true
                            },
                            CustomAccessibilityAction(adjustLabel) {
                                vm.selectText(t.id)
                                onAdjust()
                                true
                            },
                            CustomAccessibilityAction(
                                if (inGroupSelection) removeFromSelectionLabel else addToSelectionLabel
                            ) {
                                if (multiSelectMode) vm.toggleMultiSelect(t.id) else vm.enterMultiSelect(t.id)
                                true
                            },
                        )
            }
            .trackClickModifiers(clickModifierHeld)
            .combinedClickable(
                enabled = !busy,
                onLongClick = {
                    focus.requestFocus()
                    if (multiSelectMode) vm.toggleMultiSelect(t.id) else vm.enterMultiSelect(t.id)
                },
                onClick = {
                    focus.requestFocus()
                    if (clickModifierHeld.value) {
                        // Fix-round item 3: Shift/Ctrl+click toggles membership.
                        vm.toggleSelectionWithModifier(t.id)
                    } else if (multiSelectMode) {
                        vm.toggleMultiSelect(t.id)
                    } else {
                        vm.selectText(t.id)
                        val now = android.os.SystemClock.uptimeMillis()
                        // Double-click/double-tap: select (above) + enter inline editing.
                        if (now - lastClickAtMs < 350) onEditingTextChange(t.id)
                        lastClickAtMs = now
                    }
                },
            )
            .pointerInput(t.id, busy, isEditing) {
                // Read through rememberUpdatedState, not as pointerInput keys: selecting the dragged
                // element in onDragStart flips inGroupSelection, which would restart this block
                // mid-gesture and cancel the drag without onDragCancel (stale guides, no commit).
                val inGroupSelection by inGroupSelectionState
                val groupDrag by groupDragState
                if (!busy && !isEditing)
                    detectDragGestures(
                        onDragStart = {
                            focus.requestFocus()
                            elementDragActive?.value = true
                            // Fix-round item 1: as PdfImageElement's twin above — never collapse
                            // an existing 2+ group down to just this text when dragging it.
                            if (!(inGroupSelection && groupDrag != null)) vm.selectText(t.id)
                        },
                        onDragEnd = {
                            val move = delta
                            delta = androidx.compose.ui.geometry.Offset.Zero
                            elementDragActive?.value = false
                            val group = groupDrag
                            if (inGroupSelection && group != null) {
                                val (_, clampedMm) =
                                    resolveGroupDrag(
                                        page,
                                        group,
                                        move.x / pxPerMm,
                                        move.y / pxPerMm,
                                        gridMm = 5.0.takeIf { snapEnabled },
                                        thresholdMm = snapThresholdMm,
                                    )
                                group.delta.value = null
                                activeSnap = null
                                dragOffsetMm = null
                                vm.moveSelectionBy(clampedMm.first, clampedMm.second)
                            } else {
                                val otherTexts = page.texts.filter { it.id != t.id }
                                val candidateX = t.x + move.x / pxPerMm
                                val candidateY = t.y + move.y / pxPerMm
                                val result =
                                    PdfSnapGuides.resolveDrag(
                                        candidateX,
                                        candidateY,
                                        t.width,
                                        t.height,
                                        PdfSnapGuides.candidatesFor(page, page.images, otherTexts),
                                        gridMm = 5.0.takeIf { snapEnabled },
                                        thresholdMm = snapThresholdMm,
                                    )
                                activeSnap = null
                                dragOffsetMm = null
                                vm.moveTextTo(result.x, result.y)
                            }
                        },
                        onDragCancel = {
                            delta = androidx.compose.ui.geometry.Offset.Zero
                            elementDragActive?.value = false
                            if (inGroupSelection) groupDrag?.delta?.value = null
                            activeSnap = null
                            dragOffsetMm = null
                        },
                    ) { change, drag ->
                        change.consume()
                        delta += drag
                        val group = groupDrag
                        if (inGroupSelection && group != null) {
                            val (snap, clampedMm) =
                                resolveGroupDrag(
                                    page,
                                    group,
                                    delta.x / pxPerMm,
                                    delta.y / pxPerMm,
                                    gridMm = 5.0.takeIf { snapEnabled },
                                    thresholdMm = snapThresholdMm,
                                )
                            activeSnap = snap
                            group.delta.value =
                                androidx.compose.ui.geometry.Offset(
                                    (clampedMm.first * pxPerMm).toFloat(),
                                    (clampedMm.second * pxPerMm).toFloat(),
                                )
                            dragOffsetMm =
                                androidx.compose.ui.geometry.Offset(
                                    clampedMm.first.toFloat(),
                                    clampedMm.second.toFloat(),
                                )
                        } else {
                            val otherTexts = page.texts.filter { it.id != t.id }
                            val candidateX = t.x + delta.x / pxPerMm
                            val candidateY = t.y + delta.y / pxPerMm
                            activeSnap =
                                PdfSnapGuides.resolveDrag(
                                    candidateX,
                                    candidateY,
                                    t.width,
                                    t.height,
                                    PdfSnapGuides.candidatesFor(page, page.images, otherTexts),
                                    gridMm = 5.0.takeIf { snapEnabled },
                                    thresholdMm = snapThresholdMm,
                                )
                            dragOffsetMm =
                                androidx.compose.ui.geometry.Offset(
                                    (delta.x / pxPerMm).toFloat(),
                                    (delta.y / pxPerMm).toFloat(),
                                )
                        }
                    }
            }
    ) {
        if (isEditing) {
            PdfInlineTextEditor(t, vm, pxPerMm, onDone = { onEditingTextChange(null) })
        } else {
            PdfTextContent(shown, pxPerMm, Modifier.fillMaxSize())
        }
        if (isSelected && !busy && !isEditing) {
            // Round-2 fix (item G): reuse the same PdfCornerHandle every image corner uses,
            // instead of duplicating its drag-gesture code for this one corner — identical
            // behavior (48dp target, same resize callback shape), just handled generically for
            // all four corners below.
            listOf(
                    Triple(AbsoluteAlignment.BottomRight, PdfGeometry.Corner.BottomRight, 24.dp to 24.dp),
                    Triple(AbsoluteAlignment.TopLeft, PdfGeometry.Corner.TopLeft, (-24).dp to (-24).dp),
                    Triple(AbsoluteAlignment.TopRight, PdfGeometry.Corner.TopRight, 24.dp to (-24).dp),
                    Triple(AbsoluteAlignment.BottomLeft, PdfGeometry.Corner.BottomLeft, (-24).dp to 24.dp),
                )
                .forEach { (cornerAlignment, corner, cornerOffset) ->
                    PdfCornerHandle(
                        modifier =
                            Modifier.align(cornerAlignment)
                                .offset(x = cornerOffset.first, y = cornerOffset.second),
                        onResize = { dxMm, dyMm ->
                            vm.resizeSelectedTextFromCorner(corner, dxMm, dyMm)
                        },
                        onPreview = { delta ->
                            resizePreview =
                                delta?.let { (dxMm, dyMm) ->
                                    PdfGeometry.resizeTextFromCorner(t, page, corner, dxMm, dyMm)
                                }
                        },
                        pxPerMm = pxPerMm,
                        elementDragActive = elementDragActive,
                    )
                }
        }
    }
}

/**
 * Inline text editor overlaid exactly over [t]'s box (Phase G1b): a [BasicTextField] using the
 * same font/size/alignment as [PdfTextContent] so the box doesn't visibly change shape when
 * entering/leaving edit mode. IME "Done" or losing focus (an outside tap, or selecting a different
 * element) commits; [PdfTextSupport] validation runs before every commit and blocks it — showing
 * an inline error instead — for an unsupported character or an empty result; Escape/system Back
 * cancels outright, discarding the draft.
 */
@Composable
private fun PdfInlineTextEditor(
    t: PdfText,
    vm: PdfStudioViewModel,
    pxPerMm: Double,
    onDone: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var draft by
        remember(t.id) {
            mutableStateOf(
                androidx.compose.ui.text.input.TextFieldValue(
                    t.text,
                    selection = androidx.compose.ui.text.TextRange(t.text.length),
                )
            )
        }
    var error by remember(t.id) { mutableStateOf<Int?>(null) }
    var done by remember(t.id) { mutableStateOf(false) }
    // Round-2 fix: onFocusChanged fires immediately on first composition with isFocused=false
    // (nothing is focused yet — the LaunchedEffect below hasn't requested focus at that point),
    // which used to be misread as "focus lost, commit and close" and silently closed the editor
    // the instant it opened (confirmed on-device: the box never showed a cursor/IME). Only a
    // "was focused, now isn't" transition means the field actually lost focus.
    var hasFocused by remember(t.id) { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Commits [draft], or sets [error] and returns false (Item 3: unsupported glyphs/empty text
    // block the commit rather than silently failing PdfProject.validate downstream).
    fun commit(): Boolean {
        val text = draft.text
        if (text.isBlank()) {
            error = R.string.pdf_text_empty
            return false
        }
        if (PdfTextSupport.check(text).isFailure) {
            error = PdfFailure.UnsupportedGlyph.message
            return false
        }
        vm.textEdit(t.id) { it.copy(text = text) }
        return true
    }

    fun finish(commitFirst: Boolean) {
        if (done) return
        if (!commitFirst || commit()) {
            done = true
            onDone()
        }
    }

    BackHandler(enabled = true) { finish(commitFirst = false) }
    LaunchedEffect(t.id) { focusRequester.requestFocus() }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Ltr
    ) {
        Column(Modifier.fillMaxSize()) {
            val sizePx = PdfTextRenderer.sizePx(t.sizePt, pxPerMm)
            val fontFamily =
                remember(t.font, t.weight) {
                    androidx.compose.ui.text.font.FontFamily(
                        androidx.compose.ui.text.font.Font(
                            when (t.font to t.weight) {
                                PdfFontFamily.Sans to PdfFontWeight.Regular -> "fonts/NotoSans-Regular.ttf"
                                PdfFontFamily.Sans to PdfFontWeight.Bold -> "fonts/NotoSans-Bold.ttf"
                                PdfFontFamily.Serif to PdfFontWeight.Regular -> "fonts/NotoSerif-Regular.ttf"
                                else -> "fonts/NotoSerif-Bold.ttf"
                            },
                            context.assets,
                        )
                    )
                }
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    error = null
                },
                modifier =
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) hasFocused = true
                            else if (hasFocused) finish(commitFirst = true)
                        },
                textStyle =
                    androidx.compose.ui.text.TextStyle(
                        color = PdfPaperTokens.compose(t.ink),
                        fontFamily = fontFamily,
                        fontSize = with(density) { sizePx.toSp() },
                        lineHeight = with(density) { (sizePx * 1.2f).toSp() },
                        textAlign =
                            when (t.align) {
                                PdfTextAlign.Start -> androidx.compose.ui.text.style.TextAlign.Start
                                PdfTextAlign.Center -> androidx.compose.ui.text.style.TextAlign.Center
                                PdfTextAlign.End -> androidx.compose.ui.text.style.TextAlign.End
                            },
                    ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PdfPaperTokens.compose(t.ink)),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(
                        onDone = { finish(commitFirst = true) }
                    ),
            )
            if (error != null)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(
                        stringResource(error!!),
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
        }
    }
}

/**
 * Contextual toolbar shown above the page strip while a text is selected (Phase G1b), the text
 * twin of [PdfImageContextualToolbar]: Edit, Style (opens the inspector), Align, Layer, Delete.
 */
@Composable
internal fun PdfTextContextualToolbar(
    vm: PdfStudioViewModel,
    onEdit: () -> Unit,
    onStyle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAlign by remember { mutableStateOf(false) }
    var showLayer by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val editLabel = stringResource(R.string.pdf_edit)
            IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = editLabel }) {
                Icon(GalleryIcons.Edit, contentDescription = null)
            }
            val styleLabel = stringResource(R.string.pdf_text_style)
            IconButton(onClick = onStyle, modifier = Modifier.semantics { contentDescription = styleLabel }) {
                Icon(GalleryIcons.TextFields, contentDescription = null)
            }
            Box {
                val alignLabel = stringResource(R.string.pdf_toolbar_align)
                IconButton(
                    onClick = { showAlign = true },
                    modifier = Modifier.semantics { contentDescription = alignLabel },
                ) {
                    Icon(GalleryIcons.AlignHorizontalCenter, contentDescription = null)
                }
                DropdownMenu(expanded = showAlign, onDismissRequest = { showAlign = false }) {
                    listOf(
                            PdfGeometry.Align.Left to R.string.pdf_align_left,
                            PdfGeometry.Align.Center to R.string.pdf_align_center,
                            PdfGeometry.Align.Right to R.string.pdf_align_right,
                            PdfGeometry.Align.Top to R.string.pdf_align_top,
                            PdfGeometry.Align.Middle to R.string.pdf_align_middle,
                            PdfGeometry.Align.Bottom to R.string.pdf_align_bottom,
                        )
                        .forEach { (align, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    showAlign = false
                                    vm.alignSelectedText(align)
                                },
                            )
                        }
                }
            }
            Box {
                val layerLabel = stringResource(R.string.pdf_toolbar_layer)
                IconButton(
                    onClick = { showLayer = true },
                    modifier = Modifier.semantics { contentDescription = layerLabel },
                ) {
                    Icon(GalleryIcons.Layers, contentDescription = null)
                }
                DropdownMenu(expanded = showLayer, onDismissRequest = { showLayer = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_layer_forward)) },
                        onClick = {
                            showLayer = false
                            vm.bringSelectedForward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_layer_backward)) },
                        onClick = {
                            showLayer = false
                            vm.sendSelectedBackward()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_front)) },
                        onClick = {
                            showLayer = false
                            vm.bringSelectedToFront()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pdf_backlayer)) },
                        onClick = {
                            showLayer = false
                            vm.sendSelectedToBack()
                        },
                    )
                }
            }
            val deleteLabel = stringResource(R.string.pdf_delete_text)
            androidx.compose.material3.FilledTonalIconButton(
                onClick = vm::deleteSelected,
                colors =
                    androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                modifier = Modifier.semantics { contentDescription = deleteLabel },
            ) {
                Icon(GalleryIcons.Trash, contentDescription = null)
            }
        }
    }
}

/** One 48dp-touch-target corner resize handle; a smaller visual dot keeps it unobtrusive. */
@Composable
private fun PdfCornerHandle(
    modifier: Modifier,
    onResize: (dxMm: Double, dyMm: Double) -> Unit,
    pxPerMm: Double,
    elementDragActive: MutableState<Boolean>? = null,
    /** Cumulative (dxMm, dyMm) on every move so the element follows the finger; null on cancel. */
    onPreview: (Pair<Double, Double>?) -> Unit = {},
) {
    var resize by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    // The detector below only restarts on pxPerMm, so read the latest element-bound callback.
    val preview by androidx.compose.runtime.rememberUpdatedState(onPreview)
    Box(
        modifier.size(48.dp).pointerInput(pxPerMm) {
            detectDragGestures(
                onDragStart = {
                    resize = androidx.compose.ui.geometry.Offset.Zero
                    elementDragActive?.value = true
                },
                onDragEnd = {
                    elementDragActive?.value = false
                    onResize(resize.x / pxPerMm, resize.y / pxPerMm)
                    resize = androidx.compose.ui.geometry.Offset.Zero
                },
                onDragCancel = {
                    elementDragActive?.value = false
                    resize = androidx.compose.ui.geometry.Offset.Zero
                    preview(null)
                },
            ) { change, drag ->
                change.consume()
                resize += drag
                preview(resize.x / pxPerMm to resize.y / pxPerMm)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        PdfHandleDot()
    }
}

/**
 * The small ~14dp dual-tone ring shared by all four resize handles: white outer + black inner
 * (the same [PdfPaperTokens] pair as the selection outline/snap guides), so it stays visible over
 * any photo regardless of its colors, without covering meaningful image content the way a large
 * filled square would.
 */
@Composable
private fun PdfHandleDot() {
    Box(
        Modifier.size(14.dp)
            .background(PdfPaperTokens.GuideOuter, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(9.dp)
                .background(PdfPaperTokens.GuideInner, androidx.compose.foundation.shape.CircleShape)
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PdfZoomBadge(
    zoomPercent: Int,
    busy: Boolean,
    onZoom: (Float) -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.pdf_zoom_percent, zoomPercent)
    Box(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            modifier =
                Modifier.clickable(enabled = !busy, onClick = { expanded = true }).semantics {
                    contentDescription = label
                },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(GalleryIcons.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val fitLabel = stringResource(R.string.pdf_fit_view)
            DropdownMenuItem(
                text = { Text(fitLabel) },
                onClick = {
                    expanded = false
                    onFit()
                },
            )
            ZOOM_LEVELS.forEach { level ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_zoom_percent, (level * 100).toInt())) },
                    onClick = {
                        expanded = false
                        onZoom(level)
                    },
                )
            }
        }
    }
}
