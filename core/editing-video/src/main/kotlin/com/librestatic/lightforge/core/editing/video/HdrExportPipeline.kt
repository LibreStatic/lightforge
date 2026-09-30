@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Codec
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.SampleConsumer
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

/**
 * Presents decoded LOG video as an SDR RGB graph input while leaving the hardware decoder's source
 * format untouched. Media3 otherwise rejects SDR video -> HDR before the first frame, even though
 * its SDR external-texture shader can feed the float HDR effect chain.
 *
 * [ColorInfo.SRGB_BT709_FULL] is the one SDR graph input Media3 accepts when the output is HDR. The
 * decoder still receives the container's real color metadata; only the graph-facing callbacks are
 * adapted, so hardware decoding and its YUV conversion remain correct.
 */
internal class HdrGraphInputAssetLoaderFactory(
    private val delegate: AssetLoader.Factory,
) : AssetLoader.Factory {
    override fun createAssetLoader(
        editedMediaItem: EditedMediaItem,
        looper: Looper,
        listener: AssetLoader.Listener,
        compositionSettings: AssetLoader.CompositionSettings,
    ): AssetLoader = delegate.createAssetLoader(
        editedMediaItem,
        looper,
        HdrGraphInputListener(listener),
        compositionSettings,
    )
}

private class HdrGraphInputListener(
    private val delegate: AssetLoader.Listener,
) : AssetLoader.Listener {
    override fun onDurationUs(durationUs: Long) = delegate.onDurationUs(durationUs)

    override fun onTrackCount(trackCount: Int) = delegate.onTrackCount(trackCount)

    override fun onTrackAdded(inputFormat: Format, supportedOutputTypes: Int): Boolean =
        delegate.onTrackAdded(inputFormat.asHdrGraphInput(), supportedOutputTypes)

    override fun onOutputFormat(format: Format): SampleConsumer? =
        delegate.onOutputFormat(format.asHdrGraphInput())

    override fun onError(exportException: ExportException) = delegate.onError(exportException)
}

internal fun Format.asHdrGraphInput(): Format = if (MimeTypes.isVideo(sampleMimeType)) {
    buildUpon().setColorInfo(ColorInfo.SRGB_BT709_FULL).build()
} else {
    this
}

/** Keeps the MediaCodec surface format and the GL output surface on the same HDR color contract. */
internal class ForcedColorInfoEncoderFactory(
    private val delegate: Codec.EncoderFactory,
    private val targetColorInfo: ColorInfo,
) : Codec.EncoderFactory {
    @Throws(ExportException::class)
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForAudioEncoding(format, logSessionId)

    @Throws(ExportException::class)
    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
        val forcedFormat = format.withHdrColorInfo(targetColorInfo)
        val codec = delegate.createForVideoEncoding(forcedFormat, logSessionId)
        Log.d(
            Tag,
            "HDR encoder ${codec.name}: requested=${format.colorInfo}, " +
                "forced=${forcedFormat.colorInfo}, configured=${codec.configurationFormat.colorInfo}",
        )
        return codec
    }

    override fun isVideoFormatSupported(format: Format): Boolean =
        delegate.isVideoFormatSupported(format.withHdrColorInfo(targetColorInfo))

    override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()
    override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()

    private companion object {
        const val Tag = "HdrExportPipeline"
    }
}

internal fun Format.withHdrColorInfo(targetColorInfo: ColorInfo): Format =
    buildUpon().setColorInfo(targetColorInfo).build()

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
