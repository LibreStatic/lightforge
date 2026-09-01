@file:Suppress("UnsafeOptInUsageError")

package com.ugallery.core.editing.video

import android.content.Context
import android.media.metrics.LogSessionId
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.Codec
import androidx.media3.transformer.ExportException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

internal fun VideoDynamicRange.toColorInfo(): ColorInfo {
    require(this != VideoDynamicRange.SdrRec709)
    return ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(
            if (this == VideoDynamicRange.HdrHlg) C.COLOR_TRANSFER_HLG else C.COLOR_TRANSFER_ST2084,
        )
        .setLumaBitdepth(10)
        .setChromaBitdepth(10)
        .setHdrStaticInfo(if (this == VideoDynamicRange.Hdr10Pq) hdr10StaticInfo() else null)
        .build()
}

/** Transformer derives output color from the input, so LOG-in-an-SDR-container needs an override. */
internal class ForcedColorInfoVideoFrameProcessorFactory(
    private val colorInfo: ColorInfo,
    private val delegate: VideoFrameProcessor.Factory = DefaultVideoFrameProcessor.Factory.Builder().build(),
) : VideoFrameProcessor.Factory {
    @Throws(VideoFrameProcessingException::class)
    override fun create(
        context: Context,
        debugViewProvider: DebugViewProvider,
        outputColorInfo: ColorInfo,
        renderFramesAutomatically: Boolean,
        listenerExecutor: Executor,
        listener: VideoFrameProcessor.Listener,
    ): VideoFrameProcessor = delegate.create(
        context,
        debugViewProvider,
        colorInfo,
        renderFramesAutomatically,
        listenerExecutor,
        listener,
    )
}

/** Keeps the MediaCodec surface format and the GL output surface on the same HDR color contract. */
internal class ForcedColorInfoEncoderFactory(
    private val delegate: Codec.EncoderFactory,
    private val colorInfo: ColorInfo,
) : Codec.EncoderFactory {
    @Throws(ExportException::class)
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForAudioEncoding(format, logSessionId)

    @Throws(ExportException::class)
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForVideoEncoding(format.withHdrColorInfo(), logSessionId)

    override fun isVideoFormatSupported(format: Format): Boolean =
        delegate.isVideoFormatSupported(format.withHdrColorInfo())

    override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()
    override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()

    private fun Format.withHdrColorInfo() = buildUpon().setColorInfo(colorInfo).build()
}

/** CTA-861.3 type-1 metadata for a 1,000-nit BT.2020 mastering display. */
private fun hdr10StaticInfo(): ByteArray = ByteBuffer.allocate(25)
    .order(ByteOrder.LITTLE_ENDIAN)
    .apply {
        put(0) // Static Metadata Descriptor ID 1.
        putChromaticity(0.708f, 0.292f)
        putChromaticity(0.170f, 0.797f)
        putChromaticity(0.131f, 0.046f)
        putChromaticity(0.3127f, 0.3290f)
        putShort(1_000)
        putShort(50) // 0.005 nit in units of 0.0001 nit.
        putShort(1_000)
        putShort(400)
    }
    .array()

private fun ByteBuffer.putChromaticity(x: Float, y: Float) {
    putShort((x * 50_000f + 0.5f).toInt().toShort())
    putShort((y * 50_000f + 0.5f).toInt().toShort())
}

private fun ByteBuffer.putShort(value: Int) {
    putShort(value.toShort())
}
