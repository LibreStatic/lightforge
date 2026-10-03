package com.librestatic.lightforge

import com.librestatic.lightforge.feature.videoeditor.VideoOutputSource

/**
 * What MediaStore already tells us about a video for the editor's Output tool: its size and, from
 * the file size over the duration, its overall bitrate. Codec, frame rate and per-stream bitrates
 * stay unknown here; the export pipeline's source probe is the place to fill them in.
 */
internal fun videoOutputSource(source: EditorMediaSource): VideoOutputSource? {
    if (source.width <= 0 || source.height <= 0) return null
    val sizeBytes = source.libraryMedia?.sizeBytes ?: 0L
    val totalBitrate = if (sizeBytes > 0 && source.durationMillis > 0) {
        (sizeBytes * 8_000L / source.durationMillis).takeIf { it in 1L..Int.MAX_VALUE }?.toInt()
    } else null
    return VideoOutputSource(width = source.width, height = source.height, totalBitrate = totalBitrate)
}
