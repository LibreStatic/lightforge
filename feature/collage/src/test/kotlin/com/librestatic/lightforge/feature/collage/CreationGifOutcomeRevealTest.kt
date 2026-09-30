package com.librestatic.lightforge.feature.collage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationGifOutcomeRevealTest {

    @Test
    fun `a running export never steals the scroll position`() {
        CreationGifPublicationUi.values().forEach { status ->
            assertFalse(status.name, gifRevealsOutcome(status, running = true))
        }
    }

    @Test
    fun `an editable draft keeps the scroll position`() {
        assertFalse(gifRevealsOutcome(CreationGifPublicationUi.None, running = false))
    }

    @Test
    fun `an in-flight publication check keeps the scroll position`() {
        assertFalse(gifRevealsOutcome(CreationGifPublicationUi.Checking, running = false))
    }

    @Test
    fun `every settled publication outcome is revealed`() {
        listOf(
            CreationGifPublicationUi.Published,
            CreationGifPublicationUi.Unverified,
            CreationGifPublicationUi.Unreadable,
            CreationGifPublicationUi.Conflict,
            CreationGifPublicationUi.Incomplete,
            CreationGifPublicationUi.RetryableMissing,
            CreationGifPublicationUi.Retired,
        ).forEach { status -> assertTrue(status.name, gifRevealsOutcome(status, running = false)) }
    }

    @Test
    fun `whatever disables editing after a run is revealed`() {
        CreationGifPublicationUi.values().forEach { status ->
            if (status == CreationGifPublicationUi.Checking) return@forEach
            val editable = gifAllowsNewExport(status, sourcesAvailable = true)
            assertTrue(status.name, editable != gifRevealsOutcome(status, running = false))
        }
    }
}
