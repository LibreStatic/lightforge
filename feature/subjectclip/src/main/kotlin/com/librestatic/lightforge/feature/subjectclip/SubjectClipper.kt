package com.librestatic.lightforge.feature.subjectclip

import android.graphics.Bitmap
import android.graphics.Color

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
    )

    enum class ClipMethod {
        ML_SEGMENTATION,
        COLOR_DISTANCE_FALLBACK,
    }

    /** Keeps pixels whose color is within [tolerance] of the seed pixel (the tapped subject). */
    fun clipSubject(
        bitmap: Bitmap,
        tolerance: Int = 30,
        seedX: Int = bitmap.width / 2,
        seedY: Int = bitmap.height / 2,
    ): ClipResult {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val centerColor = bitmap.getPixel(seedX.coerceIn(0, width - 1), seedY.coerceIn(0, height - 1))

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val resultPixels = IntArray(width * height)
        val transparent = 0x00000000

        for (i in pixels.indices) {
            val px = pixels[i]
            val distance = colorDistance(px, centerColor)
            if (distance <= tolerance) {
                resultPixels[i] = px
            } else {
                resultPixels[i] = transparent
            }
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)

        val hasTransparency = resultPixels.any { it == transparent }
        return ClipResult(result, hasTransparency, ClipMethod.COLOR_DISTANCE_FALLBACK)
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

    companion object {
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
