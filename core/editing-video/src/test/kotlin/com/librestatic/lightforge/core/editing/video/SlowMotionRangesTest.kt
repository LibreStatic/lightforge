package com.librestatic.lightforge.core.editing.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowMotionRangesTest {
    private fun segment(start: Long, end: Long, speed: Float = 0.25f, mode: SlowMotionAudioMode = SlowMotionAudioMode.PreservePitch) =
        SlowMotionSegment(id = "s$start", startMillis = start, endMillis = end, speed = speed, audioMode = mode)

    @Test
    fun globalSlowdownBecomesOneInterpolatedRange() {
        val ranges = slowMotionRanges(VideoEditRecipe(speed = 0.5f), 10_000)
        assertEquals(listOf(PlannedRange(0, 10_000, 0.5f, 2)), ranges)
        assertEquals(1f, ranges.single().videoPlaybackSpeed, 0.0001f)
    }

    @Test
    fun globalQuarterSpeedUsesFactorFour() {
        val range = slowMotionRanges(VideoEditRecipe(speed = 0.25f), 4_000).single()
        assertEquals(4, range.interpolationFactor)
    }

    @Test
    fun flagOffLeavesNoInterpolation() {
        val recipe = VideoEditRecipe(
            speed = 0.5f,
            interpolateSlowMotion = false,
            slowMotionSegments = listOf(segment(2_000, 3_000)),
        )
        val ranges = slowMotionRanges(recipe, 6_000)
        assertEquals(3, ranges.size)
        assertTrue(ranges.all { it.interpolationFactor == null })
    }

    @Test
    fun speedsAtOrAboveOneAreNeverInterpolated() {
        assertNull(slowMotionRanges(VideoEditRecipe(speed = 1f), 5_000).single().interpolationFactor)
        assertNull(slowMotionRanges(VideoEditRecipe(speed = 2f), 5_000).single().interpolationFactor)
        assertNull(slowMotionRanges(VideoEditRecipe(speed = 0.9f), 5_000).single().interpolationFactor)
    }

    @Test
    fun nonPowerOfTwoSlowdownKeepsTheExactSpeedThroughTheVideoItem() {
        val range = slowMotionRanges(VideoEditRecipe(speed = 0.3f), 5_000).single()
        assertEquals(4, range.interpolationFactor)
        assertEquals(1.2f, range.videoPlaybackSpeed, 0.0001f)
        assertTrue(range.needsVideoSpeedChange())
    }

    @Test
    fun segmentsKeepTheirOwnFactorAndGlobalSpeedFillsTheGaps() {
        val recipe = VideoEditRecipe(
            startMillis = 1_000,
            endMillis = 9_000,
            speed = 0.25f,
            slowMotionSegments = listOf(
                segment(2_000, 3_000, 0.5f, SlowMotionAudioMode.Muted),
                segment(5_000, 6_000, 0.125f),
            ),
        )
        assertEquals(
            listOf(
                PlannedRange(1_000, 2_000, 0.25f, 4),
                PlannedRange(2_000, 3_000, 0.5f, 2, SlowMotionAudioMode.Muted),
                PlannedRange(3_000, 5_000, 0.25f, 4),
                PlannedRange(5_000, 6_000, 0.125f, 8, SlowMotionAudioMode.PreservePitch),
                PlannedRange(6_000, 9_000, 0.25f, 4),
            ),
            slowMotionRanges(recipe, 9_000),
        )
    }

    @Test
    fun segmentsTouchingTheTrimBoundariesLeaveNoEmptyGaps() {
        val recipe = VideoEditRecipe(
            startMillis = 500,
            endMillis = 4_500,
            slowMotionSegments = listOf(segment(500, 1_500), segment(3_500, 4_500)),
        )
        val ranges = slowMotionRanges(recipe, 4_500)
        assertEquals(listOf(500L to 1_500L, 1_500L to 3_500L, 3_500L to 4_500L), ranges.map { it.startMillis to it.endMillis })
        assertEquals(listOf(4, null, 4), ranges.map { it.interpolationFactor })
    }

    @Test
    fun rangesAreContiguousAndCoverTheTrimmedClip() {
        val recipe = VideoEditRecipe(
            startMillis = 700,
            speed = 0.5f,
            slowMotionSegments = listOf(segment(1_000, 2_000), segment(2_000, 2_500, 0.5f)),
        )
        val ranges = slowMotionRanges(recipe, 8_000)
        assertEquals(700, ranges.first().startMillis)
        assertEquals(8_000, ranges.last().endMillis)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis, b.startMillis) }
    }

    @Test
    fun plannedDurationsSumToTheOutputDuration() {
        listOf(
            VideoEditRecipe(speed = 0.5f),
            VideoEditRecipe(speed = 0.5f, interpolateSlowMotion = false),
            VideoEditRecipe(speed = 2f, startMillis = 250),
            VideoEditRecipe(
                startMillis = 1_000,
                speed = 0.25f,
                slowMotionSegments = listOf(segment(2_000, 3_000, 0.5f), segment(4_000, 5_500, 0.125f)),
            ),
            VideoEditRecipe(
                startMillis = 500,
                slowMotionSegments = listOf(segment(500, 1_500), segment(7_000, 9_000)),
            ),
        ).forEach { recipe ->
            val ranges = slowMotionRanges(recipe, 9_000)
            val summed = ranges.sumOf { it.outputMillis }
            assertEquals(recipe.outputDurationMillis(9_000).toDouble(), summed, 1.0)
            // An interpolated range plays factor-times the source frames at the video speed.
            ranges.forEach { range ->
                val factor = range.interpolationFactor ?: return@forEach
                assertEquals(range.outputMillis, range.sourceMillis * factor / range.videoPlaybackSpeed.toDouble(), 0.5)
            }
        }
    }
}
