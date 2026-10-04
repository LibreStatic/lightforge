package com.librestatic.lightforge.core.editing.image

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Straightening zooms in just enough to hide the corners the tilt exposes, like the video editor
 * does, so the straightened frame keeps the proportions of the frame (and of the crop box) that
 * was tilted instead of drifting to another aspect ratio.
 */
internal object StraightenGeometry {
    /**
     * The largest [width]x[height]-proportioned rectangle, centred and axis aligned, that fits
     * inside the same frame tilted by [degrees].
     */
    fun inscribedSize(width: Int, height: Int, degrees: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val radians = Math.toRadians(abs(degrees).toDouble())
        val sine = sin(radians)
        val cosine = cos(radians)
        val w = width.toDouble()
        val h = height.toDouble()
        val scale = min(1.0, min(w / (w * cosine + h * sine), h / (w * sine + h * cosine)))
        return (w * scale).roundToInt().coerceIn(1, width) to (h * scale).roundToInt().coerceIn(1, height)
    }
}
