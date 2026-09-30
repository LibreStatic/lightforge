package com.librestatic.lightforge.feature.objecteraser

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Object eraser using local inpainting.
 *
 * This implementation uses a simple texture-synthesis inpainting approach
 * as a fallback. The erased region is filled by sampling neighboring pixels
 * and applying a simple blur/interpolation.
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
}
