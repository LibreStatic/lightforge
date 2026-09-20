package com.ugallery.feature.privatealbum

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.security.PrivateAlbumCrypto
import com.ugallery.core.security.PrivatePortableArchive
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Controlled session/ActivityResult timing, real Compose + crypto + local provider; not biometric
 * acceptance.
 */
class PrivatePortableSessionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val base
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val password = "Owned lifecycle password"

    private inner class Fixture : AutoCloseable {
        val root =
            File(base.cacheDir, "private-session-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        val database =
            Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
        val repository = PrivateAlbumRepository(context, database)
        val key = PrivateAlbumCrypto.getOrCreateMasterKey()
        val original =
            File(context.cacheDir, "owned.png").apply {
                writeBytes(
                    android.util.Base64.decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                        android.util.Base64.DEFAULT,
                    )
                )
            }
        val unlocked = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val registry = DeferredRegistry()
        var closed = false

        init {
            runBlocking {
                repository.setup(key)
                assertTrue(
                    repository
                        .importFromUri(
                            Uri.fromFile(original),
                            "owned-session.png",
                            "image/png",
                            "image",
                            masterKey = key,
                        )
                        .success
                )
            }
        }

        fun show(hoistedBeforeGlobalGuard: Boolean = false) {
            val owner =
                object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }
            compose.setContent {
                if (visible.value)
                    UGalleryTheme {
                        CompositionLocalProvider(
                            LocalContext provides context,
                            LocalActivityResultRegistryOwner provides owner,
                        ) {
                            PrivatePortableContent(
                                repository,
                                true,
                                onClose = { closed = true },
                                isUnlocked = unlocked.value,
                            )
                            // Match the app host position: the global guard removes all subsequent
                            // route composition, never the registered portable ActivityResult host.
                            if (hoistedBeforeGlobalGuard && !unlocked.value) {
                                Text("Global app locked", Modifier.testTag("session-global-guard"))
                                return@CompositionLocalProvider
                            }
                            if (hoistedBeforeGlobalGuard)
                                Text("Private route", Modifier.testTag("session-private-route"))
                        }
                    }
            }
        }

        fun lock() {
            compose.runOnIdle { unlocked.value = false }
            compose.onNodeWithTag("private-portable-screen").assertDoesNotExist()
            compose.onNodeWithTag("private-portable-password").assertDoesNotExist()
            compose.onNodeWithTag("private-portable-review").assertDoesNotExist()
            compose.onNodeWithTag("private-portable-cancel-confirm").assertDoesNotExist()
        }

        fun unlock() {
            compose.runOnIdle { unlocked.value = true }
        }

        override fun close() {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            database.close()
            check(root.deleteRecursively())
        }
    }

    private inner class DeferredRegistry : ActivityResultRegistry() {
        var code: Int? = null
        var action: String? = null
        var launches = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            check(code == null)
            val intent = contract.createIntent(base, input)
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            action = intent.action
            code = requestCode
            launches++
        }

        fun returnUri(uri: Uri?) {
            val request = requireNotNull(code)
            code = null
            dispatchResult(
                request,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let {
                    Intent()
                        .setData(it)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                },
            )
        }
    }

    @Test
    fun createResultWhileLockedWaitsForUnlockAndPublishesSameCiphertextExactlyOnce() {
        val f = Fixture()
        val documentId = UUID.randomUUID().toString()
        val output =
            File(base.cacheDir, "private-document-$documentId.ugpb").apply {
                writeBytes(byteArrayOf())
            }
        val uri =
            DocumentsContract.buildDocumentUri(
                base.packageName + ".privateportable.fixture",
                documentId,
            )
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        try {
            base.grantUriPermission(base.packageName, uri, flags)
            f.show()
            click("private-portable-export")
            fill(confirm = true)
            click("private-portable-prepare")
            compose.waitUntil(30_000) { f.registry.code != null }
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, f.registry.action)
            val prepared =
                File(f.context.filesDir, "private-album").walkTopDown().single {
                    it.name == "private-backup.ugpb"
                }
            val expectedHash = PrivatePortableArchive.hash(prepared)
            val originalHash = PrivatePortableArchive.hash(f.original)
            f.lock()
            compose.runOnIdle { f.registry.returnUri(uri) }
            compose.waitForIdle()
            assertEquals("Locked callback must not publish", 0L, output.length())
            assertArrayEquals(expectedHash, PrivatePortableArchive.hash(prepared))
            assertEquals(1, f.registry.launches)
            f.unlock()
            awaitTag("private-portable-saved")
            assertArrayEquals(expectedHash, PrivatePortableArchive.hash(output))
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(f.original))
            assertEquals(1, f.registry.launches)
            f.lock()
            f.unlock()
            awaitTag("private-portable-saved")
            assertArrayEquals(expectedHash, PrivatePortableArchive.hash(output))
            assertEquals(
                "Reauthentication must not relaunch/rewrite destination",
                1,
                f.registry.launches,
            )
            assertEquals(1, runBlocking { f.repository.count() })
        } finally {
            f.close()
            if (output.exists()) check(output.delete())
            base.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    @Test
    fun openResultWhileLockedRetainsUriButDropsPasswordAndPreparedReviewOnRelock() {
        val f = Fixture()
        val exporter = f.repository.portableTransfers()
        val archive = runBlocking { exporter.prepareExport(f.key, password.toCharArray()) }
        try {
            f.show()
            click("private-portable-import")
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, f.registry.action)
            f.lock()
            compose.runOnIdle { f.registry.returnUri(Uri.fromFile(archive.file)) }
            assertEquals(1, runBlocking { f.repository.count() })
            compose.onNodeWithTag("private-portable-password").assertDoesNotExist()
            f.unlock()
            awaitTag("private-portable-password")
            assertEquals(
                "",
                compose
                    .onNodeWithTag("private-portable-password")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text,
            )
            fill(confirm = false)
            click("private-portable-prepare")
            awaitTag("private-portable-review")
            assertEquals(1, runBlocking { f.repository.count() })
            f.lock()
            compose.waitForIdle()
            f.unlock()
            awaitTag("private-portable-password")
            assertEquals(
                "",
                compose
                    .onNodeWithTag("private-portable-password")
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text,
            )
            compose.onNodeWithTag("private-portable-review").assertDoesNotExist()
            compose.onNodeWithTag("private-portable-commit").assertDoesNotExist()
            // URI survived, but the old review/data keys did not: the password must be entered
            // again.
            fill(confirm = false)
            click("private-portable-prepare")
            awaitTag("private-portable-review")
            click("private-portable-commit")
            awaitTag("private-portable-restored")
            assertEquals(2, runBlocking { f.repository.count() })
            f.lock()
            f.unlock()
            awaitTag("private-portable-restored")
            assertEquals(2, runBlocking { f.repository.count() })
            assertEquals(1, f.registry.launches)
        } finally {
            exporter.discard(archive)
            f.close()
        }
    }

    @Test
    fun hostBeforeGlobalEarlyReturnRetainsCreateResultAndExactPreparedCiphertext() {
        val f = Fixture()
        val documentId = UUID.randomUUID().toString()
        val output =
            File(base.cacheDir, "private-document-$documentId.ugpb").apply {
                writeBytes(byteArrayOf())
            }
        val uri =
            DocumentsContract.buildDocumentUri(
                base.packageName + ".privateportable.fixture",
                documentId,
            )
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        try {
            base.grantUriPermission(base.packageName, uri, flags)
            f.show(hoistedBeforeGlobalGuard = true)
            compose.onNodeWithTag("session-private-route").assertExists()
            click("private-portable-export")
            fill(confirm = true)
            click("private-portable-prepare")
            compose.waitUntil(30_000) { f.registry.code != null }
            val prepared =
                File(f.context.filesDir, "private-album").walkTopDown().single {
                    it.name == "private-backup.ugpb"
                }
            val hash = PrivatePortableArchive.hash(prepared)
            f.lock()
            compose.onNodeWithTag("session-global-guard").assertExists()
            compose.onNodeWithTag("session-private-route").assertDoesNotExist()
            compose.runOnIdle { f.registry.returnUri(uri) }
            compose.waitForIdle()
            assertEquals(0L, output.length())
            assertArrayEquals(hash, PrivatePortableArchive.hash(prepared))
            f.unlock()
            awaitTag("private-portable-saved")
            compose.onNodeWithTag("session-global-guard").assertDoesNotExist()
            compose.onNodeWithTag("session-private-route").assertExists()
            assertArrayEquals(hash, PrivatePortableArchive.hash(output))
            assertEquals(1, f.registry.launches)
            assertEquals(1, runBlocking { f.repository.count() })
        } finally {
            f.close()
            if (output.exists()) check(output.delete())
            base.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(30_000) {
            compose
                .onAllNodesWithTag(tag)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isNotEmpty()
        }
    }

    private fun click(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }

    private fun fill(confirm: Boolean) {
        compose
            .onNodeWithTag("private-portable-password")
            .performScrollTo()
            .performTextInput(password)
        if (confirm)
            compose
                .onNodeWithTag("private-portable-confirm-password")
                .performScrollTo()
                .performTextInput(password)
    }
}
