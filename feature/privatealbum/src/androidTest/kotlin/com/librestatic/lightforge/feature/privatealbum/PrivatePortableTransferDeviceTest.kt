package com.librestatic.lightforge.feature.privatealbum

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import com.librestatic.lightforge.core.security.PrivatePortableArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class PrivatePortableTransferDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password get() = "Private fixture password 2026".toCharArray()
    private fun database() = Room.inMemoryDatabaseBuilder(context, PrivateAlbumDatabase::class.java).build()
    private fun original(): File {
        val file = File(context.cacheDir, "private-original-" + UUID.randomUUID() + ".jpg")
        val bitmap = Bitmap.createBitmap(41, 37, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(32, 168, 88))
        try { FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) } } finally { bitmap.recycle() }
        return file
    }
    private suspend fun add(db: PrivateAlbumDatabase, master: SecretKey, original: File): Long {
        val result = PrivateAlbumRepository(context, db).importFromUri(Uri.fromFile(original), "private-owned-cat.jpg", "image/jpeg", "image", 41, 37, masterKey = master)
        assertTrue(result.error, result.success)
        return requireNotNull(result.mediaId)
    }
    @Test fun encryptedBackupRestoresNewKeysAndDurableReceiptWithoutPublicPlaintext() = runBlocking {
        val sourceDb = database()
        val targetName = "private-restore-test-" + UUID.randomUUID() + ".db"
        var targetDb = Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, targetName).build()
        val sourceTransfer = PrivatePortableTransfer(context, sourceDb)
        var targetTransfer = PrivatePortableTransfer(context, targetDb)
        val original = original()
        val originalHash = PrivatePortableArchive.hash(original)
        val masterA = PrivateAlbumCrypto.generateDataKey()
        val masterB = PrivateAlbumCrypto.generateDataKey()
        var exported: PrivatePortableTransfer.Export? = null
        val createdContainers = mutableListOf<File>()
        try {
            val id = add(sourceDb, masterA, original)
            val old = requireNotNull(sourceDb.privateMediaDao().getById(id))
            createdContainers += File(old.containerPath)
            exported = sourceTransfer.prepareExport(masterA, password)
            val export = requireNotNull(exported)
            assertFalse(String(export.file.readBytes(), Charsets.ISO_8859_1).contains("private-owned-cat"))
            val prepared = targetTransfer.prepareRestore(Uri.fromFile(export.file), password)
            assertEquals(1, prepared.items.size)
            assertEquals(0, targetDb.privateMediaDao().count())
            val result = targetTransfer.commit(prepared, masterB)
            assertEquals(1, result.count); assertFalse(result.alreadyCommitted)
            val row = targetDb.privateMediaDao().getPage(2, 0).single()
            createdContainers += File(row.containerPath)
            assertFalse(row.encryptedDataKey.contentEquals(old.encryptedDataKey))
            assertEquals(3, File(row.containerPath).inputStream().use { val bytes = ByteArray(5); it.read(bytes); bytes[4].toInt() })
            val key = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(row.encryptedDataKey, row.dataKeyIv), masterB)
            val decoded = ByteArrayOutputStream()
            FileInputStream(row.containerPath).use { PrivateAlbumCrypto.decryptStream(it, decoded, key, row.sha256) }
            assertArrayEquals(original.readBytes(), decoded.toByteArray())
            targetDb.close()
            targetDb = Room.databaseBuilder(context, PrivateAlbumDatabase::class.java, targetName).build()
            targetTransfer = PrivatePortableTransfer(context, targetDb)
            assertEquals(1, targetDb.privateMediaDao().count())
            assertNotNull(targetDb.portableRestoreDao().getReceiptByArchiveSha(export.sha256))
            targetDb.privateMediaDao().deleteById(row.id)
            val repeated = targetTransfer.prepareRestore(Uri.fromFile(export.file), password)
            assertNotNull(repeated.previousReceipt)
            assertTrue(targetTransfer.commit(repeated, masterB).alreadyCommitted)
            assertEquals("Historical receipt must not resurrect deleted items", 0, targetDb.privateMediaDao().count())
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(original))
        } finally {
            exported?.let(sourceTransfer::discard)
            sourceTransfer.clearOwnedStaging(); targetTransfer.clearOwnedStaging()
            sourceDb.close(); targetDb.close(); context.deleteDatabase(targetName)
            createdContainers.forEach { it.delete() }; original.delete()
        }
    }
    @Test fun wrongPasswordAndCancellationLeaveNoRowsOrUncommittedCiphertexts() = runBlocking {
        val db = database(); val target = database()
        val transfer = PrivatePortableTransfer(context, db); val importer = PrivatePortableTransfer(context, target)
        val original = original(); val key = PrivateAlbumCrypto.generateDataKey()
        var export: PrivatePortableTransfer.Export? = null
        val containers = mutableListOf<File>()
        try {
            val id = add(db, key, original); containers += File(requireNotNull(db.privateMediaDao().getById(id)).containerPath)
            export = transfer.prepareExport(key, password)
            val file = requireNotNull(export).file
            var wrongRejected = false
            try { importer.prepareRestore(Uri.fromFile(file), "Wrong fixture password!!".toCharArray()) } catch (_: IOException) { wrongRejected = true }
            assertTrue(wrongRejected)
            var cancelled = false
            try { importer.prepareRestore(Uri.fromFile(file), password) { _, _ -> throw CancellationException("owned cancellation") } }
            catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertEquals(0, target.privateMediaDao().count())
            importer.clearOwnedStaging()
            val remaining = File(context.filesDir, "private-album").listFiles().orEmpty().filter { it.name.startsWith("portable-restore-") }
            assertTrue("No incomplete private restore staging", remaining.isEmpty())
        } finally {
            export?.let(transfer::discard); transfer.clearOwnedStaging(); importer.clearOwnedStaging()
            containers.forEach { it.delete() }; original.delete(); db.close(); target.close()
        }
    }
    @Test fun preexistingSafBytesAreRejectedWithoutTruncationOrDeletion() = runBlocking {
        val db = database(); val transfer = PrivatePortableTransfer(context, db)
        val original = original(); val key = PrivateAlbumCrypto.generateDataKey()
        val documentId = UUID.randomUUID().toString()
        val existing = File(context.cacheDir, "private-document-$documentId.ugpb")
        val untouched = "Preexisting user document must remain byte exact".toByteArray()
        existing.writeBytes(untouched)
        var container: File? = null
        try {
            val id = add(db, key, original); container = File(requireNotNull(db.privateMediaDao().getById(id)).containerPath)
            val export = transfer.prepareExport(key, password)
            val uri = android.provider.DocumentsContract.buildDocumentUri(context.packageName + ".privateportable.fixture", documentId)
            PrivatePortableTestDocumentsProvider.modes.clear(); PrivatePortableTestDocumentsProvider.deletes.clear()
            var rejected = false
            try { transfer.publish(export, uri) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertArrayEquals(untouched, existing.readBytes())
            assertEquals(listOf("rw"), PrivatePortableTestDocumentsProvider.modes.toList())
            assertTrue(PrivatePortableTestDocumentsProvider.deletes.isEmpty())
        } finally { transfer.clearOwnedStaging(); container?.delete(); original.delete(); existing.delete(); db.close() }
    }

    @Test fun abandonedJournalDeletesOnlyUnreferencedTargetsAndSkipsActiveLease() = runBlocking {
        val db = database()
        val root = File(context.cacheDir, "private-journal-fixture-" + UUID.randomUUID()).also { assertTrue(it.mkdir()) }
        val journal = PrivatePortableJournal.create(root, "restore")
        try {
            journal.beforeRename(2)
            val key = PrivateAlbumCrypto.generateDataKey()
            val files = (0..1).map { File(root, "restored-${journal.id}-$it.ugpc") }
            val metadata = files.map { file -> FileOutputStream(file).use { PrivateAlbumCrypto.encryptStream("owned original".byteInputStream(), it, key, "image/jpeg") } }
            db.privateMediaDao().insert(PrivateMediaEntity(
                originalMediaKey = "owned", originalMimeType = "image/jpeg", originalDisplayName = "owned.jpg", containerPath = files[0].absolutePath,
                containerSizeBytes = files[0].length(), encryptedDataKey = ByteArray(48), dataKeyIv = ByteArray(12), chunkSize = metadata[0].chunkSize,
                totalChunks = metadata[0].totalChunks, ivBase = metadata[0].ivBase, sha256 = metadata[0].sha256, addedAtMillis = 1, mediaKind = "image",
            ))
            val hash = PrivatePortableArchive.hash(files[0])
            assertTrue(PrivatePortableJournal.recover(context, root, db.portableRestoreDao()).isEmpty())
            assertTrue(files.all { it.exists() })
            journal.closeLease() // Simulates a dead process releasing its OS file lock; no database cleanup is faked.
            assertTrue(PrivatePortableJournal.recover(context, root, db.portableRestoreDao()).isEmpty())
            assertTrue(files[0].exists()); assertFalse(files[1].exists())
            assertArrayEquals(hash, PrivatePortableArchive.hash(files[0]))
            assertFalse(journal.directory.exists())
        } finally { journal.closeLease(); root.deleteRecursively(); db.close() }
    }

    @Test fun rejectedEmptyAlbumStillWipesPublicApiPassword() = runBlocking {
        val db = database()
        val transfer = PrivatePortableTransfer(context, db)
        val secret = password
        try {
            var rejected = false
            try { transfer.prepareExport(PrivateAlbumCrypto.generateDataKey(), secret) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue("Even preflight rejection must wipe caller-owned password", secret.all { it == '\u0000' })
        } finally { transfer.clearOwnedStaging(); db.close() }
    }

    @Test fun cleanupReadDeadlineCancelsProviderAndDoesNotDeleteUnknownDocument() = runBlocking {
        val id = UUID.randomUUID().toString()
        val archive = File(context.cacheDir, "private-cleanup-source-$id").apply { writeText("unique owned encrypted prefix fixture") }
        val uri = android.provider.DocumentsContract.buildDocumentUri(context.packageName + ".privateportable.fixture", id)
        PrivatePortableTestDocumentsProvider.blockedDocument = id
        PrivatePortableTestDocumentsProvider.cancellationObserved = false
        PrivatePortableTestDocumentsProvider.deletes.clear()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            var timedOut = false
            try { PrivatePortableJournal.requireOwnedPrefix(context, uri, archive, timeoutMillis = 200) }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { timedOut = true }
            assertTrue(timedOut)
            assertTrue("Cleanup must remain bounded", android.os.SystemClock.elapsedRealtime() - started < 5000)
            // ContentResolver disconnects the remote CancellationSignal after open returns.
            // Verify the actual returned reader was closed, not a post-open signal callback.
            var readerClosed = false
            try { android.system.Os.write(requireNotNull(PrivatePortableTestDocumentsProvider.blockedWriter).fileDescriptor, byteArrayOf(1), 0, 1) }
            catch (error: android.system.ErrnoException) {
                if (error.errno != android.system.OsConstants.EPIPE) throw error
                readerClosed = true
            }
            assertTrue("Cancelled returned FD must close the pipe reader", readerClosed)
            PrivatePortableTestDocumentsProvider.blockedWriter?.close()
            PrivatePortableTestDocumentsProvider.blockedWriter = null
            PrivatePortableTestDocumentsProvider.blockedDocument = null
            PrivatePortableTestDocumentsProvider.blockedOpenDocument = id
            PrivatePortableTestDocumentsProvider.cancellationObserved = false
            val openStarted = android.os.SystemClock.elapsedRealtime()
            timedOut = false
            try { PrivatePortableJournal.requireOwnedPrefix(context, uri, archive, timeoutMillis = 200) }
            catch (_: kotlinx.coroutines.TimeoutCancellationException) { timedOut = true }
            assertTrue("Opening the provider must also time out", timedOut)
            assertTrue("Provider open cancellation must remain bounded", android.os.SystemClock.elapsedRealtime() - openStarted < 5000)
            assertTrue("During open, the provider cancellation signal must be delivered", PrivatePortableTestDocumentsProvider.cancellationObserved)
            assertTrue(PrivatePortableTestDocumentsProvider.deletes.isEmpty())
        } finally {
            PrivatePortableTestDocumentsProvider.blockedOpenDocument = null
            PrivatePortableTestDocumentsProvider.blockedDocument = null
            PrivatePortableTestDocumentsProvider.blockedWriter?.close()
            PrivatePortableTestDocumentsProvider.blockedWriter = null
            archive.delete()
        }
    }

    @Test fun actualFinalSymlinkNeverDeletesOutsideBytesAndJournalRemainsRecoverable() = runBlocking {
        val db = database()
        val root = File(context.cacheDir, "private-symlink-fixture-" + UUID.randomUUID()).also { assertTrue(it.mkdir()) }
        val outside = File(context.cacheDir, "private-symlink-original-" + UUID.randomUUID()).apply { writeText("owned source remains intact") }
        val originalHash = PrivatePortableArchive.hash(outside)
        val journal = PrivatePortableJournal.create(root, "restore")
        val link = File(journal.directory, "unexpected-link")
        try {
            android.system.Os.symlink(outside.absolutePath, link.absolutePath)
            journal.closeLease()
            val residuals = PrivatePortableJournal.recover(context, root, db.portableRestoreDao())
            assertEquals(1, residuals.size)
            assertTrue(File(journal.directory, "journal.json").exists())
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(outside))
            assertTrue("Remove only the owned symlink, never its target", link.delete())
            assertTrue(PrivatePortableJournal.recover(context, root, db.portableRestoreDao()).isEmpty())
            assertFalse(journal.directory.exists())
            assertArrayEquals(originalHash, PrivatePortableArchive.hash(outside))
        } finally { journal.closeLease(); link.delete(); root.deleteRecursively(); outside.delete(); db.close() }
    }

}
