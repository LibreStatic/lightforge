package com.ugallery.core.editing.video

import android.net.Uri

data class VideoEditRecipe(
    val startMillis: Long = 0,
    val endMillis: Long? = null,
    val speed: Float = 1f,
    val originalAudioVolume: Float = 1f,
    val musicUri: Uri? = null,
    val musicVolume: Float = 0.6f,
    val colorGrade: VideoColorGrade = VideoColorGrade(),
    val outputQuality: VideoOutputQuality = VideoOutputQuality.H264Compatible,
) {
    init {
        require(startMillis >= 0)
        require(endMillis == null || endMillis > startMillis)
        require(speed in 0.25f..4f)
        require(originalAudioVolume in 0f..1f)
        require(musicVolume in 0f..1f)
    }

    val hasChanges: Boolean
        get() = startMillis > 0 || endMillis != null || speed != 1f ||
            originalAudioVolume != 1f || musicUri != null
            || colorGrade.hasChanges
}

data class VideoExportResult(
    val output: java.io.File,
    val durationMillis: Long,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val fallbackWarning: String?,
)
