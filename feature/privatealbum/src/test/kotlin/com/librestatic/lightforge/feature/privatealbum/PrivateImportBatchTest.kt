package com.librestatic.lightforge.feature.privatealbum

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateImportBatchTest {
    private class AuthExpired : Exception()
    private val auth: (Throwable) -> Boolean = { it is AuthExpired }

    @Test fun expiredWindowBeforeFirstItemReauthenticatesAndImportsEverything() = runTest {
        var windowOpen = false
        var prompts = 0
        val result = importPrivateBatch(
            items = listOf("a", "b"),
            resolveKey = { if (!windowOpen) throw AuthExpired() else "key" },
            importOne = { _, _ -> true },
            reauthenticate = { prompts++; windowOpen = true; true },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertEquals(listOf("a", "b"), result.successful)
        assertFalse(result.interrupted)
        assertEquals(1, prompts)
    }

    @Test fun windowExpiringMidBatchResumesAtTheFailedItemWithAFreshKey() = runTest {
        var keyGeneration = 0
        var expired = false
        val attempts = mutableListOf<Pair<String, Int>>()
        val result = importPrivateBatch(
            items = listOf("a", "b", "c"),
            resolveKey = { ++keyGeneration },
            importOne = { item, key ->
                attempts += item to key
                if (item == "b" && !expired) { expired = true; throw AuthExpired() }
                true
            },
            reauthenticate = { true },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertEquals(listOf("a", "b", "c"), result.successful)
        assertEquals(listOf("a" to 1, "b" to 1, "b" to 2, "c" to 2), attempts)
    }

    @Test fun declinedPromptStopsAsInterruptedAndKeepsEarlierSuccesses() = runTest {
        val result = importPrivateBatch(
            items = listOf("a", "b"),
            resolveKey = { "key" },
            importOne = { item, _ -> if (item == "b") throw AuthExpired() else true },
            reauthenticate = { false },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertEquals(listOf("a"), result.successful)
        assertTrue(result.interrupted)
    }

    @Test fun repeatedAuthenticationFailureOnTheSameItemDoesNotLoop() = runTest {
        var prompts = 0
        val result = importPrivateBatch(
            items = listOf("a"),
            resolveKey = { throw AuthExpired() },
            importOne = { _, _ -> true },
            reauthenticate = { prompts++; true },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertTrue(result.interrupted)
        assertEquals(1, prompts)
    }

    @Test fun lostAccessStopsBeforeTheNextItem() = runTest {
        var owned = true
        val result = importPrivateBatch(
            items = listOf("a", "b"),
            resolveKey = { "key" },
            importOne = { _, _ -> owned = false; true },
            reauthenticate = { true },
            hasAccess = { owned },
            requiresAuthentication = auth,
        )
        assertEquals(listOf("a"), result.successful)
        assertTrue(result.interrupted)
    }

    @Test fun unusableKeyEndsWithoutInterruptionAndPerItemFailuresAreSkipped() = runTest {
        val broken = importPrivateBatch(
            items = listOf("a"),
            resolveKey = { error("missing key") },
            importOne = { _, _ -> true },
            reauthenticate = { true },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertEquals(emptyList<String>(), broken.successful)
        assertFalse(broken.interrupted)
        val partial = importPrivateBatch(
            items = listOf("a", "b"),
            resolveKey = { "key" },
            importOne = { item, _ -> item == "b" },
            reauthenticate = { true },
            hasAccess = { true },
            requiresAuthentication = auth,
        )
        assertEquals(listOf("b"), partial.successful)
    }
}
