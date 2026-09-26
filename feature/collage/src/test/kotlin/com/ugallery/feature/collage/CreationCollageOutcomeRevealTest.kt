package com.ugallery.feature.collage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationCollageOutcomeRevealTest {

    @Test
    fun `busy work never steals the scroll position`() {
        CreationCollagePublicationUi.values().forEach { status ->
            assertFalse(status.name, collageRevealsOutcome(status, busy = true))
        }
    }

    @Test
    fun `an editable draft and an in-flight check keep the scroll position`() {
        assertFalse(collageRevealsOutcome(CreationCollagePublicationUi.None, busy = false))
        assertFalse(collageRevealsOutcome(CreationCollagePublicationUi.Checking, busy = false))
    }

    @Test
    fun `every settled publication outcome is revealed`() {
        listOf(
            CreationCollagePublicationUi.Published,
            CreationCollagePublicationUi.RetryableMissing,
            CreationCollagePublicationUi.Incomplete,
            CreationCollagePublicationUi.Conflict,
            CreationCollagePublicationUi.Unreadable,
        ).forEach { status -> assertTrue(status.name, collageRevealsOutcome(status, busy = false)) }
    }

    @Test
    fun `whatever disables editing after a run is revealed`() {
        CreationCollagePublicationUi.values().forEach { status ->
            if (status == CreationCollagePublicationUi.Checking) return@forEach
            val editable = collageAllowsNewRender(status, sourcesAvailable = true)
            assertTrue(status.name, editable != collageRevealsOutcome(status, busy = false))
        }
    }
}
