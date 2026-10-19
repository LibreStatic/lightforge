package com.librestatic.lightforge

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedStorageMigrationModelTest {
    private class Harness(
        supported: Boolean = true,
        pending: Boolean = true,
        var granted: Boolean = false,
        private val intent: Intent? = null,
    ) {
        val pending = MutableStateFlow(pending)
        var wish = false
        val events = mutableListOf<String>()
        val model = AdvancedStorageMigrationModel(
            supported = supported,
            pending = this.pending,
            writeEnabled = { wish = it; events += "wish=$it" },
            resolve = { this.pending.value = false; events += "resolve" },
            expectLocalChange = { events += "expect=$it" },
            isGranted = { granted },
            permissionIntent = { events += "intent"; intent },
        )
    }

    @Test
    fun `visible only while pending on a supported build`() = runBlocking {
        assertTrue(Harness().model.visible.first())
        assertFalse(Harness(pending = false).model.visible.first())
        assertFalse(Harness(supported = false).model.visible.first())
    }

    @Test
    fun `switch announces the change, sets the wish, resolves and asks for the permission screen`() = runBlocking {
        val harness = Harness()
        harness.model.switchOn()
        assertEquals(listOf("expect=true", "wish=true", "resolve", "intent"), harness.events)
        assertTrue(harness.wish)
        assertFalse(harness.model.visible.first())
    }

    @Test
    fun `switch opens no screen when the permission is already granted`() = runBlocking {
        val harness = Harness(granted = true)
        assertNull(harness.model.switchOn())
        assertTrue(harness.wish)
        assertFalse(harness.events.contains("intent"))
    }

    @Test
    fun `not now resolves and leaves the wish off`() = runBlocking {
        val harness = Harness()
        harness.model.notNow()
        assertEquals(listOf("resolve"), harness.events)
        assertFalse(harness.wish)
        assertFalse(harness.model.visible.first())
    }
}
