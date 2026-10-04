package com.librestatic.lightforge.feature.collage

import org.junit.Assert.assertEquals
import org.junit.Test

/** A user Cancel cleans up its own unfinished attempt; nothing else ever triggers a discard. */
class CreationGifCancelCleanupTest {
    @Test fun `user cancel of an unfinished publication discards the pending output`() {
        assertEquals(GifCancelCleanup.DiscardPending, gifCancelCleanup(true, CreationGifPublicationUi.Incomplete))
    }

    @Test fun `user cancel before any publication intent only clears the attempt marker`() {
        assertEquals(GifCancelCleanup.ClearAttempt, gifCancelCleanup(true, CreationGifPublicationUi.RetryableMissing))
    }

    @Test fun `a committed publication survives a late cancel`() {
        assertEquals(GifCancelCleanup.None, gifCancelCleanup(true, CreationGifPublicationUi.Published))
    }

    @Test fun `conflicts and unreadable journals are never discarded implicitly`() {
        listOf(CreationGifPublicationUi.Conflict, CreationGifPublicationUi.Unreadable, CreationGifPublicationUi.Unverified)
            .forEach { assertEquals(GifCancelCleanup.None, gifCancelCleanup(true, it)) }
    }

    @Test fun `an interruption without a user cancel keeps the recovery card`() {
        CreationGifPublicationUi.values().forEach { assertEquals(GifCancelCleanup.None, gifCancelCleanup(false, it)) }
    }
}
