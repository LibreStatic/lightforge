package com.librestatic.lightforge.feature.videoeditor

import androidx.annotation.StringRes
import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.core.editing.video.VideoEncoderCapabilities
import com.librestatic.lightforge.core.editing.video.VideoOutputAdjustment
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputPlan
import com.librestatic.lightforge.core.editing.video.VideoOutputResolution
import com.librestatic.lightforge.core.editing.video.outputDurationMillis
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

/** True when this device only has a software encoder for [codec]: it works, but slowly. */
internal fun VideoEditorContentState.isSoftwareOnly(codec: VideoOutputCodec): Boolean = when (codec) {
    VideoOutputCodec.Av1 -> outputEncoders?.av1?.hardwareAccelerated == false
    VideoOutputCodec.Hevc -> outputEncoders?.hevc?.hardwareAccelerated == false
    VideoOutputCodec.H264 -> outputEncoders?.h264?.hardwareAccelerated == false
    VideoOutputCodec.Auto -> false
}

/**
 * The recipe this editor state exports, for the output plan. Null while the state is not a valid
 * recipe (for example a trim still being dragged into place).
 */
internal fun VideoEditorContentState.outputRecipe(): VideoEditRecipe? = runCatching {
    VideoEditRecipe(
        startMillis = trimStartMillis,
        endMillis = trimEndMillis.takeIf { it > trimStartMillis && (durationMillis <= 0 || it < durationMillis) },
        speed = speed,
        originalAudioVolume = originalAudioVolume,
        musicUri = selectedMusicUri,
        musicVolume = musicVolume,
        geometry = geometry,
        colorGrade = colorGrade,
        outputQuality = outputQuality,
        dynamicRange = dynamicRange,
        slowMotionSegments = slowMotionSegments,
        annotations = annotations,
        output = output,
    )
}.getOrNull()

/**
 * Length of the exported clip: the trim with the base speed and every slow-motion segment applied,
 * the same arithmetic the exporter uses. Falls back to the plain trim at base speed while the state
 * is not a valid recipe.
 */
internal fun VideoEditorContentState.outputLengthMillis(): Long {
    val clipEnd = trimEndMillis.takeIf { it > trimStartMillis } ?: durationMillis
    val recipe = outputRecipe()
        ?: return ((clipEnd - trimStartMillis) / speed).toLong().coerceAtLeast(0)
    return recipe.outputDurationMillis(recipe.endMillis ?: clipEnd)
}

/** The exporter's own plan for this state, plus the size it should produce. */
internal data class VideoOutputEstimate(val plan: VideoOutputPlan, val sizeBytes: Long)

/** Null until the source has been probed (or when it cannot be). */
internal fun VideoEditorContentState.outputEstimate(): VideoOutputEstimate? {
    val source = outputSource ?: return null
    val recipe = outputRecipe() ?: return null
    val plan = runCatching {
        VideoOutputPlan.resolve(source, recipe, outputEncoders ?: VideoEncoderCapabilities.Unrestricted)
    }.getOrNull() ?: return null
    val clipEnd = recipe.endMillis ?: durationMillis.takeIf { it > 0 } ?: source.durationMs
    return VideoOutputEstimate(plan, plan.estimatedSizeBytes(recipe.outputDurationMillis(clipEnd)))
}

/** Aspect of the source after the editor's crop and rotation, or null while the source is unknown. */
internal fun VideoEditorContentState.editedSourceAspect(): Float? {
    val source = outputSource ?: return null
    val (width, height) = VideoOutputPlan.editedSize(source, geometry)
    return if (width > 0 && height > 0) (width / height).toFloat() else null
}

@StringRes
internal fun VideoOutputAdjustment.reason(): Int = when (this) {
    VideoOutputAdjustment.CodecUnavailableFellBackToHevc -> R.string.video_editor_output_reason_fallback_hevc
    VideoOutputAdjustment.CodecUnavailableFellBackToH264 -> R.string.video_editor_output_reason_fallback_h264
    VideoOutputAdjustment.HdrRequiresHevc -> R.string.video_editor_output_reason_hdr_hevc
    VideoOutputAdjustment.ResolutionClampedToEncoder -> R.string.video_editor_output_reason_clamped
    VideoOutputAdjustment.FrameRateCapAboveSource -> R.string.video_editor_output_reason_frame_rate
    VideoOutputAdjustment.ShortSideAboveSource -> R.string.video_editor_output_reason_short_side
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
    /** The frame is a window inside the video (Crop) rather than a canvas around it. */
    val crop: Boolean = false,
)

private fun fit(aspect: Float, width: Float, height: Float): Pair<Float, Float> =
    if (aspect >= width / height.coerceAtLeast(1f)) width to width / aspect else height * aspect to height

/**
 * Preview boxes, centred in the container. Without [crop] the output frame ([frameAspect]) fills
 * the container and the video ([contentAspect]) fits inside it, so bars show where padding goes;
 * with [crop] the video fits the container and the frame marks the window that survives.
 */
internal fun videoPreviewBoxes(
    containerWidth: Float,
    containerHeight: Float,
    contentAspect: Float,
    frameAspect: Float,
    crop: Boolean,
): VideoPreviewBoxes = if (crop) {
    val (vw, vh) = fit(contentAspect, containerWidth, containerHeight)
    val (fw, fh) = fit(frameAspect, vw, vh)
    VideoPreviewBoxes(vw, vh, fw, fh, crop = true)
} else {
    val (fw, fh) = fit(frameAspect, containerWidth, containerHeight)
    val (vw, vh) = fit(contentAspect, fw, fh)
    VideoPreviewBoxes(vw, vh, fw, fh)
}

/**
 * Preview boxes for the output frame, or null to keep the plain video fit. The frame shape comes
 * from the resolved output plan (so a custom size shows its letterbox); [playerAspect] is what the
 * player reports, and before it is ready the probed source after crop and rotation is used.
 *
 * Stretch draws the video at the forced shape, Pad fits the video inside the output frame, Crop
 * keeps the video shape and marks the forced-shape window that survives.
 */
internal fun videoOutputPreviewBoxes(
    state: VideoEditorContentState,
    playerAspect: Float?,
    containerWidth: Float,
    containerHeight: Float,
): VideoPreviewBoxes? {
    val forced = state.output.aspect as? VideoAspectOverride.Forced
    val custom = state.output.resolution as? VideoOutputResolution.Custom
    if (forced == null && custom == null) return null
    val customAspect = custom?.let { it.width.toFloat() / it.height }
    val sourceAspect = playerAspect?.takeIf { it > 0f } ?: state.editedSourceAspect() ?: forced?.ratio ?: customAspect ?: return null
    val height = containerHeight.coerceAtLeast(1f)
    if (forced?.mode == VideoAspectMode.Crop) {
        return videoPreviewBoxes(containerWidth, height, sourceAspect, forced.ratio, crop = true)
    }
    val plan = state.outputEstimate()?.plan
    val frameAspect = plan?.let { it.width.toFloat() / it.height } ?: customAspect ?: forced?.ratio ?: sourceAspect
    val contentAspect = if (forced?.mode == VideoAspectMode.Stretch) forced.ratio else sourceAspect
    return videoPreviewBoxes(containerWidth, height, contentAspect, frameAspect, crop = false)
}
