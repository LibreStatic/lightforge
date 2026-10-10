package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/** Deterministic textured pattern expressed in a 1280-wide coordinate system, shifted horizontally. */
internal fun texturedBitmap(width: Int, height: Int, shiftPixels: Float): Bitmap {
    val unit = 1280.0 / width
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val py = y * unit
        for (x in 0 until width) {
            val px = (x - shiftPixels) * unit
            val r = 128 + 50 * sin(px * 2 * PI / 97) * sin(py * 2 * PI / 61) + 40 * sin((px + py) * 2 * PI / 23)
            val g = 128 + 55 * sin(px * 2 * PI / 53 + 1) + 35 * sin(py * 2 * PI / 17 + px * 0.01)
            val b = 128 + 45 * sin((px - py) * 2 * PI / 131) + 40 * sin(px * 2 * PI / 11) * sin(py * 2 * PI / 13)
            pixels[y * width + x] = (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** Mean absolute difference per channel (0..255), ignoring a border of [margin] pixels. */
internal fun meanAbsDiff(a: Bitmap, b: Bitmap, margin: Int = 0): Double {
    require(a.width == b.width && a.height == b.height)
    val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
    val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    var sum = 0L
    var count = 0L
    for (y in margin until a.height - margin) for (x in margin until a.width - margin) {
        val p = pa[y * a.width + x]
        val q = pb[y * a.width + x]
        sum += abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
            abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
        count += 3
    }
    return sum.toDouble() / count
}

internal fun psnr(a: Bitmap, b: Bitmap, margin: Int = 0): Double {
    val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
    val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    var sum = 0.0
    var count = 0L
    for (y in margin until a.height - margin) for (x in margin until a.width - margin) {
        val p = pa[y * a.width + x]
        val q = pb[y * a.width + x]
        for (s in intArrayOf(16, 8, 0)) {
            val d = ((p shr s) and 255) - ((q shr s) and 255)
            sum += d * d
            count++
        }
    }
    val mse = sum / count
    return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
}
