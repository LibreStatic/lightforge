package com.librestatic.lightforge

import com.librestatic.lightforge.core.data.ManualMomentDraft
import com.librestatic.lightforge.core.data.ManualMomentSource
import com.librestatic.lightforge.core.mediastore.MediaActionTarget
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.selection.MediaQuery
import com.librestatic.lightforge.core.selection.SelectionSpec
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test

class ManualMomentRestoreSnapshotTest {
    private val id = "d29c9467-cd8b-4ee1-95b4-fb5aa5bf9eea"
    private fun source(index: Long = 1) = ManualMomentSourceSnapshot(
        MediaKey("external_primary", index), 7, 12, index == 2L,
    )
    private fun snapshot() = ManualMomentRestoreSnapshot(id, listOf(source(), source(2)))
    private fun origin(keys: List<MediaKey>) = CreationSelectionSnapshot(
        SelectionSpec.explicit(keys), keys.map { MediaActionTarget(it, MediaKind.Image) }, keys.size.toLong(),
    )
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid manual draft to be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun capturesDetachedSourcesAndOriginWithoutChangingSpecialFlags() {
        val sources = mutableListOf(source(), source(2))
        val keys = sources.map { it.key }
        val targets = keys.map { MediaActionTarget(it, MediaKind.Image) }.toMutableList()
        val raw = ManualMomentRestoreSnapshot(id, sources,
            CreationSelectionSnapshot(SelectionSpec.explicit(keys), targets, 2))
        val detached = ManualMomentRestoreSnapshot.validatedCopy(raw)
        sources.clear(); targets.clear()
        assertEquals(listOf(false, true), detached.sources.map { it.requiresSpecialMedia })
        assertEquals(keys, (detached.originSelection!!.selection as SelectionSpec.Explicit).keys.toList())
        assertEquals(2, detached.originSelection.targets.size)
        assertNotSame(raw.sources, detached.sources)
        assertNotSame(keys[0], detached.sources[0].key)
        try {
            (detached.sources as MutableList<ManualMomentSourceSnapshot>).clear()
            fail("Validated sources must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun serializableRoundtripAndToDraftKeepExactPreparedIdentity() {
        val value = snapshot().let { it.copy(originSelection = origin(it.sources.map { source -> source.key })) }
        val bytes = ByteArrayOutputStream().also { sink ->
            ObjectOutputStream(sink).use { it.writeObject(value) }
        }.toByteArray()
        val decoded = ObjectInputStream(bytes.inputStream()).use { it.readObject() } as ManualMomentRestoreSnapshot
        val checked = ManualMomentRestoreSnapshot.validatedCopy(decoded)
        assertEquals(value, checked)
        val draft = checked.toDraft()
        assertEquals(id, draft.id)
        assertEquals(listOf(ManualMomentSource(source().key, 7, 12, false),
            ManualMomentSource(source(2).key, 7, 12, true)), draft.sources)
        assertEquals(value, ManualMomentRestoreSnapshot.capture(draft, checked.originSelection))
        assertNotSame(checked.sources[0].key, draft.sources[0].key)
    }

    @Test fun documentsOriginRemainsNullAndOneOr120SourcesAreAccepted() {
        listOf(1, 120).forEach { count ->
            val draft = ManualMomentDraft(id, (1..count).map {
                ManualMomentSource(MediaKey("external_primary", it.toLong()), 0, 0, true)
            })
            val captured = ManualMomentRestoreSnapshot.capture(draft, null)
            assertNull(captured.originSelection)
            assertEquals(draft, captured.toDraft())
        }
    }

    @Test fun rejectsNoncanonicalUuidDuplicatesGenerationsAndSize() {
        listOf("bad", id.uppercase(), "1-1-1-1-1").forEach {
            invalid { ManualMomentRestoreSnapshot.validatedCopy(snapshot().copy(id = it)) }
        }
        listOf(emptyList(), listOf(source(), source()), (1..121).map { source(it.toLong()) },
            listOf(source().copy(generationAdded = -1)),
            listOf(source().copy(generationModified = -1))).forEach {
            invalid { ManualMomentRestoreSnapshot.validatedCopy(snapshot().copy(sources = it)) }
        }
    }

    @Test fun rejectsQueryOriginWrongOrderWrongMembersCountsOrMediaKind() {
        val keys = snapshot().sources.map { it.key }
        val good = origin(keys)
        listOf(
            CreationSelectionSnapshot(SelectionSpec.queryAll(MediaQuery()), emptyList(), 2),
            origin(keys.reversed()), origin(listOf(keys[0])),
            good.copy(count = 1),
            good.copy(targets = good.targets.map { it.copy(kind = MediaKind.Video) }),
        ).forEach { invalid { ManualMomentRestoreSnapshot.validatedCopy(snapshot().copy(originSelection = it)) } }
    }

    @Test fun combinedEnvelopeBudgetRejectsWholeSnapshotInsteadOfDroppingManualSources() {
        val manual = ManualMomentRestoreSnapshot(id,
            listOf(source().copy(key = MediaKey("v".repeat(20_000), 1))))
        val video = CreationVideoSnapshot(id, "t".repeat(50_000),
            listOf(CreationVideoSourceSnapshot("content://media/external_primary/images/media/1", 12, 7)))
        assertNotNull(CreationRestoreSnapshot.capture(null, manualMoment = manual))
        assertNotNull(CreationRestoreSnapshot.capture(null, video = video))
        assertNull(CreationRestoreSnapshot.capture(null, video = video, manualMoment = manual))
        assertEquals(20_000, manual.sources.single().key.volumeName.length)
    }
    @Test fun matchingOriginOwnsSelectionButDocumentsDoNot() {
        val origin = origin(snapshot().sources.map { it.key })
        assertTrue(manualMomentOwnsSelection(origin, 8, origin.selection, origin.targets, 2, 8))
        assertFalse(manualMomentOwnsSelection(null, 8, origin.selection, origin.targets, 2, 8))
        assertFalse(manualMomentOwnsSelection(origin, null, origin.selection, origin.targets, 2, 8))
    }

    @Test fun changedRevisionRejectsAbaEvenIfSelectionMatchesAgain() {
        val origin = origin(snapshot().sources.map { it.key })
        assertFalse(manualMomentOwnsSelection(origin, 8, origin.selection, origin.targets, 2, 10))
    }

    @Test fun changedKeysTargetsOrCountDoNotConsumeSelection() {
        val origin = origin(snapshot().sources.map { it.key })
        assertFalse(manualMomentOwnsSelection(origin, 8, SelectionSpec.explicit(listOf(source(3).key)),
            origin.targets, 2, 8))
        assertFalse(manualMomentOwnsSelection(origin, 8, origin.selection, origin.targets.reversed(), 2, 8))
        assertFalse(manualMomentOwnsSelection(origin, 8, origin.selection,
            origin.targets.map { it.copy(kind = MediaKind.Video) }, 2, 8))
        assertFalse(manualMomentOwnsSelection(origin, 8, origin.selection, origin.targets, 1, 8))
    }

}
