package com.librestatic.lightforge

import org.junit.Assert.*
import org.junit.Test

class MemoryVideoRequestEpochTest {
    @Test fun newerMemoryPreparationOwnsPublicationWhenSlowSelectionFinishesLast() {
        val epoch = MemoryVideoRequestEpoch()
        val slowSelection = epoch.begin()
        val newMemory = epoch.begin()
        var published: String? = null
        fun complete(token: MemoryVideoRequestEpoch.Token, value: String) {
            if (epoch.isCurrent(token)) published = value
        }
        complete(newMemory, "new memory")
        complete(slowSelection, "stale selection")
        assertEquals("new memory", published)
        assertFalse(epoch.isCurrent(slowSelection))
        assertTrue(epoch.isCurrent(newMemory))
    }

    @Test fun navigationInvalidatesDelayedPreparationAndNeverRevivesItsToken() {
        val epoch = MemoryVideoRequestEpoch()
        val old = epoch.begin()
        epoch.cancel()
        assertFalse(epoch.isCurrent(old))
        val reopened = epoch.begin()
        assertNotSame(old, reopened)
        assertFalse(epoch.isCurrent(old))
        assertTrue(epoch.isCurrent(reopened))
    }

    @Test fun staleCancellationCannotInvalidateOrClearTheNewSession() {
        val epoch = MemoryVideoRequestEpoch()
        val old = epoch.begin()
        val newer = epoch.begin()
        var session: String? = "new draft"
        if (epoch.cancel(old)) session = null
        assertEquals("new draft", session)
        assertTrue(epoch.isCurrent(newer))
        assertTrue(epoch.cancel(newer))
        assertFalse(epoch.isCurrent(newer))
        assertFalse(epoch.cancel(newer))
    }
}
