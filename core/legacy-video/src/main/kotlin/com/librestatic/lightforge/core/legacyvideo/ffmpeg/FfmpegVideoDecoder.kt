package com.librestatic.lightforge.core.legacyvideo.ffmpeg

import android.graphics.Bitmap
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Software decoder for Sorenson Spark (FLV1), VP6F and VP6A built on vendored FFmpeg (LGPL).
 *
 * Designed to be driven by a Media3 `SimpleDecoder`: one packet in produces zero or one frame out.
 * Call [sendPacket]; on [STATUS_EAGAIN] call [receiveFrame] first. After [STATUS_OK] from
 * [receiveFrame] the frame is current until the next [receiveFrame] or [flush]; use
 * [copyFrameToYuv] to move it into an output buffer. All instance methods are serialized.
 */
class FfmpegVideoDecoder(codec: Codec, extradata: ByteArray?, threads: Int = 1) : AutoCloseable {
    enum class Codec(internal val nativeId: Int) { FLV1(0), VP6F(1), VP6A(2) }

    private val lock = Any()
    private var handle: Long
    private val info = LongArray(4)

    /** Size of the current frame after FFmpeg's VP6 crop; valid after [receiveFrame] returned [STATUS_OK]. */
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /** Presentation time (as passed to [sendPacket]) of the current frame. */
    var timeUs: Long = 0
        private set

    /** True when the current frame came from VP6A (alpha plane available to [copyToBitmap]). */
    var hasAlpha: Boolean = false
        private set

    init {
        if (!isAvailable) throw IOException("FFmpeg legacy video library unavailable")
        handle = FfmpegNative.nativeOpen(codec.nativeId, extradata, threads)
        if (handle == 0L) throw IOException("Cannot open FFmpeg decoder for $codec")
    }

    /**
     * Queues [size] bytes starting at [data]'s current position (position is left unchanged).
     * Returns [STATUS_OK], [STATUS_EAGAIN] (drain with [receiveFrame] and resend) or [STATUS_ERROR].
     */
    fun sendPacket(data: ByteBuffer, size: Int, timeUs: Long, isKeyFrame: Boolean): Int = synchronized(lock) {
        if (handle == 0L || size < 0 || size > data.remaining()) return STATUS_ERROR
        if (data.isDirect) {
            FfmpegNative.nativeSendDirect(handle, data, data.position(), size, timeUs, isKeyFrame)
        } else if (data.hasArray()) {
            FfmpegNative.nativeSendArray(handle, data.array(), data.arrayOffset() + data.position(), size, timeUs, isKeyFrame)
        } else {
            val copy = ByteArray(size)
            data.duplicate().get(copy)
            FfmpegNative.nativeSendArray(handle, copy, 0, size, timeUs, isKeyFrame)
        }
    }

    /** Signals end of stream so [receiveFrame] drains buffered frames and then returns [STATUS_EOF]. */
    fun sendEndOfStream(): Int = synchronized(lock) {
        if (handle == 0L) STATUS_ERROR else FfmpegNative.nativeSendEos(handle)
    }

    /** Returns [STATUS_OK] (frame current), [STATUS_EAGAIN] (send more input), [STATUS_EOF] or [STATUS_ERROR]. */
    fun receiveFrame(): Int = synchronized(lock) {
        if (handle == 0L) return STATUS_ERROR
        val status = FfmpegNative.nativeReceive(handle, info)
        if (status == STATUS_OK) {
            width = info[0].toInt()
            height = info[1].toInt()
            timeUs = info[2]
            hasAlpha = info[3] != 0L
        }
        status
    }

    /** Drops buffered input and the current frame (e.g. on seek). */
    fun flush() = synchronized(lock) {
        if (handle != 0L) FfmpegNative.nativeFlush(handle)
    }

    /**
     * Writes the current frame as I420 into [dst] (direct): [height] rows of Y at [yStride], then
     * (height+1)/2 rows of U and of V at [uvStride].
     */
    fun copyFrameToYuv(dst: ByteBuffer, yStride: Int, uvStride: Int): Boolean = synchronized(lock) {
        handle != 0L && dst.isDirect && FfmpegNative.nativeCopyYuv(handle, dst, yStride, uvStride)
    }

    /** Pushes the current frame into [surface] as YV12. */
    fun renderToSurface(surface: Surface): Boolean = synchronized(lock) {
        handle != 0L && surface.isValid && FfmpegNative.nativeRenderCurrent(handle, surface)
    }

    /** Converts the current frame (BT.601 limited range, VP6A alpha honoured) into a same-size ARGB_8888 [bitmap]. */
    fun copyToBitmap(bitmap: Bitmap): Boolean = synchronized(lock) {
        handle != 0L && FfmpegNative.nativeCopyBitmap(handle, bitmap)
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                FfmpegNative.nativeClose(handle)
                handle = 0L
            }
        }
    }

    companion object {
        const val STATUS_OK = 0
        const val STATUS_EAGAIN = 1
        const val STATUS_EOF = 2
        const val STATUS_ERROR = -1

        /** False when the native library could not be loaded (unsupported ABI). */
        val isAvailable: Boolean by lazy {
            try {
                System.loadLibrary("lightforge_legacy_video")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        /**
         * Pushes an I420 frame (e.g. Media3's `VideoDecoderOutputBuffer.yuvPlanes`/`yuvStrides`, planes
         * Y, U, V as direct buffers) into [surface] as YV12. The Surface's window is cached until the
         * Surface changes or [releaseSurfaceCache] is called.
         */
        fun renderYuvToSurface(surface: Surface, yuvPlanes: Array<ByteBuffer>, yuvStrides: IntArray, width: Int, height: Int): Boolean {
            if (!isAvailable || !surface.isValid || yuvPlanes.size < 3 || yuvStrides.size < 3) return false
            return FfmpegNative.nativeRenderYuv(
                surface, yuvPlanes[0], yuvPlanes[1], yuvPlanes[2], yuvStrides[0], yuvStrides[1], width, height,
            )
        }

        /** Call when the renderer drops its output Surface. */
        fun releaseSurfaceCache() {
            if (isAvailable) FfmpegNative.nativeReleaseSurface()
        }
    }
}
