package com.librestatic.lightforge.core.legacyvideo

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.librestatic.lightforge.core.legacyvideo.ffmpeg.FfmpegVideoDecoder
import java.io.IOException

/**
 * Media3 decoder for Sorenson Spark / VP6 backed by [FfmpegVideoDecoder]. Single threaded, so one
 * packet yields at most one I420 frame, in decode order (these codecs have no B-frames).
 */
@UnstableApi
internal class LegacyVideoDecoder(
    codec: FfmpegVideoDecoder.Codec,
    extradata: ByteArray?,
    inputBufferCount: Int = 4,
    outputBufferCount: Int = 4,
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderException>(
    inputBuffers(inputBufferCount),
    outputBuffers(outputBufferCount),
) {
    private val decoder = try {
        FfmpegVideoDecoder(codec, extradata, threads = 1)
    } catch (e: IOException) {
        throw DecoderException("Cannot create legacy video decoder", e)
    }

    @Volatile private var outputMode = C.VIDEO_OUTPUT_MODE_NONE

    init {
        setInitialInputBufferSize(INITIAL_INPUT_BUFFER_SIZE)
    }

    override fun getName(): String = "LegacyVideoDecoder"

    fun setOutputMode(mode: Int) {
        outputMode = mode
    }

    override fun createInputBuffer(): DecoderInputBuffer =
        DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): VideoDecoderOutputBuffer =
        VideoDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable): DecoderException =
        DecoderException("Unexpected decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean,
    ): DecoderException? {
        if (reset) decoder.flush()
        if (inputBuffer.isEndOfStream) {
            // Drain anything still buffered, then mark the output as the end marker.
            decoder.sendEndOfStream()
            if (decoder.receiveFrame() != FfmpegVideoDecoder.STATUS_OK) {
                outputBuffer.shouldBeSkipped = true
                return null
            }
            return fillOutput(outputBuffer)
        }
        val data = inputBuffer.data ?: return DecoderException("Missing input data")
        val size = data.remaining()
        var status = decoder.sendPacket(data, size, inputBuffer.timeUs, inputBuffer.isKeyFrame)
        if (status == FfmpegVideoDecoder.STATUS_EAGAIN) {
            // Cannot happen with one thread; drop a stale frame and retry rather than failing.
            decoder.receiveFrame()
            status = decoder.sendPacket(data, size, inputBuffer.timeUs, inputBuffer.isKeyFrame)
        }
        if (status != FfmpegVideoDecoder.STATUS_OK) return DecoderException("Legacy video decode failed")
        return when (decoder.receiveFrame()) {
            FfmpegVideoDecoder.STATUS_OK -> fillOutput(outputBuffer)
            // The codec consumed the packet without producing a picture (e.g. a leading inter frame).
            FfmpegVideoDecoder.STATUS_EAGAIN, FfmpegVideoDecoder.STATUS_EOF -> {
                outputBuffer.shouldBeSkipped = true
                null
            }
            else -> DecoderException("Legacy video decode failed")
        }
    }

    private fun fillOutput(outputBuffer: VideoDecoderOutputBuffer): DecoderException? {
        // No output attached yet (Surface not set): emit a frameless buffer the renderer drops, as libvpx does.
        if (outputMode == C.VIDEO_OUTPUT_MODE_NONE) {
            outputBuffer.init(decoder.timeUs, C.VIDEO_OUTPUT_MODE_NONE, null)
            return null
        }
        // SURFACE_YUV also carries I420 planes; the renderer pushes them to the Surface.
        if (outputMode != C.VIDEO_OUTPUT_MODE_YUV && outputMode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) return DecoderException("Unsupported output mode $outputMode")
        val width = decoder.width
        val height = decoder.height
        val yStride = width
        val uvStride = (width + 1) / 2
        outputBuffer.init(decoder.timeUs, outputMode, null)
        if (!outputBuffer.initForYuvFrame(width, height, yStride, uvStride, VideoDecoderOutputBuffer.COLORSPACE_BT601)) {
            return DecoderException("Cannot allocate output frame")
        }
        val dst = outputBuffer.data ?: return DecoderException("Missing output buffer")
        if (!decoder.copyFrameToYuv(dst, yStride, uvStride)) return DecoderException("Cannot copy decoded frame")
        return null
    }

    override fun release() {
        super.release()
        decoder.close()
    }

    private companion object {
        // SimpleDecoder fills the arrays itself through createInputBuffer/createOutputBuffer.
        @Suppress("UNCHECKED_CAST")
        fun inputBuffers(n: Int) = arrayOfNulls<DecoderInputBuffer>(n) as Array<DecoderInputBuffer>

        @Suppress("UNCHECKED_CAST")
        fun outputBuffers(n: Int) = arrayOfNulls<VideoDecoderOutputBuffer>(n) as Array<VideoDecoderOutputBuffer>

        const val INITIAL_INPUT_BUFFER_SIZE = 256 * 1024
    }
}
