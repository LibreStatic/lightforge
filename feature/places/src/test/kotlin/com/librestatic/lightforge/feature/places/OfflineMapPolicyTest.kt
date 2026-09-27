package com.librestatic.lightforge.feature.places

import com.librestatic.lightforge.core.model.MediaKey
import org.junit.Assert.*
import org.junit.Test

class OfflineMapPolicyTest {
    private val good =
        OfflineMapDeviceFacts(
            200L * 1024 * 1024 * 1024,
            8L * 1024 * 1024 * 1024,
            false,
            true,
            true,
            true,
        )

    @Test
    fun worldRequiresRealStorageNotMockSize() {
        assertNull(OfflineMapEligibility.waiting(good, OfflineMapCatalog.World.bytes, true))
        assertEquals(
            OfflineMapTaskStatus.WaitingStorage,
            OfflineMapEligibility.waiting(
                good.copy(freeBytes = 90L * 1024 * 1024 * 1024),
                OfflineMapCatalog.World.bytes,
                true,
            ),
        )
    }

    @Test
    fun worldRequiresHardwareWifiAndCharging() {
        assertEquals(
            OfflineMapTaskStatus.WaitingHardware,
            OfflineMapEligibility.waiting(good.copy(lowRam = true), 1, true),
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingHardware,
            OfflineMapEligibility.waiting(good.copy(rendererCompatible = false), 1, true),
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingHardware,
            OfflineMapEligibility.waiting(
                good.copy(totalRamBytes = 4L * 1024 * 1024 * 1024),
                1,
                true,
            ),
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingWifi,
            OfflineMapEligibility.waiting(good.copy(wifi = false), 1, true),
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingCharging,
            OfflineMapEligibility.waiting(good.copy(charging = false), 1, true),
        )
    }

    @Test
    fun localImportDoesNotRequireNetworkOrCharging() {
        assertNull(
            OfflineMapEligibility.waiting(
                good.copy(wifi = false, charging = false, lowRam = true),
                2_000_000,
                false,
            )
        )
    }

    @Test
    fun storageKeepsExplicitHeadroomAndRejectsOverflow() {
        assertNull(
            OfflineMapEligibility.waiting(
                good.copy(freeBytes = OfflineMapCatalog.HeadroomBytes + 1),
                1,
                false,
            )
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingStorage,
            OfflineMapEligibility.waiting(
                good.copy(freeBytes = OfflineMapCatalog.HeadroomBytes),
                1,
                false,
            ),
        )
        assertEquals(
            OfflineMapTaskStatus.WaitingStorage,
            OfflineMapEligibility.waiting(good, Long.MAX_VALUE, false),
        )
    }

    @Test
    fun catalogHasPublisherIdentityAndRealDetailedCoverage() {
        val w = OfflineMapCatalog.World
        assertEquals(137823988466L, w.bytes)
        assertEquals(64, w.blake3.length)
        assertEquals(15, w.maxZoom)
        assertEquals(0, w.minZoom)
        assertTrue(w.url.startsWith("https://build.protomaps.com/"))
        assertEquals("4.15.2", w.schemaVersion)
    }

    @Test
    fun datelineBoundsIncludeBothSidesButNotGreenwich() {
        val b = PlaceBounds(170.0, -20.0, -170.0, 20.0)
        assertTrue(b.contains(0.0, 179.0))
        assertTrue(b.contains(0.0, -179.0))
        assertFalse(b.contains(0.0, 0.0))
    }

    @Test
    fun invalidCoordinatesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaceBounds(Double.NaN, 0.0, 1.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) { PlaceBounds(0.0, 2.0, 1.0, 1.0) }
    }

    @Test
    fun groupingPreservesEveryPhotoAndStableNewestOrder() {
        val photos = (1L..25L).map { PlacePhoto(MediaKey("external", it), 43.73, 7.42, it, 1) }
        val groups = groupPlacePhotos(photos, 12.0)
        assertEquals(25, groups.sumOf { it.photos.size })
        assertEquals(1, groups.size)
        assertEquals(25L, groups.single().photos.first().key.mediaStoreId)
    }

    @Test
    fun queryOverflowIsExplicitAndBounded() {
        assertEquals(10000L, PlacesPhotoPage(emptyList(), 10000).totalMatching)
        assertThrows(IllegalArgumentException::class.java) {
            PlacesPhotoPage(
                List(2001) { PlacePhoto(MediaKey("x", it.toLong()), 0.0, 0.0, 0, 1) },
                2001,
            )
        }
    }
}
