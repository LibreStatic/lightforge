package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Ruler sizing shared between the layout (to decide whether rulers fit at all) and the drawing
 * itself. */
internal object PdfRulerDefaults {
    // Phase F review fix (MINOR): 20dp only fit a single rotated digit before the label ran past
    // the ruler band and got clipped by the Surface's own shape ("120" showed only its "1"). 28dp
    // gives a 3-4 digit label (e.g. "1000" at 200% font) room to draw horizontally instead (see
    // PdfLeftRuler below), which is both simpler and safer than tuning rotated-text geometry.
    val Thickness = 28.dp
    /** Below this many dp of *canvas* height left over once rulers are subtracted, rulers hide
     * instead — matches the pre-existing 840x320 probe threshold (item A). */
    val MinCanvasHeight = 120.dp
}

/**
 * Wraps [PdfCanvas] with top/left rulers (Phase F2 item A), shown only in expanded modes
 * ([showRulers]) and only when doing so still leaves at least [PdfRulerDefaults.MinCanvasHeight]
 * of canvas height — otherwise this renders a plain [PdfCanvas], unchanged from before this phase.
 * Both rulers, and the canvas, size themselves off the *same* [maxWidth]/[maxHeight] region (the
 * canvas area net of ruler thickness), so a ruler tick and the page edge it points at always land
 * in the same place on screen.
 */
@Composable
internal fun PdfCanvasWithRulers(
    page: PdfPage,
    selected: Int,
    vm: PdfStudioViewModel,
    modifier: Modifier,
    busy: Boolean,
    pageIndex: Int,
    pageCount: Int,
    onAdjustImage: () -> Unit,
    onReplaceImage: () -> Unit,
    commands: PdfEditorCommandDispatcher,
    showRulers: Boolean,
) {
    val current by vm.state.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier) {
        val thickness = PdfRulerDefaults.Thickness
        val rulersFit = showRulers && (maxHeight - thickness) >= PdfRulerDefaults.MinCanvasHeight
        if (!rulersFit) {
            PdfCanvas(
                page,
                selected,
                vm,
                Modifier.fillMaxSize(),
                busy,
                pageIndex,
                pageCount,
                onAdjustImage,
                onReplaceImage,
                commands,
            )
        } else {
            val unit = current.project?.unit ?: PdfUnit.Millimeter
            val dpi = current.project?.dpi ?: 300
            val canvasAreaWidth = maxWidth - thickness
            val canvasAreaHeight = maxHeight - thickness
            // Item A: never mirrored in RTL — the physical page origin is always the top-left,
            // so both rulers (their layout order and their drawn tick positions) are forced LTR
            // regardless of the app's locale.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(thickness)) {
                        Spacer(Modifier.width(thickness))
                        PdfTopRuler(
                            page = page,
                            zoom = current.zoom,
                            panX = current.panX,
                            unit = unit,
                            dpi = dpi,
                            canvasAreaWidth = canvasAreaWidth,
                            canvasAreaHeight = canvasAreaHeight,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        PdfLeftRuler(
                            page = page,
                            zoom = current.zoom,
                            panY = current.panY,
                            unit = unit,
                            dpi = dpi,
                            canvasAreaWidth = canvasAreaWidth,
                            canvasAreaHeight = canvasAreaHeight,
                            modifier = Modifier.width(thickness).fillMaxHeight(),
                        )
                        PdfCanvas(
                            page,
                            selected,
                            vm,
                            Modifier.weight(1f).fillMaxHeight(),
                            busy,
                            pageIndex,
                            pageCount,
                            onAdjustImage,
                            onReplaceImage,
                            commands,
                        )
                    }
                }
            }
        }
    }
}

/** Physical-mm-per-on-screen-dp scale at the canvas's current zoom, and the on-screen dp position
 * of the page's physical (0,0) origin, for a canvas area sized `canvasAreaWidth` x
 * `canvasAreaHeight` — must mirror [PdfCanvas]'s own centering exactly (see
 * [pdfCanvasPageBoxSize] and its `Alignment.Center` + `offset(y = -badgeBandHeight / 2)` box).
 */
private fun rulerTransform(
    page: PdfPage,
    zoom: Float,
    canvasAreaWidth: Dp,
    canvasAreaHeight: Dp,
): Triple<Double, Double, Double> {
    val (boxWidth, boxHeight) = pdfCanvasPageBoxSize(canvasAreaWidth, canvasAreaHeight, page)
    val dpPerMm = zoom * (boxWidth.value / page.width)
    val pivotX = canvasAreaWidth.value / 2.0
    val pivotY = canvasAreaHeight.value / 2.0 - PdfCanvasBadgeBandHeight.value / 2.0
    val originX = pivotX - zoom * boxWidth.value / 2.0
    val originY = pivotY - zoom * boxHeight.value / 2.0
    return Triple(dpPerMm.toDouble(), originX, originY)
}

@Composable
private fun PdfTopRuler(
    page: PdfPage,
    zoom: Float,
    panX: Float,
    unit: PdfUnit,
    dpi: Int,
    canvasAreaWidth: Dp,
    canvasAreaHeight: Dp,
    modifier: Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Canvas(Modifier.fillMaxSize()) {
            val (dpPerMm, originXDp, _) = rulerTransform(page, zoom, canvasAreaWidth, canvasAreaHeight)
            // Matches PdfCanvas's own graphicsLayer: translationX is a plain (unscaled-by-zoom)
            // dp shift, applied in the parent's coordinate space alongside the zoom-about-center
            // scale — not multiplied by zoom.
            val originXPx = originXDp * density.density + panX * density.density
            val widthPx = size.width
            val startMm = (0.0 - originXPx) / (dpPerMm * density.density)
            val endMm = (widthPx - originXPx) / (dpPerMm * density.density)
            val minSpacingPx = with(density) { 28.dp.toPx() }
            val minMajorPx = with(density) { 44.dp.toPx() }
            val minorStep = PdfRulerMath.minorStepMm(unit, dpPerMm * density.density, minSpacingPx.toDouble())
            val majorStep = PdfRulerMath.majorStepMm(minorStep, dpPerMm * density.density, minMajorPx.toDouble())
            val ticks =
                PdfRulerMath.ticks(minOf(startMm, endMm), maxOf(startMm, endMm), minorStep, majorStep, unit, dpi)
            drawIntoCanvas { canvas ->
                val paint =
                    android.graphics.Paint().apply {
                        color = labelColor
                        textSize = with(density) { 9.sp.toPx() }
                        isAntiAlias = true
                    }
                ticks.forEach { tick ->
                    val x = (originXPx + tick.positionMm * dpPerMm * density.density).toFloat()
                    val tickHeight = if (tick.major) size.height * 0.6f else size.height * 0.35f
                    drawLine(
                        color = tickColor,
                        start = androidx.compose.ui.geometry.Offset(x, size.height - tickHeight),
                        end = androidx.compose.ui.geometry.Offset(x, size.height),
                        strokeWidth = 1f,
                    )
                    if (tick.major)
                        canvas.nativeCanvas.drawText(formatTickLabel(tick.labelUnits), x + 2f, size.height * 0.55f, paint)
                }
            }
        }
    }
}

@Composable
private fun PdfLeftRuler(
    page: PdfPage,
    zoom: Float,
    panY: Float,
    unit: PdfUnit,
    dpi: Int,
    canvasAreaWidth: Dp,
    canvasAreaHeight: Dp,
    modifier: Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Canvas(Modifier.fillMaxSize()) {
            val (dpPerMm, _, originYDp) = rulerTransform(page, zoom, canvasAreaWidth, canvasAreaHeight)
            val originYPx = originYDp * density.density + panY * density.density
            val heightPx = size.height
            val startMm = (0.0 - originYPx) / (dpPerMm * density.density)
            val endMm = (heightPx - originYPx) / (dpPerMm * density.density)
            val minSpacingPx = with(density) { 28.dp.toPx() }
            val minMajorPx = with(density) { 44.dp.toPx() }
            val minorStep = PdfRulerMath.minorStepMm(unit, dpPerMm * density.density, minSpacingPx.toDouble())
            val majorStep = PdfRulerMath.majorStepMm(minorStep, dpPerMm * density.density, minMajorPx.toDouble())
            val ticks =
                PdfRulerMath.ticks(minOf(startMm, endMm), maxOf(startMm, endMm), minorStep, majorStep, unit, dpi)
            drawIntoCanvas { canvas ->
                val paint =
                    android.graphics.Paint().apply {
                        color = labelColor
                        textSize = with(density) { 9.sp.toPx() }
                        isAntiAlias = true
                        // Right-aligns each label against the ruler's inner edge (see the
                        // non-rotated drawText call below); the top ruler keeps the Paint default
                        // (LEFT) since it anchors from the tick going rightward instead.
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                ticks.forEach { tick ->
                    val y = (originYPx + tick.positionMm * dpPerMm * density.density).toFloat()
                    val tickWidth = if (tick.major) size.width * 0.6f else size.width * 0.35f
                    drawLine(
                        color = tickColor,
                        start = androidx.compose.ui.geometry.Offset(size.width - tickWidth, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                    // Phase F review fix (MINOR): drawn horizontally and right-aligned against
                    // the ruler's inner edge (nearest the canvas) rather than rotated -90°, which
                    // previously clipped every label down to its first digit once it ran past the
                    // (then 20dp) band width. Right-aligning also keeps labels closest to the tick
                    // they belong to, matching the top ruler's left-aligned-from-the-tick style.
                    if (tick.major)
                        canvas.nativeCanvas.drawText(
                            formatTickLabel(tick.labelUnits),
                            size.width - 4f,
                            y - 3f,
                            paint,
                        )
                }
            }
        }
    }
}

private fun formatTickLabel(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
