@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

/** Float-processing color grade used when the final surface is BT.2020 HLG or PQ. */
class HdrVideoColorGradeEffect(
    grade: VideoColorGrade,
    customLut: CubeLut?,
) : GlEffect {
    private val input = HdrGradeInput(grade, customLut)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        check(useHdr) { "HDR color grading requires an HDR frame-processing surface" }
        return HdrVideoColorGradeShaderProgram(context) { input }
    }
}

/** One immutable grade for [HdrVideoColorGradeShaderProgram]; a new instance means new uniforms. */
internal class HdrGradeInput(val grade: VideoColorGrade, val customLut: CubeLut?)

/**
 * Applies the grade returned by [gradeSource] on every frame. The source is read on the GL thread,
 * and uniforms and the custom LUT texture are only rewritten when it returns a different instance,
 * so a live preview can swap grades without rebuilding the effect chain.
 */
internal class HdrVideoColorGradeShaderProgram(
    context: Context,
    private val gradeSource: () -> HdrGradeInput,
) : BaseGlShaderProgram(/* useHdr= */ true, /* texturePoolCapacity= */ 1) {
    private val program = try {
        GlProgram(context, R.raw.video_hdr_grade_vertex, R.raw.video_hdr_grade_fragment)
    } catch (failure: Exception) {
        throw VideoFrameProcessingException(failure)
    }
    private var appliedInput: HdrGradeInput? = null
    private var appliedLut: CubeLut? = null
    private var lutTexture = Format.NO_VALUE

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    private fun apply(input: HdrGradeInput) {
        val grade = input.grade
        val customLut = input.customLut
        if (lutTexture == Format.NO_VALUE || customLut !== appliedLut) {
            // GlProgram requires every active sampler to be bound even when the shader branch that
            // reads it is disabled. Keep a real identity texture for built-in looks and grading
            // without a .cube.
            val bitmap = customLut?.toAtlasBitmap() ?: Bitmap.createBitmap(
                1,
                1,
                Bitmap.Config.ARGB_8888,
            ).apply { eraseColor(Color.WHITE) }
            val replacement = try {
                GlUtil.createTexture(bitmap)
            } finally {
                bitmap.recycle()
            }
            if (lutTexture != Format.NO_VALUE) GlUtil.deleteTexture(lutTexture)
            lutTexture = replacement
            appliedLut = customLut
        }
        program.setIntUniform("uInputProfile", grade.inputProfile.ordinal)
        program.setIntUniform("uBypass", if (grade.bypass) 1 else 0)
        program.setFloatUniform("uExposure", grade.exposureEv)
        program.setFloatUniform("uTemperature", grade.temperature)
        program.setFloatUniform("uTint", grade.tint)
        program.setFloatUniform("uContrast", grade.contrast)
        program.setFloatUniform("uPivot", grade.pivot)
        program.setFloatUniform("uSaturation", grade.saturation)
        program.setFloatUniform("uToneShadows", grade.shadows)
        program.setFloatUniform("uToneHighlights", grade.highlights)
        program.setFloatUniform("uVibrance", grade.vibrance)
        program.setFloatsUniform("uShadows", grade.logWheels.shadows.toUniform())
        program.setFloatsUniform("uMidtones", grade.logWheels.midtones.toUniform())
        program.setFloatsUniform("uHighlights", grade.logWheels.highlights.toUniform())
        grade.hueBands.forEachIndexed { index, band ->
            program.setFloatsUniform(
                "uBand$index",
                floatArrayOf(band.hueShiftDegrees, band.saturation, band.luminance),
            )
        }
        program.setIntUniform("uLook", grade.lut.builtIn.ordinal)
        program.setFloatUniform("uLutIntensity", grade.lut.intensity)
        program.setIntUniform("uHasCustomLut", if (customLut == null) 0 else 1)
        program.setFloatUniform("uLutSize", customLut?.size?.toFloat() ?: 1f)
        program.setFloatsUniform("uLutDomainMin", customLut?.domainMin ?: floatArrayOf(0f, 0f, 0f))
        program.setFloatsUniform("uLutDomainMax", customLut?.domainMax ?: floatArrayOf(1f, 1f, 1f))
        appliedInput = input
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val input = gradeSource()
            if (input !== appliedInput) apply(input)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uLutSampler", lutTexture, 1)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (failure: GlUtil.GlException) {
            throw VideoFrameProcessingException(failure, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            if (lutTexture != Format.NO_VALUE) GlUtil.deleteTexture(lutTexture)
            lutTexture = Format.NO_VALUE
            program.delete()
        } catch (failure: GlUtil.GlException) {
            throw VideoFrameProcessingException(failure)
        }
    }
}

private fun LogWheel.toUniform() = floatArrayOf(red, green, blue, level)

/** Blue slices laid horizontally; green is the atlas row and red varies inside each slice. */
private fun CubeLut.toAtlasBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(size * size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size * size)
    for (green in 0 until size) for (blue in 0 until size) for (red in 0 until size) {
        val source = ((blue * size + green) * size + red) * 3
        val destination = green * size * size + blue * size + red
        fun channel(offset: Int) = (values[source + offset].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        pixels[destination] = (0xFF shl 24) or
            (channel(0) shl 16) or
            (channel(1) shl 8) or
            channel(2)
    }
    bitmap.setPixels(pixels, 0, size * size, 0, 0, size * size, size)
    return bitmap
}
