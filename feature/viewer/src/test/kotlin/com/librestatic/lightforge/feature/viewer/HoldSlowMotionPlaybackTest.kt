package com.librestatic.lightforge.feature.viewer

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldSlowMotionPlaybackTest {
    private val frames = longArrayOf(100, 140, 180, 400)

    private fun nearest(target: Long) = nearestFrameIndex(frames.size, { frames[it] }, target)

    @Test
    fun nearestFramePicksClosestAndClampsToEnds() {
        assertEquals(-1, nearestFrameIndex(0, { 0L }, 50))
        assertEquals(0, nearest(-500))
        assertEquals(0, nearest(100))
        assertEquals(0, nearest(119))
        assertEquals(0, nearest(120)) // tie goes to the earlier frame
        assertEquals(1, nearest(121))
        assertEquals(2, nearest(250))
        assertEquals(3, nearest(300))
        assertEquals(3, nearest(9_999))
    }

    @Test
    fun paceDelayScalesWithRateAndIsClamped() {
        assertEquals(null, slowMotionPaceDelayMillis(8, 0f))
        assertEquals(32L, slowMotionPaceDelayMillis(8, 0.25f))
        assertEquals(32L, slowMotionPaceDelayMillis(8, -0.25f))
        assertEquals(64L, slowMotionPaceDelayMillis(8, 0.125f))
        assertEquals(16L, slowMotionPaceDelayMillis(8, 1f)) // minimum clamp
        assertEquals(100L, slowMotionPaceDelayMillis(100, 0.25f)) // maximum clamp
        assertEquals(32L, slowMotionPaceDelayMillis(-8, 0.25f)) // gap sign is irrelevant
    }

    @Test
    fun rateIsClampedToUnitMagnitude() {
        assertEquals(1f, clampSlowMotionRate(3f), 0f)
        assertEquals(-1f, clampSlowMotionRate(-3f), 0f)
        assertEquals(-0.25f, clampSlowMotionRate(-0.25f), 0f)
        assertEquals(0f, clampSlowMotionRate(Float.NaN), 0f)
    }

    @Test
    fun windowForDirectionUsesPreviousPositionWhenReversing() {
        assertEquals(4_000L, slowMotionWindowFor(4_000, 1))
        assertEquals(19_500L, slowMotionWindowFor(19_900, 1))
        assertEquals(1_500L, slowMotionWindowFor(4_000, -1))
        assertEquals(2_500L, slowMotionWindowFor(4_501, -1))
        assertEquals(0L, slowMotionWindowFor(2_000, -1))
        assertEquals(0L, slowMotionWindowFor(0, -1))
    }

    private fun candidate(
        start: Long,
        last: Long? = null,
        complete: Boolean = false,
        end: Long = start + SlowMotionWindowSpanMillis,
        preparing: Boolean = false,
    ) = SlowMotionWindowCandidate(start, last, complete, end, preparing)

    @Test
    fun completeWindowCoveringTargetIsReused() {
        val chosen = chooseSlowMotionWindow(12_000, 12_000, listOf(candidate(8_000, complete = true)))
        assertEquals(8_000L, chosen)
    }

    @Test
    fun warmWindowStartedHalfSecondEarlierIsReused() {
        val warm = candidate(19_500, last = 19_900, preparing = true)
        assertEquals(19_500L, chooseSlowMotionWindow(20_000, 20_000, listOf(warm)))
        assertEquals(19_500L, chooseSlowMotionWindow(20_000, 20_000, listOf(candidate(19_500, preparing = true))))
    }

    @Test
    fun partialWindowTooFarBehindTargetIsRejected() {
        val partial = candidate(10_000, last = 11_000)
        assertEquals(12_500L, chooseSlowMotionWindow(12_500, 12_500, listOf(partial)))
        assertEquals(10_000L, chooseSlowMotionWindow(12_000, 12_000, listOf(partial)))
    }

    @Test
    fun windowAboutToEndIsRejected() {
        val complete = candidate(8_000, complete = true)
        assertEquals(14_000L, chooseSlowMotionWindow(14_000, 14_000, listOf(complete)))
        assertEquals(13_500L, chooseSlowMotionWindow(13_500, 13_500, listOf(complete)))
        assertEquals(8_000L, chooseSlowMotionWindow(12_999, 12_500, listOf(complete)))
    }

    @Test
    fun windowAtEndOfVideoIsAcceptedNearItsEnd() {
        val last = candidate(8_000, complete = true, end = 9_800)
        assertEquals(8_000L, chooseSlowMotionWindow(9_700, 9_500, listOf(last)))
        assertEquals(8_000L, chooseSlowMotionWindow(20_000, 20_000, listOf(last)))
    }

    @Test
    fun windowStartingAfterTargetIsRejected() {
        assertEquals(1_500L, chooseSlowMotionWindow(1_600, 1_500, listOf(candidate(2_000, complete = true))))
    }

    @Test
    fun leastSourceLeftWinsThenFurthestReach() {
        val rendering = candidate(10_000, last = 10_800)
        val complete = candidate(9_000, complete = true)
        assertEquals(9_000L, chooseSlowMotionWindow(11_000, 11_000, listOf(rendering, complete)))
        val earlier = candidate(7_500, complete = true)
        assertEquals(9_000L, chooseSlowMotionWindow(11_000, 11_000, listOf(earlier, complete)))
    }

    @Test
    fun freshStartIsTheFallback() {
        assertEquals(5_000L, chooseSlowMotionWindow(5_200, 5_000, emptyList()))
    }

    private fun buffer(
        windowStart: Long = 4_000,
        first: Long = 4_000,
        last: Long = 9_990,
        complete: Boolean = true,
        end: Long = windowStart + 6_000,
        count: Int = 100,
    ) = SlowMotionBufferSnapshot(windowStart, count, first, last, complete, end)

    @Test
    fun targetInsideBufferIsShown() {
        assertEquals(SlowMotionBufferPlan.Show, planSlowMotionBuffer(6_000, buffer()))
    }

    @Test
    fun runningPastEitherEndSwitchesWindows() {
        assertEquals(SlowMotionBufferPlan.Switch(1_500), planSlowMotionBuffer(3_999, buffer()))
        assertEquals(SlowMotionBufferPlan.Switch(10_000), planSlowMotionBuffer(10_000, buffer()))
        assertEquals(SlowMotionBufferPlan.Switch(12_500), planSlowMotionBuffer(12_500, buffer()))
    }

    @Test
    fun tailOfCompleteWindowIsHeldUntilItsEnd() {
        assertEquals(SlowMotionBufferPlan.Show, planSlowMotionBuffer(9_995, buffer()))
    }

    @Test
    fun startAndEndOfVideoHoldInsteadOfSwitching() {
        assertEquals(SlowMotionBufferPlan.Show, planSlowMotionBuffer(-1, buffer(windowStart = 0, first = 0)))
        assertEquals(SlowMotionBufferPlan.Show, planSlowMotionBuffer(4_010, buffer(windowStart = 4_000, first = 4_030)))
        val last = buffer(windowStart = 8_000, first = 8_000, last = 9_700, end = 9_800)
        assertEquals(SlowMotionBufferPlan.Show, planSlowMotionBuffer(20_000, last))
    }

    @Test
    fun incompleteBufferWaitsForFramesInsteadOfSwitching() {
        val rendering = buffer(last = 5_000, complete = false)
        assertEquals(SlowMotionBufferPlan.AwaitFrames, planSlowMotionBuffer(5_500, rendering))
        assertEquals(SlowMotionBufferPlan.AwaitFrames, planSlowMotionBuffer(2_000_000, rendering))
        assertEquals(SlowMotionBufferPlan.AwaitFrames, planSlowMotionBuffer(0, buffer(count = 0, complete = false)))
        assertEquals(SlowMotionBufferPlan.Empty, planSlowMotionBuffer(0, buffer(count = 0)))
    }

    @Test
    fun visitedRangeBecomesClipOnlyWhenNotEmpty() {
        val range = SlowMotionVisitedRange()
        assertNull(range.range())
        range.include(5_000)
        assertNull(range.range())
        range.include(4_200)
        range.include(6_100)
        range.include(5_500)
        assertEquals(4_200L..6_100L, range.range())
    }

    @Test
    fun rateLabelShowsDirectionAndTrimsZeros() {
        assertEquals("0.25×", slowMotionRateLabel(0.25f, Locale.US))
        assertEquals("−0.5×", slowMotionRateLabel(-0.5f, Locale.US))
        assertEquals("0.125×", slowMotionRateLabel(0.125f, Locale.US))
        assertEquals("0×", slowMotionRateLabel(0f, Locale.US))
        assertEquals("0,25×", slowMotionRateLabel(0.25f, Locale.GERMANY))
    }
}
