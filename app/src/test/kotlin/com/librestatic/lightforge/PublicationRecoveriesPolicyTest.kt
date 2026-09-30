package com.librestatic.lightforge

import com.librestatic.lightforge.feature.collage.CreationCollagePublicationEntry
import com.librestatic.lightforge.feature.collage.CreationCollagePublicationResolution
import com.librestatic.lightforge.feature.motionphotos.MotionPhotoPublicationEntry
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class PublicationRecoveriesPolicyTest {
    private val allowed = PublicationRecoveryPermissions(false, false, false, true, true, true, true)

    @Test fun actionAvailabilityRequiresCurrentReadableNonBusyProofAndExactCapability() {
        for (action in PublicationRecoveryAction.entries) {
            assertTrue(publicationRecoveryAllows(action, allowed))
            for (blocked in listOf(allowed.copy(unreadable = true), allowed.copy(busy = true)))
                assertFalse(publicationRecoveryAllows(action, blocked))
        }
        val absentPendingBytes = allowed.copy(backingFileMissing = true)
        assertFalse(publicationRecoveryAllows(PublicationRecoveryAction.Complete, absentPendingBytes))
        assertTrue(publicationRecoveryAllows(PublicationRecoveryAction.RemovePending, absentPendingBytes))
        assertTrue(publicationRecoveryAllows(PublicationRecoveryAction.Forget, absentPendingBytes))
        assertFalse(publicationRecoveryAllows(PublicationRecoveryAction.Complete, allowed.copy(canComplete = false)))
        assertFalse(publicationRecoveryAllows(PublicationRecoveryAction.RemovePending, allowed.copy(canRemovePending = false)))
        assertFalse(publicationRecoveryAllows(PublicationRecoveryAction.Forget, allowed.copy(canForget = false)))
    }

    @Test fun pendingOrUnreadablePublicationNeverGrantsHandoff() {
        assertTrue(publicationRecoveryAllowsHandoff(allowed))
        for (blocked in listOf(allowed.copy(unreadable = true), allowed.copy(busy = true),
            allowed.copy(backingFileMissing = true), allowed.copy(published = false))) assertFalse(publicationRecoveryAllowsHandoff(blocked))
    }

    @Test fun confirmationIsBoundToSelectionRevisionAndWholeReviewedProof() {
        val key = "motion:7b02c826-fd57-4eaa-ab49-88e21fe3edda"
        assertTrue(publicationRecoveryOwns(key, 7, key, 7, true))
        assertFalse(publicationRecoveryOwns(key, 7, key, 9, true)) // Select another row and return: no ABA adoption.
        assertFalse(publicationRecoveryOwns(key, 7, "gif:7b02c826-fd57-4eaa-ab49-88e21fe3edda", 7, true))
        assertFalse(publicationRecoveryOwns(key, 7, null, 7, true))
        assertFalse(publicationRecoveryOwns(key, 7, key, 7, false))
    }

    @Test fun pendingHashSizeAndMarkerChangesInvalidateTheActualTypedProof() {
        val entry = CreationCollagePublicationEntry("7b02c826-fd57-4eaa-ab49-88e21fe3edda", "a".repeat(64))
        val resolution = CreationCollagePublicationResolution(entry, pendingBytesSha256 = "b".repeat(64), pendingBytesSize = 123L, canRemovePending = true)
        val reviewed = PublicationRecoveryProof.Collage(resolution)
        assertEquals(reviewed, PublicationRecoveryProof.Collage(resolution.copy()))
        for (changed in listOf(resolution.copy(entry = entry.copy(journalSha256 = "c".repeat(64))),
            resolution.copy(pendingBytesSha256 = "d".repeat(64)), resolution.copy(pendingBytesSize = 124L),
            resolution.copy(busy = true), resolution.copy(backingFileMissing = true))) {
            val fresh = PublicationRecoveryProof.Collage(changed)
            assertNotEquals(reviewed, fresh)
            assertFalse(publicationRecoveryOwns(reviewed.entry.key, 1, fresh.entry.key, 1, reviewed == fresh))
        }
    }

    @Test fun unreadableMotionNeverInventsFrameOrClipFromItsId() {
        val entry = PublicationRecoveryEntry.Motion(MotionPhotoPublicationEntry("7b02c826-fd57-4eaa-ab49-88e21fe3edda", unreadable = true))
        assertEquals(PublicationRecoveryKind.MotionUnknown, entry.kind)
        assertEquals("motion:7b02c826-fd57-4eaa-ab49-88e21fe3edda", entry.key)
        assertNull(entry.phase); assertNull(entry.name); assertNull(entry.sourceIdentity)
    }

    @Test fun rootTrackingInvalidationOnlyFollowsDurableForgetOrPendingRemoval() {
        for (action in PublicationRecoveryAction.entries) assertFalse(publicationRecoveryRemovesTracking(action, false))
        assertFalse(publicationRecoveryRemovesTracking(PublicationRecoveryAction.Complete, true))
        assertTrue(publicationRecoveryRemovesTracking(PublicationRecoveryAction.RemovePending, true))
        assertTrue(publicationRecoveryRemovesTracking(PublicationRecoveryAction.Forget, true))
    }

    @Test fun rootMotionProviderCleanupMatchesOnlyCanonicalImageIdentityAndBothGenerations() {
        assertEquals("motion:external_primary:7:Image:10:8:false",
            publicationRecoveryMotionProviderKey("content://media/external_primary/images/media/7@10/8"))
        for (invalid in listOf(null, "file:///owned.jpg@10/8", "content://media/external_primary/images/media/7@10/null",
            "content://media/external_primary/video/media/7@10/8", "content://media/external_primary/images/media/07@10/8",
            "content://media/external_primary/images/media/7@010/8", "content://media/external_primary/images/media/7@10/08",
            "content://media/external_primary/images/media/7@10/8junk", "content://media/external.primary/images/media/7@10/8"))
            assertNull(invalid, publicationRecoveryMotionProviderKey(invalid))
    }
    @Test fun cooperativeEngineDeadlineSurvivesTheNavigationCancellationShield(): Unit = runBlocking {
        var acknowledged = false
        try {
            publicationRecoveryApplyWithinDeadline(50) { delay(10_000); acknowledged = true; true }
            fail("Expected the inner engine deadline")
        } catch (_: TimeoutCancellationException) { }
        assertFalse(acknowledged)
    }

    @Test fun leavingTheScreenDoesNotDropDurableTrackingAcknowledgement(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var acknowledged = false
        val operation = launch {
            publicationRecoveryApplyWithinDeadline(5_000) {
                entered.complete(Unit); release.await(); acknowledged = true; true
            }
        }
        entered.await(); operation.cancel(); release.complete(Unit); operation.join()
        assertTrue(acknowledged)
    }

}
