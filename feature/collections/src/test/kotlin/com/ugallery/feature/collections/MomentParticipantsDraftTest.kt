package com.ugallery.feature.collections

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class MomentParticipantsDraftTest {
    private val snapshot = MomentParticipantsSnapshot("story", 4, MomentParticipantsMode.Automatic, emptySet(),
        listOf(PersonCardUi("a", "Alice", 3, null), PersonCardUi("b", null, 2, null)), setOf("a"))

    @Test fun automaticAndExplicitManualEmptyHaveDifferentRequests() {
        val auto = MomentParticipantsDraft.from(snapshot)
        assertEquals(emptySet<String>(), auto.request(snapshot)!!.selectedIds)
        assertEquals(MomentParticipantsMode.Automatic, auto.request(snapshot)!!.mode)
        val manual = auto.chooseMode(MomentParticipantsMode.Manual, snapshot).toggle("a", snapshot)
        assertEquals(emptySet<String>(), manual.request(snapshot)!!.selectedIds)
        assertEquals(MomentParticipantsMode.Manual, manual.request(snapshot)!!.mode)
    }
    @Test fun onlyExistingIdsCanBeSelectedAndCanonicalNamesAreNotEditable() {
        val draft = MomentParticipantsDraft.from(snapshot).chooseMode(MomentParticipantsMode.Manual, snapshot)
        assertEquals(draft, draft.toggle("invented", snapshot))
        val selected = draft.toggle("b", snapshot)
        assertEquals(setOf("a", "b"), selected.request(snapshot)!!.selectedIds)
        assertEquals(selected, selected.reconcile(snapshot.copy(people = snapshot.people.map { it.copy(displayName = "Renamed") })))
    }
    @Test fun deletionBlocksRatherThanSilentlySavingTrimmedSelection() {
        val draft = MomentParticipantsDraft.from(snapshot).chooseMode(MomentParticipantsMode.Manual, snapshot)
        val removed = snapshot.copy(people = snapshot.people.drop(1), automaticIds = emptySet())
        val changed = draft.reconcile(removed)
        assertEquals(setOf("a"), changed.selectedIds)
        assertTrue(changed.reviewRequired)
        assertNull(changed.request(removed))
        assertTrue(changed.reconcile(snapshot).reviewRequired)
    }
    @Test fun explicitReviewDropsUnavailableManualIdsLocallyWithoutChangingSavedSnapshot() {
        val hidden = snapshot.copy(mode = MomentParticipantsMode.Manual, selectedIds = setOf("a", "b"),
            people = snapshot.people.filter { it.clusterId == "a" })
        val initial = MomentParticipantsDraft.from(hidden)
        assertTrue(initial.reviewRequired)
        assertNull(initial.request(hidden))
        assertEquals(setOf("a", "b"), initial.selectedIds)
        val reviewed = MomentParticipantsDraft.reviewed(hidden)
        assertFalse(reviewed.reviewRequired)
        assertEquals(setOf("a"), reviewed.request(hidden)!!.selectedIds)
        assertEquals(setOf("a", "b"), hidden.selectedIds)
    }
    @Test fun concurrentRevisionRequiresExplicitReload() {
        val draft = MomentParticipantsDraft.from(snapshot)
        val latest = snapshot.copy(revision = 5)
        assertNull(draft.request(latest))
        val reviewed = MomentParticipantsDraft.from(latest)
        assertEquals(5L, reviewed.request(latest)!!.expectedRevision)
    }
    @Test fun changingAutomaticMembershipRequiresReview() {
        val changed = snapshot.copy(automaticIds = setOf("b"))
        assertTrue(MomentParticipantsDraft.from(snapshot).reconcile(changed).reviewRequired)
    }
    @Test fun anotherMomentNeverReceivesOldRequest() {
        val draft = MomentParticipantsDraft.from(snapshot)
        assertNull(draft.request(snapshot.copy(momentId = "another")))
        assertEquals("another", draft.reconcile(snapshot.copy(momentId = "another")).momentId)
    }
    @Test fun cancellationAndReopeningDiscardUnappliedDraft() {
        val edited = MomentParticipantsDraft.from(snapshot).chooseMode(MomentParticipantsMode.Manual, snapshot).toggle("b", snapshot)
        assertNotEquals(edited, MomentParticipantsDraft.from(snapshot))
        assertEquals(MomentParticipantsMode.Automatic, MomentParticipantsDraft.from(snapshot).mode)
        assertEquals(setOf("a"), MomentParticipantsDraft.from(snapshot).selectedIds)
    }
    @Test fun thumbnailRequestRequiresCurrentGenerationInsteadOfAssumingCacheZero() {
        val person = snapshot.people.first().copy(coverKey = com.ugallery.core.model.MediaKey("external_primary", 7))
        assertNull(participantThumbnailRequest(person, null))
        assertNull(participantThumbnailRequest(person, -1))
        assertNull(participantThumbnailRequest(person.copy(coverKey = null), 42))
        assertEquals(42L, participantThumbnailRequest(person, 42)!!.generationModified)
        assertEquals(person.coverKey, participantThumbnailRequest(person, 42)!!.mediaKey)
        assertNotEquals(participantThumbnailRequest(person, 42), participantThumbnailRequest(person, 43))
    }
    @Test fun saverPreservesSmallDraftAndConflictWithoutImagesOrNames() {
        val draft = MomentParticipantsDraft.from(snapshot).copy(reviewRequired = true)
        val saved = with(MomentParticipantsDraft.Saver) { with(SaverScope { true }) { save(draft) } }!!
        assertEquals(draft, MomentParticipantsDraft.Saver.restore(saved))
        assertFalse(saved.toString().contains("Alice"))
    }
}
