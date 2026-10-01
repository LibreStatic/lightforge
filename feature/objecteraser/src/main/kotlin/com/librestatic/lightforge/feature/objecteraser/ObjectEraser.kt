package com.librestatic.lightforge.feature.objecteraser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Object eraser using local inpainting.
 *
 * With the downloaded MI-GAN model ([inpaintRegions]) each group of brush dabs is filled by the model from a
 * crop around it. Without it, [eraseRegions] is an explicit fallback that interpolates the colors around each
 * brushed square.
 *
 * No cloud, no network - all processing is local.
 * The original bitmap is never modified; a copy is returned.
 */
class ObjectEraser {

    data class EraseRegion(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    data class EraseResult(
        val bitmap: Bitmap,
        val regionsErased: Int,
        val method: EraseMethod,
    )

    enum class EraseMethod {
        ML_INPAINTING,
        NEIGHBOR_INTERPOLATION_FALLBACK,
    }

    /**
     * Erases the specified regions by filling them with interpolated neighbor pixels.
     *
     * @param bitmap The source bitmap (not modified)
     * @param regions List of rectangular regions to erase
     * @return A new bitmap with erased regions filled
     */
    fun eraseRegions(
        bitmap: Bitmap,
        regions: List<EraseRegion>,
    ): EraseResult {
        require(regions.isNotEmpty()) { "At least one region required" }

        val width = bitmap.width
        val height = bitmap.height
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        for (region in regions) {
            eraseRegion(result, region, width, height)
        }

        return EraseResult(result, regions.size, EraseMethod.NEIGHBOR_INTERPOLATION_FALLBACK)
    }

    /**
     * Fills the round dabs inscribed in [regions] with [inpainter]. Each pass scales a crop around one group of
     * dabs to the model input, and the output is blended back at full resolution in row bands, so memory stays
     * bounded on large photos. [ensureActive] is called between passes and bands so callers can cancel.
     */
    fun inpaintRegions(
        bitmap: Bitmap,
        regions: List<EraseRegion>,
        inpainter: MiganInpainter,
        ensureActive: () -> Unit = {},
    ): EraseResult {
        require(regions.isNotEmpty()) { "At least one region required" }
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val size = InpaintPlan.ModelSize
            val modelInput = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            try {
                for (pass in InpaintPlan.passes(regions, result.width, result.height)) {
                    ensureActive()
                    modelInput.eraseColor(0)
                    Canvas(modelInput).drawBitmap(
                        result,
                        Rect(pass.crop.left, pass.crop.top, pass.crop.right, pass.crop.bottom),
                        Rect(0, 0, size, size),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    modelInput.getPixels(pixels, 0, size, 0, 0, size, size)
                    val filled = inpainter.inpaint(pixels, InpaintPlan.keepMask(pass))
                    blend(result, pass, filled, ensureActive)
                }
            } finally {
                modelInput.recycle()
            }
            return EraseResult(result, regions.size, EraseMethod.ML_INPAINTING)
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    /** Blends the model output [filled] (the pass crop at model size) into [target] around the pass dabs. */
    private fun blend(target: Bitmap, pass: InpaintPass, filled: IntArray, ensureActive: () -> Unit) {
        val feather = InpaintPlan.feather(pass)
        val grow = ceil(feather).toInt()
        val area = PixelRect(
            (pass.holes.left - grow).coerceAtLeast(pass.crop.left),
            (pass.holes.top - grow).coerceAtLeast(pass.crop.top),
            (pass.holes.right + grow).coerceAtMost(pass.crop.right),
            (pass.holes.bottom + grow).coerceAtMost(pass.crop.bottom),
        )
        if (area.width <= 0 || area.height <= 0) return
        val size = InpaintPlan.ModelSize
        val scaleX = size.toFloat() / pass.crop.width
        val scaleY = size.toFloat() / pass.crop.height
        val band = IntArray(area.width * BandRows)
        var top = area.top
        while (top < area.bottom) {
            ensureActive()
            val rows = minOf(BandRows, area.bottom - top)
            val bandArea = PixelRect(area.left, top, area.right, top + rows)
            val weights = InpaintPlan.coverage(pass.dabs, bandArea, feather)
            target.getPixels(band, 0, area.width, area.left, top, area.width, rows)
            for (row in 0 until rows) {
                // Bilinear sample of the model output at this pixel's center.
                val my = ((top + row + 0.5f - pass.crop.top) * scaleY - 0.5f).coerceIn(0f, size - 1f)
                val y0 = floor(my).toInt(); val y1 = minOf(y0 + 1, size - 1); val fy = my - y0
                for (column in 0 until area.width) {
                    val index = row * area.width + column
                    val weight = weights[index]
                    if (weight <= 0f) continue
                    val mx = ((area.left + column + 0.5f - pass.crop.left) * scaleX - 0.5f).coerceIn(0f, size - 1f)
                    val x0 = floor(mx).toInt(); val x1 = minOf(x0 + 1, size - 1); val fx = mx - x0
                    val sampled = bilinear(
                        filled[y0 * size + x0], filled[y0 * size + x1], filled[y1 * size + x0], filled[y1 * size + x1], fx, fy,
                    )
                    band[index] = if (weight >= 1f) sampled else blendColor(band[index], sampled, weight)
                }
            }
            target.setPixels(band, 0, area.width, area.left, top, area.width, rows)
            top += rows
        }
    }

    private fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int =
        blendColor(blendColor(c00, c10, fx), blendColor(c01, c11, fx), fy)

    /**
     * Erases a single region by interpolating from border pixels.
     * Uses bilinear interpolation from the nearest non-erased pixels.
     */
    private fun eraseRegion(
        bitmap: Bitmap,
        region: EraseRegion,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ) {
        val x1 = region.x.coerceIn(0, bitmapWidth - 1)
        val y1 = region.y.coerceIn(0, bitmapHeight - 1)
        val x2 = (region.x + region.width).coerceAtMost(bitmapWidth)
        val y2 = (region.y + region.height).coerceAtMost(bitmapHeight)

        if (x1 >= x2 || y1 >= y2) return

        // Sample border pixels
        val leftColors = mutableListOf<Int>()
        val rightColors = mutableListOf<Int>()
        val topColors = mutableListOf<Int>()
        val bottomColors = mutableListOf<Int>()

        for (y in y1 until y2) {
            if (x1 > 0) leftColors.add(bitmap.getPixel(x1 - 1, y))
            if (x2 < bitmapWidth) rightColors.add(bitmap.getPixel(x2, y))
        }
        for (x in x1 until x2) {
            if (y1 > 0) topColors.add(bitmap.getPixel(x, y1 - 1))
            if (y2 < bitmapHeight) bottomColors.add(bitmap.getPixel(x, y2))
        }

        val avgLeft = averageColor(leftColors)
        val avgRight = averageColor(rightColors)
        val avgTop = averageColor(topColors)
        val avgBottom = averageColor(bottomColors)

        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val tX = if (x2 > x1) (x - x1).toFloat() / (x2 - x1) else 0.5f
                val tY = if (y2 > y1) (y - y1).toFloat() / (y2 - y1) else 0.5f

                val horizontalColor = blendColor(avgLeft, avgRight, tX)
                val verticalColor = blendColor(avgTop, avgBottom, tY)
                val finalColor = blendColor(horizontalColor, verticalColor, 0.5f)

                bitmap.setPixel(x, y, finalColor)
            }
        }
    }

    private fun averageColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFF808080.toInt()
        var r = 0; var g = 0; var b = 0
        for (c in colors) {
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
        r /= colors.size
        g /= colors.size
        b /= colors.size
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val r = ((c1 shr 16) and 0xFF) * (1 - t) + ((c2 shr 16) and 0xFF) * t
        val g = ((c1 shr 8) and 0xFF) * (1 - t) + ((c2 shr 8) and 0xFF) * t
        val b = (c1 and 0xFF) * (1 - t) + (c2 and 0xFF) * t
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    private companion object {
        /** Rows blended per band; bounds the pixel buffers on full-resolution photos. */
        const val BandRows = 128
    }
}
