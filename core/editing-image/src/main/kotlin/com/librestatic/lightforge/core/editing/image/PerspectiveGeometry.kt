package com.librestatic.lightforge.core.editing.image

import com.librestatic.lightforge.core.model.NormalizedPoint
import kotlin.math.abs

/**
 * Homography maths for [com.librestatic.lightforge.core.model.EditOperation.Perspective], kept
 * free of `android.graphics` so JVM tests can cover it. The quad of source corners (TL, TR, BR,
 * BL) is mapped onto the full output rectangle of the same size as the frame.
 */
internal object PerspectiveGeometry {
    /** Source corners in pixels of a [width] x [height] frame, as x0,y0,...,x3,y3. */
    fun cornerPixels(corners: List<NormalizedPoint>, width: Int, height: Int): FloatArray {
        val out = FloatArray(8)
        corners.forEachIndexed { i, p ->
            out[i * 2] = p.x * width
            out[i * 2 + 1] = p.y * height
        }
        return out
    }

    /** Destination corners: the full [width] x [height] rectangle, TL, TR, BR, BL. */
    fun rectPixels(width: Int, height: Int): FloatArray = floatArrayOf(
        0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat(),
    )

    /**
     * Row-major 3x3 values (the order of `Matrix.setValues`) of the homography that maps [corners]
     * of a [width] x [height] frame onto the full frame rectangle.
     */
    fun matrixValues(corners: List<NormalizedPoint>, width: Int, height: Int): FloatArray? {
        val h = solve(cornerPixels(corners, width, height), rectPixels(width, height)) ?: return null
        return FloatArray(9) { h[it].toFloat() }
    }

    /** Solves h (h33 = 1) so that the homography maps each [src] point to the matching [dst] point. */
    fun solve(src: FloatArray, dst: FloatArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = src[i * 2].toDouble()
            val y = src[i * 2 + 1].toDouble()
            val u = dst[i * 2].toDouble()
            val v = dst[i * 2 + 1].toDouble()
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (col in 0 until 8) {
            var pivot = col
            for (row in col + 1 until 8) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            if (abs(a[pivot][col]) < 1e-12) return null
            val swap = a[col]; a[col] = a[pivot]; a[pivot] = swap
            for (row in 0 until 8) {
                if (row == col) continue
                val factor = a[row][col] / a[col][col]
                if (factor == 0.0) continue
                for (k in col..8) a[row][k] -= factor * a[col][k]
            }
        }
        val h = DoubleArray(9)
        for (i in 0 until 8) h[i] = a[i][8] / a[i][i]
        h[8] = 1.0
        return h
    }

    /** Maps ([x], [y]) through the row-major homography [h]. */
    fun map(h: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val w = h[6] * x + h[7] * y + h[8]
        return (h[0] * x + h[1] * y + h[2]) / w to (h[3] * x + h[4] * y + h[5]) / w
    }
}
