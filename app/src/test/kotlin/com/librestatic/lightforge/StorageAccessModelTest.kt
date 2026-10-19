package com.librestatic.lightforge

import android.content.Intent
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.OperationSettings
import com.librestatic.lightforge.feature.settings.AdvancedStatus
import com.librestatic.lightforge.feature.settings.StorageAccessMessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessModelTest {
    private class Fake(
        var granted: Boolean,
        hidden: List<String>,
        val failing: Set<String> = emptySet(),
        /** Without the permission the owner can still delete its own marker (slice 1's owner-delete path). */
        var ownerDelete: Boolean = true,
    ) : AdvancedStorage {
        val registry = hidden.toMutableList()
        var revokeAfter: Int? = null
        override val isSupported = true
        override fun isGranted() = granted
        override fun permissionIntent(): Intent? = null
        override suspend fun hideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun unhideFolder(absolutePath: String): AdvancedResult = when {
            !granted && !ownerDelete -> AdvancedResult.NeedsTreeGrant(absolutePath)
            absolutePath in failing -> AdvancedResult.Failed("busy")
            else -> { registry.remove(absolutePath); AdvancedResult.Done }
        }
        override suspend fun hiddenByApp() = registry.map { HiddenEntry(it, HiddenKind.NoMediaFile, 5L) }
        override suspend fun unhideAllRecorded(): List<HiddenEntry> {
            val failed = mutableListOf<String>()
            var done = 0
            for (path in registry.toList()) {
                if (revokeAfter == done) { granted = false; ownerDelete = false }
                if (path in failing || (!granted && !ownerDelete)) failed += path else { registry.remove(path); done++ }
            }
            return failed.map { HiddenEntry(it, HiddenKind.NoMediaFile, 5L) }
        }
        val scanned = mutableListOf<String>()
        var scanResult: Int? = 3
        override fun absolutePathOf(volumeName: String, relativePath: String?, displayName: String?): String? =
            if (volumeName == "external_primary") "/storage/emulated/0/" + relativePath.orEmpty() else null
        override suspend fun scanFolderTree(absolutePath: String): Int? { scanned += absolutePath; return scanResult }
        override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String) = AdvancedResult.Done
        override suspend fun deleteEmptyDirectory(absolutePath: String) = AdvancedResult.Done
    }

    private class Env(val storage: Fake, var enabled: Boolean = true, var media: Boolean = false) {
        val localChanges = mutableListOf<Boolean>()
        val model = StorageAccessModel(
            advanced = storage,
            manageMediaSupported = true,
            canManageMedia = { media },
            readEnabled = { enabled },
            writeEnabled = { enabled = it },
            io = Dispatchers.Unconfined,
            expectLocalChange = { localChanges += it },
            readExtras = { StorageExtras(scannedFolders = included) },
            writeScannedFolders = { included = it },
        )
        var included: Set<String> = emptySet()
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

    @Test fun `unhide without the permission uses the owner path and removes the entry`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/a")))
        assertNull(env.model.unhide("/a"))
        assertTrue(env.model.snapshot().hidden.isEmpty())
    }

    @Test fun `an entry the owner cannot unhide asks for the folder and stays listed`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/a", "/b"), ownerDelete = false))
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, env.model.unhide("/a")?.kind)
        val state = env.model.snapshot()
        assertEquals(listOf("/a", "/b"), state.hidden.map { it.absolutePath })
        assertEquals(setOf("/a"), state.treeGrantPaths)
        // The picked folder finishes the unhide; the other row is untouched.
        env.storage.registry.remove("/a")
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, env.model.afterTreeUnhide("/a", AdvancedResult.Failed("mismatch"))?.kind)
        assertNull(env.model.afterTreeUnhide("/a", AdvancedResult.Done))
        val after = env.model.snapshot()
        assertEquals(listOf("/b"), after.hidden.map { it.absolutePath })
        assertTrue(after.treeGrantPaths.isEmpty())
    }

    @Test fun `unhide removes the entry and a failure keeps it listed`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = listOf("/a", "/b"), failing = setOf("/b")))
        assertNull(env.model.unhide("/a"))
        val failure = env.model.unhide("/b")
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, failure?.kind)
        assertEquals(listOf("/b"), env.model.snapshot().hidden.map { it.absolutePath })
    }

    @Test fun `unhide and turn off keeps the mode on while entries remain without the permission`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = listOf("/a"), ownerDelete = false))
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, env.model.unhideAndTurnOff()?.kind)
        assertTrue(env.enabled)
        val state = env.model.snapshot()
        assertEquals(1, state.hidden.size)
        assertEquals(setOf("/a"), state.treeGrantPaths)
        assertEquals(1, state.revokedWithHidden)
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

    @Test fun `revoking during unhide all keeps the partial progress and lists the rest`() = runBlocking {
        val storage = Fake(granted = true, hidden = listOf("/a", "/b", "/c")).also { it.revokeAfter = 1 }
        val env = Env(storage)
        val message = env.model.unhideAll()
        assertEquals(StorageAccessMessageKind.NotAllUnhidden, message?.kind)
        assertEquals(2, message?.count)
        val state = env.model.snapshot()
        assertEquals(listOf("/b", "/c"), state.hidden.map { it.absolutePath })
        assertEquals(setOf("/b", "/c"), state.treeGrantPaths)
        assertTrue(state.permissionRevoked)
        assertEquals(2, state.revokedWithHidden)
    }

    @Test fun `an imported opt-in without the permission lands in waiting for permission`() = runBlocking {
        val env = Env(Fake(granted = false, hidden = emptyList()), enabled = true)
        assertEquals(AdvancedStatus.WaitingForPermission, env.model.snapshot().advancedStatus)
    }

    @Test fun `turning the mode off from the page records a local change so no prompt is raised`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = listOf("/a")))
        env.model.setEnabled(false)
        assertEquals(listOf(false), env.localChanges)
    }

    private fun statusFor(enabled: MutableStateFlow<Boolean>, storage: Fake) = AdvancedStorageStatus(
        storage,
        enabled.map { GallerySettings(operations = OperationSettings(advancedStorageEnabled = it)) },
        CoroutineScope(Dispatchers.Unconfined),
    )

    @Test fun `an import that turns the mode off with hidden folders raises the unhide prompt`() = runBlocking {
        val enabled = MutableStateFlow(true)
        val status = statusFor(enabled, Fake(granted = true, hidden = listOf("/a", "/b")))
        assertEquals(0, status.pendingUnhide.value)
        enabled.value = false
        assertEquals(2, status.pendingUnhide.value)
        status.dismissPendingUnhide()
        assertEquals(0, status.pendingUnhide.value)
    }

    @Test fun `a change made on the page, or with nothing hidden, raises no prompt`() = runBlocking {
        val own = MutableStateFlow(true)
        val ownStatus = statusFor(own, Fake(granted = true, hidden = listOf("/a")))
        ownStatus.expectLocalChange(false)
        own.value = false
        assertEquals(0, ownStatus.pendingUnhide.value)

        val none = MutableStateFlow(true)
        val noneStatus = statusFor(none, Fake(granted = true, hidden = emptyList()))
        none.value = false
        assertEquals(0, noneStatus.pendingUnhide.value)
    }

    @Test fun `a picked folder is scanned and remembered, and removing it forgets it`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = emptyList()))
        assertNull(env.model.includeFolder("primary:Scans/2024"))
        assertEquals(listOf("/storage/emulated/0/Scans/2024"), env.storage.scanned)
        assertEquals(setOf("/storage/emulated/0/Scans/2024"), env.included)
        assertEquals(listOf("/storage/emulated/0/Scans/2024"), env.model.snapshot().scannedFolders)
        env.model.removeIncludedFolder("/storage/emulated/0/Scans/2024")
        assertTrue(env.included.isEmpty())
    }

    @Test fun `a folder the scanner refuses, or a volume root, is not remembered`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = emptyList()))
        env.storage.scanResult = null
        assertEquals(StorageAccessMessageKind.IncludeFailed, env.model.includeFolder("primary:Scans")!!.kind)
        assertEquals(StorageAccessMessageKind.IncludeFailed, env.model.includeFolder("primary:")!!.kind)
        assertEquals(StorageAccessMessageKind.IncludeFailed, env.model.includeFolder(null)!!.kind)
        env.storage.granted = false
        assertEquals(StorageAccessMessageKind.PermissionNeeded, env.model.includeFolder("primary:Scans")!!.kind)
        assertTrue(env.included.isEmpty())
    }

    @Test fun `rescanning scans every included folder`() = runBlocking {
        val env = Env(Fake(granted = true, hidden = emptyList()))
        env.included = setOf("/a", "/b")
        env.model.rescanScannedFolders()
        assertEquals(setOf("/a", "/b"), env.storage.scanned.toSet())
    }

    @Test fun `tree document ids map to a volume and a relative path`() {
        assertEquals("external_primary" to "Scans/2024", treeDocumentVolumeAndPath("primary:Scans/2024/"))
        assertEquals("abcd-1234" to "Photos", treeDocumentVolumeAndPath("ABCD-1234:Photos"))
        assertNull(treeDocumentVolumeAndPath("primary:"))
        assertNull(treeDocumentVolumeAndPath("garbage"))
    }
}
