package com.ugallery.feature.widget

import org.junit.Assert.*
import org.junit.Test

/** Calls production policy; real provider/query/reapply contracts have native fixtures. */
class WidgetPhotoSelectionTest {
    private fun uri(id: Int) = "content://media/external/images/media/$id"

    @Test fun rotationPreservesNormalOrderAndWrap() {
        assertEquals(1, WidgetSelectionPolicy.nextIndex(0, 5))
        assertEquals(0, WidgetSelectionPolicy.nextIndex(4, 5))
        assertEquals(0, WidgetSelectionPolicy.nextIndex(0, 1))
    }
    @Test fun rotationRejectsUnboundedListsAndNormalizesCorruptIndexWithoutOverflow() {
        assertNull(WidgetSelectionPolicy.nextIndex(0, 0))
        assertNull(WidgetSelectionPolicy.nextIndex(0, 101))
        for (index in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, 5))
            assertEquals(1, WidgetSelectionPolicy.nextIndex(index, 5))
    }
    @Test fun cachePreservesBoundedOrderWithoutArtificialSorting() {
        val values = listOf(uri(5),uri(2),uri(1))
        assertEquals(values, WidgetSelectionPolicy.cachedUris(values.joinToString("\n"),1000,1001))
        assertNull(WidgetSelectionPolicy.cachedUris("",1000,1001))
        val full = (1..100).map(::uri)
        assertEquals(full, WidgetSelectionPolicy.cachedUris(full.joinToString("\n"),1000,1001))
    }
    @Test fun invalidAndOversizedCacheRequiresFreshQuery() {
        for (raw in listOf<String?>(null, (1..101).joinToString("\n") { uri(it) },
            uri(1)+"\n"+uri(1),uri(1)+"\n", "x".repeat(10000))) {
            assertNull(WidgetSelectionPolicy.cachedUris(raw,1000,1001))
        }
    }
    @Test fun cacheExpiryAndClockReversalRequireFreshQuery() {
        val max = WidgetSelectionPolicy.CACHE_MAX_AGE_MS
        assertNotNull(WidgetSelectionPolicy.cachedUris(uri(1),1000,1000+max-1))
        assertNull(WidgetSelectionPolicy.cachedUris(uri(1),1000,1000+max))
        assertNull(WidgetSelectionPolicy.cachedUris(uri(1),1000,999))
        assertNull(WidgetSelectionPolicy.cachedUris(uri(1),-1,1000))
        assertNull(WidgetSelectionPolicy.cachedUris(uri(1),0,Long.MAX_VALUE))
    }
    @Test fun uriValidationNeverOpensArbitraryProvidersOrMalformedAddresses() {
        assertEquals(1L,WidgetSelectionPolicy.mediaId(uri(1)))
        assertEquals(Long.MAX_VALUE,WidgetSelectionPolicy.mediaId("content://media/external/images/media/${Long.MAX_VALUE}"))
        for (invalid in listOf("file:///photo.png","https://media/photo.png",uri(1)+"?x=1",uri(1)+"#fragment",
            uri(1)+"/extra",uri(1).replace("media/1","media/01"),uri(1).replace("images","video"),
            uri(1).replace("content://media/","content://other/"),uri(1).replace("/1","/-1"),
            "content://media/external/images/media/9223372036854775808")) {
            assertNull(invalid,WidgetSelectionPolicy.mediaId(invalid))
            assertNull(invalid,WidgetSelectionPolicy.cachedUris(invalid,1000,1001))
        }
    }
}
