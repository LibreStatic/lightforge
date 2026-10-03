package com.librestatic.lightforge.feature.videoeditor

import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoDynamicRange
import com.librestatic.lightforge.core.editing.video.VideoGeometry
import com.librestatic.lightforge.core.editing.video.VideoOutputAudio
import com.librestatic.lightforge.core.editing.video.VideoOutputBitrate
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputFrameRate
import com.librestatic.lightforge.core.editing.video.VideoOutputQuality
import com.librestatic.lightforge.core.editing.video.VideoOutputResolution
import com.librestatic.lightforge.core.editing.video.VideoOutputSettings
import com.librestatic.lightforge.core.editing.video.VideoQualityPreset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the output panel knows about the source video. Unknown facts are null and simply left out
 * of the summary. The app fills it from MediaStore today (size, duration, file size); the export
 * pipeline's source probe can supply the codec, frame rate and stream bitrates.
 */
data class VideoOutputSource(
    val width: Int,
    val height: Int,
    /** Video track MIME type, e.g. `video/hevc`. */
    val videoMimeType: String? = null,
    val frameRate: Float? = null,
    /** Whole-file bitrate (video plus audio), e.g. file size over duration. */
    val totalBitrate: Int? = null,
    val videoBitrate: Int? = null,
    val audioBitrate: Int? = null,
    val hasAudio: Boolean? = null,
) {
    val codec: VideoOutputCodec? get() = videoMimeType?.let(::videoCodecForMime)

    /** Video stream bitrate: measured, or the file bitrate minus the audio when only that is known. */
    val estimatedVideoBitrate: Int?
        get() = videoBitrate ?: totalBitrate?.let { total -> (total - (audioBitrate ?: 0)).takeIf { it > 0 } }
}

internal fun videoCodecForMime(mime: String): VideoOutputCodec? = when (mime.lowercase()) {
    "video/avc" -> VideoOutputCodec.H264
    "video/hevc" -> VideoOutputCodec.Hevc
    "video/av01" -> VideoOutputCodec.Av1
    else -> null
}

/** Short display name of a source codec: the common ones by name, others by MIME subtype. */
internal fun videoCodecDisplayName(mime: String): String = when (mime.lowercase()) {
    "video/avc" -> "H.264"
    "video/hevc" -> "HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4"
    "video/3gpp" -> "H.263"
    "video/dolby-vision" -> "Dolby Vision"
    else -> mime.substringAfter('/').uppercase()
}

internal fun VideoOutputCodec.displayName(): String = when (this) {
    VideoOutputCodec.Auto -> "Auto"
    VideoOutputCodec.H264 -> "H.264"
    VideoOutputCodec.Hevc -> "HEVC"
    VideoOutputCodec.Av1 -> "AV1"
}

data class VideoPixelSize(val width: Int, val height: Int) {
    val shortSide: Int get() = minOf(width, height)
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)
}

/** The source frame after the editor's crop and quarter-turn rotation. */
internal fun editedSourceSize(source: VideoOutputSource, geometry: VideoGeometry): VideoPixelSize {
    val width = (source.width * (geometry.right - geometry.left)).roundToInt().coerceAtLeast(2)
    val height = (source.height * (geometry.bottom - geometry.top)).roundToInt().coerceAtLeast(2)
    val quarterTurns = ((geometry.rotationDegrees / 90f).roundToInt() % 4 + 4) % 4
    return if (quarterTurns % 2 == 1) VideoPixelSize(height, width) else VideoPixelSize(width, height)
}

/**
 * Frame size after forcing [aspect]: Stretch and Pad grow the short dimension so no source pixel is
 * lost (960×1088 at 16:9 becomes 1934×1088), Crop keeps the largest box of that shape.
 */
internal fun aspectAdjustedSize(base: VideoPixelSize, aspect: VideoAspectOverride): VideoPixelSize {
    val forced = aspect as? VideoAspectOverride.Forced ?: return base
    val ratio = forced.ratio
    val widerTarget = ratio > base.aspect
    return when (forced.mode) {
        VideoAspectMode.Stretch, VideoAspectMode.Pad -> if (widerTarget) {
            VideoPixelSize((base.height * ratio).roundToInt(), base.height)
        } else {
            VideoPixelSize(base.width, (base.width / ratio).roundToInt())
        }
        VideoAspectMode.Crop -> if (widerTarget) {
            VideoPixelSize(base.width, (base.width / ratio).roundToInt())
        } else {
            VideoPixelSize((base.height * ratio).roundToInt(), base.height)
        }
    }
}

/** Applies the resolution choice; [VideoOutputResolution.ShortSide] never upscales. Sides are even. */
internal fun resolvedOutputSize(adjusted: VideoPixelSize, resolution: VideoOutputResolution): VideoPixelSize {
    val size = when (resolution) {
        VideoOutputResolution.Original -> adjusted
        is VideoOutputResolution.Custom -> VideoPixelSize(resolution.width, resolution.height)
        is VideoOutputResolution.ShortSide -> {
            if (resolution.shortSide >= adjusted.shortSide) {
                adjusted
            } else {
                val scale = resolution.shortSide.toFloat() / adjusted.shortSide
                VideoPixelSize((adjusted.width * scale).roundToInt(), (adjusted.height * scale).roundToInt())
            }
        }
    }
    return VideoPixelSize(size.width.even(), size.height.even())
}

private fun Int.even(): Int = (this - this % 2).coerceAtLeast(2)

/** The parts of an edit that force a full decode and encode of the video stream. */
internal data class VideoEditsSummary(
    val hasPictureEdits: Boolean,
    val hasTimingEdits: Boolean,
    val hasAudioEdits: Boolean,
    val wantsTenBit: Boolean,
)

internal fun VideoEditorContentState.editsSummary(): VideoEditsSummary = VideoEditsSummary(
    hasPictureEdits = colorGrade.hasChanges || !geometry.isIdentity || annotations.isNotEmpty() ||
        dynamicRange != VideoDynamicRange.SdrRec709,
    hasTimingEdits = speed != 1f || slowMotionSegments.isNotEmpty(),
    hasAudioEdits = selectedMusicUri != null || originalAudioVolume != 1f,
    wantsTenBit = outputQuality == VideoOutputQuality.HevcMain10 || dynamicRange != VideoDynamicRange.SdrRec709,
)

/**
 * What the export will roughly produce. This is the panel's own estimate for the summary and the
 * size line; the exporter's output plan is authoritative.
 */
internal data class VideoOutputEstimate(
    val size: VideoPixelSize?,
    val codec: VideoOutputCodec?,
    val frameRate: Float?,
    val videoBitrate: Int?,
    /** 0 when the audio track is removed or absent; null when unknown. */
    val audioBitrate: Int?,
    val sizeBytes: Long?,
    /** True when the streams can be copied as they are (only a trim, at most). */
    val isLosslessCopy: Boolean,
)

/** Bits per pixel per frame used when the source bitrate is unknown (a decent H.264 rate). */
private const val FallbackBitsPerPixel = 0.1
private const val DefaultAacBitrate = 128_000

internal fun VideoQualityPreset.bitrateFactor(): Double = when (this) {
    VideoQualityPreset.Original -> 1.0
    VideoQualityPreset.High -> 0.75
    VideoQualityPreset.Medium -> 0.5
    VideoQualityPreset.Low -> 0.3
}

internal fun estimateVideoOutput(
    source: VideoOutputSource?,
    settings: VideoOutputSettings,
    edits: VideoEditsSummary,
    geometry: VideoGeometry,
    outputDurationMillis: Long,
): VideoOutputEstimate {
    val base = source?.takeIf { it.width > 0 && it.height > 0 }?.let { EditedBase(editedSourceSize(it, geometry)) }
    val outputSize = base?.let { resolvedOutputSize(aspectAdjustedSize(it.edited, settings.aspect), settings.resolution) }
    val sourceFps = source?.frameRate?.takeIf { it > 0f }
    val outputFps = when (val rate = settings.frameRate) {
        VideoOutputFrameRate.Original -> sourceFps
        is VideoOutputFrameRate.Max -> sourceFps?.coerceAtMost(rate.fps.toFloat()) ?: rate.fps.toFloat()
    }
    val sameSize = outputSize != null && base != null && outputSize.width == base.edited.width.even() &&
        outputSize.height == base.edited.height.even()
    val sameRate = settings.frameRate == VideoOutputFrameRate.Original ||
        (sourceFps != null && outputFps != null && abs(outputFps - sourceFps) < 0.01f)
    val sourceCodec = source?.codec
    val codecKept = settings.codec == VideoOutputCodec.Auto || settings.codec == sourceCodec
    val lossless = !edits.hasPictureEdits && !edits.hasTimingEdits && !edits.hasAudioEdits &&
        settings.aspect == VideoAspectOverride.Original &&
        (settings.resolution == VideoOutputResolution.Original || sameSize) && sameRate && codecKept &&
        settings.quality == VideoOutputBitrate.Preset(VideoQualityPreset.Original) &&
        settings.audio == VideoOutputAudio.Keep
    val outputCodec = when {
        lossless -> sourceCodec
        settings.codec != VideoOutputCodec.Auto -> settings.codec
        edits.wantsTenBit -> VideoOutputCodec.Hevc
        else -> VideoOutputCodec.H264
    }
    val videoBitrate: Int? = when (val quality = settings.quality) {
        is VideoOutputBitrate.Target -> quality.bitsPerSecond
        is VideoOutputBitrate.Preset -> {
            val sourceBitrate = source?.estimatedVideoBitrate
            if (lossless) {
                sourceBitrate
            } else if (sourceBitrate != null && base != null && outputSize != null) {
                val pixelRatio = outputSize.width.toDouble() * outputSize.height /
                    (base.edited.width.toDouble() * base.edited.height)
                val fpsRatio = if (sourceFps != null && outputFps != null) outputFps / sourceFps.toDouble() else 1.0
                (sourceBitrate * pixelRatio * fpsRatio * quality.preset.bitrateFactor()).roundToInt()
            } else if (outputSize != null) {
                val fps = outputFps ?: 30f
                (outputSize.width.toDouble() * outputSize.height * fps * FallbackBitsPerPixel *
                    quality.preset.bitrateFactor()).roundToInt()
            } else {
                null
            }
        }
    }
    val audioBitrate: Int? = when (val audio = settings.audio) {
        VideoOutputAudio.Remove -> 0
        is VideoOutputAudio.Aac -> if (source?.hasAudio == false) 0 else audio.bitsPerSecond
        VideoOutputAudio.Keep -> when {
            source?.hasAudio == false -> 0
            source?.audioBitrate != null -> source.audioBitrate
            edits.hasAudioEdits || edits.hasTimingEdits -> DefaultAacBitrate
            else -> null
        }
    }
    val sizeBytes = videoBitrate?.let { video ->
        ((video.toLong() + (audioBitrate ?: 0)) * outputDurationMillis.coerceAtLeast(0) / 8_000.0).roundToLong()
    }
    return VideoOutputEstimate(outputSize, outputCodec, outputFps, videoBitrate, audioBitrate, sizeBytes, lossless)
}

private data class EditedBase(val edited: VideoPixelSize)


/** The estimate for this editor state. */
internal fun VideoEditorContentState.outputEstimate(): VideoOutputEstimate =
    estimateVideoOutput(outputSource, output, editsSummary(), geometry, outputDurationMillis())

/** Edited output length: trimmed range over the base speed (slow-motion ranges are ignored). */
internal fun VideoEditorContentState.outputDurationMillis(): Long {
    val trimEnd = trimEndMillis.takeIf { it > trimStartMillis } ?: durationMillis
    return ((trimEnd - trimStartMillis) / speed).toLong().coerceAtLeast(0)
}

/** "0.98 Mbps" style numbers: two decimals below 10 Mbps, one above. */
internal fun formatMbps(bitsPerSecond: Int): String {
    val mbps = bitsPerSecond / 1_000_000.0
    return if (mbps < 10) "%.2f".format(mbps) else "%.1f".format(mbps)
}

internal fun formatFrameRate(fps: Float): String =
    if (abs(fps - fps.roundToInt()) < 0.01f) fps.roundToInt().toString() else "%.2f".format(fps)

/** Parses a user-typed Mbps value ("4", "2.5", "2,5") into a valid target, or null. */
internal fun parseTargetMbps(text: String): Int? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val bps = (value * 1_000_000).roundToLong()
    return bps.takeIf { it in 100_000L..200_000_000L }?.toInt()
}

/** Where the preview draws the video and the output frame, in the same units as the container. */
internal data class VideoPreviewBoxes(
    val videoWidth: Float,
    val videoHeight: Float,
    val frameWidth: Float,
    val frameHeight: Float,
)

private fun fit(aspect: Float, width: Float, height: Float): Pair<Float, Float> =
    if (aspect >= width / height.coerceAtLeast(1f)) width to width / aspect else height * aspect to height

/**
 * Preview boxes for a forced aspect: Stretch draws the video at the forced shape; Pad fits the video
 * inside a forced-shape frame (bars around it); Crop keeps the video shape and marks the
 * forced-shape window that survives. Both boxes are centred in the container.
 */
internal fun videoPreviewBoxes(
    containerWidth: Float,
    containerHeight: Float,
    sourceAspect: Float,
    forced: VideoAspectOverride.Forced,
): VideoPreviewBoxes = when (forced.mode) {
    VideoAspectMode.Stretch -> {
        val (w, h) = fit(forced.ratio, containerWidth, containerHeight)
        VideoPreviewBoxes(w, h, w, h)
    }
    VideoAspectMode.Pad -> {
        val (fw, fh) = fit(forced.ratio, containerWidth, containerHeight)
        val (vw, vh) = fit(sourceAspect, fw, fh)
        VideoPreviewBoxes(vw, vh, fw, fh)
    }
    VideoAspectMode.Crop -> {
        val (vw, vh) = fit(sourceAspect, containerWidth, containerHeight)
        val (fw, fh) = fit(forced.ratio, vw, vh)
        VideoPreviewBoxes(vw, vh, fw, fh)
    }
}
