package com.librestatic.lightforge.feature.objecteraser

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Integer pixel rectangle, [right] and [bottom] exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun union(other: PixelRect) =
        PixelRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun intersects(other: PixelRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun expandedBy(amount: Int) = PixelRect(left - amount, top - amount, right + amount, bottom + amount)
}

/** A round brush dab in image pixels: the circle inscribed in an [ObjectEraser.EraseRegion]. */
data class BrushDab(val centerX: Float, val centerY: Float, val radius: Float) {
    val bounds get() = PixelRect(
        floor(centerX - radius).toInt(), floor(centerY - radius).toInt(),
        ceil(centerX + radius).toInt(), ceil(centerY + radius).toInt(),
    )

    companion object {
        fun of(region: ObjectEraser.EraseRegion) = BrushDab(
            region.x + region.width / 2f, region.y + region.height / 2f, min(region.width, region.height) / 2f,
        )
    }
}

/** One model pass: the [crop] of the image the model sees, and the [dabs] it fills inside it. */
data class InpaintPass(val crop: PixelRect, val holes: PixelRect, val dabs: List<BrushDab>)

/**
 * Geometry and tensor encoding for MI-GAN 512, kept free of Android types so it is unit tested on the JVM.
 *
 * Each group of nearby dabs gets its own square crop around it with as much context again as the hole, never
 * smaller than the model input, so a small mark keeps native detail and a large one still sees its surroundings.
 */
object InpaintPlan {
    const val ModelSize = 512

    /** Extra context around a hole, as a fraction of the hole's longer side, on each side. */
    private const val ContextFraction = 0.5f

    /** Model-space hole growth, in model pixels, so anti-aliased object edges are filled too. */
    private const val MaskGrowth = 2f

    fun passes(regions: List<ObjectEraser.EraseRegion>, imageWidth: Int, imageHeight: Int): List<InpaintPass> {
        require(imageWidth > 0 && imageHeight > 0)
        val image = PixelRect(0, 0, imageWidth, imageHeight)
        val dabs = regions.map(BrushDab::of).filter { it.radius > 0f && it.bounds.intersects(image) }
        if (dabs.isEmpty()) return emptyList()
        // Dabs closer than a brush diameter share one pass, so a stroke is filled as a whole.
        val parent = IntArray(dabs.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        for (i in dabs.indices) {
            val near = dabs[i].bounds.expandedBy((dabs[i].radius * 2f).roundToInt())
            for (j in i + 1 until dabs.size) if (near.intersects(dabs[j].bounds)) parent[root(j)] = root(i)
        }
        return dabs.indices.groupBy(::root).values.map { members ->
            val group = members.map { dabs[it] }
            val holes = clamp(group.map { it.bounds }.reduce(PixelRect::union), image)
            InpaintPass(cropAround(holes, imageWidth, imageHeight), holes, group)
        }
    }

    /** A square crop centered on [holes], shifted inside the image; narrower only when the image is. */
    fun cropAround(holes: PixelRect, imageWidth: Int, imageHeight: Int): PixelRect {
        val longer = max(holes.width, holes.height)
        val side = max(ModelSize, (longer * (1f + 2f * ContextFraction)).roundToInt())
        val width = min(side, imageWidth)
        val height = min(side, imageHeight)
        val left = ((holes.left + holes.right) / 2 - width / 2).coerceIn(0, imageWidth - width)
        val top = ((holes.top + holes.bottom) / 2 - height / 2).coerceIn(0, imageHeight - height)
        return PixelRect(left, top, left + width, top + height)
    }

    /** The model mask for [pass]: true where a pixel is kept, false where the model fills it. */
    fun keepMask(pass: InpaintPass): BooleanArray {
        val keep = BooleanArray(ModelSize * ModelSize) { true }
        val scaleX = ModelSize.toFloat() / pass.crop.width
        val scaleY = ModelSize.toFloat() / pass.crop.height
        for (dab in pass.dabs) {
            val cx = (dab.centerX - pass.crop.left) * scaleX
            val cy = (dab.centerY - pass.crop.top) * scaleY
            val rx = dab.radius * scaleX + MaskGrowth
            val ry = dab.radius * scaleY + MaskGrowth
            val top = floor(cy - ry).toInt().coerceAtLeast(0)
            val bottom = ceil(cy + ry).toInt().coerceAtMost(ModelSize)
            val left = floor(cx - rx).toInt().coerceAtLeast(0)
            val right = ceil(cx + rx).toInt().coerceAtMost(ModelSize)
            for (y in top until bottom) {
                val dy = (y + 0.5f - cy) / ry
                for (x in left until right) {
                    val dx = (x + 0.5f - cx) / rx
                    if (dx * dx + dy * dy <= 1f) keep[y * ModelSize + x] = false
                }
            }
        }
        return keep
    }

    /** Image pixels of the model's grown hole outside each dab; the output is blended over this band. */
    fun feather(pass: InpaintPass): Float = max(1f, MaskGrowth * max(pass.crop.width, pass.crop.height) / ModelSize)

    /**
     * Blend weights of the model output over [area] (row-major): 1 inside a dab, fading to 0 over [feather]
     * pixels outside it, so filled areas meet the original without a hard seam. Costs the dabs' area, not
     * area times dabs, so long strokes on full-resolution photos stay cheap.
     */
    fun coverage(dabs: List<BrushDab>, area: PixelRect, feather: Float): FloatArray {
        val weights = FloatArray(area.width * area.height)
        for (dab in dabs) {
            val reach = dab.radius + feather
            val top = floor(dab.centerY - reach).toInt().coerceAtLeast(area.top)
            val bottom = ceil(dab.centerY + reach).toInt().coerceAtMost(area.bottom)
            val left = floor(dab.centerX - reach).toInt().coerceAtLeast(area.left)
            val right = ceil(dab.centerX + reach).toInt().coerceAtMost(area.right)
            for (y in top until bottom) {
                val dy = y + 0.5f - dab.centerY
                val row = (y - area.top) * area.width - area.left
                for (x in left until right) {
                    val dx = x + 0.5f - dab.centerX
                    val outside = sqrt(dx * dx + dy * dy) - dab.radius
                    val weight = if (outside <= 0f) 1f else 1f - outside / feather
                    if (weight > weights[row + x]) weights[row + x] = weight
                }
            }
        }
        return weights
    }

    /** NHWC model input: concat(mask - 0.5, (rgb * 2 - 1) * mask), mask 1 where kept. */
    fun encode(argb: IntArray, keep: BooleanArray): FloatArray {
        require(argb.size == ModelSize * ModelSize && keep.size == argb.size)
        val input = FloatArray(argb.size * 4)
        for (i in argb.indices) {
            val o = i * 4
            if (keep[i]) {
                val p = argb[i]
                input[o] = 0.5f
                input[o + 1] = ((p ushr 16) and 255) / 127.5f - 1f
                input[o + 2] = ((p ushr 8) and 255) / 127.5f - 1f
                input[o + 3] = (p and 255) / 127.5f - 1f
            } else input[o] = -0.5f
        }
        return input
    }

    /** Opaque ARGB pixels from the NHWC model output in [-1, 1]. */
    fun decode(output: FloatArray): IntArray {
        require(output.size == ModelSize * ModelSize * 3)
        require(output.all(Float::isFinite)) { "Inpainting produced non-finite values" }
        fun channel(value: Float) = ((value + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
        return IntArray(ModelSize * ModelSize) { i ->
            val o = i * 3
            (0xFF shl 24) or (channel(output[o]) shl 16) or (channel(output[o + 1]) shl 8) or channel(output[o + 2])
        }
    }

    private fun clamp(rect: PixelRect, image: PixelRect) = PixelRect(
        rect.left.coerceIn(0, image.right), rect.top.coerceIn(0, image.bottom),
        rect.right.coerceIn(0, image.right), rect.bottom.coerceIn(0, image.bottom),
    )
}
