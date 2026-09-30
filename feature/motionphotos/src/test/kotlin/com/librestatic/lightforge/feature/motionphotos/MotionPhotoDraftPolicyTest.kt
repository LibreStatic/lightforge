package com.librestatic.lightforge.feature.motionphotos

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class MotionPhotoDraftPolicyTest {
    private fun rejects(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Invalid restoration must be rejected before exposing controls", rejected)
    }

    @Test fun identityIncludesUriAndBothGenerationsWithoutInferringThem() {
        val original = motionPhotoInputIdentity("content://media/external/images/media/7", 10, 8)
        assertEquals("content://media/external/images/media/7@10/8", original)
        requireMotionPhotoIdentity(original, original)
        for (changed in listOf(motionPhotoInputIdentity("content://media/external/images/media/8", 10, 8),
            motionPhotoInputIdentity("content://media/external/images/media/7", 11, 8),
            motionPhotoInputIdentity("content://media/external/images/media/7", 10, 9)))
            rejects { requireMotionPhotoIdentity(original, changed) }
    }

    @Test fun validRestoredFrameWinsOverDifferentConfirmedAndDefaultFrames() {
        assertEquals(1_600_000L, restoredMotionPhotoFrame(1_600_000, 500_000, 1_000_000, 2_000_000))
        assertEquals(0L, restoredMotionPhotoFrame(0, null, 0, 1))
        assertEquals(MaximumMotionDurationUs - 1, restoredMotionPhotoFrame(MaximumMotionDurationUs - 1,
            null, 0, MaximumMotionDurationUs))
    }

    @Test fun onlyMinusOneInitialUsesAnInRangeConfirmedFrameOrDefault() {
        assertEquals(500L, restoredMotionPhotoFrame(-1, 500, 100, 1_000))
        for (confirmed in listOf(null, -1L, 1_000L, Long.MAX_VALUE))
            assertEquals(100L, restoredMotionPhotoFrame(-1, confirmed, 100, 1_000))
    }

    @Test fun invalidSavedFramesNeverBecomeNewDraftOrClampedFrame() {
        for (saved in listOf(-2L, 1_000L, Long.MIN_VALUE, Long.MAX_VALUE))
            rejects { restoredMotionPhotoFrame(saved, 500, 100, 1_000) }
    }

    @Test fun onlyTheSameDurationRangeAsSessionOpenIsAccepted() {
        for (duration in listOf(0L, -1L, MaximumMotionDurationUs + 1, Long.MAX_VALUE))
            rejects { restoredMotionPhotoFrame(-1, null, 0, duration) }
        for (default in listOf(-1L, 1_000L)) rejects { restoredMotionPhotoFrame(-1, null, default, 1_000) }
    }

    @Test fun actualProviderGenerationsTrashAndPendingAllGateReads() {
        requireMotionPhotoGeneration(10, 8, 10, 8, false, false)
        for (case in 0..5) rejects {
            requireMotionPhotoGeneration(if (case == 4) -1 else 10, if (case == 5) -1 else 8,
                if (case == 0) 11 else 10, if (case == 1) 9 else 8, case == 2, case == 3)
        }
    }

    @Test fun nullableAddedPreservesExistingFixturesAndDoesNotInventABinding() {
        requireMotionPhotoGeneration(10, null, 10, 88, false, false)
        assertEquals("file:///owned-motion.jpg@null/null", motionPhotoInputIdentity("file:///owned-motion.jpg", null, null))
        assertNotEquals(motionPhotoInputIdentity("content://media/external/images/media/7", 10, null),
            motionPhotoInputIdentity("content://media/external/images/media/7", 10, 8))
    }

    @Test fun recoveredPublicationNeverOpensOriginalOrEnablesAnotherExport() {
        for (status in MotionPhotoPublicationUi.entries) {
            assertEquals(status == MotionPhotoPublicationUi.None, motionAllowsNewPublication(status, true))
            assertFalse(motionAllowsNewPublication(status, false))
            assertEquals(status == MotionPhotoPublicationUi.None, motionMayOpenSource(status, false, true))
            assertFalse(motionMayOpenSource(status, true, false))
        }
        assertTrue(motionMayOpenSource(MotionPhotoPublicationUi.Checking, true, true))
        assertFalse(motionMayOpenSource(MotionPhotoPublicationUi.Published, true, true))
    }

    @Test fun confirmedMissingAndRetirementCannotBecomeRetryable() {
        assertEquals(MotionPhotoPublicationUi.None, motionMissingPublication(false, false, false, false))
        assertEquals(MotionPhotoPublicationUi.RetryableMissing, motionMissingPublication(false, false, false, true))
        for (attempted in listOf(false, true)) {
            assertEquals(MotionPhotoPublicationUi.Conflict, motionMissingPublication(true, false, false, attempted))
            assertEquals(MotionPhotoPublicationUi.Conflict, motionMissingPublication(false, false, true, attempted))
            assertEquals(MotionPhotoPublicationUi.Conflict, motionMissingPublication(false, true, false, attempted))
            assertEquals(MotionPhotoPublicationUi.Retired, motionMissingPublication(true, true, false, attempted))
            assertFalse(motionAllowsNewPublication(motionMissingPublication(true, true, false, attempted), true))
        }
    }

    @Test fun publicationMarkerRoundTripIsSmallAndExactlyIdentityBound() {
        val id = "b9d73ef8-6d2f-4905-8080-b5eab10c2b12"
        val identity = motionPhotoInputIdentity("content://media/external/images/media/7", 10, 8)
        val marker = MotionPhotoPublicationMarker(true, true, true)
        val encoded = marker.encode(id, identity)
        assertEquals(marker, MotionPhotoPublicationMarker.decode(ArrayList(encoded), id, identity))
        assertEquals(6, encoded.size)
        assertEquals(64, encoded[2].length)
        assertEquals(MotionPhotoPublicationMarker(), MotionPhotoPublicationMarker.decode(null, id, identity))
        val corruptions = listOf(encoded.dropLast(1), encoded + "trailing", encoded.toMutableList().apply { this[0] = "2" },
            encoded.toMutableList().apply { this[3] = "TRUE" }, encoded.toMutableList().apply { this[4] = "false" })
        corruptions.forEach { value ->
            var rejected = false
            try { MotionPhotoPublicationMarker.decode(value, id, identity) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        for ((otherId, otherIdentity) in listOf(java.util.UUID.randomUUID().toString() to identity,
            id to motionPhotoInputIdentity("content://media/external/images/media/7", 11, 8),
            id to motionPhotoInputIdentity("content://media/external/images/media/7", 10, 9))) {
            var rejected = false
            try { MotionPhotoPublicationMarker.decode(encoded, otherId, otherIdentity) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test fun recoveryCancellationReleasesBusyWithoutOpeningOriginal() {
        val cancelled = motionPublicationCancelled(MotionPhotoPublicationState(busy = true, exporting = true))
        assertFalse(cancelled.busy); assertFalse(cancelled.exporting)
        assertEquals(MotionPhotoPublicationUi.Unreadable, cancelled.status)
        assertTrue(motionKeepsPublication(cancelled.status))
        assertFalse(motionMayOpenSource(cancelled.status, cancelled.exporting, true))
    }

    @Test fun cancelledWorkerOwnsFinalReconciliationUntilCompleted() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { draining.complete(Unit); release.await() } }
        }
        try {
            entered.await(); job.cancel(); draining.await()
            assertFalse(job.isActive); assertTrue(motionHasPublicationWork(job))
        } finally { release.complete(Unit); job.join() }
        assertFalse(motionHasPublicationWork(job)); assertFalse(motionHasPublicationWork(null))
    }
}
