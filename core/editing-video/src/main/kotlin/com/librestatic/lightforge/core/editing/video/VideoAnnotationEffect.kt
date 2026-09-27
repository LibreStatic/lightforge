@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import java.util.concurrent.atomic.AtomicReference

/** A timestamp-aware annotation effect shared by ExoPlayer preview and Transformer export. */
class VideoAnnotationEffect(initialLayers: List<VideoAnnotationLayer>) : GlEffect {
    private val layers = AtomicReference(initialLayers.toList())

    fun updateLayers(updated: List<VideoAnnotationLayer>) {
        layers.set(updated.toList())
    }

    // Preview instances start empty and receive layers later, so this mutable effect must remain
    // installed for the lifetime of the input stream.
    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram =
        VideoAnnotationShaderProgram(context, useHdr, layers)
}

private class VideoAnnotationShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val layers: AtomicReference<List<VideoAnnotationLayer>>,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program = try {
        GlProgram(context, R.raw.video_annotation_vertex, R.raw.video_annotation_fragment)
    } catch (failure: Exception) {
        throw VideoFrameProcessingException(failure)
    }
    private var inputWidth = 1
    private var inputHeight = 1
    private var inkBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var inkTexture = -1
    private var maskTexture = -1

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

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val scale = (MAX_MASK_DIMENSION.toFloat() / maxOf(inputWidth, inputHeight)).coerceAtMost(1f)
        val width = (inputWidth * scale).toInt().coerceAtLeast(1)
        val height = (inputHeight * scale).toInt().coerceAtLeast(1)
        recycleBitmaps()
        inkBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val ink = checkNotNull(inkBitmap)
        val mask = checkNotNull(maskBitmap)
        VideoAnnotationRasterizer.draw(layers.get(), presentationTimeUs / 1_000L, ink, mask)
        try {
            if (inkTexture == -1) inkTexture = GlUtil.createTexture(ink) else GlUtil.setTexture(inkTexture, ink)
            if (maskTexture == -1) maskTexture = GlUtil.createTexture(mask) else GlUtil.setTexture(maskTexture, mask)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uInkSampler", inkTexture, 1)
            program.setSamplerTexIdUniform("uMaskSampler", maskTexture, 2)
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (failure: GlUtil.GlException) {
            throw VideoFrameProcessingException(failure, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        recycleBitmaps()
        try {
            if (inkTexture != -1) GlUtil.deleteTexture(inkTexture)
            if (maskTexture != -1) GlUtil.deleteTexture(maskTexture)
            program.delete()
        } catch (failure: GlUtil.GlException) {
            throw VideoFrameProcessingException(failure)
        }
    }

    private fun recycleBitmaps() {
        inkBitmap?.recycle(); inkBitmap = null
        maskBitmap?.recycle(); maskBitmap = null
    }

    private companion object { const val MAX_MASK_DIMENSION = 1920 }
}
