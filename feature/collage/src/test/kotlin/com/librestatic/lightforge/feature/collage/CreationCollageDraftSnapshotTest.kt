package com.librestatic.lightforge.feature.collage

import org.junit.Assert.*
import org.junit.Test

class CreationCollageDraftSnapshotTest {
    private val id = "91fd41b5-f420-4afb-8118-cd41665997b9"
    private val identities = listOf("content://media/external/images/media/1@7/5", "content://media/external/images/media/2@9/6")
    private fun draft() = CreationCollageDraftSnapshot(id, identities,
        CreationCollageLayout.initial(2).move(0, 1).crop(0, CreationCollageCrop(2f, -.5f, .75f)), 1)

    @Test fun freshSnapshotRestoresOrderSourceBoundCropsAndCurrentSlot() {
        val original = draft()
        assertEquals(original, CreationCollageDraftSnapshot.restore(ArrayList(original.encode()), id, identities))
        val restored = requireNotNull(CreationCollageDraftSnapshot.restore(original.encode(), id, identities))
        assertEquals(listOf(1, 0), restored.layout.order)
        assertEquals(CreationCollageCrop(2f, -.5f, .75f), restored.layout.crops[restored.layout.order[1]])
        assertEquals(1, restored.selectedSlot)
        assertNull(restored.resultUri)
        assertFalse(restored.publicationUncertain)
    }

    @Test fun templateAndCropsForEverySupportedCountRoundTrip() {
        for (template in CreationCollageTemplate.entries) {
            val sources = List(template.sourceCount) { "content://media/external/images/media/${it+1}@9/2" }
            val layout = CreationCollageLayout(template, sources.indices.reversed().toList(),
                List(sources.size) { CreationCollageCrop(1.5f, .25f, -.25f) })
            val snapshot = CreationCollageDraftSnapshot(id, sources, layout, sources.lastIndex)
            assertEquals(snapshot, CreationCollageDraftSnapshot.restore(snapshot.encode(), id, sources))
        }
    }

    @Test fun anotherDraftSourceOrderOrEitherGenerationNeverAdoptsState() {
        val raw = draft().encode()
        assertNull(CreationCollageDraftSnapshot.restore(raw, "other-draft", identities))
        for (sources in listOf(identities.reversed(), listOf(identities[0].replace("@7/5", "@8/5"), identities[1]),
            listOf(identities[0].replace("@7/5", "@7/8"), identities[1]), listOf(identities[0], identities[0])))
            assertNull(CreationCollageDraftSnapshot.restore(raw, id, sources))
    }

    @Test fun versionMalformedIndicesNonfiniteCropAndOversizedInputAreRejected() {
        val raw = draft().encode()
        // Header3, identities2, template/current/uncertain/URI/hash/notified/journal7, order2, crops6.
        for ((index, value) in listOf(0 to "3", 2 to "4", 5 to "Grid3", 6 to "2", 7 to "maybe",
            12 to "0", 14 to "NaN", 15 to "2.0", 3 to "x".repeat(1025))) {
            val changed = ArrayList(raw).apply { this[index] = value }
            assertNull("index=$index value=${value.take(20)}", CreationCollageDraftSnapshot.restore(changed, id, identities))
        }
        assertNull(CreationCollageDraftSnapshot.restore(raw + "trailing", id, identities))
        assertNull(CreationCollageDraftSnapshot.restore(listOf(1, 2), id, identities))
    }

    @Test fun confirmedResultRoundTripsOnlyWithPublishedMediaUriAndExactDigest() {
        val result = draft().copy(resultUri = "content://media/external/images/media/17", resultSha256 = "a".repeat(64), resultNotified = true)
        assertEquals(result, CreationCollageDraftSnapshot.restore(result.encode(), id, identities))
        for (wrong in listOf(result.copy(resultUri = "file:///tmp/foreign.png"),
            result.copy(resultUri = "content://other.provider/images/17"),
            result.copy(resultUri = "content://media/external/images/media/17?query=foreign"),
            result.copy(resultSha256 = "wrong"), result.copy(resultSha256 = null),
            result.copy(publicationUncertain = true), draft().copy(resultNotified = true)))
            assertNull(CreationCollageDraftSnapshot.restore(wrong.encode(), id, identities))
    }

    @Test fun interruptedPublicationRetainsDraftButNeverInventsResultOrReplay() {
        val pending = draft().copy(publicationUncertain = true)
        val restored = requireNotNull(CreationCollageDraftSnapshot.restore(pending.encode(), id, identities))
        assertEquals(pending.layout, restored.layout)
        assertTrue(restored.publicationUncertain)
        assertNull(restored.resultUri)
        assertNull(restored.resultSha256)
    }

    @Test fun restoredCollectionsDoNotAliasTheSavedMutableList() {
        val raw = draft().encode()
        val restored = requireNotNull(CreationCollageDraftSnapshot.restore(raw, id, identities))
        raw[3] = "replaced"
        assertEquals(identities, restored.identities)
        assertEquals(draft().layout, restored.layout)
    }
    @Test fun legacyUnknownPublicationNeverBecomesJournalBackedImplicitly() {
        val pending = draft().copy(publicationUncertain = true)
        val legacy = pending.encode().apply { this[0] = "1"; removeAt(11) }
        val restored = requireNotNull(CreationCollageDraftSnapshot.restore(legacy, id, identities))
        assertTrue(restored.publicationUncertain)
        assertFalse(restored.publicationUsesJournal)
        val journal = pending.copy(publicationUsesJournal = true)
        assertEquals(journal, CreationCollageDraftSnapshot.restore(journal.encode(), id, identities))
    }

    @Test fun unresolvedOrPublishedReceiptCannotEnableANewRender() {
        CreationCollagePublicationUi.entries.forEach { status ->
            assertEquals(status == CreationCollagePublicationUi.None, collageAllowsNewRender(status, true))
            assertFalse(collageAllowsNewRender(status, false))
            assertEquals(status !in setOf(CreationCollagePublicationUi.None, CreationCollagePublicationUi.Published),
                collageKeepsRecovery(status))
        }
    }

}
