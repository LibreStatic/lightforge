package com.librestatic.lightforge.core.legacyvideo

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.librestatic.lightforge.core.legacyvideo.ffmpeg.FfmpegVideoDecoder

/** Software renderer for Sorenson Spark, VP6F and VP6A, which MediaCodec does not decode. */
@UnstableApi
class LegacyVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {
    private var decoder: LegacyVideoDecoder? = null
    private var outputMode = C.VIDEO_OUTPUT_MODE_NONE

    override fun getName(): String = "LegacyVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        if (!LegacyVideoMimeTypes.isLegacy(format.sampleMimeType)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!FfmpegVideoDecoder.isAvailable) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        return RendererCapabilities.create(C.FORMAT_HANDLED)
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException> {
        val codec = when (format.sampleMimeType) {
            LegacyVideoMimeTypes.VIDEO_FLV1 -> FfmpegVideoDecoder.Codec.FLV1
            LegacyVideoMimeTypes.VIDEO_VP6F -> FfmpegVideoDecoder.Codec.VP6F
            LegacyVideoMimeTypes.VIDEO_VP6A -> FfmpegVideoDecoder.Codec.VP6A
            else -> throw DecoderException("Unsupported format ${format.sampleMimeType}")
        }
        return LegacyVideoDecoder(codec, format.initializationData.firstOrNull()).also {
            it.setOutputMode(outputMode)
            decoder = it
        }
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        // Like Media3's libvpx renderer, the subclass owns the buffer once it is pushed to a Surface.
        try {
            val planes = outputBuffer.yuvPlanes
            val strides = outputBuffer.yuvStrides
            if (planes == null || strides == null ||
                !FfmpegVideoDecoder.renderYuvToSurface(surface, planes, strides, outputBuffer.width, outputBuffer.height)
            ) {
                throw DecoderException("Failed to render legacy video frame to surface")
            }
        } finally {
            outputBuffer.release()
        }
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        this.outputMode = outputMode
        decoder?.setOutputMode(outputMode)
    }

    override fun onDisabled() {
        try {
            super.onDisabled()
        } finally {
            FfmpegVideoDecoder.releaseSurfaceCache()
        }
    }
}
