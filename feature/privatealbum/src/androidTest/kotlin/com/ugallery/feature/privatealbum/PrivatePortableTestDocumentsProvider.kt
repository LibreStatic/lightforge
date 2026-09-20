package com.ugallery.feature.privatealbum

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Test-only local SAF fixture; document identifiers are UUIDs and every byte belongs to the test. */
class PrivatePortableTestDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true
    private fun file(id: String): File {
        require(java.util.UUID.fromString(id).toString() == id)
        return File(requireNotNull(context).cacheDir, "private-document-$id.ugpb")
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException()
        return MatrixCursor(columns).apply { addRow(Array<Any?>(columns.size) { index -> when (columns[index]) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> f.name
            DocumentsContract.Document.COLUMN_SIZE -> f.length()
            DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/octet-stream"
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> null
        } }) }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        modes += mode
        if (documentId == blockedOpenDocument && mode == "r") {
            val cancelled = java.util.concurrent.CountDownLatch(1)
            signal?.setOnCancelListener { cancellationObserved = true; cancelled.countDown() }
            check(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Open fixture deadline was not cancelled" }
            requireNotNull(signal).throwIfCanceled()
            throw FileNotFoundException("Cancelled owned fixture")
        }
        if (documentId == blockedDocument && mode == "r") {
            val pipe = ParcelFileDescriptor.createPipe()
            blockedWriter = pipe[1]
            signal?.setOnCancelListener { cancellationObserved = true }
            return pipe[0]
        }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }
    override fun deleteDocument(documentId: String) {
        deletes += documentId
        if (!file(documentId).delete()) throw FileNotFoundException("Owned test document cleanup failed")
    }
    companion object {
        @Volatile var blockedOpenDocument: String? = null
        @Volatile var blockedDocument: String? = null
        @Volatile var blockedWriter: ParcelFileDescriptor? = null
        @Volatile var cancellationObserved = false
        val modes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
}
