package com.librestatic.lightforge.core.editing.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoAutoEnhancementFitTest {
    @Test
    fun darkPhotoIsBrightenedEvenWhenFullContrastIsInfeasible() {
        val (contrast, brightness) = PhotoAutoEnhancementAnalyzer.fitTone(1.35f, 0f, 0.08f, 0.3f)
        assertTrue("brightness=$brightness", brightness > 0.05f)
        // The tone must not darken the median.
        val median = contrast * 0.08f + (1f - contrast) * 0.5f + brightness
        assertTrue("median=$median", median > 0.08f)
    }

    @Test
    fun wellExposedPhotoKeepsRequestedContrast() {
        val (contrast, _) = PhotoAutoEnhancementAnalyzer.fitTone(1.1f, 0.1f, 0.5f, 0.9f)
        assertEquals(1.1f, contrast, 0f)
    }
}
