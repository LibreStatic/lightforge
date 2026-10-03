package com.librestatic.lightforge.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoPlaybackRecoveryTest {
    private val decoderInitFailed = 4001
    private val decodingFailed = 4003
    private val frameProcessingFailed = 7001
    private val frameProcessorInitFailed = 7000
    private val fileNotFound = 2005
    private val audioTrackInitFailed = 5001

    @Test
    fun decoderErrorsRetryWithSoftwareDecodersBeforeDroppingEffects() {
        val editor = VideoPipeline(videoEffects = true)

        val software = VideoPlaybackRecovery.next(editor, decoderInitFailed)
        assertEquals(VideoPipeline(videoEffects = true, preferSoftwareDecoder = true), software)

        val plain = VideoPlaybackRecovery.next(software!!, decodingFailed)
        assertEquals(VideoPipeline(videoEffects = false, preferSoftwareDecoder = true), plain)

        assertNull(VideoPlaybackRecovery.next(plain!!, decodingFailed))
    }

    @Test
    fun effectsGraphErrorsDropTheEffectsButKeepTheDecoderChoice() {
        assertEquals(
            VideoPipeline(videoEffects = false),
            VideoPlaybackRecovery.next(VideoPipeline(videoEffects = true), frameProcessingFailed),
        )
        assertEquals(
            VideoPipeline(videoEffects = false, preferSoftwareDecoder = true),
            VideoPlaybackRecovery.next(
                VideoPipeline(videoEffects = true, preferSoftwareDecoder = true),
                frameProcessorInitFailed,
            ),
        )
        assertNull(VideoPlaybackRecovery.next(VideoPipeline(videoEffects = false), frameProcessingFailed))
    }

    @Test
    fun viewerPlaybackOnlyRetriesTheDecoder() {
        val viewer = VideoPipeline(videoEffects = false)

        assertEquals(
            VideoPipeline(videoEffects = false, preferSoftwareDecoder = true),
            VideoPlaybackRecovery.next(viewer, decodingFailed),
        )
        assertNull(VideoPlaybackRecovery.next(viewer.copy(preferSoftwareDecoder = true), decodingFailed))
    }

    @Test
    fun sourceAndAudioErrorsAreShownWithoutRetrying() {
        val editor = VideoPipeline(videoEffects = true)

        assertNull(VideoPlaybackRecovery.next(editor, fileNotFound))
        assertNull(VideoPlaybackRecovery.next(editor, audioTrackInitFailed))
    }
}
