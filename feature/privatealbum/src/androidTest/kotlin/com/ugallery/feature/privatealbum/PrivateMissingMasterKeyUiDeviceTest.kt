package com.ugallery.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Missing media-master key is not a fresh vault, even when the encrypted index remains readable. */
class PrivateMissingMasterKeyUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingUuidMasterShowsUnavailableAndRetryPreservesReadableIndexAndCiphertext(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "private-missing-master-ui-$token").apply { check(mkdirs()) }
        val context = IsolatedContext(base, root)
        val databaseName = "private-missing-master-ui-$token.db"
        val alias = "ugallery.privatealbum.missing-ui.$token"
        check(databaseName != PrivateAlbumDatabase.DatabaseName && alias != PrivateAlbumCrypto.MASTER_KEY_ALIAS)
        val indexKey = PrivateIndexKey(context, databaseName)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var database: PrivateAlbumDatabase? = null
        var ownsAliases = false
        val visible = mutableStateOf(true)
        var backCalls = 0
        var forbiddenCalls = 0
        try {
            assertFalse("UUID master must be newly owned", store.containsAlias(alias))
            assertFalse("UUID index alias must be newly owned", store.containsAlias(indexKey.alias))
            ownsAliases = true
            val source = File(context.filesDir, "original.jpg")
            Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.rgb(42, 101, 160))
                try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
                finally { bitmap.recycle() }
            }
            val master = PrivateAlbumCrypto.getOrCreateMasterKey(alias)
            val seeded = PrivateAlbumDatabase.open(context, databaseName)
            try {
                val seedRepository = PrivateAlbumRepository(context, seeded)
                seedRepository.setup(master, alias)
                val imported = seedRepository.importFromUri(Uri.fromFile(source), "original.jpg", "image/jpeg", "image",
                    width = 8, height = 6, masterKey = master)
                assertTrue("Real encrypted fixture import must succeed", imported.success)
                assertEquals(1, seeded.privateMediaDao().count())
                seedRepository.requireMasterKey()
            } finally { seeded.close() }

            val opened = PrivateAlbumDatabase.open(context, databaseName)
            database = opened
            val metadata = opened.metadataDao().get()!!
            val item = opened.privateMediaDao().getPage(1, 0).single()
            val rowFingerprint = fingerprint(item)
            val container = File(item.containerPath)
            check(container.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            val sourceHash = hash(source)
            val cipherHash = hash(container)
            val indexHash = hash(context.getDatabasePath(databaseName))
            val indexKeyHash = hash(indexKey.file)
            val containerFiles = container.parentFile!!.listFiles()!!.map { it.name }.sorted()
            assertEquals(alias, metadata.keyAlias)
            assertTrue(metadata.isSetup)
            // Delete only this test's UUID master. Its independent index alias/key stays valid.
            store.deleteEntry(alias)
            assertFalse(store.containsAlias(alias))
            assertTrue(store.containsAlias(indexKey.alias))
            assertEquals(metadata, opened.metadataDao().get())
            assertEquals(1, opened.privateMediaDao().count())
            val repository = PrivateAlbumRepository(context, opened)
            compose.setContent {
                if (visible.value) UGalleryTheme(darkTheme = false) {
                    PrivateAlbumContent(
                        repository = repository,
                        onBack = { backCalls++; visible.value = false },
                        onUnlockRequest = { _, _ -> forbiddenCalls++ },
                        onExport = { _, _, _ -> forbiddenCalls++ },
                        // Even a stale UI unlock cannot bypass missing cryptographic key material.
                        isUnlocked = true,
                        onUnlocked = { forbiddenCalls++ },
                        onAddRequest = { forbiddenCalls++ },
                        onPortableRequest = { forbiddenCalls++ },
                    )
                }
            }
            awaitUnavailable()
            assertUnavailableOnly(base)
            compose.onNodeWithTag("private-index-retry").performClick()
            awaitUnavailable()
            assertUnavailableOnly(base)
            assertFalse("Retry must never regenerate the missing UUID master", store.containsAlias(alias))
            assertTrue("The independently readable index key is retained", store.containsAlias(indexKey.alias))
            assertEquals(metadata, opened.metadataDao().get())
            assertEquals(1, opened.privateMediaDao().count())
            assertEquals(rowFingerprint, fingerprint(opened.privateMediaDao().getById(item.id)!!))
            assertEquals(sourceHash, hash(source))
            assertEquals(cipherHash, hash(container))
            assertEquals(indexHash, hash(context.getDatabasePath(databaseName)))
            assertEquals(indexKeyHash, hash(indexKey.file))
            assertEquals(containerFiles, container.parentFile!!.listFiles()!!.map { it.name }.sorted())
            compose.onNodeWithContentDescription(base.getString(R.string.private_back)).performClick()
            compose.runOnIdle { assertEquals(1, backCalls); assertEquals(0, forbiddenCalls) }
            assertFalse(store.containsAlias(alias))
            assertEquals(metadata, opened.metadataDao().get())
            assertEquals(rowFingerprint, fingerprint(opened.privateMediaDao().getById(item.id)!!))
            assertEquals(sourceHash, hash(source))
            assertEquals(cipherHash, hash(container))
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            database?.close()
            // Both names are derived solely from this UUID fixture; never delete global aliases.
            if (ownsAliases) { store.deleteEntry(alias); store.deleteEntry(indexKey.alias) }
            root.deleteRecursively()
        }
    }

    private fun awaitUnavailable() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("private-index-unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("private-index-unavailable").assertIsDisplayed()
    }

    private fun assertUnavailableOnly(context: Context) {
        compose.onNodeWithTag("private-index-retry").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.private_back)).assertIsDisplayed()
        listOf(R.string.private_setup_title, R.string.private_empty_title, R.string.private_choose_media,
            R.string.private_portable_title, R.string.private_unlock).forEach {
            compose.onNodeWithText(context.getString(it)).assertDoesNotExist()
        }
        compose.onNodeWithTag("private-portable-open").assertDoesNotExist()
    }

    // PrivateMediaEntity.equals compares only id; explicitly cover every persisted field here.
    private fun fingerprint(item: PrivateMediaEntity): List<Any> = listOf(item.id, item.originalMediaKey,
        item.originalMimeType, item.originalDisplayName, item.containerPath, item.containerSizeBytes,
        item.encryptedDataKey.toList(), item.dataKeyIv.toList(), item.chunkSize, item.totalChunks,
        item.ivBase.toList(), item.sha256.toList(), item.addedAtMillis, item.mediaKind,
        item.width, item.height, item.durationMillis)

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
        override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
    }
}
