package com.ugallery.core.data

import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStoreChangeMonitorTest {
    @Test
    fun burstIsDeduplicatedAfterQuietWindow() = runTest {
        val batches = mutableListOf<Set<Int>>()
        val coalescer = ChangeBurstCoalescer<Int>(this, 300) { batches.add(it) }

        coalescer.submit(1)
        advanceTimeBy(200)
        coalescer.submit(2)
        coalescer.submit(1)
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, batches.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(setOf(1, 2)), batches)
    }

    @Test
    fun parsesOnlyRowSpecificMediaStoreUris() {
        assertEquals(
            MediaKey("external_primary", 42),
            mediaKeyFromObserverUriString("content://media/external_primary/file/42"),
        )
        assertEquals(
            MediaKey("1a2b-3c4d", 7),
            mediaKeyFromObserverUriString("content://media/1a2b-3c4d/images/media/7"),
        )
        assertNull(mediaKeyFromObserverUriString("content://media/external_primary/file"))
        assertNull(mediaKeyFromObserverUriString("content://media/external/file/42"))
        assertNull(mediaKeyFromObserverUriString("content://other/external_primary/file/42"))
    }

    @Test
    fun collectionNotificationsRequireFullReconciliation() {
        val row = "content://media/external_primary/images/media/42"
        val collection = "content://media/external/images/media"

        assertEquals(false, mediaStoreChangeBatchStrings(setOf(row)).requiresFullVolumeReconciliation)
        assertEquals(true, mediaStoreChangeBatchStrings(setOf(collection)).requiresFullVolumeReconciliation)
        assertEquals(true, mediaStoreChangeBatchStrings(setOf(row, collection)).requiresFullVolumeReconciliation)
    }
}
