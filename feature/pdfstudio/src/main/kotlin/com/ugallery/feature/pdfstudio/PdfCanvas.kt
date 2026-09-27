package com.ugallery.feature.pdfstudio

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.AbsoluteAlignment
import com.ugallery.core.designsystem.GalleryIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
                        androidx.compose.material3.CircularProgressIndicator(
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
            page.images.forEach { i ->
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

private val ZOOM_LEVELS = listOf(.5f, 1f, 2f)

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
) {
    val current by vm.state.collectAsStateWithLifecycle()
    val viewport by rememberUpdatedState(current)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val canvasLabel = stringResource(R.string.pdf_canvas_label)
    Surface(
        modifier.semantics { contentDescription = canvasLabel },
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .clipToBounds()
                .focusRequester(focus)
                .pointerInput(page.id, busy) {
                    if (!busy)
                        detectTapGestures(onDoubleTap = { vm.viewport(1f, 0f, 0f) })
                }
                .onPreviewKeyEvent { event ->
                    if (busy || event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown)
                        false
                    else if (
                        event.isCtrlPressed && event.key == androidx.compose.ui.input.key.Key.Z
                    ) {
                        if (event.isShiftPressed) vm.redo() else vm.undo()
                        true
                    } else {
                        val step =
                            if (event.isShiftPressed) 10.0
                            else if (current.project?.snap == true) 5.0 else 1.0
                        when (event.key) {
                            androidx.compose.ui.input.key.Key.DirectionLeft -> {
                                vm.moveImage(-step, 0.0)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionRight -> {
                                vm.moveImage(step, 0.0)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionUp -> {
                                vm.moveImage(0.0, -step)
                                true
                            }
                            androidx.compose.ui.input.key.Key.DirectionDown -> {
                                vm.moveImage(0.0, step)
                                true
                            }
                            else -> false
                        }
                    }
                }
                .focusable()
                .pointerInput(page.id, busy, density) {
                    if (!busy)
                        detectTransformGestures { _, offset, scale, _ ->
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
            val badgeBandHeight = 64.dp
            val width =
                minOf(
                        maxWidth - 24.dp,
                        (maxHeight - 24.dp - badgeBandHeight) *
                            (page.width / page.height).toFloat(),
                    )
                    .coerceAtLeast(80.dp)
            val height = width * (page.height / page.width).toFloat()
            // Shared across every image below: only one drag is ever active at a time, so a
            // single pair of hoisted states is enough to draw the guide overlay/chip for whichever
            // image is currently moving; hoisted here (not inside the images branch) so the
            // overlay and badges drawn as siblings of the page box below can still read them.
            var activeSnap by
                remember(page.id) { mutableStateOf<PdfSnapGuides.SnapResult?>(null) }
            var dragOffsetMm by
                remember(page.id) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
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
                    .clipToBounds(),
                // PDF coordinates are physical, not reading-direction relative.
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                if (page.source != null) {
                    PdfPageBitmap(page, vm, 1024, Modifier.fillMaxSize())
                } else {
                    if (page.images.isEmpty())
                        Text(
                            stringResource(R.string.pdf_empty),
                            Modifier.align(Alignment.Center).padding(16.dp),
                            color = PdfPaperTokens.Ink,
                        )
                    page.images.forEachIndexed { n, i ->
                        val imageLabel = stringResource(R.string.pdf_image_label, n + 1)
                        val resizeLabel = stringResource(R.string.pdf_resize_label, n + 1)
                        val adjustLabel = stringResource(R.string.pdf_adjust)
                        val imageSelected = selected == n
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val pxPerMm = with(density) { width.toPx() } / page.width
                        // A constant on-screen catch distance (~8dp) rather than a constant page-
                        // space one: zoomed in, guides should feel just as "sticky" in screen
                        // terms, not proportionally wider in mm.
                        val snapThresholdMm =
                            (with(density) { 8.dp.toPx() } / (pxPerMm * current.zoom))
                                .coerceIn(0.1, 50.0)
                        var delta by
                            remember(i.id, i.x, i.y) {
                                mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
                            }
                        Box(
                            Modifier.absoluteOffset(
                                    x = width * (i.x / page.width).toFloat(),
                                    y = height * (i.y / page.height).toFloat(),
                                )
                                .size(
                                    width * (i.width / page.width).toFloat(),
                                    height * (i.height / page.height).toFloat(),
                                )
                                .graphicsLayer {
                                    val snap = activeSnap
                                    if (snap != null) {
                                        translationX = ((snap.x - i.x) * pxPerMm).toFloat()
                                        translationY = ((snap.y - i.y) * pxPerMm).toFloat()
                                    } else {
                                        translationX = delta.x
                                        translationY = delta.y
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
                                    else Modifier
                                )
                                .semantics {
                                    contentDescription = imageLabel
                                    this.selected = imageSelected
                                    customActions =
                                        if (busy) emptyList()
                                        else
                                            listOf(
                                                CustomAccessibilityAction(adjustLabel) {
                                                    vm.selectImage(n)
                                                    onAdjustImage()
                                                    true
                                                }
                                            )
                                }
                                .clickable(enabled = !busy) {
                                    focus.requestFocus()
                                    vm.selectImage(n)
                                }
                                .pointerInput(i, busy) {
                                    if (!busy)
                                        detectDragGestures(
                                            onDragStart = {
                                                focus.requestFocus()
                                                vm.selectImage(n)
                                            },
                                            onDragEnd = {
                                                val move = delta
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                                val others =
                                                    page.images.filterIndexed { m, _ -> m != n }
                                                val candidateX = i.x + move.x / pxPerMm
                                                val candidateY = i.y + move.y / pxPerMm
                                                // Same resolution the live overlay/chip below
                                                // already computed every frame, so the committed
                                                // position always equals what was last shown.
                                                val result =
                                                    PdfSnapGuides.resolveDrag(
                                                        candidateX,
                                                        candidateY,
                                                        i.width,
                                                        i.height,
                                                        PdfSnapGuides.candidates(page, others),
                                                        gridMm = 5.0.takeIf { current.project?.snap == true },
                                                        thresholdMm = snapThresholdMm,
                                                    )
                                                activeSnap = null
                                                dragOffsetMm = null
                                                vm.moveImageTo(result.x, result.y)
                                            },
                                            onDragCancel = {
                                                delta = androidx.compose.ui.geometry.Offset.Zero
                                                activeSnap = null
                                                dragOffsetMm = null
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            delta += drag
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
                                                    PdfSnapGuides.candidates(page, others),
                                                    gridMm = 5.0.takeIf { current.project?.snap == true },
                                                    thresholdMm = snapThresholdMm,
                                                )
                                            dragOffsetMm =
                                                androidx.compose.ui.geometry.Offset(
                                                    (delta.x / pxPerMm).toFloat(),
                                                    (delta.y / pxPerMm).toFloat(),
                                                )
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
                                                },
                                                onDragEnd = {
                                                    val w = max(.1, i.width + resize.x / pxPerMm)
                                                    val h = max(.1, i.height + resize.y / pxPerMm)
                                                    vm.imageEdit {
                                                        PdfGeometry.resize(it, page, w, h, true)
                                                    }
                                                },
                                            ) { change, drag ->
                                                change.consume()
                                                resize += drag
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
                                            pxPerMm = pxPerMm,
                                        )
                                    }
                        }
                    }
                }
            }
            if (activeSnap != null)
                Canvas(Modifier.matchParentSize().graphicsLayer {
                    scaleX = current.zoom
                    scaleY = current.zoom
                    translationX = current.panX * density.density
                    translationY = current.panY * density.density
                }) {
                    val pageBoxWidth = width.toPx()
                    val pageBoxHeight = pageBoxWidth * (page.height / page.width).toFloat()
                    val left = (this.size.width - pageBoxWidth) / 2f
                    // Matches the page box's own offset(y = -badgeBandHeight / 2) above.
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
            // The reserved band itself: badges (with the drag measurement chip floating just
            // above them while dragging) normally, or the contextual toolbar in their place while
            // an image is selected — never over the paper, and never changing the page's fit size
            // since the band's height is reserved unconditionally above.
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
                if (selected in page.images.indices && !busy) {
                    PdfImageContextualToolbar(vm = vm, onReplace = onReplaceImage)
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

/** One 48dp-touch-target corner resize handle; a smaller visual dot keeps it unobtrusive. */
@Composable
private fun PdfCornerHandle(
    modifier: Modifier,
    onResize: (dxMm: Double, dyMm: Double) -> Unit,
    pxPerMm: Double,
) {
    var resize by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        modifier.size(48.dp).pointerInput(pxPerMm) {
            detectDragGestures(
                onDragStart = { resize = androidx.compose.ui.geometry.Offset.Zero },
                onDragEnd = {
                    onResize(resize.x / pxPerMm, resize.y / pxPerMm)
                    resize = androidx.compose.ui.geometry.Offset.Zero
                },
                onDragCancel = { resize = androidx.compose.ui.geometry.Offset.Zero },
            ) { change, drag ->
                change.consume()
                resize += drag
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
private fun PdfZoomBadge(
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
