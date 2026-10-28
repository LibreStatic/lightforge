package com.librestatic.lightforge.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisEtaTest {
    private val now = 1_000_000_000L

    /** One completion every [everyMillis], newest at [now]. */
    private fun steady(count: Int, everyMillis: Long) = List(count) { now - it * everyMillis }

    @Test
    fun steadyRateGivesRemainingOverRate() {
        // 31 stamps one second apart: 30 items in 30 s, so 1000 left take about 1000 s.
        assertEquals(1_000_000L, AnalysisEta.estimate(steady(31, 1_000), now, 1_000, true))
    }

    @Test
    fun nullWhenNotRunningOrNothingRemains() {
        assertNull(AnalysisEta.estimate(steady(50, 1_000), now, 1_000, false))
        assertNull(AnalysisEta.estimate(steady(50, 1_000), now, 0, true))
    }

    @Test
    fun nullWithTooFewSamplesOrTooShortSpan() {
        assertNull(AnalysisEta.estimate(steady(19, 1_000), now, 100, true))
        assertNull(AnalysisEta.estimate(steady(30, 100), now, 100, true))
    }

    @Test
    fun nullWhenTheNewestSampleIsStale() {
        val old = steady(30, 1_000).map { it - AnalysisEta.MaxStaleMillis - 1 }
        assertNull(AnalysisEta.estimate(old, now, 100, true))
    }

    @Test
    fun samplesOutsideTheWindowAreIgnored() {
        val ancient = List(40) { now - AnalysisEta.WindowMillis - 1_000 - it }
        assertNull(AnalysisEta.estimate(ancient + steady(5, 1_000), now, 100, true))
    }

    @Test
    fun roundCoarseSnapsToMinutesFiveMinutesAndHalfHours() {
        val minute = 60_000L
        assertEquals(minute, AnalysisEta.roundCoarse(5_000))
        assertEquals(4 * minute, AnalysisEta.roundCoarse(3 * minute + 40_000))
        assertEquals(25 * minute, AnalysisEta.roundCoarse(26 * minute))
        assertEquals(120 * minute, AnalysisEta.roundCoarse(110 * minute))
    }

    @Test
    fun builderClampsAndSuppressesEtaWhileWaiting() {
        val times = steady(40, 1_000)
        val running = AnalysisProgress.of(400, 300, true, null, times, now)
        assertEquals(300, running.processed)
        assertEquals(0, running.remaining)
        assertTrue(running.complete)
        assertNull(running.etaMillis)
        val live = AnalysisProgress.of(100, 300, true, null, times, now)
        assertEquals(200, live.remaining)
        assertFalse(live.complete)
        assertTrue(live.etaMillis != null)
        assertNull(AnalysisProgress.of(100, 300, true, AnalysisWait.Heat, times, now).etaMillis)
    }
}
