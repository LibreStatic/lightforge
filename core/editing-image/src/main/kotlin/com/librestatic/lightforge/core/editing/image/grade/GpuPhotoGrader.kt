package com.librestatic.lightforge.core.editing.image.grade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Trace
import android.util.Log

/**
 * The GLES 3.0 grader of ADR-045. Inputs larger than [GlImageEngine.MaxTileSide] px are graded in
 * sub-tiles that carry their global origin, so the result equals a whole-frame grade. If EGL/GLES3
 * or the shader is unavailable it logs once and delegates to [fallback] from then on.
 */
class GpuPhotoGrader internal constructor(
    private val engine: () -> GlImageEngine,
    private val fallback: PhotoGrader = CpuPhotoGrader(),
) : PhotoGrader {
    constructor(context: Context, fallback: PhotoGrader = CpuPhotoGrader()) :
        this({ GlImageEngine.get(context) }, fallback)

    @Volatile private var failed = false

    override fun grade(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Bitmap {
        if (failed) return fallback.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        if (params.isIdentity) {
            return input.copy(Bitmap.Config.ARGB_8888, false)
                ?: fallback.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        }
        // Perfetto slice for the latency budget (grade + read back, per call).
        Trace.beginSection("PhotoGrade.gpu")
        return try {
            gradeOnGpu(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        } catch (failure: GlUnavailableException) {
            markFailed(failure)
            fallback.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        } catch (failure: GlOperationException) {
            Log.w(Tag, "GPU grade failed; using the CPU grader for this call", failure)
            fallback.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        } finally {
            Trace.endSection()
        }
    }

    /** Frees the GL context and its thread resources now. */
    fun release() = engine().release()

    private fun gradeOnGpu(
        input: Bitmap,
        params: PhotoGradeParams,
        base: PhotoLocalBase?,
        tileOriginX: Int,
        tileOriginY: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Bitmap {
        val gl = engine()
        val side = GlImageEngine.MaxTileSide
        if (input.width <= side && input.height <= side) {
            return gl.grade(input, params, base, tileOriginX, tileOriginY, frameWidth, frameHeight)
        }
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        var y = 0
        while (y < input.height) {
            val h = minOf(side, input.height - y)
            var x = 0
            while (x < input.width) {
                val w = minOf(side, input.width - x)
                val sub = Bitmap.createBitmap(input, x, y, w, h)
                val graded = try {
                    gl.grade(sub, params, base, tileOriginX + x, tileOriginY + y, frameWidth, frameHeight)
                } finally {
                    if (sub !== input) sub.recycle()
                }
                canvas.drawBitmap(graded, x.toFloat(), y.toFloat(), copy)
                graded.recycle()
                x += w
            }
            y += h
        }
        return output
    }

    private fun markFailed(failure: Throwable) {
        if (!failed) {
            failed = true
            Log.w(Tag, "GLES 3 grading unavailable; falling back to the CPU grader", failure)
        }
    }

    private companion object { const val Tag = "GpuPhotoGrader" }
}

object PhotoGraders {
    /** GPU grader that falls back to the CPU reference when GLES 3 is unavailable. One shared instance. */
    @Volatile private var shared: GpuPhotoGrader? = null

    fun default(context: Context): PhotoGrader =
        shared ?: synchronized(this) { shared ?: GpuPhotoGrader(context.applicationContext).also { shared = it } }

    /** Frees the shared GL context, e.g. from `onTrimMemory` or when the editor closes. */
    fun trim(context: Context) = GlImageEngine.get(context).trim()
}
