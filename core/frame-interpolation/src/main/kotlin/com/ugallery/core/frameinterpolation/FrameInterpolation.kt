package com.ugallery.core.frameinterpolation

import android.graphics.Bitmap

/** Produces ordered intermediate frames without duplicating either endpoint. */
fun RifeFrameInterpolator.interpolateFactor(
    first: Bitmap,
    second: Bitmap,
    factor: Int,
): List<Bitmap> {
    require(factor == 2 || factor == 4 || factor == 8)
    return (1 until factor).map { index -> interpolate(first, second, index.toFloat() / factor) }
}
