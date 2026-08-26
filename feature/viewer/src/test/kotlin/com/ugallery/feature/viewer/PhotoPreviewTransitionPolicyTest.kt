package com.ugallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPreviewTransitionPolicyTest {
    @Test
    fun previewFasterThanThresholdIsImmediate() {
        assertEquals(
            PhotoPreviewTransition.Immediate,
            PhotoPreviewTransitionPolicy.decide(249, normalizedVisualDifference = null),
        )
    }

    @Test
    fun subtleSlowUpgradeIsImmediate() {
        assertEquals(
            PhotoPreviewTransition.Immediate,
            PhotoPreviewTransitionPolicy.decide(250, normalizedVisualDifference = 0.04f),
        )
    }

    @Test
    fun noticeableSlowUpgradeCrossfades() {
        assertEquals(
            PhotoPreviewTransition.Crossfade,
            PhotoPreviewTransitionPolicy.decide(250, normalizedVisualDifference = 0.20f),
        )
        assertEquals(
            PhotoPreviewTransition.Crossfade,
            PhotoPreviewTransitionPolicy.decide(250, normalizedVisualDifference = null),
        )
    }
}
