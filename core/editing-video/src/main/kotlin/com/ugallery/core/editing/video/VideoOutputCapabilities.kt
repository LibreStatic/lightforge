package com.ugallery.core.editing.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
}
