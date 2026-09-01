package com.ugallery.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEditRecipeCodecTest {
    @Test
    fun roundTripsPersistedColorRecipe() {
        val grade = VideoColorGrade(
            inputProfile = LogInputProfile.SonySLog3,
            profileWasAutoDetected = true,
            exposureEv = 1.25f,
            temperature = 0.2f,
            tint = -0.1f,
            contrast = 0.3f,
            pivot = 0.45f,
            saturation = 0.15f,
            logWheels = LogWheels(
                shadows = LogWheel(red = 0.1f, level = -0.2f),
                midtones = LogWheel(green = 0.15f),
                highlights = LogWheel(blue = -0.1f, level = 0.25f),
            ),
            hueBands = HueBand.entries.map { HueBandAdjustment(it, 12f, 0.1f, -0.05f) },
            lut = LutReference(customId = 42L, intensity = 0.7f),
        )
        val recipe = VideoEditRecipe(
            startMillis = 500L,
            endMillis = 9_500L,
            speed = 1.5f,
            originalAudioVolume = 0.75f,
            musicVolume = 0.4f,
            geometry = VideoGeometry(
                left = 0.1f,
                top = 0.2f,
                right = 0.9f,
                bottom = 0.8f,
                rotationDegrees = 2.5f,
                flipHorizontal = true,
            ),
            colorGrade = grade,
            outputQuality = VideoOutputQuality.HevcMain10,
            dynamicRange = VideoDynamicRange.Hdr10Pq,
            slowMotionSegments = listOf(
                SlowMotionSegment(
                    id = "slow-1",
                    startMillis = 1_000,
                    endMillis = 2_500,
                    speed = 0.125f,
                    audioMode = SlowMotionAudioMode.Muted,
                ),
            ),
            annotations = listOf(
                VideoAnnotationLayer(
                    id = "annotation-1",
                    shape = VideoAnnotationShape.Star,
                    points = listOf(NormalizedPoint(0.2f, 0.25f), NormalizedPoint(0.6f, 0.7f)),
                    style = VideoAnnotationStyle(
                        appearance = VideoAnnotationAppearance.Mosaic,
                        colorArgb = 0xFF123456.toInt(),
                        strokeWidth = 0.02f,
                        opacity = 0.8f,
                        filled = true,
                        intensity = 0.75f,
                    ),
                    startMillis = 700,
                    endMillis = 9_000,
                    trackingMode = VideoAnnotationTrackingMode.Automatic,
                    keyframes = listOf(
                        VideoAnnotationKeyframe(700, VideoAnnotationTransform()),
                        VideoAnnotationKeyframe(
                            9_000,
                            VideoAnnotationTransform(0.1f, -0.1f, 1.2f, 0.9f, 15f),
                            confidence = 0.82f,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(recipe, VideoEditRecipeCodec.decode(VideoEditRecipeCodec.encode(recipe)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownRecipeVersion() {
        VideoEditRecipeCodec.decode("version=99")
    }

    @Test
    fun readsVersionOneRecipesWithoutSlowSegments() {
        val decoded = VideoEditRecipeCodec.decode("version=1\nspeed=0.5\n")
        assertEquals(0.5f, decoded.speed)
        assertEquals(emptyList<SlowMotionSegment>(), decoded.slowMotionSegments)
        assertEquals(VideoGeometry(), decoded.geometry)
        assertEquals(emptyList<VideoAnnotationLayer>(), decoded.annotations)
        assertEquals(VideoDynamicRange.SdrRec709, decoded.dynamicRange)
    }

    @Test
    fun versionFourRecipeDefaultsToSdr() {
        val decoded = VideoEditRecipeCodec.decode("version=4\nquality=HevcMain10\n")

        assertEquals(VideoOutputQuality.HevcMain10, decoded.outputQuality)
        assertEquals(VideoDynamicRange.SdrRec709, decoded.dynamicRange)
    }

    @Test
    fun normalizesPartialAndDuplicateHueBands() {
        val decoded = VideoEditRecipeCodec.decode(
            """
            version=4
            bands=Red,5.0,0.1,-0.2;Blue,2.0,0.0,0.0;Red,-7.0,0.3,0.4;invalid
            """.trimIndent(),
        )

        assertEquals(HueBand.entries, decoded.colorGrade.hueBands.map(HueBandAdjustment::band))
        assertEquals(
            HueBandAdjustment(HueBand.Red, -7f, 0.3f, 0.4f),
            decoded.colorGrade.hueBands.first(),
        )
        assertEquals(
            HueBandAdjustment(HueBand.Blue, 2f, 0f, 0f),
            decoded.colorGrade.hueBands.first { it.band == HueBand.Blue },
        )
        assertEquals(
            HueBandAdjustment(HueBand.Green),
            decoded.colorGrade.hueBands.first { it.band == HueBand.Green },
        )
    }

    @Test
    fun interpolatesAnnotationKeyframesAcrossShortestRotation() {
        val layer = VideoAnnotationLayer(
            shape = VideoAnnotationShape.Rectangle,
            points = listOf(NormalizedPoint(0.1f, 0.1f), NormalizedPoint(0.3f, 0.3f)),
            startMillis = 0,
            endMillis = 1_001,
            trackingMode = VideoAnnotationTrackingMode.Keyframes,
            keyframes = listOf(
                VideoAnnotationKeyframe(0, VideoAnnotationTransform(rotationDegrees = 170f)),
                VideoAnnotationKeyframe(1_000, VideoAnnotationTransform(translationX = 0.2f, rotationDegrees = -170f)),
            ),
        )

        assertEquals(0.1f, layer.transformAt(500).translationX, 0.0001f)
        assertEquals(180f, layer.transformAt(500).rotationDegrees, 0.0001f)
    }
}
