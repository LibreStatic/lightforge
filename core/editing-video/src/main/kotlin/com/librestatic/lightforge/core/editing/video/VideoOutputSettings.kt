package com.librestatic.lightforge.core.editing.video

/**
 * Converter-style output options for a video export: codec, quality, size, frame rate, a forced
 * display aspect ratio and the audio track. Defaults keep the source as it is, so a recipe without
 * output changes exports exactly as before.
 */
data class VideoOutputSettings(
    val codec: VideoOutputCodec = VideoOutputCodec.Auto,
    val quality: VideoOutputBitrate = VideoOutputBitrate.Preset(VideoQualityPreset.Original),
    val resolution: VideoOutputResolution = VideoOutputResolution.Original,
    val frameRate: VideoOutputFrameRate = VideoOutputFrameRate.Original,
    val aspect: VideoAspectOverride = VideoAspectOverride.Original,
    val audio: VideoOutputAudio = VideoOutputAudio.Keep,
) {
    val isDefault: Boolean get() = this == VideoOutputSettings()
}

/** Auto keeps today's behaviour: H.264 for SDR, HEVC for 10-bit and HDR outputs. */
enum class VideoOutputCodec { Auto, H264, Hevc, Av1 }

enum class VideoQualityPreset { Original, High, Medium, Low }

sealed interface VideoOutputBitrate {
    /** Original targets roughly the source bitrate; the others scale it down from the output size. */
    data class Preset(val preset: VideoQualityPreset) : VideoOutputBitrate

    data class Target(val bitsPerSecond: Int) : VideoOutputBitrate {
        init { require(bitsPerSecond in 100_000..200_000_000) }
    }
}

sealed interface VideoOutputResolution {
    data object Original : VideoOutputResolution

    /** Scales so the shorter side of the output is [shortSide] px; never upscales. */
    data class ShortSide(val shortSide: Int) : VideoOutputResolution {
        init { require(shortSide in 144..4320) }
    }

    /** Exact output size in pixels; both sides are rounded down to even numbers by the exporter. */
    data class Custom(val width: Int, val height: Int) : VideoOutputResolution {
        init { require(width in 16..8192 && height in 16..8192) }
    }

    companion object {
        val Common: List<ShortSide> = listOf(2160, 1440, 1080, 720, 480).map(::ShortSide)
    }
}

sealed interface VideoOutputFrameRate {
    data object Original : VideoOutputFrameRate

    /** Caps the frame rate by dropping frames; never invents frames above the source rate. */
    data class Max(val fps: Int) : VideoOutputFrameRate {
        init { require(fps in 1..240) }
    }

    companion object {
        val Common: List<Max> = listOf(60, 30, 25, 24, 15).map(::Max)
    }
}

/**
 * A display aspect ratio to force on the output, for sources that report the wrong one (e.g. CCTV
 * recorders that store a 16:9 scene in a 960×1088 frame). Applied after crop and rotation.
 */
sealed interface VideoAspectOverride {
    data object Original : VideoAspectOverride

    data class Forced(val width: Int, val height: Int, val mode: VideoAspectMode) : VideoAspectOverride {
        init { require(width in 1..100 && height in 1..100) }

        val ratio: Float get() = width.toFloat() / height
    }

    companion object {
        val Common: List<Pair<Int, Int>> = listOf(16 to 9, 4 to 3, 1 to 1, 9 to 16, 21 to 9)
    }
}

enum class VideoAspectMode {
    /** Resample the frame to the new ratio: fixes a wrong pixel aspect, distorts a correct one. */
    Stretch,

    /** Fill the new ratio and cut what overflows. */
    Crop,

    /** Fit inside the new ratio and pad with black bars. */
    Pad,
}

sealed interface VideoOutputAudio {
    data object Keep : VideoOutputAudio

    data object Remove : VideoOutputAudio

    data class Aac(val bitsPerSecond: Int) : VideoOutputAudio {
        init { require(bitsPerSecond in 32_000..320_000) }
    }
}
