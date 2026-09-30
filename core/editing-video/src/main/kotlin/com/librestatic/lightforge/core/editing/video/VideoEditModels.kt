package com.librestatic.lightforge.core.editing.video

import android.net.Uri
import java.util.UUID

enum class SlowMotionAudioMode { PreservePitch, Muted, Varispeed }

data class VideoGeometry(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
) {
    init {
        require(left >= 0f && left < right && right <= 1f)
        require(top >= 0f && top < bottom && bottom <= 1f)
        require(rotationDegrees in -45f..315f)
    }

    val isIdentity: Boolean get() = this == VideoGeometry()
}

data class SlowMotionSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMillis: Long,
    val endMillis: Long,
    val speed: Float = 0.25f,
    val audioMode: SlowMotionAudioMode = SlowMotionAudioMode.PreservePitch,
) {
    init {
        require(startMillis >= 0)
        require(endMillis > startMillis)
        require(speed == 0.5f || speed == 0.25f || speed == 0.125f)
    }

    val interpolationFactor: Int get() = (1f / speed).toInt()
}

data class VideoEditRecipe(
    val startMillis: Long = 0,
    val endMillis: Long? = null,
    val speed: Float = 1f,
    val originalAudioVolume: Float = 1f,
    val musicUri: Uri? = null,
    val musicVolume: Float = 0.6f,
    val geometry: VideoGeometry = VideoGeometry(),
    val colorGrade: VideoColorGrade = VideoColorGrade(),
    val outputQuality: VideoOutputQuality = VideoOutputQuality.H264Compatible,
    val dynamicRange: VideoDynamicRange = VideoDynamicRange.SdrRec709,
    val slowMotionSegments: List<SlowMotionSegment> = emptyList(),
    val annotations: List<VideoAnnotationLayer> = emptyList(),
) {
    init {
        require(startMillis >= 0)
        require(endMillis == null || endMillis > startMillis)
        require(speed in 0.25f..4f)
        require(originalAudioVolume in 0f..1f)
        require(musicVolume in 0f..1f)
        require(slowMotionSegments == slowMotionSegments.sortedBy(SlowMotionSegment::startMillis))
        require(slowMotionSegments.zipWithNext().none { (left, right) -> left.endMillis > right.startMillis })
        require(slowMotionSegments.all { segment ->
            segment.startMillis >= startMillis && (endMillis == null || segment.endMillis <= endMillis)
        })
        require(annotations.all { layer ->
            layer.startMillis >= startMillis && (endMillis == null || layer.endMillis <= endMillis)
        })
    }

    val hasChanges: Boolean
        get() = startMillis > 0 || endMillis != null || speed != 1f ||
            originalAudioVolume != 1f || musicUri != null
            || colorGrade.hasChanges
            || dynamicRange != VideoDynamicRange.SdrRec709
            || !geometry.isIdentity
            || slowMotionSegments.isNotEmpty()
            || annotations.isNotEmpty()
}

data class VideoExportResult(
    val output: java.io.File,
    val durationMillis: Long,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val fallbackWarning: String?,
    val videoEncoderName: String? = null,
    val videoDecoderName: String? = null,
    val usedSoftwareCodec: Boolean = false,
)
