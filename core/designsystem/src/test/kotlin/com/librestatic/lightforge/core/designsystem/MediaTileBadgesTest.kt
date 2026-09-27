package com.librestatic.lightforge.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTileBadgesTest {

    @Test
    fun fileTypeLabel_marksNotableImageFormats() {
        assertEquals("GIF", mediaFileTypeLabel("party.GIF", isVideo = false))
        assertEquals("PNG", mediaFileTypeLabel("shot.png", isVideo = false))
        assertEquals("HEIC", mediaFileTypeLabel("IMG_1.heif", isVideo = false))
        assertEquals("RAW", mediaFileTypeLabel("DSC_0001.NEF", isVideo = false))
        assertEquals("RAW", mediaFileTypeLabel("PXL.dng", isVideo = false))
    }

    @Test
    fun fileTypeLabel_skipsEverydayFormatsAndUnknownNames() {
        assertNull(mediaFileTypeLabel("IMG_1.jpg", isVideo = false))
        assertNull(mediaFileTypeLabel("VID_1.mp4", isVideo = true))
        assertNull(mediaFileTypeLabel("no-extension", isVideo = false))
        assertNull(mediaFileTypeLabel(null, isVideo = false))
        assertEquals("MOV", mediaFileTypeLabel("clip.mov", isVideo = true))
    }

    @Test
    fun animatableNames_areGifAndWebpOnly() {
        assertTrue(isAnimatableMediaName("a.gif"))
        assertTrue(isAnimatableMediaName("a.WEBP"))
        assertFalse(isAnimatableMediaName("a.png"))
        assertFalse(isAnimatableMediaName(null))
    }
}
