package com.librestatic.lightforge.core.frameinterpolation

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

/**
 * Guided counterpart of [interpolateFactor]: flow from the low-res pair, frames composed at high-res.
 * The timesteps run concurrently so the CPU compose of one overlaps the GPU pass of the next.
 */
fun RifeFrameInterpolator.interpolateFactorGuided(
    lowFirst: Bitmap,
    lowSecond: Bitmap,
    highFirst: Bitmap,
    highSecond: Bitmap,
    factor: Int,
): List<Bitmap> {
    require(factor == 2 || factor == 4 || factor == 8)
    val results = java.util.stream.IntStream.range(1, factor).parallel().mapToObj { index ->
        runCatching { interpolateGuided(lowFirst, lowSecond, highFirst, highSecond, index.toFloat() / factor) }
    }.toList()
    val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
    if (failure != null) {
        results.forEach { it.getOrNull()?.recycle() }
        throw failure
    }
    return results.map { it.getOrThrow() }
}
