@file:Suppress("UnsafeOptInUsageError")

package com.ugallery.core.editing.video

import android.graphics.Bitmap
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.ColorLut
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A color LUT whose pixels can be replaced without replacing Media3's effect chain.
 *
 * Media3 treats every call to `ExoPlayer.setVideoEffects` as a new input-stream boundary. Slider
 * drags can therefore starve playback by queuing many boundaries. This effect stays installed for
 * the lifetime of the preview and uploads only the latest pending LUT on the GL render thread.
 */
class RealtimeColorLut(initialCube: Array<Array<IntArray>>) : ColorLut {
    private data class PendingLut(val bitmap: Bitmap, val length: Int)

    private val pendingLut = AtomicReference(cubeToPendingLut(initialCube))
    private val released = AtomicBoolean(false)
    private var textureId = Format.NO_VALUE
    private var textureLength = initialCube.size

    @Synchronized
    fun updateCube(cube: Array<Array<IntArray>>) {
        val update = cubeToPendingLut(cube)
        if (released.get()) {
            update.bitmap.recycle()
            return
        }
        pendingLut.getAndSet(update)?.bitmap?.recycle()
    }

    override fun getLutTextureId(presentationTimeUs: Long): Int {
        pendingLut.getAndSet(null)?.let(::upload)
        check(textureId != Format.NO_VALUE) { "The realtime LUT has not been uploaded" }
        return textureId
    }

    override fun getLength(presentationTimeUs: Long): Int = textureLength

    @Synchronized
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        pendingLut.getAndSet(null)?.bitmap?.recycle()
        if (textureId != Format.NO_VALUE) {
            GlUtil.deleteTexture(textureId)
            textureId = Format.NO_VALUE
        }
    }

    private fun upload(update: PendingLut) {
        try {
            val replacementTextureId = GlUtil.createTexture(update.bitmap)
            val previousTextureId = textureId
            textureId = replacementTextureId
            textureLength = update.length
            if (previousTextureId != Format.NO_VALUE) GlUtil.deleteTexture(previousTextureId)
        } catch (error: GlUtil.GlException) {
            throw IllegalStateException("Unable to upload realtime color LUT", error)
        } finally {
            update.bitmap.recycle()
        }
    }

    private companion object {
        fun cubeToPendingLut(cube: Array<Array<IntArray>>): PendingLut {
            require(cube.isNotEmpty())
            val length = cube.size
            require(cube.all { plane ->
                plane.size == length && plane.all { row -> row.size == length }
            })
            val colors = IntArray(length * length * length)
            for (red in 0 until length) {
                for (green in 0 until length) {
                    for (blue in 0 until length) {
                        colors[blue + length * (green + length * red)] = cube[red][green][blue]
                    }
                }
            }
            return PendingLut(
                Bitmap.createBitmap(
                    colors,
                    length,
                    length * length,
                    Bitmap.Config.ARGB_8888,
                ),
                length,
            )
        }
    }
}
