package com.librestatic.lightforge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedMoveBannerSpecTest {
    @Test
    fun `a completed move reads as success with a single dismissal`() {
        val banner = verifiedMoveBanner(VerifiedMovePhase.Completed, failed = false, unreadable = false)
        assertEquals(VerifiedMoveTone.Success, banner.tone)
        assertTrue(banner.showDone)
        assertFalse(banner.showForget)
        assertFalse(banner.showRetry)
        assertFalse(banner.showAccess)
        assertFalse(banner.showReview)
    }

    @Test
    fun `a move awaiting the system keeps its recovery actions`() {
        val banner = verifiedMoveBanner(VerifiedMovePhase.AwaitingSystem, failed = false, unreadable = false)
        assertEquals(VerifiedMoveTone.Progress, banner.tone)
        assertTrue(banner.showRetry)
        assertTrue(banner.showAccess)
        assertTrue(banner.showForget)
        assertFalse(banner.showDone)
    }

    @Test
    fun `a failed attempt still reads as attention`() {
        val banner = verifiedMoveBanner(VerifiedMovePhase.RequestFailed, failed = true, unreadable = false)
        assertEquals(VerifiedMoveTone.Attention, banner.tone)
        assertTrue(banner.showRetry)
        assertTrue(banner.showForget)
        assertFalse(banner.showDone)
    }

    @Test
    fun `a completed move with a degraded record never collapses into success`() {
        val banner = verifiedMoveBanner(VerifiedMovePhase.Completed, failed = true, unreadable = false)
        assertEquals(VerifiedMoveTone.Attention, banner.tone)
        assertFalse(banner.showDone)
        assertTrue(banner.showForget)
        assertFalse(banner.showRetry)
    }

    @Test
    fun `an unreadable record offers review without an entry`() {
        val banner = verifiedMoveBanner(null, failed = false, unreadable = true)
        assertEquals(VerifiedMoveTone.Attention, banner.tone)
        assertTrue(banner.showReview)
        assertFalse(banner.showRetry)
        assertFalse(banner.showForget)
        assertFalse(banner.showDone)
    }
}
