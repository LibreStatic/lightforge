package com.librestatic.lightforge.feature.privatealbum

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivatePortableVirtualCropTest {
    private fun probe(id: Long, name: String, mime: String, kind: String = "image") =
        PrivatePortableProbe(id, name, mime, kind)

    private val isRaw: (String?, String?) -> Boolean =
        { name, mime -> name?.endsWith(".dng") == true || mime == "image/x-adobe-dng" }

    @Test
    fun countsOnlyRawImagesWhoseFramingHidesTheSensor() = runBlocking {
        val probes = listOf(
            probe(1, "a.dng", "image/x-adobe-dng"),
            probe(2, "b.dng", "image/x-adobe-dng"),
            probe(3, "c.jpg", "image/jpeg"),
            probe(4, "d.dng", "video/mp4", kind = "video"),
        )
        val inspected = mutableListOf<Long>()
        val count = privateRawsWithVirtualCrop(probes, isRaw) { inspected += it; it == 2L }
        assertEquals(1, count)
        assertEquals(listOf(1L, 2L), inspected)
    }

    @Test
    fun nothingIsOpenedWithoutRawPhotos() = runBlocking {
        var opened = false
        val count = privateRawsWithVirtualCrop(listOf(probe(1, "a.jpg", "image/jpeg")), isRaw) { opened = true; true }
        assertEquals(0, count)
        assertEquals(false, opened)
    }

    @Test
    fun anUnreadableItemCountsAsCropped() = runBlocking {
        val probes = listOf(probe(1, "a.dng", "image/x-adobe-dng"), probe(2, "b.dng", "image/x-adobe-dng"))
        val count = privateRawsWithVirtualCrop(probes, isRaw) { if (it == 1L) error("corrupt") else false }
        assertEquals(1, count)
    }

    @Test
    fun rawsPastTheInspectionLimitCountAsCropped() = runBlocking {
        val probes = List(5) { probe(it + 1L, "p$it.dng", "image/x-adobe-dng") }
        var inspections = 0
        val count = privateRawsWithVirtualCrop(probes, isRaw, limit = 2) { inspections++; false }
        assertEquals(2, inspections)
        assertEquals(3, count)
    }

    @Test
    fun cancellationAndLockedVaultPropagate() {
        val probes = listOf(probe(1, "a.dng", "image/x-adobe-dng"))
        assertThrows(CancellationException::class.java) {
            runBlocking { privateRawsWithVirtualCrop(probes, isRaw) { throw CancellationException("stop") } }
        }
        assertThrows(PrivateIndexLockedException::class.java) {
            runBlocking { privateRawsWithVirtualCrop(probes, isRaw) { throw PrivateIndexLockedException() } }
        }
    }
}
