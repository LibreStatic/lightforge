package com.ugallery.feature.settings

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBackupStorageDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver: ContentResolver
        get() = context.contentResolver

    private val authority = "com.ugallery.feature.settings.test.localbackup"
    private val root
        get() = DocumentsContract.buildDocumentUri(authority, "root")

    private val tree
        get() = DocumentsContract.buildTreeDocumentUri(authority, "root")

    private val cacheFiles = mutableListOf<File>()

    private fun command(name: String) {
        resolver.call(Uri.parse("content://$authority"), name, null, null)
    }

    @Before
    fun setUp() {
        command("fixture-reset")
    }

    @After
    fun cleanup() {
        command("fixture-reset")
        cacheFiles.forEach { it.delete() }
    }

    private fun created(name: String, bytes: ByteArray): Uri {
        val uri =
            requireNotNull(
                DocumentsContract.createDocument(resolver, root, "application/octet-stream", name)
            )
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        return uri
    }

    private fun children(parent: Uri = root): List<Uri> {
        val id = DocumentsContract.getDocumentId(parent)
        val uri = DocumentsContract.buildChildDocumentsUri(authority, id)
        return resolver
            .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        DocumentsContract.buildDocumentUri(authority, c.getString(0))
                    )
                }
            }
    }

    private fun bytes(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun file(storage: LocalBackupStorage) = storage.newArchive().also { cacheFiles += it }

    @Test
    fun actualSafBackupInspectionAndRestorePreserveOriginalsAndExistingDestination() {
        val storage = LocalBackupStorage(context)
        val firstBytes = ByteArray(200_000) { (it % 251).toByte() }
        val first = created("photo.jpg", firstBytes)
        val second = created("document.pdf", byteArrayOf(1, 2, 3, 4))
        val existing = created("keep.txt", byteArrayOf(7, 8))
        val archive = file(storage)
        val manifest =
            LocalBackupArchive.create(
                listOf(storage.source(first), storage.source(second)),
                archive,
            )
        val backupUri = created("verified.ugallery.zip", byteArrayOf())
        storage.publish(archive, backupUri) {}
        val imported = file(storage)
        val checked = storage.importArchive(backupUri, imported) {}
        assertEquals(manifest, checked)
        val target = storage.restoreTarget(tree, checked) {}
        LocalBackupArchive.restore(imported, checked, target)
        val folder =
            children().single { DocumentsContract.getDocumentId(it).contains(target.folderName) }
        val restored = children(folder).map(::bytes)
        assertEquals(2, restored.size)
        assertTrue(restored.any { it.contentEquals(firstBytes) })
        assertTrue(restored.any { it.contentEquals(byteArrayOf(1, 2, 3, 4)) })
        assertArrayEquals(firstBytes, bytes(first))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes(second))
        assertArrayEquals(byteArrayOf(7, 8), bytes(existing))
    }

    @Test
    fun corruptedDestinationReadbackRejectsSuccessAndRemovesOnlyNewRestoreFolder() {
        val storage = LocalBackupStorage(context)
        val original = created("keep.jpg", byteArrayOf(1, 2, 3))
        val archive = file(storage)
        val manifest = LocalBackupArchive.create(listOf(storage.source(original)), archive)
        command("fixture-corrupt-restored-reads")
        val target = storage.restoreTarget(tree, manifest) {}
        assertThrows(IOException::class.java) {
            LocalBackupArchive.restore(archive, manifest, target)
        }
        assertEquals(listOf(original), children())
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes(original))
    }

    @Test
    fun cancelledRestoreRemovesCreatedFilesAndLeavesSources() {
        val storage = LocalBackupStorage(context)
        val first = created("first.jpg", byteArrayOf(1))
        val second = created("second.jpg", byteArrayOf(2))
        val archive = file(storage)
        val manifest =
            LocalBackupArchive.create(
                listOf(storage.source(first), storage.source(second)),
                archive,
            )
        var cancel = false
        val check = { if (cancel) throw CancellationException() }
        val target = storage.restoreTarget(tree, manifest, check)
        assertThrows(CancellationException::class.java) {
            LocalBackupArchive.restore(archive, manifest, target, check, { cancel = true })
        }
        assertEquals(setOf(first, second), children().toSet())
        assertArrayEquals(byteArrayOf(1), bytes(first))
        assertArrayEquals(byteArrayOf(2), bytes(second))
    }

    @Test
    fun malformedImportIsRemovedBeforeAnyRestoreIsOffered() {
        val storage = LocalBackupStorage(context)
        val source = created("bad.zip", byteArrayOf(1, 2, 3, 4))
        val imported = file(storage)
        assertThrows(IOException::class.java) { storage.importArchive(source, imported) {} }
        assertFalse(imported.exists())
        assertEquals(listOf(source), children())
    }
}
