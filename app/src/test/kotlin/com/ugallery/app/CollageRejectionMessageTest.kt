package com.ugallery.app

import org.junit.Assert.*
import org.junit.Test

class CollageRejectionMessageTest {
    @Test fun everyRejectionHasItsOwnMessage() {
        val ids = CollageRejection.entries.map { collageRejectionMessage(it, 3).stringRes }
        assertEquals(CollageRejection.entries.size, ids.toSet().size)
    }

    @Test fun nothingSelectedKeepsTheExistingHint() {
        val message = collageRejectionMessage(CollageRejection.NothingSelected, 0)
        assertEquals(com.ugallery.feature.collage.R.string.creation_collage_no_selection, message.stringRes)
        assertNull(message.formatArg)
    }

    @Test fun countedReasonsReportTheActualSelectionSize() {
        assertEquals(11, collageRejectionMessage(CollageRejection.TooMany, 11).formatArg)
        assertEquals(1, collageRejectionMessage(CollageRejection.NotEnoughSelection, 1).formatArg)
    }

    @Test fun uncountedReasonsCarryNoArgument() {
        assertNull(collageRejectionMessage(CollageRejection.NonImageSelected, 3).formatArg)
        assertNull(collageRejectionMessage(CollageRejection.DraftPending, 0).formatArg)
        assertNull(collageRejectionMessage(CollageRejection.SourceUnavailable, 2).formatArg)
    }
}
