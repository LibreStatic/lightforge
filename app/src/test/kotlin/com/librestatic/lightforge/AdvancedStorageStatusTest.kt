package com.librestatic.lightforge

import android.content.Intent
import com.librestatic.lightforge.core.mediastore.AdvancedResult
import com.librestatic.lightforge.core.mediastore.AdvancedStorage
import com.librestatic.lightforge.core.mediastore.HiddenEntry
import com.librestatic.lightforge.core.mediastore.HiddenKind
import com.librestatic.lightforge.core.mediastore.NoOpAdvancedStorage
import com.librestatic.lightforge.core.preferences.GallerySettings
import com.librestatic.lightforge.core.preferences.OperationSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedStorageStatusTest {
    private class Fake(var granted: Boolean, var hidden: Int = 0, override val isSupported: Boolean = true) : AdvancedStorage {
        override fun isGranted() = granted
        override fun permissionIntent(): Intent? = null
        override suspend fun hideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun unhideFolder(absolutePath: String) = AdvancedResult.Done
        override suspend fun hiddenByApp() = List(hidden) { HiddenEntry("/h$it", HiddenKind.NoMediaFile, 1L) }
        override suspend fun unhideAllRecorded(): List<HiddenEntry> = emptyList()
        override suspend fun renameInPlace(sourceAbsolutePath: String, targetAbsolutePath: String) = AdvancedResult.Done
        override suspend fun deleteEmptyDirectory(absolutePath: String) = AdvancedResult.Done
    }

    private class Env(storage: AdvancedStorage, enabled: Boolean = true, showHidden: Boolean = false, deleteEmpty: Boolean = false) {
        val settings = MutableStateFlow(GallerySettings().with(enabled, showHidden, deleteEmpty))
        val status = AdvancedStorageStatus(storage, settings, CoroutineScope(Dispatchers.Unconfined))
        val now get() = status.status.value
        fun set(enabled: Boolean? = null, showHidden: Boolean? = null, deleteEmpty: Boolean? = null) {
            val o = settings.value.operations
            settings.value = settings.value.with(enabled ?: o.advancedStorageEnabled, showHidden ?: o.advancedShowHidden, deleteEmpty ?: o.advancedDeleteEmptyFolders)
        }
    }

    @Test fun `granted then revoked drops the capability and counts the folders still hidden`() {
        val storage = Fake(granted = true, hidden = 3)
        val env = Env(storage, showHidden = true, deleteEmpty = true)
        env.status.refresh()
        assertTrue(env.now.active)
        assertTrue(env.now.showHidden)
        assertTrue(env.now.deleteEmptyFolders)
        assertEquals(0, env.now.revokedWithHidden)

        storage.granted = false
        env.status.refresh()
        assertEquals(AdvancedStatus(active = false, revokedWithHidden = 3), env.now)
    }

    @Test fun `turning the mode off removes every capability and the revoked count`() {
        val storage = Fake(granted = false, hidden = 2)
        val env = Env(storage)
        env.status.refresh()
        assertEquals(2, env.now.revokedWithHidden)
        env.set(enabled = false)
        assertEquals(AdvancedStatus(), env.now)
    }

    @Test fun `the no-op binding is never active`() {
        val env = Env(NoOpAdvancedStorage, showHidden = true, deleteEmpty = true)
        env.status.refresh()
        env.status.setTemporaryShowHidden(true)
        assertEquals(AdvancedStatus(), env.now)
    }

    @Test fun `a flag-off build with folders hidden earlier offers to unhide them and stays inactive`() {
        val env = Env(Fake(granted = false, hidden = 2, isSupported = false), enabled = false)
        assertEquals(2, env.status.pendingUnhide.value)
        assertEquals(AdvancedStatus(), env.now)
        env.status.dismissPendingUnhide()
        assertEquals(0, env.status.pendingUnhide.value)
    }

    @Test fun `a flag-off build with nothing hidden raises no prompt`() {
        val env = Env(Fake(granted = false, hidden = 0, isSupported = false), enabled = false)
        assertEquals(0, env.status.pendingUnhide.value)
    }

    @Test fun `temporary show-hidden needs the mode and lasts until the timeout`() {
        val storage = Fake(granted = true)
        val env = Env(storage)
        env.status.refresh()
        env.status.setTemporaryShowHidden(true)
        assertTrue(env.now.showHidden)
        assertTrue(env.now.temporaryShowHidden)

        // The mode turning off takes the temporary state with it, even if the mode comes back later.
        env.set(enabled = false)
        env.set(enabled = true)
        assertFalse(env.now.temporaryShowHidden)
    }

    @Test fun `the temporary state survives 4 59 in the background and not 5 00`() {
        val env = Env(Fake(granted = true))
        env.status.refresh()

        env.status.setTemporaryShowHidden(true)
        env.status.onBackgrounded(1_000L)
        env.status.onForegrounded(1_000L + 5 * 60_000L - 1_000L)
        assertTrue(env.now.temporaryShowHidden)

        env.status.onBackgrounded(10_000L)
        env.status.onForegrounded(10_000L + 5 * 60_000L)
        assertFalse(env.now.temporaryShowHidden)
        assertFalse(env.now.showHidden)
    }

    @Test fun `the saved show-hidden setting is not touched by the timeout`() {
        val env = Env(Fake(granted = true), showHidden = true)
        env.status.refresh()
        env.status.onBackgrounded(0L)
        env.status.onForegrounded(10 * 60_000L)
        assertTrue(env.now.showHidden)
    }
}

private fun GallerySettings.with(enabled: Boolean, showHidden: Boolean, deleteEmpty: Boolean) = copy(
    operations = OperationSettings(
        advancedStorageEnabled = enabled,
        advancedShowHidden = showHidden,
        advancedDeleteEmptyFolders = deleteEmpty,
    ),
)
