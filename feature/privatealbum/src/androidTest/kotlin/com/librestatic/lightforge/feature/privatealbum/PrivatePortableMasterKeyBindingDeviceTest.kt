package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import com.librestatic.lightforge.core.security.PrivatePortableArchive
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Stale alias at method entry: uses only UUID-scoped Keystore entries, files and an in-memory DB. */
class PrivatePortableMasterKeyBindingDeviceTest {
    @Test fun aliasChangedBeforeCommitRejectsOldBindingAndPreservesExistingVault(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val fixture = File(base.cacheDir, "private-binding-$id").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(fixture, "files").apply { mkdirs() }
            override fun getCacheDir() = File(fixture, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(fixture, "no-backup").apply { mkdirs() }
        }
        val db = Room.inMemoryDatabaseBuilder(base, PrivateAlbumDatabase::class.java).build()
        val repository = PrivateAlbumRepository(context, db)
        val transfer = repository.portableTransfers()
        val aliasA = "lightforge.privatealbum.fixture.binding.$id.a"
        val aliasB = "lightforge.privatealbum.fixture.binding.$id.b"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val password = "Private binding fixture password 2026"
        fun tableSnapshot(table: String): List<List<Any?>> =
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                            else -> cursor.getString(column)
                        }
                    })
                }
            }
        fun filesSnapshot(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(root).path to PrivatePortableArchive.hash(it).toList() }
        try {
            repository.setupNewAlbum(aliasA)
            val source = File(context.cacheDir, "owned-photo.png")
            val bitmap = Bitmap.createBitmap(9, 7, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            val originalHash = PrivatePortableArchive.hash(source)
            val bindingA = repository.requireMasterKeyBinding()
            assertEquals(aliasA, bindingA.alias)
            val added = repository.importFromUri(Uri.fromFile(source), "owned-photo.png", "image/png", "image",
                width = 9, height = 7, masterKey = bindingA)
            assertTrue(added.error, added.success)
            val old = requireNotNull(db.privateMediaDao().getById(requireNotNull(added.mediaId)))
            val container = File(old.containerPath)
            val containerHash = PrivatePortableArchive.hash(container)
            val historicalReceipt = PrivateRestoreReceiptEntity(UUID.randomUUID().toString(), "a".repeat(64), 1, 1234)
            db.portableRestoreDao().insertReceipt(historicalReceipt)
            val export = transfer.prepareExport(bindingA.secretKey, password.toCharArray())
            val privateRoot = File(context.filesDir, "private-album")
            val filesBeforeRestore = filesSnapshot(privateRoot)
            val prepared = transfer.prepareRestore(Uri.fromFile(export.file), password.toCharArray())
            assertEquals(1, prepared.items.size)
            assertNull(prepared.previousReceipt)
            assertTrue(prepared.directory.exists())

            // Arrange an intervening alias change before entering commit. This is a fixture setup,
            // not an implementation or acceptance claim for the eventual master-key migration.
            val keyB = PrivateAlbumCrypto.getOrCreateMasterKey(aliasB)
            val dataKey = PrivateAlbumCrypto.decryptDataKey(
                PrivateAlbumCrypto.EncryptedDataKey(old.encryptedDataKey, old.dataKeyIv), bindingA.secretKey)
            val wrappedB = PrivateAlbumCrypto.encryptDataKey(dataKey, keyB)
            db.withTransaction {
                db.privateMediaDao().insert(old.copy(encryptedDataKey = wrappedB.encryptedKey, dataKeyIv = wrappedB.iv))
                db.metadataDao().upsert(requireNotNull(db.metadataDao().get()).copy(keyAlias = aliasB))
            }
            assertEquals(aliasB, repository.requireMasterKeyBinding().alias)
            val mediaBefore = tableSnapshot("private_media")
            val metadataBefore = tableSnapshot("private_album_metadata")
            val receiptsBefore = tableSnapshot("private_restore_receipts")
            var rejected = false
            try { transfer.commit(prepared, bindingA) }
            catch (expected: IllegalStateException) {
                assertEquals("Private master key changed during restore", expected.message)
                rejected = true
            }
            assertTrue("A previously resolved A binding must not be relabeled as current B", rejected)
            assertEquals(mediaBefore, tableSnapshot("private_media"))
            assertEquals(metadataBefore, tableSnapshot("private_album_metadata"))
            assertEquals(receiptsBefore, tableSnapshot("private_restore_receipts"))
            assertEquals(1, db.privateMediaDao().count())
            assertNull(db.portableRestoreDao().getReceipt(prepared.id))
            assertNull(db.portableRestoreDao().getReceiptByArchiveSha(prepared.archiveSha256))
            assertEquals(historicalReceipt, db.portableRestoreDao().getReceipt(historicalReceipt.id))
            assertFalse("Rejected restore must remove only its own staging", prepared.directory.exists())
            assertEquals(filesBeforeRestore, filesSnapshot(privateRoot))
            assertArrayEquals(containerHash, PrivatePortableArchive.hash(container))
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(source))
            assertTrue(store.containsAlias(aliasA))
            assertTrue(store.containsAlias(aliasB))

            // The preserved archive remains usable after rejection: a newly resolved B binding
            // imports exactly one private copy with a durable receipt and verified plaintext.
            val retry = transfer.prepareRestore(Uri.fromFile(export.file), password.toCharArray())
            val currentBinding = repository.requireMasterKeyBinding()
            assertEquals(aliasB, currentBinding.alias)
            val accepted = transfer.commit(retry, currentBinding)
            assertFalse(accepted.alreadyCommitted)
            assertEquals(1, accepted.count)
            assertEquals(2, db.privateMediaDao().count())
            val restored = db.privateMediaDao().getPage(3, 0).single { it.id != old.id }
            val restoredKey = PrivateAlbumCrypto.decryptDataKey(
                PrivateAlbumCrypto.EncryptedDataKey(restored.encryptedDataKey, restored.dataKeyIv), currentBinding.secretKey)
            val plaintext = java.io.ByteArrayOutputStream()
            File(restored.containerPath).inputStream().use {
                PrivateAlbumCrypto.decryptStream(it, plaintext, restoredKey, restored.sha256)
            }
            assertArrayEquals(source.readBytes(), plaintext.toByteArray())
            assertArrayEquals(originalHash, restored.sha256)
            val receipt = requireNotNull(db.portableRestoreDao().getReceiptByArchiveSha(export.sha256))
            assertEquals(retry.id, receipt.id)
            assertEquals(1, receipt.itemCount)
            assertEquals(2, tableSnapshot("private_restore_receipts").size)
            assertEquals(historicalReceipt, db.portableRestoreDao().getReceipt(historicalReceipt.id))
            assertEquals(metadataBefore, tableSnapshot("private_album_metadata"))
            assertEquals(mediaBefore, tableSnapshot("private_media").take(1))
            assertFalse(retry.directory.exists())
            val filesAfterSuccess = filesSnapshot(privateRoot)
            assertEquals(filesBeforeRestore.keys + File(restored.containerPath).relativeTo(privateRoot).path,
                filesAfterSuccess.keys)
            filesBeforeRestore.forEach { (path, hash) -> assertEquals(hash, filesAfterSuccess[path]) }
            assertArrayEquals(containerHash, PrivatePortableArchive.hash(container))
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(source))
        } finally {
            try { transfer.clearOwnedStaging() }
            finally {
                db.close()
                store.deleteEntry(aliasA)
                store.deleteEntry(aliasB)
                fixture.deleteRecursively()
            }
        }
    }
}
