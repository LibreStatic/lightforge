package com.librestatic.lightforge.core.editing.image.grade

import android.graphics.Bitmap

/**
 * Applies the ADR-045 colour grade to a (tile of a) geometry-applied frame. Implementations must
 * agree with [PhotoGradeMath] within 2/255.
 */
interface PhotoGrader {
    /**
     * Returns a new bitmap with [params] applied to [input], which sits at ([tileOriginX],
     * [tileOriginY]) inside a [frameWidth] x [frameHeight] frame. [base] is only read when
     * [PhotoGradeParams.needsLocalBase]. Identity params may return a plain copy.
     */
    fun grade(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int = 0,
        tileOriginY: Int = 0,
        frameWidth: Int = input.width,
        frameHeight: Int = input.height,
    ): Bitmap
}

/** The reference grader: reads the pixels, runs [PhotoGradeMath] and writes them back. */
class CpuPhotoGrader : PhotoGrader {
    override fun grade(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Bitmap {
        val width = input.width
        val height = input.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (params.isIdentity) {
            val pixels = IntArray(width * height)
            input.getPixels(pixels, 0, width, 0, 0, width, height)
            output.setPixels(pixels, 0, width, 0, 0, width, height)
            return output
        }
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        // getPixels/setPixels use straight alpha; the maths works on premultiplied pixels.
        premultiply(pixels)
        val graded = PhotoGradeMath.grade(
            pixels, width, height, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight,
        )
        unpremultiply(graded)
        output.setPixels(graded, 0, width, 0, 0, width, height)
        return output
    }

    private fun premultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 255) continue
            val r = Math.round(((p shr 16) and 0xff) * a / 255f)
            val g = Math.round(((p shr 8) and 0xff) * a / 255f)
            val b = Math.round((p and 0xff) * a / 255f)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun unpremultiply(pixels: IntArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            if (a == 255 || a == 0) continue
            val r = Math.min(255, Math.round(((p shr 16) and 0xff) * 255f / a))
            val g = Math.min(255, Math.round(((p shr 8) and 0xff) * 255f / a))
            val b = Math.min(255, Math.round((p and 0xff) * 255f / a))
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
