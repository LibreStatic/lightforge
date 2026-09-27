@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.transformer.EncoderSelector
import com.google.common.collect.ImmutableList

/** Keeps platform hardware codecs ahead of software codecs without removing the safe fallback. */
object HardwareCodecSelectors {
    val encoder: EncoderSelector = EncoderSelector { mimeType ->
        ImmutableList.copyOf(
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter(MediaCodecInfo::isEncoder)
                .filter { codec -> codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .sortedWith(
                    compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated }
                        .thenBy { it.isSoftwareOnly }
                        .thenBy(MediaCodecInfo::getName),
                )
                .toList(),
        )
    }

    val decoder: MediaCodecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            .sortedWith(
                compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                    it.hardwareAccelerated
                }.thenBy { it.softwareOnly }.thenBy { it.name },
            )
    }

    fun isSoftwareCodec(codecName: String?): Boolean {
        if (codecName.isNullOrBlank()) return false
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .firstOrNull { it.name == codecName }
                ?.let { it.isSoftwareOnly || !it.isHardwareAccelerated }
        }.getOrNull() ?: codecName.lowercase().let { name ->
            name.startsWith("omx.google.") || name.startsWith("c2.android.") ||
                name.contains("software") || name.contains("sw.")
        }
    }
}
