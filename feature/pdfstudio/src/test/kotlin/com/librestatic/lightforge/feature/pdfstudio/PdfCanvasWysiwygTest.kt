package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Feedback item A's WYSIWYG requirement: the canvas ([PdfImagePreview] in PdfCanvas.kt, which
 * paints a Compose `Image` with `ContentScale.Crop`/`Fit` and a `BiasAlignment` derived from
 * `focusX`/`focusY`) must paint the exact same geometry the exporter computes via
 * [PdfPrintLayout.contentRect] (used by [PdfProcessingService]). Rather than making the canvas call
 * the shared pure function directly — a refactor of a file with recent, fragile drag/pan/zoom fix
 * history (see git log: "stop ghost element drags, group-outline desync, and stray pan/zoom") —
 * this proves the two independent formulas agree: Compose's `ContentScale.Crop`/`Fit`
 * `computeScaleFactor` is the same `max`/`min` of width/height ratios [PdfPrintLayout.contentRect]
 * uses, and `BiasAlignment(focusX*2-1, focusY*2-1)`'s real-valued position formula
 * `(space - size) * (bias + 1) / 2` is algebraically `(space - size) * focusX` — exactly
 * [PdfPrintLayout.contentRect]'s `x`/`y` formula — up to `BiasAlignment.align`'s integer-pixel
 * rounding (asserted here to within 1px at a representative render resolution).
 */
class PdfCanvasWysiwygTest {
    /** Mirrors [PdfImagePreview]'s `BiasAlignment((focusX * 2 - 1).toFloat(), (focusY * 2 -
     * 1).toFloat())` call exactly. */
    private fun canvasContentRect(
        frameWidthPx: Int,
        frameHeightPx: Int,
        contentWidth: Double,
        contentHeight: Double,
        fit: PdfFit,
        focusX: Double,
        focusY: Double,
    ): Triple<Int, Int, IntSize> {
        val contentScale = if (fit == PdfFit.Cover) ContentScale.Crop else ContentScale.Fit
        val scaleFactor =
            contentScale.computeScaleFactor(
                Size(contentWidth.toFloat(), contentHeight.toFloat()),
                Size(frameWidthPx.toFloat(), frameHeightPx.toFloat()),
            )
        val scaledSize =
            IntSize(
                (contentWidth * scaleFactor.scaleX).roundToInt(),
                (contentHeight * scaleFactor.scaleY).roundToInt(),
            )
        val alignment = BiasAlignment((focusX * 2 - 1).toFloat(), (focusY * 2 - 1).toFloat())
        val offset = alignment.align(scaledSize, IntSize(frameWidthPx, frameHeightPx), LayoutDirection.Ltr)
        return Triple(offset.x, offset.y, scaledSize)
    }

    private fun assertMatchesExporter(
        frameW: Double,
        frameH: Double,
        contentW: Double,
        contentH: Double,
        fit: PdfFit,
        focusX: Double = .5,
        focusY: Double = .5,
    ) {
        // Render at a representative resolution (mirrors the exporter's own dpi-scaled points):
        // enough pixels that integer rounding error stays well under 1mm-equivalent.
        val scalePxPerMm = 20.0
        val frameWpx = (frameW * scalePxPerMm).roundToInt()
        val frameHpx = (frameH * scalePxPerMm).roundToInt()
        val contentWpx = contentW * scalePxPerMm
        val contentHpx = contentH * scalePxPerMm

        val exporter = PdfPrintLayout.contentRect(frameW, frameH, contentW, contentH, fit, focusX, focusY)
        val exporterXpx = exporter.x * scalePxPerMm
        val exporterYpx = exporter.y * scalePxPerMm
        val exporterWpx = exporter.width * scalePxPerMm
        val exporterHpx = exporter.height * scalePxPerMm

        val (canvasX, canvasY, canvasSize) =
            canvasContentRect(frameWpx, frameHpx, contentWpx, contentHpx, fit, focusX, focusY)

        assertEquals("scaled width", exporterWpx, canvasSize.width.toDouble(), 1.0)
        assertEquals("scaled height", exporterHpx, canvasSize.height.toDouble(), 1.0)
        assertEquals("x offset", exporterXpx, canvasX.toDouble(), 1.0)
        assertEquals("y offset", exporterYpx, canvasY.toDouble(), 1.0)
    }

    @Test
    fun `Fill (Cover) matches the canvas for a panoramic photo in a portrait slot`() {
        assertMatchesExporter(frameW = 100.0, frameH = 150.0, contentW = 300.0, contentH = 100.0, fit = PdfFit.Cover)
    }

    @Test
    fun `Fit (Contain) matches the canvas for a panoramic photo in a portrait slot`() {
        assertMatchesExporter(frameW = 100.0, frameH = 150.0, contentW = 300.0, contentH = 100.0, fit = PdfFit.Contain)
    }

    @Test
    fun `Fill matches the canvas for a portrait photo in a landscape slot`() {
        assertMatchesExporter(frameW = 150.0, frameH = 100.0, contentW = 100.0, contentH = 300.0, fit = PdfFit.Cover)
    }

    @Test
    fun `Fit matches the canvas for a portrait photo in a landscape slot`() {
        assertMatchesExporter(frameW = 150.0, frameH = 100.0, contentW = 100.0, contentH = 300.0, fit = PdfFit.Contain)
    }

    @Test
    fun `Fill matches the canvas with an off-center focus point`() {
        assertMatchesExporter(
            frameW = 100.0,
            frameH = 150.0,
            contentW = 300.0,
            contentH = 100.0,
            fit = PdfFit.Cover,
            focusX = 0.0,
            focusY = 1.0,
        )
        assertMatchesExporter(
            frameW = 100.0,
            frameH = 150.0,
            contentW = 300.0,
            contentH = 100.0,
            fit = PdfFit.Cover,
            focusX = 1.0,
            focusY = 0.25,
        )
    }

    @Test
    fun `Fill and Fit agree with the canvas for a 10x15 print slot at typical photo aspect ratios`() {
        // 4:3 and 3:2 camera aspect ratios, the two orientations, both modes.
        for (fit in listOf(PdfFit.Cover, PdfFit.Contain)) {
            assertMatchesExporter(100.0, 150.0, 4000.0, 3000.0, fit)
            assertMatchesExporter(100.0, 150.0, 3000.0, 4000.0, fit)
            assertMatchesExporter(100.0, 150.0, 6000.0, 4000.0, fit)
            assertMatchesExporter(100.0, 150.0, 4000.0, 6000.0, fit)
        }
    }

    @Test
    fun `exact matching aspect ratio produces no crop or letterbox in either mode`() {
        for (fit in listOf(PdfFit.Cover, PdfFit.Contain)) {
            assertMatchesExporter(100.0, 150.0, 200.0, 300.0, fit)
        }
    }
}
