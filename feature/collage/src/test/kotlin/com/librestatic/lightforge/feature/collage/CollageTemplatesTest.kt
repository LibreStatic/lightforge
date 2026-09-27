package com.librestatic.lightforge.feature.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for slot/layout geometry.
 * Rendering tests that need android.graphics.Bitmap/Canvas live in
 * CollageRenderTest (instrumented) or use Robolectric.
 */
class CollageTemplatesTest {

    @Test
    fun getSlots_grid2_has2Slots() {
        val slots = CollageTemplates.getSlots(CollageTemplate.GRID_2)
        assertEquals(2, slots.size)
    }

    @Test
    fun getSlots_grid4_has4Slots() {
        val slots = CollageTemplates.getSlots(CollageTemplate.GRID_4)
        assertEquals(4, slots.size)
    }

    @Test
    fun getSlots_allTemplates_haveCorrectSlotCount() {
        for (template in CollageTemplate.entries) {
            val slots = CollageTemplates.getSlots(template)
            assertEquals("Template $template should have ${template.slotCount} slots",
                template.slotCount, slots.size)
        }
    }

    @Test
    fun getSlots_strip3_areHorizontalThirds() {
        val slots = CollageTemplates.getSlots(CollageTemplate.STRIP_3)
        assertEquals(3, slots.size)
        assertEquals(0f, slots[0].x, 0.001f)
        assertEquals(0.333f, slots[1].x, 0.001f)
        assertEquals(0.667f, slots[2].x, 0.001f)
        assertTrue(slots.all { it.height == 1f })
    }

    @Test
    fun getSlots_grid4_coverFullArea() {
        val slots = CollageTemplates.getSlots(CollageTemplate.GRID_4)
        // All slots should be within 0..1 normalized range
        assertTrue(slots.all { it.x >= 0f && it.x + it.width <= 1.001f })
        assertTrue(slots.all { it.y >= 0f && it.y + it.height <= 1.001f })
    }

    @Test
    fun getSlots_stack3_haveRotations() {
        val slots = CollageTemplates.getSlots(CollageTemplate.STACK_3)
        assertTrue(slots.any { it.rotation != 0f })
    }

    @Test
    fun getSlots_polaroid3_havePadding() {
        val slots = CollageTemplates.getSlots(CollageTemplate.POLAROID_3)
        assertTrue(slots.all { it.padding > 0f })
    }
}
