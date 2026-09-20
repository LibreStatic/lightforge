package com.ugallery.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Initial locked UI contract only: never authenticates, opens SQLCipher or creates keys. */
class PrivateIndexSessionUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialLockedIndexOffersUnlockWithoutSetupOrDatabaseAccess() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "private-index-session-ui-${UUID.randomUUID()}")
        check(root.mkdir())
        val context = ClosedIndexContext(base, root)
        val repository = PrivateAlbumRepository.sessionBacked(context)
        val visible = mutableStateOf(true)
        var unlockRequests = 0
        var forbiddenCallbacks = 0
        try {
            assertTrue(repository.isSessionBacked)
            assertEquals(PrivateIndexAccessState.Locked, repository.accessState.value)
            compose.setContent {
                if (visible.value) UGalleryTheme(darkTheme = false) {
                    PrivateAlbumContent(
                        repository = repository,
                        onBack = { forbiddenCallbacks++ },
                        // Record the request only: do not invoke success/error or open the index.
                        onUnlockRequest = { _, _ -> unlockRequests++ },
                        onExport = { _, _, _ -> forbiddenCallbacks++ },
                        isUnlocked = false,
                        onUnlocked = { forbiddenCallbacks++ },
                        onAddRequest = { forbiddenCallbacks++ },
                        onPortableRequest = { _ -> forbiddenCallbacks++ },
                    )
                }
            }
            val unlock = compose.onNodeWithText(base.getString(R.string.private_unlock))
            unlock.assertIsDisplayed().assertIsEnabled()
            assertLockedPresentation(base)
            compose.runOnIdle {
                assertEquals(0, unlockRequests)
                assertEquals(0, forbiddenCallbacks)
                assertEquals(0, context.storageRequests.get())
            }
            unlock.performClick()
            compose.runOnIdle {
                assertEquals(1, unlockRequests)
                assertEquals(0, forbiddenCallbacks)
                assertEquals(PrivateIndexAccessState.Locked, repository.accessState.value)
                assertEquals("Locked UI must never resolve index storage", 0, context.storageRequests.get())
                assertTrue("No index, key wrapper or media files were created", root.listFiles()!!.isEmpty())
            }
            unlock.assertIsDisplayed().assertIsEnabled()
            assertLockedPresentation(base)
        } finally {
            compose.runOnIdle { visible.value = false }
            repository.disposeSession()
            check(root.deleteRecursively())
        }
    }

    private fun assertLockedPresentation(context: Context) {
        compose.onNodeWithTag("private-index-loading").assertDoesNotExist()
        compose.onNodeWithTag("private-index-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("private-portable-open").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-open").assertDoesNotExist()
        compose.onNodeWithTag("private-key-protection-dialog").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.private_setup_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.private_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.private_choose_media)).assertDoesNotExist()
    }

    /** Any accidental storage open fails before touching a real index or a Keystore alias. */
    private class ClosedIndexContext(base: Context, private val root: File) : ContextWrapper(base) {
        val storageRequests = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = File(root, "cache")
        override fun getFilesDir(): File = forbiddenStorage()
        override fun getNoBackupFilesDir(): File = forbiddenStorage()
        override fun getDatabasePath(name: String): File = forbiddenStorage()
        private fun forbiddenStorage(): Nothing {
            storageRequests.incrementAndGet()
            throw AssertionError("Locked index must not request private storage")
        }
    }
}
