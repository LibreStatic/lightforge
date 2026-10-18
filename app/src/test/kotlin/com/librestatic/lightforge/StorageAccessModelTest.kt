package com.librestatic.lightforge

import android.content.Intent
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import com.librestatic.lightforge.feature.settings.StorageAccessMessageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessModelTest {
    private class Fake(var granted: Boolean, hidden: List<String>, val failing: Set<String> = emptySet()) : AdvancedStorage {
        val registry = hidden.toMutableList()
        override val isSupported = true
        override fun isGranted() = granted
        override fun permissionIntent(): Intent? = null
        override suspend fun hideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun unhideFolder(absolutePath: String): AdvancedResult = when {
            !granted -> AdvancedResult.NotGranted
            absolutePath in failing -> AdvancedResult.Failed("busy")
            else -> { registry.remove(absolutePath); AdvancedResult.Done }
        }
        override suspend fun hiddenByApp() = registry.map { HiddenEntry(it, HiddenKind.NoMediaFile, 5L) }
        override suspend fun unhideAllRecorded(): List<HiddenEntry> {
            val failed = registry.filter { it in failing }
            registry.retainAll(failed)
            return failed.map { HiddenEntry(it, HiddenKind.NoMediaFile, 5L) }
        }
        override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String) = AdvancedResult.Done
        override suspend fun deleteEmptyDirectory(absolutePath: String) = AdvancedResult.Done
    }

    private class Env(val storage: Fake, var enabled: Boolean = true, var media: Boolean = false) {
        val model = StorageAccessModel(
            advanced = storage,
            manageMediaSupported = true,
            canManageMedia = { media },
            readEnabled = { enabled },
            writeEnabled = { enabled = it },
            io = Dispatchers.Unconfined,
        )
    }

    @Test fun `snapshot combines both permissions, the opt-in and the sorted registry`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/b", "/a")), enabled = true, media = true)
        val state = env.model.snapshot()
        assertTrue(state.manageMediaGranted)
        assertTrue(state.advancedEnabled)
        assertFalse(state.allFilesGranted)
        assertEquals(listOf("/a", "/b"), state.hidden.map { it.absolutePath })
        assertTrue(state.permissionRevoked)
    }

    @Test fun `unhide without the permission explains and keeps the entry`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/a")))
        assertEquals(StorageAccessMessageKind.PermissionNeeded, env.model.unhide("/a")?.kind)
        assertEquals(1, env.model.snapshot().hidden.size)
    }

    @Test fun `unhide removes the entry and a failure keeps it listed`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = listOf("/a", "/b"), failing = setOf("/b")))
        assertNull(env.model.unhide("/a"))
        val failure = env.model.unhide("/b")
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, failure?.kind)
        assertEquals(listOf("/b"), env.model.snapshot().hidden.map { it.absolutePath })
    }

    @Test fun `unhide and turn off needs the permission and changes nothing without it`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/a")))
        assertEquals(StorageAccessMessageKind.PermissionNeeded, env.model.unhideAndTurnOff()?.kind)
        assertTrue(env.enabled)
        assertEquals(1, env.model.snapshot().hidden.size)
    }

    @Test fun `unhide and turn off clears the registry and switches the mode off`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = listOf("/a", "/b")))
        assertNull(env.model.unhideAndTurnOff())
        assertFalse(env.enabled)
        assertTrue(env.model.snapshot().hidden.isEmpty())
    }

    @Test fun `turning off with partial failures still switches off and reports the leftovers`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = listOf("/a", "/b"), failing = setOf("/b")))
        val message = env.model.unhideAndTurnOff()
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, message?.kind)
        assertEquals(1, message?.count)
        assertFalse(env.enabled)
        assertEquals(listOf("/b"), env.model.snapshot().hidden.map { it.absolutePath })
    }
}
