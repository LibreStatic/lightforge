package com.librestatic.lightforge.feature.subjectclip

import android.graphics.Bitmap

/**
 * Subject clipping using a local segmentation approach.
 *
 * This implementation uses a simple color-based segmentation as a
 * fallback when ML Kit Subject Segmentation is not available.
 *
 * No cloud, no network - all processing is local.
 */
class SubjectClipper {

    data class ClipResult(
        val bitmap: Bitmap,
        val hasTransparency: Boolean,
        val method: ClipMethod,
        /** Number of pixels kept as the subject. */
        val keptPixels: Int = 0,
    ) {
        /** True when the tap selected too little to be a usable subject. */
        val isEmpty: Boolean get() = keptPixels < minSubjectPixels(bitmap.width, bitmap.height)
    }

    enum class ClipMethod {
        ML_SEGMENTATION,
        COLOR_DISTANCE_FALLBACK,
    }

    /**
     * Keeps the region connected to the seed pixel (the tapped subject) whose colors are within
     * [tolerance] of the seed color; same-colored areas elsewhere in the frame are not included.
     */
    fun clipSubject(
        bitmap: Bitmap,
        tolerance: Int = 30,
        seedX: Int = bitmap.width / 2,
        seedY: Int = bitmap.height / 2,
    ): ClipResult {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val mask = connectedMask(pixels, width, height, seedX.coerceIn(0, width - 1), seedY.coerceIn(0, height - 1), tolerance)
        val resultPixels = IntArray(width * height)
        var kept = 0
        for (i in pixels.indices) {
            if (mask[i]) {
                resultPixels[i] = pixels[i]
                kept++
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return ClipResult(result, kept < pixels.size, ClipMethod.COLOR_DISTANCE_FALLBACK, kept)
    }

    /** Crops [clip] to the bounding box of its kept pixels plus a small margin; recycles the source. */
    fun cropToSubject(clip: ClipResult): ClipResult {
        val source = clip.bitmap
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val bounds = opaqueBounds(pixels, width, height) ?: return clip
        val margin = (maxOf(bounds.right - bounds.left, bounds.bottom - bounds.top) * 0.02f).toInt().coerceAtLeast(1)
        val left = (bounds.left - margin).coerceAtLeast(0)
        val top = (bounds.top - margin).coerceAtLeast(0)
        val right = (bounds.right + margin).coerceAtMost(width)
        val bottom = (bounds.bottom + margin).coerceAtMost(height)
        if (left == 0 && top == 0 && right == width && bottom == height) return clip
        val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        if (cropped !== source) source.recycle()
        return clip.copy(bitmap = cropped)
    }

    fun clipRegion(
        bitmap: Bitmap,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): ClipResult {
        val clipped = Bitmap.createBitmap(
            bitmap,
            x.coerceIn(0, bitmap.width - 1),
            y.coerceIn(0, bitmap.height - 1),
            width.coerceAtMost(bitmap.width - x),
            height.coerceAtMost(bitmap.height - y),
        )
        return ClipResult(clipped, false, ClipMethod.COLOR_DISTANCE_FALLBACK)
    }

    data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        /** A tap that keeps less than this share of the frame (min 16 px) is treated as "no subject". */
        fun minSubjectPixels(width: Int, height: Int): Int =
            maxOf(16, (width.toLong() * height / 1000).toInt())

        /** Flood fill from the seed over 4-connected pixels within [tolerance] of the seed color. */
        fun connectedMask(pixels: IntArray, width: Int, height: Int, seedX: Int, seedY: Int, tolerance: Int): BooleanArray {
            val mask = BooleanArray(width * height)
            val seedColor = pixels[seedY * width + seedX]
            val stack = IntArray(width * height)
            var top = 0
            val start = seedY * width + seedX
            mask[start] = true
            stack[top++] = start
            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                if (x > 0) top = visit(pixels, mask, stack, top, index - 1, seedColor, tolerance)
                if (x < width - 1) top = visit(pixels, mask, stack, top, index + 1, seedColor, tolerance)
                if (y > 0) top = visit(pixels, mask, stack, top, index - width, seedColor, tolerance)
                if (y < height - 1) top = visit(pixels, mask, stack, top, index + width, seedColor, tolerance)
            }
            return mask
        }

        private fun visit(pixels: IntArray, mask: BooleanArray, stack: IntArray, top: Int, index: Int, seed: Int, tolerance: Int): Int {
            if (mask[index] || colorDistance(pixels[index], seed) > tolerance) return top
            mask[index] = true
            stack[top] = index
            return top + 1
        }

        /** Bounding box (right/bottom exclusive) of pixels with non-zero alpha, or null if none. */
        fun opaqueBounds(pixels: IntArray, width: Int, height: Int): PixelBounds? {
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if ((pixels[y * width + x] ushr 24) != 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            return if (maxX < 0) null else PixelBounds(minX, minY, maxX + 1, maxY + 1)
        }

        /**
         * Euclidean distance between two ARGB colors in RGB space.
         * Uses pure bit operations - no android.graphics.Color dependency.
         */
        fun colorDistance(c1: Int, c2: Int): Int {
            val r1 = (c1 shr 16) and 0xFF
            val g1 = (c1 shr 8) and 0xFF
            val b1 = c1 and 0xFF
            val r2 = (c2 shr 16) and 0xFF
            val g2 = (c2 shr 8) and 0xFF
            val b2 = c2 and 0xFF
            return kotlin.math.sqrt(
                ((r1 - r2) * (r1 - r2) +
                 (g1 - g2) * (g1 - g2) +
                 (b1 - b2) * (b1 - b2)).toDouble()
            ).toInt()
        }
    }
}
