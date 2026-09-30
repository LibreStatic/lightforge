package com.librestatic.lightforge.feature.collage

import org.junit.Assert.*
import org.junit.Test

/**
 * Recovery is the only terminal emitter of the GIF export surface. These cover the rule that no exit
 * path may leave the controls disabled behind a live progress bar, and that a verification timeout is
 * reported as unverified-but-present rather than as a broken file.
 */
class CreationGifRecoveryResolutionTest {
    private val published = "content://media/external_primary/images/media/17"

    private fun running(publication: CreationGifPublicationUi = CreationGifPublicationUi.Checking,
        uri: String? = null, progress: Int = 0) =
        CreationGifExportState(running = true, progress = progress, uri = uri, publication = publication)

    @Test fun `invalid binding early return still clears running`() {
        // bind() replaced the state before recovery could emit; recovery returns at its first guard.
        val abandoned = gifRecoveryResolved(CreationGifExportState(running = true,
            publication = CreationGifPublicationUi.Conflict))
        assertEquals(false, abandoned.running)
        assertEquals(CreationGifPublicationUi.Conflict, abandoned.publication)
    }

    @Test fun `invalid binding after reconcile still clears running`() {
        // The second guard, reached once reconcile() returned but the session was re-derived meanwhile.
        val abandoned = gifRecoveryResolved(running(CreationGifPublicationUi.Checking, progress = 100))
        assertEquals(false, abandoned.running)
        assertEquals(100, abandoned.progress)
        assertEquals(CreationGifPublicationUi.Checking, abandoned.publication)
    }

    @Test fun `receipt mismatch early return still clears running`() {
        val conflict = gifRecoveryResolved(CreationGifExportState(running = true,
            publication = CreationGifPublicationUi.Conflict))
        assertEquals(false, conflict.running)
    }

    @Test fun `a resolved state is returned unchanged`() {
        val resolved = CreationGifExportState(uri = published, progress = 100,
            publication = CreationGifPublicationUi.Published)
        assertSame(resolved, gifRecoveryResolved(resolved))
    }

    @Test fun `verification timeout keeps the published result and renders the success surface`() {
        val timedOut = gifRecoveryUnverified(running(progress = 90), published)
        assertEquals(false, timedOut.running)
        assertEquals(false, timedOut.failed)
        assertEquals(published, timedOut.uri)
        assertEquals(100, timedOut.progress)
        assertEquals(CreationGifPublicationUi.Unverified, timedOut.publication)
        assertNotEquals(CreationGifPublicationUi.Unreadable, timedOut.publication)
    }

    @Test fun `verification timeout prefers the state uri over the reported one`() {
        val timedOut = gifRecoveryUnverified(running(CreationGifPublicationUi.Published, uri = published), "content://other")
        assertEquals(published, timedOut.uri)
        assertEquals(CreationGifPublicationUi.Unverified, timedOut.publication)
    }

    @Test fun `verification timeout without any published output stays a failure`() {
        val timedOut = gifRecoveryUnverified(running(), null)
        assertEquals(false, timedOut.running)
        assertEquals(true, timedOut.failed)
        assertEquals(null, timedOut.uri)
        assertEquals(CreationGifPublicationUi.Unreadable, timedOut.publication)
    }

    @Test fun `unverified blocks a new export but keeps recovery available`() {
        assertEquals(false, gifAllowsNewExport(CreationGifPublicationUi.Unverified, true))
        assertEquals(true, gifKeepsRecovery(CreationGifPublicationUi.Unverified))
    }
}
