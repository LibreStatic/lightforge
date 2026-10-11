package com.librestatic.lightforge

import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.mediastore.ShareCandidate
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.raw.DngCameraFraming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualCropShareTest {
    private fun target(id: Long, kind: MediaKind = MediaKind.Image) =
        MediaActionTarget(MediaKey("external_primary", id), kind)

    private val zoomed = DngCameraFraming(4000, 3000, 1563, 1173, 873, 654)
    private val margin = DngCameraFraming(4032, 3024, 8, 8, 4016, 3008)

    @Test
    fun onlyRawPhotosWithAMeaningfulCropNeedTheWarning() = runBlocking {
        val probes = listOf(
            RawShareProbe(target(1), "OCC_1.dng", "image/x-adobe-dng"),
            RawShareProbe(target(2), "OCC_2.dng", "image/x-adobe-dng"),
            RawShareProbe(target(3), "IMG_3.jpg", "image/jpeg"),
            RawShareProbe(target(4), "clip.mp4", "video/mp4"),
            RawShareProbe(target(5), "IMG_5.dng", null),
        )
        val inspected = mutableListOf<Long>()
        val crops = virtualCropsAmong(probes) {
            inspected += it.key.mediaStoreId
            when (it.key.mediaStoreId) { 1L -> zoomed; 2L -> margin; else -> null }
        }
        assertEquals(listOf(1L), crops.map { it.target.key.mediaStoreId })
        // Ordinary photos and videos are never opened.
        assertEquals(listOf(1L, 2L, 5L), inspected)
    }

    @Test
    fun noRawMeansNoFramingReads() = runBlocking {
        var reads = 0
        val crops = virtualCropsAmong(listOf(RawShareProbe(target(1), "a.jpg", "image/jpeg"))) { reads++; zoomed }
        assertTrue(crops.isEmpty())
        assertEquals(0, reads)
    }

    @Test
    fun croppedShareReplacesOnlyTheAffectedItemsAndKeepsOrder() {
        val candidates = listOf(
            ShareCandidate(target(1), "image/*"),
            ShareCandidate(target(2), "image/*"),
            ShareCandidate(target(3), "image/*", isPrivate = true),
            ShareCandidate(target(1), "image/*"),
        )
        val shared = assembleCroppedShare(candidates, mapOf(MediaKey("external_primary", 1) to "jpeg-1")) {
            "original-${it.target.key.mediaStoreId}"
        }
        assertEquals(listOf("jpeg-1", "original-2"), shared)
    }

    @Test
    fun itemInfoCarriesTheFramedAndFullSizes() {
        val info = VirtualCropShareItem(target(1), zoomed).info()
        assertEquals("873 × 654", info.framedSize)
        assertEquals("4000 × 3000", info.fullSize)
    }
}
