@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Format
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.ColorLut
import androidx.media3.effect.GlShaderProgram
import java.util.concurrent.atomic.AtomicReference

/**
 * A color LUT whose pixels can be replaced without replacing Media3's effect chain.
 *
 * Media3 treats every call to `ExoPlayer.setVideoEffects` as a new input-stream boundary. Slider
 * drags can therefore starve playback by queuing many boundaries. This effect stays installed for
 * the lifetime of the preview and uploads only the latest LUT on the GL render thread.
 *
 * The same instance is passed to every `setVideoEffects` call (for example each time the geometry
 * changes), and Media3 builds a new shader program for each chain and releases the old one. Each
 * shader program therefore gets its own [ProgramLut] with its own texture: releasing a replaced
 * chain must not delete the texture, or end the lifetime, of the chain that replaced it. Sharing a
 * single texture failed every chain after the first with "The realtime LUT has not been uploaded".
 *
 * Media3's LUT shader rejects HDR frames, and the player runs an HDR graph for HLG and PQ sources.
 * There the grade itself is applied with the float [HdrVideoColorGradeShaderProgram] used by the
 * HDR export, so callers pass the grade along with its cube to [update].
 */
class RealtimeColorLut(initialCube: Array<Array<IntArray>>) : ColorLut {
    /** An immutable LUT revision; its bitmap is never recycled while programs may still upload it. */
    private class LutRevision(val bitmap: Bitmap, val length: Int, val revision: Long)

    private val latest = AtomicReference(cubeToRevision(initialCube, revision = 0))
    private val latestHdr = AtomicReference(HdrGradeInput(VideoColorGrade(), customLut = null))

    /** Replaces the SDR [cube] and the HDR [grade] it was built from, for the next drawn frame. */
    fun update(cube: Array<Array<IntArray>>, grade: VideoColorGrade, customLut: CubeLut?) {
        latestHdr.set(HdrGradeInput(grade, customLut))
        updateCube(cube)
    }

    private fun updateCube(cube: Array<Array<IntArray>>) {
        // Superseded bitmaps are left to the GC: another chain's GL thread may be uploading one.
        // A preview cube is a few kilobytes, so this costs nothing measurable.
        while (true) {
            val current = latest.get()
            val update = cubeToRevision(cube, current.revision + 1)
            if (latest.compareAndSet(current, update)) return
        }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        if (useHdr) {
            HdrVideoColorGradeShaderProgram(context, latestHdr::get)
        } else {
            ProgramLut().toGlShaderProgram(context, useHdr)
        }

    // Media3 only talks to the per-program [ProgramLut]s created above. These members exist to
    // satisfy the interface and answer with the latest revision's metadata.
    override fun getLutTextureId(presentationTimeUs: Long): Int =
        error("RealtimeColorLut is only used through its per-program LUTs")

    override fun getLength(presentationTimeUs: Long): Int = latest.get().length

    override fun release() = Unit

    /** The LUT of one shader program: owns one texture and follows [latest] until released. */
    private inner class ProgramLut : ColorLut {
        private var textureId = Format.NO_VALUE
        private var uploadedRevision = -1L
        private var textureLength = latest.get().length

        override fun getLutTextureId(presentationTimeUs: Long): Int {
            val revision = latest.get()
            if (revision.revision != uploadedRevision || textureId == Format.NO_VALUE) upload(revision)
            return textureId
        }

        override fun getLength(presentationTimeUs: Long): Int = textureLength

        override fun release() {
            if (textureId != Format.NO_VALUE) {
                GlUtil.deleteTexture(textureId)
                textureId = Format.NO_VALUE
            }
            uploadedRevision = -1L
        }

        private fun upload(revision: LutRevision) {
            val replacementTextureId = try {
                GlUtil.createTexture(revision.bitmap)
            } catch (error: GlUtil.GlException) {
                throw IllegalStateException("Unable to upload realtime color LUT", error)
            }
            val previousTextureId = textureId
            textureId = replacementTextureId
            textureLength = revision.length
            uploadedRevision = revision.revision
            if (previousTextureId != Format.NO_VALUE) GlUtil.deleteTexture(previousTextureId)
        }
    }

    private companion object {
        fun cubeToRevision(cube: Array<Array<IntArray>>, revision: Long): LutRevision {
            val length = cube.size
            return LutRevision(
                Bitmap.createBitmap(
                    packCube(cube),
                    length,
                    length * length,
                    Bitmap.Config.ARGB_8888,
                ),
                length,
                revision,
            )
        }
    }
}

/**
 * Packs a `[red][green][blue]` cube into the row layout Media3's `ColorLutShaderProgram` samples:
 * `length` columns of blue by `length * length` rows of (red, green).
 */
internal fun packCube(cube: Array<Array<IntArray>>): IntArray {
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
    return colors
}
