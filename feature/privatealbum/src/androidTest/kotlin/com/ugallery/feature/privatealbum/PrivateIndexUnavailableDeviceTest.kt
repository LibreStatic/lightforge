package com.ugallery.feature.privatealbum

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Genuine production opener failures, with the vault remaining locked throughout. */
class PrivateIndexUnavailableDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun missingKeyShowsUnavailableAndRetryRecoversOnlyAfterExactKeyRestitution() {
        exercise(missingKey = true, darkTheme = false)
    }

    @Test
    fun damagedIndexShowsUnavailableAndRetryPreservesDamagedBytes() {
        exercise(missingKey = false, darkTheme = true)
    }

    private fun exercise(missingKey: Boolean, darkTheme: Boolean) {
        val name = "private-ui-index-${UUID.randomUUID()}.db"
        check(name != PrivateAlbumDatabase.DatabaseName)
        val file = context.getDatabasePath(name)
        val key = PrivateIndexKey(context, name)
        val masterAlias = "ugallery.privatealbum.index-ui-master.${UUID.randomUUID()}"
        val heldKey = File(context.cacheDir, "private-ui-held-key-${UUID.randomUUID()}")
        var database: PrivateAlbumDatabase? = null
        var backCalls = 0
        var forbiddenCalls = 0
        val showContent = mutableStateOf(true)
        try {
            PrivateAlbumCrypto.getOrCreateMasterKey(masterAlias)
            val seed = PrivateAlbumDatabase.open(context, name)
            try {
                runBlocking {
                    seed
                        .metadataDao()
                        .upsert(
                            PrivateAlbumMetadataEntity(isSetup = true, keyAlias = masterAlias)
                        )
                    assertEquals(0, seed.privateMediaDao().count())
                }
            } finally {
                seed.close()
            }
            val pristineHash = hash(file)
            assertTrue(key.file.isFile)
            if (missingKey) {
                check(key.file.renameTo(heldKey))
            } else {
                RandomAccessFile(file, "rw").use {
                    val first = it.readByte()
                    it.seek(0)
                    it.writeByte(first.toInt() xor 0x5a)
                    it.fd.sync()
                }
            }
            val protectedHash = hash(file)
            if (missingKey) assertEquals(pristineHash, protectedHash)
            database = PrivateAlbumDatabase.open(context, name)
            val repository = PrivateAlbumRepository(context, requireNotNull(database))
            compose.setContent {
                if (showContent.value)
                    UGalleryTheme(darkTheme = darkTheme) {
                        PrivateAlbumContent(
                            repository = repository,
                            onBack = { backCalls++ },
                            onUnlockRequest = { _, _ -> forbiddenCalls++ },
                            onExport = { _, _, _ -> forbiddenCalls++ },
                            isUnlocked = false,
                            onUnlocked = { forbiddenCalls++ },
                            onAddRequest = { forbiddenCalls++ },
                        )
                    }
            }
            awaitUnavailable()
            assertOnlyUnavailableActions()
            assertEquals(protectedHash, hash(file))
            compose.onNodeWithTag("private-index-retry").performClick()
            awaitUnavailable()
            assertOnlyUnavailableActions()
            assertEquals(protectedHash, hash(file))
            if (missingKey) {
                assertFalse("Retry must not regenerate a missing wrapped key", key.file.exists())
                check(heldKey.renameTo(key.file))
                compose.onNodeWithTag("private-index-retry").performClick()
                compose.waitUntil(timeoutMillis = 30_000) {
                    compose
                        .onAllNodesWithTag("private-index-unavailable")
                        .fetchSemanticsNodes()
                        .isEmpty() &&
                        compose
                            .onAllNodesWithTag("private-index-loading")
                            .fetchSemanticsNodes()
                            .isEmpty()
                }
                compose
                    .onNodeWithText(context.getString(R.string.private_unlock))
                    .assertIsDisplayed()
                compose
                    .onNodeWithText(context.getString(R.string.private_setup_title))
                    .assertDoesNotExist()
                compose
                    .onNodeWithText(context.getString(R.string.private_empty_title))
                    .assertDoesNotExist()
                assertEquals(pristineHash, hash(file))
            }
            compose
                .onNodeWithContentDescription(context.getString(R.string.private_back))
                .performClick()
            compose.runOnIdle {
                assertEquals(1, backCalls)
                assertEquals("No setup/import/export/auth bypass callbacks", 0, forbiddenCalls)
            }
        } finally {
            compose.runOnIdle { showContent.value = false }
            compose.waitForIdle()
            database?.close()
            context.deleteDatabase(name)
            heldKey.delete()
            listOf("", ".bak", ".new").forEach { suffix -> File(key.file.path + suffix).delete() }
            File(context.noBackupFilesDir, "$name.index-migration.lock").delete()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(key.alias)
                deleteEntry(masterAlias)
            }
        }
    }

    private fun awaitUnavailable() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose
                .onAllNodesWithTag("private-index-unavailable")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("private-index-unavailable").assertIsDisplayed()
    }

    private fun assertOnlyUnavailableActions() {
        compose.onNodeWithTag("private-index-retry").assertIsDisplayed()
        compose
            .onNodeWithContentDescription(context.getString(R.string.private_back))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.private_setup_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.private_empty_title)).assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.private_choose_media))
            .assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.private_portable_title))
            .assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.private_unlock)).assertDoesNotExist()
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
}
