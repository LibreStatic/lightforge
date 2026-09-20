package com.ugallery.feature.collage

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class CreationGifDraftPolicyTest {
    private val id = "owned-gif-draft"
    private val identities = listOf(creationGifSourceIdentity("content://media/external/images/media/1", 7, 5),
        creationGifSourceIdentity("content://media/external/images/media/2", 9, 6))
    private fun valid(order: List<Int> = listOf(1, 0), seconds: Int = 4, current: Int = 1,
                      saved: List<String> = identities, session: String = id) =
        validCreationGifDraft(id, session, saved, identities, order, seconds, current)

    @Test fun restoredReviewRetainsReversedOrderFourSecondsAndSecondFrame() {
        // Values represent independent Bundle-native saveable fields after deserialization.
        assertTrue(valid(ArrayList(listOf(1, 0)), 4, 1, ArrayList(identities)))
        assertEquals("content://media/external/images/media/1@7/5", identities[0])
    }

    @Test fun anotherDraftOrEitherGenerationDoesNotAdoptSavedReview() {
        assertFalse(valid(session = "new-draft"))
        assertFalse(valid(session = ""))
        assertFalse(valid(saved = identities.reversed()))
        assertFalse(valid(saved = listOf(creationGifSourceIdentity("content://media/external/images/media/1", 8, 5), identities[1])))
        assertFalse(valid(saved = listOf(creationGifSourceIdentity("content://media/external/images/media/1", 7, 8), identities[1])))
    }

    @Test fun allSupportedDurationsAndCurrentIndicesAreValidOnlyWithinBounds() {
        for (seconds in 1..5) for (current in 0..1) assertTrue(valid(seconds = seconds, current = current))
        for (seconds in listOf(0, 6, Int.MAX_VALUE)) assertFalse(valid(seconds = seconds))
        for (current in listOf(-1, 2, Int.MAX_VALUE)) assertFalse(valid(current = current))
    }

    @Test fun reorderOrRemoveNeverDuplicatesOrReadsAnUnknownSource() {
        for (order in listOf(emptyList(), listOf(0), listOf(0, 0), listOf(-1, 0), listOf(0, 2)))
            assertFalse(valid(order = order))
        val three = identities + creationGifSourceIdentity("content://media/external/images/media/3", 11, 8)
        assertTrue(validCreationGifDraft(id, id, three, three, listOf(2, 0), 3, 1))
        assertFalse(validCreationGifDraft(id, id, three, three, listOf(2, 0), 3, 2))
    }

    @Test fun sourceCountIsBoundedWithoutBreakingRepeatedFrameFixtures() {
        for (count in listOf(2, 60)) {
            val repeated = List(count) { "file:///owned-fixture.png@null/null" }
            assertTrue(validCreationGifDraft(id, id, repeated, repeated, repeated.indices.toList(), 2, 0))
        }
        for (count in listOf(0, 1, 61)) {
            val many = List(count) { "source-$it" }
            assertFalse(validCreationGifDraft(id, id, many, many, many.indices.toList(), 2, 0))
        }
    }

    @Test fun currentProviderGenerationsTrashAndPendingAreCheckedTogether() {
        requireCreationGifGeneration(7, 5, 7, 5, false, false)
        for (case in 0..5) {
            var rejected = false
            try {
                requireCreationGifGeneration(if (case == 4) -1 else 7, if (case == 5) -1 else 5,
                    if (case == 0) 8 else 7, if (case == 1) 8 else 5, case == 2, case == 3)
            } catch (_: IllegalStateException) { rejected = true }
            assertTrue("case=$case", rejected)
        }
    }

    @Test fun nullableAddedGenerationRetainsExistingFixtureContract() {
        requireCreationGifGeneration(7, null, 7, 55, false, false)
        assertEquals("file:///owned.png@null/null", creationGifSourceIdentity("file:///owned.png", null, null))
        assertNotEquals(creationGifSourceIdentity("content://media/external/images/media/1", 7, null), identities[0])
    }

    @Test fun onlyResolvedEmptyPublicationWithAvailableSourcesAllowsExport() {
        for (status in CreationGifPublicationUi.entries) {
            assertEquals(status == CreationGifPublicationUi.None, gifAllowsNewExport(status, true))
            assertFalse(gifAllowsNewExport(status, false))
        }
    }

    @Test fun unresolvedPublicationBackPreservesRecoveryRatherThanStartingAnotherCopy() {
        for (status in CreationGifPublicationUi.entries) {
            assertEquals(status != CreationGifPublicationUi.None && status != CreationGifPublicationUi.Published,
                gifKeepsRecovery(status))
        }
    }

    @Test fun confirmedMissingNeverBecomesRetryableAndDurableRetirementOnlyAllowsClosing() {
        assertEquals(CreationGifPublicationUi.None, gifMissingPublicationState(false, false, false, false))
        assertEquals(CreationGifPublicationUi.RetryableMissing, gifMissingPublicationState(false, false, false, true))
        for (attempted in listOf(false, true)) {
            assertEquals(CreationGifPublicationUi.Conflict, gifMissingPublicationState(true, false, false, attempted))
            assertEquals(CreationGifPublicationUi.Conflict, gifMissingPublicationState(false, false, true, attempted))
            assertEquals(CreationGifPublicationUi.Conflict, gifMissingPublicationState(false, true, false, attempted))
            assertEquals(CreationGifPublicationUi.Retired, gifMissingPublicationState(true, true, false, attempted))
            assertFalse(gifAllowsNewExport(gifMissingPublicationState(true, true, false, attempted), true))
        }
    }

    @Test fun publicationMarkerIsSmallAndBoundToSessionExactOrderAndGenerations() {
        val marker = gifPublicationBinding(id, identities)
        assertEquals(marker, gifPublicationBinding(id, ArrayList(identities)))
        assertEquals(2, marker.size)
        assertEquals(64, marker[1].length)
        assertNotEquals(marker, gifPublicationBinding("another-session", identities))
        assertNotEquals(marker, gifPublicationBinding(id, identities.reversed()))
        assertNotEquals(marker, gifPublicationBinding(id, listOf(identities[0] + "1", identities[1])))
        assertNotEquals(gifPublicationBinding(id, listOf("a", "bc")), gifPublicationBinding(id, listOf("ab", "c")))
    }

    @Test fun cancellingRecoveryReleasesBusyAndKeepsUnknownPublicationBlocked() {
        val cancelled = gifRecoveryCancelled(CreationGifExportState(running = true))
        assertFalse(cancelled.running)
        assertEquals(CreationGifPublicationUi.Unreadable, cancelled.publication)
        assertTrue(gifKeepsRecovery(cancelled.publication))
        assertFalse(gifAllowsNewExport(cancelled.publication, true))
        val published = CreationGifExportState(running = true, uri = "content://media/external/images/media/1",
            publication = CreationGifPublicationUi.Published)
        assertEquals(published.copy(running = false), gifRecoveryCancelled(published))
        assertEquals(CreationGifPublicationUi.Retired,
            gifRecoveryCancelled(CreationGifExportState(running = true, publication = CreationGifPublicationUi.Retired)).publication)
    }

    @Test fun cancelledPublicationStillOwnsItsJobUntilRecoveryFinallyCompletes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val recoveryEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { recoveryEntered.complete(Unit); release.await() } }
        }
        try {
            entered.await()
            job.cancel()
            recoveryEntered.await()
            assertFalse(job.isActive)
            assertTrue(gifHasOwnedWork(job))
        } finally { release.complete(Unit); job.join() }
        assertFalse(gifHasOwnedWork(job))
        assertFalse(gifHasOwnedWork(null))
    }
}
