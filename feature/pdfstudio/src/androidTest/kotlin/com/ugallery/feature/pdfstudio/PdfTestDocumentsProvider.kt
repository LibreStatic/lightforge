package com.ugallery.feature.pdfstudio

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.util.UUID

/** A real local SAF provider confined to the instrumentation fixture application. */
class PdfTestDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true

    private fun file(id: String): File {
        UUID.fromString(id)
        return File(requireNotNull(context).filesDir, "test-documents/$id").also {
            it.parentFile!!.mkdirs()
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c =
            MatrixCursor(
                projection
                    ?: arrayOf(
                        Root.COLUMN_ROOT_ID,
                        Root.COLUMN_DOCUMENT_ID,
                        Root.COLUMN_TITLE,
                        Root.COLUMN_FLAGS,
                        Root.COLUMN_MIME_TYPES,
                    )
            )
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, "root")
            .add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "PDF test documents")
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_MIME_TYPES, "application/pdf")
        return c
    }

    override fun queryDocument(id: String, projection: Array<out String>?): Cursor {
        val c =
            MatrixCursor(
                projection
                    ?: arrayOf(
                        Document.COLUMN_DOCUMENT_ID,
                        Document.COLUMN_DISPLAY_NAME,
                        Document.COLUMN_MIME_TYPE,
                        Document.COLUMN_FLAGS,
                        Document.COLUMN_SIZE,
                    )
            )
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, "Test PDF")
            .add(
                Document.COLUMN_MIME_TYPE,
                if (id == "root") Document.MIME_TYPE_DIR else "application/pdf",
            )
            .add(
                Document.COLUMN_FLAGS,
                if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE
                else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE,
            )
            .add(Document.COLUMN_SIZE, if (id == "root") 0 else file(id).length())
        return c
    }

    override fun queryChildDocuments(
        parent: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID))

    override fun createDocument(parent: String, mime: String, name: String): String {
        require(parent == "root")
        val id = UUID.randomUUID().toString()
        file(id).createNewFile()
        return id
    }

    override fun openDocument(
        id: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (
            (mode.contains("w") &&
                File(requireNotNull(context).filesDir, "pdf-destination-denied").exists()) ||
                File(requireNotNull(context).filesDir, "pdf-destination-read-denied").exists()
        )
            throw SecurityException("Fixture destination unavailable")
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode))
    }

    override fun deleteDocument(id: String) {
        if (File(requireNotNull(context).filesDir, "pdf-destination-delete-denied").exists())
            throw SecurityException("Fixture cleanup unavailable")
        file(id).delete()
    }

    companion object {
        const val AUTHORITY = "com.ugallery.feature.pdfstudio.test.documents"
    }
}
