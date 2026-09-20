package com.ugallery.feature.privatealbum

import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.ugallery.core.security.PrivateAlbumCrypto
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real repository/Keystore operations; only UUID aliases and a private fixture filesystem. */
class PrivateMasterKeyPreservationDeviceTest {
    private class Fixture : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "private-master-preservation-$id").apply { check(mkdir()) }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        // Index encryption is tested separately. Avoid even opening the global index-key alias.
        val database =
            Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
        val repository = PrivateAlbumRepository(context, database)
        val alias = "ugallery.privatealbum.fixture.$id"
        val replacementAlias = "ugallery.privatealbum.fixture.replacement.$id"
        val containers
            get() = File(context.filesDir, "private-album")

        val keyStore
            get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        init {
            check(alias != PrivateAlbumCrypto.MASTER_KEY_ALIAS)
            check(replacementAlias != PrivateAlbumCrypto.MASTER_KEY_ALIAS)
            check(!keyStore.containsAlias(alias))
            check(!keyStore.containsAlias(replacementAlias))
        }

        override fun close() {
            database.close()
            // The exact aliases allocated by this fixture only; never deleteMasterKey().
            keyStore.deleteEntry(alias)
            keyStore.deleteEntry(replacementAlias)
            check(root.deleteRecursively())
        }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            return
        }
        fail("Operation must reject rather than create or replace a private master key")
    }

    private fun assertMetadata(
        expected: PrivateAlbumMetadataEntity,
        actual: PrivateAlbumMetadataEntity?,
    ) {
        val value = requireNotNull(actual)
        assertEquals(expected.id, value.id)
        assertEquals(expected.isSetup, value.isSetup)
        assertEquals(expected.keyAlias, value.keyAlias)
        assertEquals(expected.createdAtMillis, value.createdAtMillis)
    }

    private fun assertItem(expected: PrivateMediaEntity, actual: PrivateMediaEntity?) {
        val value = requireNotNull(actual)
        // Entity equality is ID-only: compare every field, including all key/container BLOBs.
        assertEquals(expected.id, value.id)
        assertEquals(expected.originalMediaKey, value.originalMediaKey)
        assertEquals(expected.originalMimeType, value.originalMimeType)
        assertEquals(expected.originalDisplayName, value.originalDisplayName)
        assertEquals(expected.containerPath, value.containerPath)
        assertEquals(expected.containerSizeBytes, value.containerSizeBytes)
        assertArrayEquals(expected.encryptedDataKey, value.encryptedDataKey)
        assertArrayEquals(expected.dataKeyIv, value.dataKeyIv)
        assertEquals(expected.chunkSize, value.chunkSize)
        assertEquals(expected.totalChunks, value.totalChunks)
        assertArrayEquals(expected.ivBase, value.ivBase)
        assertArrayEquals(expected.sha256, value.sha256)
        assertEquals(expected.addedAtMillis, value.addedAtMillis)
        assertEquals(expected.mediaKind, value.mediaKind)
        assertEquals(expected.width, value.width)
        assertEquals(expected.height, value.height)
        assertEquals(expected.durationMillis, value.durationMillis)
    }

    @Test
    fun lostUuidMasterNeverRegeneratesOrChangesPopulatedVault(): Unit = runBlocking {
        Fixture().use { fixture ->
            val repository = fixture.repository
            repository.setupNewAlbum(fixture.alias)
            assertTrue(fixture.keyStore.containsAlias(fixture.alias))
            assertTrue(repository.isSetup())
            val master = repository.requireMasterKey()
            val original =
                File(fixture.context.cacheDir, "owned.png").apply {
                    writeBytes(
                        android.util.Base64.decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                            android.util.Base64.DEFAULT,
                        )
                    )
                }
            val originalSha = sha(original)
            val imported =
                repository.importFromUri(
                    Uri.fromFile(original),
                    "owned.png",
                    "image/png",
                    "image",
                    1,
                    1,
                    masterKey = master,
                )
            assertTrue("Fixture import must succeed", imported.success)
            val mediaId = requireNotNull(imported.mediaId)
            val metadata = requireNotNull(fixture.database.metadataDao().get())
            assertEquals(fixture.alias, metadata.keyAlias)
            val item = requireNotNull(fixture.database.privateMediaDao().getById(mediaId))
            val ciphertext = File(item.containerPath)
            val ciphertextSha = sha(ciphertext)
            assertArrayEquals(originalSha, item.sha256)
            // Exercise the populated-vault key path before removing the fixture alias.
            repository.requireMasterKey()
            rejected { repository.setupNewAlbum(fixture.replacementAlias) }
            assertFalse(fixture.keyStore.containsAlias(fixture.replacementAlias))

            val keyB = PrivateAlbumCrypto.getOrCreateMasterKey(fixture.replacementAlias)
            rejected { repository.setup(keyB, fixture.replacementAlias) }
            assertMetadata(metadata, fixture.database.metadataDao().get())
            assertItem(item, fixture.database.privateMediaDao().getById(mediaId))
            assertArrayEquals(ciphertextSha, sha(ciphertext))
            fixture.keyStore.deleteEntry(fixture.replacementAlias)

            fixture.keyStore.deleteEntry(fixture.alias)
            repeat(3) {
                rejected { repository.requireMasterKey() }
                assertFalse(fixture.keyStore.containsAlias(fixture.alias))
                rejected { repository.setupNewAlbum(fixture.alias) }
                rejected { repository.setupNewAlbum(fixture.replacementAlias) }
                assertFalse(fixture.keyStore.containsAlias(fixture.alias))
                assertFalse(fixture.keyStore.containsAlias(fixture.replacementAlias))
                assertMetadata(metadata, fixture.database.metadataDao().get())
                assertItem(item, fixture.database.privateMediaDao().getById(mediaId))
                assertEquals(1, repository.count())
                assertArrayEquals(ciphertextSha, sha(ciphertext))
                assertArrayEquals(originalSha, sha(original))
                assertEquals(
                    listOf(ciphertext.name),
                    fixture.containers.listFiles()!!.map { it.name },
                )
            }
        }
    }

    @Test
    fun orphanedContainerRejectsSetupWithoutCreatingAnyMaster(): Unit = runBlocking {
        Fixture().use { fixture ->
            val orphan =
                File(fixture.containers.apply { check(mkdirs()) }, "owned-orphan.ugpc").apply {
                    writeBytes(ByteArray(4097) { (it * 31 + 7).toByte() })
                }
            val before = sha(orphan)
            assertFalse(fixture.repository.isSetup())
            repeat(2) {
                rejected { fixture.repository.setupNewAlbum(fixture.alias) }
                assertFalse(fixture.keyStore.containsAlias(fixture.alias))
                assertNull(fixture.database.metadataDao().get())
                assertEquals(0, fixture.repository.count())
                assertArrayEquals(before, sha(orphan))
                assertEquals(listOf(orphan.name), fixture.containers.listFiles()!!.map { it.name })
            }
        }
    }

    @Test
    fun staleMasterBindingRejectsImportAfterMetadataSwitchWithoutPartialCiphertext(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val repository = fixture.repository
                repository.setupNewAlbum(fixture.alias)
                val bindingA = repository.requireMasterKeyBinding()
                val initial = requireNotNull(fixture.database.metadataDao().get())
                PrivateAlbumCrypto.getOrCreateMasterKey(fixture.replacementAlias)
                val switched = initial.copy(keyAlias = fixture.replacementAlias)
                fixture.database.metadataDao().upsert(switched)
                repository.requireMasterKey() // B is a real, usable UUID-owned key.
                val source =
                    File(fixture.context.cacheDir, "owned-stale-binding.png").apply {
                        writeBytes(
                            android.util.Base64.decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                                android.util.Base64.DEFAULT,
                            )
                        )
                    }
                val before = sha(source)
                val result =
                    repository.importFromUri(
                        Uri.fromFile(source),
                        source.name,
                        "image/png",
                        "image",
                        1,
                        1,
                        masterKey = bindingA,
                    )
                assertFalse(
                    "Stale A binding must not write metadata belonging to B",
                    result.success,
                )
                assertNull(result.mediaId)
                assertEquals(0, repository.count())
                assertMetadata(switched, fixture.database.metadataDao().get())
                assertTrue(fixture.containers.listFiles()!!.isEmpty())
                assertArrayEquals(before, sha(source))
                assertTrue(fixture.keyStore.containsAlias(fixture.alias))
                assertTrue(fixture.keyStore.containsAlias(fixture.replacementAlias))
                repository.requireMasterKey()
            }
        }

    @Test
    fun incompleteMetadataIsPreservedAndNeverTreatedAsNewEmptyVault(): Unit = runBlocking {
        Fixture().use { fixture ->
            val metadata =
                PrivateAlbumMetadataEntity(
                    id = 0,
                    isSetup = false,
                    keyAlias = fixture.alias,
                    createdAtMillis = 1_700_000_123_456L,
                )
            fixture.database.metadataDao().upsert(metadata)
            assertFalse(fixture.repository.isSetup())
            repeat(2) {
                rejected { fixture.repository.setupNewAlbum(fixture.replacementAlias) }
                rejected { fixture.repository.setupNewAlbum(fixture.alias) }
                assertMetadata(metadata, fixture.database.metadataDao().get())
                assertEquals(0, fixture.repository.count())
                assertFalse(fixture.keyStore.containsAlias(fixture.alias))
                assertFalse(fixture.keyStore.containsAlias(fixture.replacementAlias))
                assertTrue(
                    !fixture.containers.exists() || fixture.containers.listFiles()!!.isEmpty()
                )
            }
        }
    }
}
