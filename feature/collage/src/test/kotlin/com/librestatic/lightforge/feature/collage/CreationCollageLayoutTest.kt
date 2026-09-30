package com.librestatic.lightforge.feature.collage

import org.junit.Assert.*
import org.junit.Test

class CreationCollageLayoutTest {
    @Test fun allExistingTemplatesAndApprovedFourPhotoStripRemainAvailable() {
        assertEquals(CollageTemplate.entries.toSet(), CreationCollageTemplate.entries.mapNotNull { it.legacy }.toSet())
        assertEquals(listOf(CreationCollageTemplate.Grid4, CreationCollageTemplate.Strip4), CreationCollageTemplate.forCount(4))
        assertEquals(4, CreationCollageTemplate.Strip4.slots().size)
    }
    @Test fun movingPhotosPreservesTheirOwnCropAndNeverLosesAMember() {
        val crop = CreationCollageCrop(2f, -.5f, .5f)
        val initial = CreationCollageLayout.initial(3).crop(0, crop)
        val moved = initial.move(0, 1)
        assertEquals(listOf(1, 0, 2), moved.order)
        assertEquals(crop, moved.crops[moved.order[1]])
        assertEquals(listOf(0, 1, 2), initial.order)
    }
    @Test fun everyNewDraftHasCleanOrderAndDefaultCrops() {
        val old = CreationCollageLayout.initial(4).move(0, 1).crop(1, CreationCollageCrop(3f))
        val next = CreationCollageLayout.initial(4)
        assertNotEquals(old, next)
        assertEquals(listOf(0, 1, 2, 3), next.order)
        assertTrue(next.crops.all { it == CreationCollageCrop() })
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateSourceIndexIsRejected() {
        CreationCollageLayout(CreationCollageTemplate.Grid3, listOf(0, 0, 2))
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchedTemplateCannotSilentlyOmitPhotos() {
        CreationCollageLayout.initial(4).copy(template = CreationCollageTemplate.Strip3)
    }
    @Test(expected = IllegalArgumentException::class) fun nonfiniteCropIsRejected() { CreationCollageCrop(Float.NaN) }
    @Test(expected = IllegalArgumentException::class) fun outOfBoundsCropIsRejected() { CreationCollageCrop(horizontal = 1.1f) }
    @Test(expected = IllegalArgumentException::class) fun movingPastFirstSlotIsRejected() { CreationCollageLayout.initial(3).move(0, -1) }
}
