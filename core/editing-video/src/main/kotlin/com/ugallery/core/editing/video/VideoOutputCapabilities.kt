package com.ugallery.core.editing.video

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

object VideoOutputCapabilities {
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

    /** Media3 HDR export uses a GLES 3 float pipeline feeding a hardware Main10 encoder surface. */
    fun hdr(context: Context): Hdr {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !supportsHevcMain10()) return Hdr(false, false)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val available = activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        return Hdr(hlg = available, hdr10 = available)
    }
}
