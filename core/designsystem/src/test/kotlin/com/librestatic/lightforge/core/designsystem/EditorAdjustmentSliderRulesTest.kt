package com.librestatic.lightforge.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorAdjustmentSliderRulesTest {
    @Test
    fun valuesNearNeutralSnapToIt() {
        assertEquals(0f, EditorAdjustmentSliderRules.snap(0.03f, -1f..1f, 0f))
        assertEquals(0.05f, EditorAdjustmentSliderRules.snap(0.05f, -1f..1f, 0f))
        // Neutral need not be the middle: contrast is 0..2 with 1 as "no change".
        assertEquals(1f, EditorAdjustmentSliderRules.snap(1.03f, 0f..2f, 1f))
        assertEquals(2f, EditorAdjustmentSliderRules.snap(3f, 0f..2f, 1f))
    }

    @Test
    fun hapticTickOnlyWhenArrivingAtNeutral() {
        assertTrue(EditorAdjustmentSliderRules.entersDetent(0.2f, 0f, 0f))
        assertFalse(EditorAdjustmentSliderRules.entersDetent(0f, 0f, 0f))
        assertFalse(EditorAdjustmentSliderRules.entersDetent(0.2f, 0.1f, 0f))
    }

    @Test
    fun valueTextIsSigned() {
        assertEquals("+0.25", EditorAdjustmentSliderRules.format(0.25f, 0f))
        assertEquals("−0.40", EditorAdjustmentSliderRules.format(-0.4f, 0f))
        assertEquals("0", EditorAdjustmentSliderRules.format(0.001f, 0f))
        assertEquals("+0.20", EditorAdjustmentSliderRules.format(1.2f, 1f))
        assertEquals("−12", EditorAdjustmentSliderRules.format(-12f, 0f, decimals = 0))
    }

    @Test
    fun fillGrowsFromNeutralTowardTheThumb() {
        assertEquals(0.5f to 0.75f, EditorAdjustmentSliderRules.fill(0.5f, -1f..1f, 0f))
        assertEquals(0.25f to 0.5f, EditorAdjustmentSliderRules.fill(-0.5f, -1f..1f, 0f))
        assertEquals(0.5f to 0.5f, EditorAdjustmentSliderRules.fill(1f, 0f..2f, 1f))
    }
}
