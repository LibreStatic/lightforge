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
     * Fallback eraser: fills the round dabs inscribed in [regions] (the shapes the brush preview shows) from the
     * colors around them. Colors flow inward one ring at a time from the pixels that are not masked, so a
     * neighboring dab never feeds the object's own color back in, then a few smoothing passes soften the rings.
     *
     * @param bitmap The source bitmap (not modified)
     * @param regions Square regions whose inscribed circles are erased
     * @return A new bitmap with the masked pixels filled
     */
    fun eraseRegions(
        bitmap: Bitmap,
        regions: List<EraseRegion>,
    ): EraseResult {
        require(regions.isNotEmpty()) { "At least one region required" }
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        fillMasked(result, regions)
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

    private fun fillMasked(target: Bitmap, regions: List<EraseRegion>) {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (r in regions) {
            left = minOf(left, r.x)
            top = minOf(top, r.y)
            right = maxOf(right, r.x + r.width)
            bottom = maxOf(bottom, r.y + r.height)
        }
        // One pixel of unmasked margin supplies the colors the fill starts from.
        left = (left - 1).coerceAtLeast(0)
        top = (top - 1).coerceAtLeast(0)
        right = (right + 1).coerceAtMost(target.width)
        bottom = (bottom + 1).coerceAtMost(target.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        val mask = BooleanArray(w * h)
        var masked = 0
        for (r in regions) {
            val radius = minOf(r.width, r.height) / 2f
            if (radius <= 0f) continue
            val cx = r.x + r.width / 2f
            val cy = r.y + r.height / 2f
            for (y in maxOf(top, floor(cy - radius).toInt())..minOf(bottom - 1, ceil(cy + radius).toInt())) {
                for (x in maxOf(left, floor(cx - radius).toInt())..minOf(right - 1, ceil(cx + radius).toInt())) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    val index = (y - top) * w + (x - left)
                    if (dx * dx + dy * dy <= radius * radius && !mask[index]) {
                        mask[index] = true
                        masked++
                    }
                }
            }
        }
        if (masked == 0) return
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, left, top, w, h)
        val known = BooleanArray(w * h) { !mask[it] }
        val queued = BooleanArray(w * h)
        val filledOrder = IntArray(masked)
        var filledCount = 0
        var frontier = ArrayList<Int>()
        fun enqueue(index: Int) {
            if (mask[index] && !known[index] && !queued[index]) {
                queued[index] = true
                frontier.add(index)
            }
        }
        fun addNeighbors(index: Int) {
            val x = index % w
            val y = index / w
            if (x > 0) enqueue(index - 1)
            if (x < w - 1) enqueue(index + 1)
            if (y > 0) enqueue(index - w)
            if (y < h - 1) enqueue(index + w)
        }
        for (index in mask.indices) {
            if (!mask[index]) addNeighbors(index)
        }
        while (frontier.isNotEmpty()) {
            val wave = frontier
            frontier = ArrayList()
            val colors = IntArray(wave.size)
            for (i in wave.indices) colors[i] = averageKnown(wave[i], pixels, known, w, h)
            for (i in wave.indices) {
                val index = wave[i]
                pixels[index] = colors[i]
                known[index] = true
                filledOrder[filledCount++] = index
            }
            for (index in wave) addNeighbors(index)
        }
        // A mask with no known pixel to start from (the whole image) falls back to neutral gray.
        for (index in mask.indices) {
            if (mask[index] && !known[index]) {
                pixels[index] = 0xFF808080.toInt()
                filledOrder[filledCount++] = index
            }
        }
        if (masked <= MaxSmoothedPixels) smooth(pixels, filledOrder, filledCount, w, h)
        target.setPixels(pixels, 0, w, left, top, w, h)
    }

    private fun averageKnown(index: Int, pixels: IntArray, known: BooleanArray, w: Int, h: Int): Int {
        val x = index % w
        val y = index / w
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val neighbor = ny * w + nx
                if (!known[neighbor]) continue
                val c = pixels[neighbor]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        if (n == 0) return 0xFF808080.toInt()
        return (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    /** Jacobi passes over the filled pixels only; the surrounding photo stays untouched. */
    private fun smooth(pixels: IntArray, order: IntArray, count: Int, w: Int, h: Int) {
        val next = IntArray(count)
        repeat(SmoothingPasses) {
            for (i in 0 until count) {
                val index = order[i]
                val x = index % w
                val y = index / w
                val l = pixels[if (x > 0) index - 1 else index]
                val r = pixels[if (x < w - 1) index + 1 else index]
                val u = pixels[if (y > 0) index - w else index]
                val d = pixels[if (y < h - 1) index + w else index]
                next[i] = blendColor(blendColor(l, r, 0.5f), blendColor(u, d, 0.5f), 0.5f)
            }
            for (i in 0 until count) pixels[order[i]] = next[i]
        }
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

        /** Larger masks skip smoothing to keep full-resolution saves quick. */
        const val MaxSmoothedPixels = 2_000_000
        const val SmoothingPasses = 12
    }
}
