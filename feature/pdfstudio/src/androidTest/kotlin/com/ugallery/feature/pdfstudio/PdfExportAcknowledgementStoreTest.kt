package com.ugallery.feature.pdfstudio

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Real `SharedPreferences`-backed store, so this runs as an instrumentation test (no Robolectric). */
class PdfExportAcknowledgementStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unknownIdIsNotAcknowledged() {
        val store = PdfExportAcknowledgementStore(context)
        val id = newId()
        assertFalse(store.isAcknowledged(id))
        store.prune(emptySet()) // must not throw on a never-seen id
        assertFalse(store.isAcknowledged(id))
    }

    @Test
    fun acknowledgeIsIdempotentAndSurvivesANewStoreInstance() {
        val id = newId()
        try {
            PdfExportAcknowledgementStore(context).acknowledge(id)
            PdfExportAcknowledgementStore(context).acknowledge(id) // a second call is a no-op
            assertTrue(PdfExportAcknowledgementStore(context).isAcknowledged(id))
        } finally {
            PdfExportAcknowledgementStore(context).prune(emptySet())
        }
    }

    @Test
    fun pruneDropsOnlyIdsNoLongerInTheGivenSet() {
        val kept = newId()
        val dropped = newId()
        try {
            val store = PdfExportAcknowledgementStore(context)
            store.acknowledge(kept)
            store.acknowledge(dropped)
            store.prune(setOf(kept))
            assertTrue(store.isAcknowledged(kept))
            assertFalse(store.isAcknowledged(dropped))
        } finally {
            PdfExportAcknowledgementStore(context).prune(emptySet())
        }
    }
}
