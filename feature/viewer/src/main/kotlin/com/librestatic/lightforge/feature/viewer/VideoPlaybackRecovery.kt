package com.librestatic.lightforge.feature.viewer

/**
 * How a [VideoViewerController] plays: through Media3's GL effects graph or straight to the
 * surface, and with hardware or software decoders first.
 */
internal data class VideoPipeline(
    val videoEffects: Boolean,
    val preferSoftwareDecoder: Boolean = false,
)

/**
 * Picks the next, more conservative pipeline after a playback error, or null when retrying cannot
 * help (I/O, missing files, unsupported containers, audio errors) and the failure must be shown.
 *
 * Decoder errors first retry with software decoders, which accept streams some hardware decoders
 * reject (HikVision HEVC, odd sizes). Effects-graph errors, and decoder errors that persist with
 * software decoders, then drop the effects graph so the clip still plays without effects.
 */
internal object VideoPlaybackRecovery {
    // androidx.media3.common.PlaybackException codes, inlined so this stays a pure JVM function.
    private val DecoderErrorCodes = 4001..4006
    private const val VideoFrameProcessorInitFailed = 7000
    private const val VideoFrameProcessingFailed = 7001
    private val FrameProcessingErrorCodes = setOf(VideoFrameProcessorInitFailed, VideoFrameProcessingFailed)

    /** Bounds the ladder even if a step is tried twice. */
    const val MaxRecoveries = 3

    fun next(current: VideoPipeline, errorCode: Int): VideoPipeline? = when {
        errorCode in DecoderErrorCodes && !current.preferSoftwareDecoder ->
            current.copy(preferSoftwareDecoder = true)
        errorCode in DecoderErrorCodes && current.videoEffects ->
            current.copy(videoEffects = false)
        errorCode in FrameProcessingErrorCodes && current.videoEffects ->
            current.copy(videoEffects = false)
        else -> null
    }
}
