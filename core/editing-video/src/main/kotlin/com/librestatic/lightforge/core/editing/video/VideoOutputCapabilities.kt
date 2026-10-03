package com.librestatic.lightforge.core.editing.video

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EncoderUtil

object VideoOutputCapabilities {
    /**
     * Encoders for the output codec choice, preferring hardware ones (the exporter's selector does
     * too). Blocking but cheap: callers may cache it for the session.
     */
    fun encoders(): VideoEncoderCapabilities = runCatching {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        fun support(mimeType: String): VideoEncoderSupport? {
            val codec = encoders
                .filter { codec -> codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .sortedByDescending { it.isHardwareAccelerated }
                .firstOrNull() ?: return null
            val video = codec.getCapabilitiesForType(mimeType).videoCapabilities ?: return VideoEncoderSupport()
            return VideoEncoderSupport(
                widthAlignment = video.widthAlignment,
                heightAlignment = video.heightAlignment,
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                maxBitrate = video.bitrateRange.upper,
            )
        }
        VideoEncoderCapabilities(
            h264 = support(MimeTypes.VIDEO_H264),
            hevc = support(MimeTypes.VIDEO_H265),
            av1 = support(MimeTypes.VIDEO_AV1),
            hevcMain10 = supportsHevcMain10(),
        )
    }.getOrDefault(VideoEncoderCapabilities(h264 = VideoEncoderSupport(), hevc = null, av1 = null))

    fun supportsHevcMain10(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) } &&
                codec.getCapabilitiesForType(MimeTypes.VIDEO_H265).profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
        }
    }.getOrDefault(false)

    data class Hdr(
        val hlg: Boolean,
        val hdr10: Boolean,
    ) {
        val any: Boolean get() = hlg || hdr10
    }

    /** Mirrors the requirements enforced by Media3's DefaultEncoderFactory for HDR surfaces. */
    @Suppress("NewApi")
    @androidx.annotation.OptIn(UnstableApi::class)
    fun hdr(context: Context): Hdr {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Hdr(false, false)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager.deviceConfigurationInfo.reqGlEsVersion < 0x30000) return Hdr(false, false)
        fun supports(range: VideoDynamicRange): Boolean = runCatching {
            EncoderUtil.getSupportedEncodersForHdrEditing(MimeTypes.VIDEO_H265, range.toColorInfo())
                .any { encoder ->
                    EncoderUtil.isHardwareAccelerated(encoder, MimeTypes.VIDEO_H265) &&
                        EncoderUtil.getSupportedColorFormats(encoder, MimeTypes.VIDEO_H265).contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_Format32bitABGR2101010,
                        )
                }
        }.getOrDefault(false)
        return Hdr(
            hlg = supports(VideoDynamicRange.HdrHlg),
            hdr10 = supports(VideoDynamicRange.Hdr10Pq),
        )
    }
}
