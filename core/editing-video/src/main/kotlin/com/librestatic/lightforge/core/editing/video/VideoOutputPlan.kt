package com.librestatic.lightforge.core.editing.video

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/** Edited output length for a clip ending at [clipEndMillis]: speed and slow-motion ranges applied. */
fun VideoEditRecipe.outputDurationMillis(clipEndMillis: Long): Long {
    var cursor = startMillis
    var duration = 0.0
    slowMotionSegments.forEach { segment ->
        duration += (segment.startMillis - cursor).coerceAtLeast(0) / speed.toDouble()
        duration += (segment.endMillis - segment.startMillis) / segment.speed.toDouble()
        cursor = segment.endMillis
    }
    duration += (clipEndMillis - cursor).coerceAtLeast(0) / speed.toDouble()
    return duration.toLong().coerceAtLeast(0)
}

/** What an encoder on this device can produce for one codec. Sizes are in pixels, bitrate in bits/s. */
data class VideoEncoderSupport(
    val widthAlignment: Int = 2,
    val heightAlignment: Int = 2,
    val maxWidth: Int = 8192,
    val maxHeight: Int = 8192,
    val maxBitrate: Int? = null,
    /** False when only a software encoder exists (AV1 on most phones): it works, slowly. */
    val hardwareAccelerated: Boolean = true,
)

/** Video encoders available for the output codec choice; a null entry means no encoder for that codec. */
data class VideoEncoderCapabilities(
    val h264: VideoEncoderSupport?,
    val hevc: VideoEncoderSupport?,
    val av1: VideoEncoderSupport?,
    val hevcMain10: Boolean = false,
) {
    fun supports(codec: VideoOutputCodec): Boolean = codec == VideoOutputCodec.Auto || support(codec) != null

    internal fun support(codec: VideoOutputCodec): VideoEncoderSupport? = when (codec) {
        VideoOutputCodec.Auto -> null
        VideoOutputCodec.H264 -> h264
        VideoOutputCodec.Hevc -> hevc
        VideoOutputCodec.Av1 -> av1
    }

    companion object {
        /** Every codec present with permissive limits: for previews and tests. */
        val Unrestricted = VideoEncoderCapabilities(
            h264 = VideoEncoderSupport(),
            hevc = VideoEncoderSupport(),
            av1 = VideoEncoderSupport(),
            hevcMain10 = true,
        )
    }
}

sealed interface VideoAudioPlan {
    /** The source has no audio track and no music is mixed in. */
    data object None : VideoAudioPlan

    /**
     * [VideoOutputAudio.Remove] without added music: the output has no audio track. With music,
     * Remove drops only the source audio and the music track is encoded ([Encode]).
     */
    data object Removed : VideoAudioPlan

    /** The source audio stream is copied without decoding. */
    data object Copy : VideoAudioPlan

    /**
     * Audio is decoded, processed and re-encoded as AAC. [requested] is false when the bitrate is
     * the encoder default (today's behaviour) and only used for estimates.
     */
    data class Encode(val bitsPerSecond: Int, val requested: Boolean) : VideoAudioPlan
}

/** Why the plan differs from what the output settings asked for. Shown (localised) by the UI. */
enum class VideoOutputAdjustment {
    CodecUnavailableFellBackToHevc,
    CodecUnavailableFellBackToH264,

    /** HDR output (HLG/PQ) is produced with HEVC only, so an H.264/AV1 choice is replaced. */
    HdrRequiresHevc,

    /** The size exceeded what the encoder accepts and was scaled down proportionally. */
    ResolutionClampedToEncoder,

    /** The frame-rate cap is at or above the source rate, so no frames are dropped. */
    FrameRateCapAboveSource,

    /** [VideoOutputResolution.ShortSide] never upscales: the source size is kept. */
    ShortSideAboveSource,
}

/**
 * One resize of the frame, in order. The exporter maps it to a Media3 `Presentation`
 * (Stretch -> stretch to fit, Crop -> scale to fit with crop, Pad -> scale to fit).
 */
data class VideoFrameResize(val width: Int, val height: Int, val mode: VideoAspectMode)

/**
 * The concrete export decisions for a recipe and a source: codec, size, bitrate, frame rate, audio
 * and whether the streams can be copied losslessly. Pure: computed from [VideoSourceInfo] and
 * [VideoEncoderCapabilities], so it is unit tested and shared by the exporter and the UI estimate.
 */
data class VideoOutputPlan(
    /** Resolved codec, never Auto; the source codec when the video is copied. */
    val codec: VideoOutputCodec,
    val videoMimeType: String,
    val width: Int,
    val height: Int,
    /** Target bitrate in bits/s; the source's own when the video is copied. */
    val videoBitrate: Int,
    /** Expected output frame rate. */
    val frameRate: Float,
    /** Non-null when frames are dropped to this rate. */
    val frameRateCap: Int?,
    val audio: VideoAudioPlan,
    /** The video stream is copied: no decoding, no effects, no quality loss. */
    val remuxVideo: Boolean,
    /** The audio stream is copied. */
    val remuxAudio: Boolean,
    /** An HDR source is tone-mapped because the chosen codec (explicit H.264) cannot carry it. */
    val toneMapToSdr: Boolean,
    /** Encode HEVC with the Main10 profile (legacy [VideoOutputQuality.HevcMain10] in SDR). */
    val hevcMain10: Boolean,
    /** Resizes appended after every other video effect; empty keeps the edited frame size. */
    val resizes: List<VideoFrameResize>,
    val adjustments: List<VideoOutputAdjustment>,
    /** Used to estimate a copied audio stream. */
    val sourceAudioBitrate: Int = DefaultAudioBitrate,
) {
    /** The whole export is a stream copy (trim and/or track removal only). */
    val remuxOnly: Boolean
        get() = remuxVideo && (remuxAudio || audio == VideoAudioPlan.None || audio == VideoAudioPlan.Removed)

    /** Rough output size for [durationMs] of edited output (after trim, speed and slow motion). */
    fun estimatedSizeBytes(durationMs: Long): Long {
        if (durationMs <= 0) return 0
        val audioBitrate = when (audio) {
            VideoAudioPlan.None, VideoAudioPlan.Removed -> 0
            VideoAudioPlan.Copy -> sourceAudioBitrate
            is VideoAudioPlan.Encode -> audio.bitsPerSecond
        }
        val payloadBits = (videoBitrate.toLong() + audioBitrate) * durationMs / 1_000L
        val payloadBytes = payloadBits / 8.0
        // Media3's MP4 muxer reserves about 400 kB up front for a streamable (moov first) file and
        // leaves what the sample tables do not use as a free box: a constant in every export, large
        // for short clips. Past that, the sample tables themselves add roughly 1-2 %.
        return (payloadBytes + max(MuxerReservedBytes, payloadBytes * (ContainerOverhead - 1.0))).roundToLong()
    }

    companion object {
        const val MimeH264 = "video/avc"
        const val MimeHevc = "video/hevc"
        const val MimeAv1 = "video/av01"
        const val MimeAac = "audio/mp4a-latm"

        /** Media3's AAC default when no audio bitrate is requested. */
        const val DefaultAudioBitrate = 128_000

        internal const val ContainerOverhead = 1.015
        internal const val MuxerReservedBytes = 400_000.0
        internal const val FallbackFrameRate = 30f
        private const val MinVideoBitrate = 100_000
        private const val MaxVideoBitrate = 200_000_000

        /** Bits per pixel per frame for H.264; other codecs scale by [efficiency]. */
        private const val HighBpp = 0.10
        private const val MediumBpp = 0.06
        private const val LowBpp = 0.035
        private const val CeilingBpp = 0.20
        private const val FloorBpp = 0.015

        /** Coded heights that are a standard height plus macroblock padding (1080 stored as 1088). */
        private val PaddedStandardSides = mapOf(1088 to 1080, 544 to 540, 368 to 360, 2176 to 2160)

        /**
         * Resolves [recipe]'s output settings against [source] and the device [capabilities].
         *
         * Size pipeline: displayed source size (rotation and pixel aspect applied) -> crop and
         * rotation of [VideoEditRecipe.geometry] (quarter turns swap the sides; straightening keeps them, like Media3's
         * `ScaleAndRotateTransformation`) -> forced aspect -> resolution -> encoder limits ->
         * even and encoder-aligned sizes.
         *
         * Forced aspect:
         * - Stretch expands the squeezed axis to the target ratio (never shrinks one), then snaps a
         *   macroblock-padded side to its standard size: 960x1088 at 16:9 -> 1934x1088 -> 1920x1080.
         * - Crop fills the ratio and cuts the overflow: 960x1088 at 16:9 -> 960x540.
         * - Pad fits the frame and adds black bars: 960x1088 at 16:9 -> 1934x1088.
         *
         * Resolution: ShortSide scales uniformly and never upscales. Custom is the exact canvas;
         * the content keeps its (original or forced) aspect and is letterboxed into it, so stretch to
         * a custom size by forcing the same aspect with Stretch.
         *
         * Stream copy ([remuxVideo]) needs: a recipe with changes (a default recipe always re-encodes,
         * which the share sanitizer relies on to drop bitstream side data), no effect at all (geometry,
         * grade, annotations, HDR range, legacy Main10, speed, slow motion, music), output settings that
         * are no-ops for this source (codec Auto or the source codec, preset Original, size, aspect and
         * frame rate unchanged), a codec the MP4 muxer accepts and square pixels. Trims are copied with
         * an MP4 edit list (Media3 `experimentalSetMp4EditListTrimEnabled`) so they stay frame accurate.
         */
        fun resolve(
            source: VideoSourceInfo,
            recipe: VideoEditRecipe,
            capabilities: VideoEncoderCapabilities,
        ): VideoOutputPlan {
            val output = recipe.output
            val adjustments = mutableListOf<VideoOutputAdjustment>()
            val hdrOutput = recipe.dynamicRange != VideoDynamicRange.SdrRec709
            val sourceFps = source.frameRate.takeIf { it > 0f } ?: FallbackFrameRate

            // Frame size.
            val (editedWidth, editedHeight) = editedSize(source, recipe.geometry)
            val sizes = frameSize(editedWidth, editedHeight, output, adjustments)
            val geometryOnly = sizes.resizes.isEmpty()

            // Frame rate.
            val speedFps = sourceFps * if (recipe.slowMotionSegments.isEmpty()) recipe.speed else 1f
            val frameRateCap = when (val rate = output.frameRate) {
                VideoOutputFrameRate.Original -> null
                is VideoOutputFrameRate.Max -> if (rate.fps < speedFps - 0.01f) {
                    rate.fps
                } else {
                    adjustments += VideoOutputAdjustment.FrameRateCapAboveSource
                    null
                }
            }
            val outputFps = frameRateCap?.toFloat() ?: speedFps

            // Stream copy eligibility.
            val sourceCodec = codecForMime(source.videoMimeType)
            val noVideoEffects = recipe.geometry.isIdentity && !recipe.colorGrade.hasChanges &&
                recipe.annotations.isEmpty() && !hdrOutput &&
                recipe.outputQuality == VideoOutputQuality.H264Compatible &&
                recipe.speed == 1f && recipe.slowMotionSegments.isEmpty() && recipe.musicUri == null
            val remuxVideo = recipe.hasChanges && noVideoEffects && sourceCodec != null &&
                (output.codec == VideoOutputCodec.Auto || output.codec == sourceCodec) &&
                output.quality == VideoOutputBitrate.Preset(VideoQualityPreset.Original) &&
                geometryOnly && frameRateCap == null &&
                abs(source.pixelWidthHeightRatio - 1f) < 0.001f

            // Codec.
            var toneMapToSdr = false
            val codec = if (remuxVideo) {
                checkNotNull(sourceCodec)
            } else {
                resolveCodec(output.codec, recipe, hdrOutput, capabilities, adjustments).also { codec ->
                    toneMapToSdr = source.isHdr && !hdrOutput && output.codec == VideoOutputCodec.H264 &&
                        codec == VideoOutputCodec.H264
                }
            }
            val hevcMain10 = !remuxVideo && codec == VideoOutputCodec.Hevc && !hdrOutput &&
                recipe.outputQuality == VideoOutputQuality.HevcMain10

            // Encoder limits and alignment.
            var width = sizes.width
            var height = sizes.height
            val encoder = if (remuxVideo) null else capabilities.support(codec)
            var clamped = false
            if (encoder != null) {
                val fitsLandscape = width <= encoder.maxWidth && height <= encoder.maxHeight
                // Media3 may encode portrait frames rotated, so either orientation is acceptable.
                val fitsPortrait = height <= encoder.maxWidth && width <= encoder.maxHeight
                if (!fitsLandscape && !fitsPortrait) {
                    val longLimit = max(encoder.maxWidth, encoder.maxHeight).toDouble()
                    val shortLimit = min(encoder.maxWidth, encoder.maxHeight).toDouble()
                    val scale = min(longLimit / max(width, height), shortLimit / min(width, height))
                    width = (width * scale + 1e-6).toInt()
                    height = (height * scale + 1e-6).toInt()
                    clamped = true
                    adjustments += VideoOutputAdjustment.ResolutionClampedToEncoder
                }
            }
            val alignedWidth = if (encoder == null) width else align(width, encoder.widthAlignment, clamped)
            val alignedHeight = if (encoder == null) height else align(height, encoder.heightAlignment, clamped)
            // Without an aspect or resolution change the edited frame goes to the encoder as today.
            val resizes = when {
                remuxVideo -> emptyList()
                sizes.resizes.isNotEmpty() ->
                    sizes.resizes.dropLast(1) + sizes.resizes.last().copy(width = alignedWidth, height = alignedHeight)
                clamped -> listOf(VideoFrameResize(alignedWidth, alignedHeight, VideoAspectMode.Stretch))
                else -> emptyList()
            }

            // Bitrate.
            val sourcePixels = source.displayWidth.toDouble() * source.displayHeight
            val outputPixels = alignedWidth.toDouble() * alignedHeight
            val sourceEfficiency = efficiency(sourceCodec, source.videoMimeType)
            val outputEfficiency = efficiency(codec, null)
            val ceiling = (outputPixels * outputFps * CeilingBpp * outputEfficiency)
            val floor = (outputPixels * outputFps * FloorBpp * outputEfficiency)
            // The source bitrate expressed for the output size, frame rate and codec.
            val originalEquivalent = source.videoBitrate?.takeIf { it > 0 }?.let { bitrate ->
                bitrate * (outputPixels / sourcePixels) * (outputFps / sourceFps) *
                    (outputEfficiency / sourceEfficiency)
            }
            val videoBitrate = if (remuxVideo) {
                source.videoBitrate ?: (sourcePixels * sourceFps * HighBpp * sourceEfficiency).toInt()
            } else {
                val target = when (val quality = output.quality) {
                    is VideoOutputBitrate.Target -> quality.bitsPerSecond.toDouble()
                    is VideoOutputBitrate.Preset -> {
                        val (bpp, sourceShare) = when (quality.preset) {
                            VideoQualityPreset.Original -> null to 1.0
                            VideoQualityPreset.High -> HighBpp to 1.25
                            VideoQualityPreset.Medium -> MediumBpp to 0.8
                            VideoQualityPreset.Low -> LowBpp to 0.5
                        }
                        val byBpp = (bpp ?: HighBpp) * outputPixels * outputFps * outputEfficiency
                        val bySource = originalEquivalent?.times(sourceShare)
                        val preset = when {
                            bpp == null -> bySource ?: byBpp
                            // Re-encoding cannot add detail: never spend more than the source carried.
                            bySource != null -> min(byBpp, bySource)
                            else -> byBpp
                        }
                        preset.coerceIn(floor, max(floor, ceiling))
                    }
                }
                val encoderCap = encoder?.maxBitrate ?: MaxVideoBitrate
                target.coerceIn(MinVideoBitrate.toDouble(), min(encoderCap, MaxVideoBitrate).toDouble()).toInt()
            }

            // Audio.
            val audio = audioPlan(
                output.audio,
                source,
                hasMusic = recipe.musicUri != null,
                // The source audio is independent from the video: a re-encoded video keeps the
                // original AAC stream untouched as long as volume, speed and slow motion leave it alone.
                copyable = recipe.originalAudioVolume == 1f && recipe.speed == 1f &&
                    recipe.slowMotionSegments.isEmpty(),
            )

            return VideoOutputPlan(
                codec = codec,
                videoMimeType = if (remuxVideo) checkNotNull(source.videoMimeType) else mimeFor(codec),
                width = alignedWidth,
                height = alignedHeight,
                videoBitrate = videoBitrate,
                frameRate = if (remuxVideo) sourceFps else outputFps,
                frameRateCap = frameRateCap,
                audio = audio,
                remuxVideo = remuxVideo,
                remuxAudio = audio == VideoAudioPlan.Copy,
                toneMapToSdr = toneMapToSdr,
                hevcMain10 = hevcMain10,
                resizes = resizes,
                adjustments = adjustments.distinct(),
                sourceAudioBitrate = source.audioBitrate ?: DefaultAudioBitrate,
            )
        }

        /**
         * Audio decision. Remove drops the source audio only: with added music the track stays and
         * is mixed and encoded. [copyable] is true when nothing touches the source audio stream.
         */
        internal fun audioPlan(
            requested: VideoOutputAudio,
            source: VideoSourceInfo,
            hasMusic: Boolean,
            copyable: Boolean,
        ): VideoAudioPlan = when {
            requested == VideoOutputAudio.Remove && !hasMusic -> VideoAudioPlan.Removed
            !source.hasAudio && !hasMusic -> VideoAudioPlan.None
            requested is VideoOutputAudio.Aac -> VideoAudioPlan.Encode(requested.bitsPerSecond, requested = true)
            copyable && !hasMusic && source.hasAudio && source.audioMimeType == MimeAac -> VideoAudioPlan.Copy
            else -> VideoAudioPlan.Encode(DefaultAudioBitrate, requested = false)
        }

        fun mimeFor(codec: VideoOutputCodec): String = when (codec) {
            VideoOutputCodec.Auto, VideoOutputCodec.H264 -> MimeH264
            VideoOutputCodec.Hevc -> MimeHevc
            VideoOutputCodec.Av1 -> MimeAv1
        }

        /** The output codec for a source MIME the MP4 muxer can copy, or null. */
        fun codecForMime(mimeType: String?): VideoOutputCodec? = when (mimeType?.lowercase()) {
            MimeH264 -> VideoOutputCodec.H264
            MimeHevc -> VideoOutputCodec.Hevc
            MimeAv1 -> VideoOutputCodec.Av1
            else -> null
        }

        /** Width and height after the recipe's crop and rotation, in displayed source pixels. */
        fun editedSize(source: VideoSourceInfo, geometry: VideoGeometry): Pair<Double, Double> {
            val cropWidth = source.displayWidth * (geometry.right - geometry.left).toDouble()
            val cropHeight = source.displayHeight * (geometry.bottom - geometry.top).toDouble()
            // Straightening zooms within the frame, so only the quarter turns change its shape.
            val radians = Math.toRadians(VideoStraighten.quarterTurnDegrees(geometry.rotationDegrees).toDouble())
            val c = abs(cos(radians)).let { if (it < 1e-9) 0.0 else it }
            val s = abs(sin(radians)).let { if (it < 1e-9) 0.0 else it }
            return (cropWidth * c + cropHeight * s) to (cropWidth * s + cropHeight * c)
        }

        private class Sizes(val width: Int, val height: Int, val resizes: List<VideoFrameResize>)

        private fun frameSize(
            editedWidth: Double,
            editedHeight: Double,
            output: VideoOutputSettings,
            adjustments: MutableList<VideoOutputAdjustment>,
        ): Sizes {
            val resizes = mutableListOf<VideoFrameResize>()
            var width = editedWidth
            var height = editedHeight
            val aspect = output.aspect
            if (aspect is VideoAspectOverride.Forced &&
                abs(width / height - aspect.width.toDouble() / aspect.height) > 0.002 * (aspect.width.toDouble() / aspect.height)
            ) {
                val ratio = aspect.width.toDouble() / aspect.height
                val widen = width / height < ratio
                when (aspect.mode) {
                    VideoAspectMode.Stretch, VideoAspectMode.Pad -> if (widen) width = height * ratio else height = width / ratio
                    VideoAspectMode.Crop -> if (widen) height = width / ratio else width = height * ratio
                }
                if (aspect.mode == VideoAspectMode.Stretch) {
                    val shortSide = min(width, height).roundToInt()
                    PaddedStandardSides[shortSide]?.let { standard ->
                        val scale = standard.toDouble() / shortSide
                        width *= scale
                        height *= scale
                    }
                }
                resizes += VideoFrameResize(width.roundToInt(), height.roundToInt(), aspect.mode)
            }
            when (val resolution = output.resolution) {
                VideoOutputResolution.Original -> Unit
                is VideoOutputResolution.ShortSide -> {
                    val shortSide = min(width, height)
                    if (resolution.shortSide < shortSide - 0.5) {
                        val scale = resolution.shortSide / shortSide
                        width *= scale
                        height *= scale
                        val mode = resizes.lastOrNull()?.mode ?: VideoAspectMode.Stretch
                        resizes.clear()
                        resizes += VideoFrameResize(width.roundToInt(), height.roundToInt(), mode)
                    } else if (resolution.shortSide > shortSide + 0.5) {
                        adjustments += VideoOutputAdjustment.ShortSideAboveSource
                    }
                }
                is VideoOutputResolution.Custom -> {
                    // Odd sizes round down to even, as VideoOutputResolution.Custom promises.
                    val evenWidth = max(2, resolution.width / 2 * 2)
                    val evenHeight = max(2, resolution.height / 2 * 2)
                    width = evenWidth.toDouble()
                    height = evenHeight.toDouble()
                    resizes += VideoFrameResize(evenWidth, evenHeight, VideoAspectMode.Pad)
                }
            }
            return Sizes(even(width), even(height), resizes)
        }

        private fun resolveCodec(
            requested: VideoOutputCodec,
            recipe: VideoEditRecipe,
            hdrOutput: Boolean,
            capabilities: VideoEncoderCapabilities,
            adjustments: MutableList<VideoOutputAdjustment>,
        ): VideoOutputCodec {
            val wanted = when {
                hdrOutput && requested != VideoOutputCodec.Auto && requested != VideoOutputCodec.Hevc -> {
                    adjustments += VideoOutputAdjustment.HdrRequiresHevc
                    VideoOutputCodec.Hevc
                }
                requested == VideoOutputCodec.Auto ->
                    if (hdrOutput || recipe.outputQuality == VideoOutputQuality.HevcMain10) {
                        VideoOutputCodec.Hevc
                    } else VideoOutputCodec.H264
                else -> requested
            }
            if (capabilities.support(wanted) != null) return wanted
            // HDR is HEVC only; the exporter reports missing HDR encoders itself.
            if (hdrOutput) return VideoOutputCodec.Hevc
            val fallbacks = when (wanted) {
                VideoOutputCodec.Av1 -> listOf(VideoOutputCodec.Hevc, VideoOutputCodec.H264)
                VideoOutputCodec.Hevc -> listOf(VideoOutputCodec.H264)
                else -> listOf(VideoOutputCodec.Hevc)
            }
            val fallback = fallbacks.firstOrNull { capabilities.support(it) != null } ?: return wanted
            adjustments += if (fallback == VideoOutputCodec.Hevc) {
                VideoOutputAdjustment.CodecUnavailableFellBackToHevc
            } else VideoOutputAdjustment.CodecUnavailableFellBackToH264
            return fallback
        }

        /** Relative bits needed for the same quality, H.264 = 1. */
        private fun efficiency(codec: VideoOutputCodec?, mimeType: String?): Double = when (codec) {
            VideoOutputCodec.Hevc -> 0.65
            VideoOutputCodec.Av1 -> 0.5
            VideoOutputCodec.H264, VideoOutputCodec.Auto -> 1.0
            null -> when (mimeType?.lowercase()) {
                "video/x-vnd.on2.vp9" -> 0.65
                "video/mp4v-es", "video/3gpp", "video/mjpeg" -> 1.5
                else -> 1.0
            }
        }

        private fun even(value: Double): Int = max(2, (value / 2.0).roundToInt() * 2)

        private fun align(value: Int, alignment: Int, floor: Boolean): Int {
            val step = max(2, alignment)
            val aligned = if (floor) value / step * step else (value.toDouble() / step).roundToInt() * step
            return max(step, aligned)
        }
    }
}
