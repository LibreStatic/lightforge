package com.ugallery.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightedLruCacheTest {
    @Test
    fun evictsLeastRecentlyUsedWithoutExceedingBound() {
        val cache = WeightedLruCache<String, String>(5) { it.length.toLong() }
        cache.put("a", "aa")
        cache.put("b", "bb")
        cache.get("a")
        cache.put("c", "cc")

        assertNull(cache.get("b"))
        assertEquals("aa", cache.get("a"))
        assertEquals("cc", cache.get("c"))
        assertEquals(4, cache.currentWeight)
    }

    @Test
    fun explicitTrimAndClearHonorByteCeiling() {
        val cache = WeightedLruCache<Int, String>(10) { it.length.toLong() }
        repeat(5) { cache.put(it, "xx") }
        cache.trimTo(4)
        assertEquals(2, cache.size())
        assertEquals(4, cache.currentWeight)
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0, cache.currentWeight)
    }
}
