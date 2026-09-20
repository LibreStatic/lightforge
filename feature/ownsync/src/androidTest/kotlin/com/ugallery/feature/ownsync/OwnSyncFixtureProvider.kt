package com.ugallery.feature.ownsync

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File

/** Test APK only. Exposes exclusively deterministic private fixture bytes. */
class OwnSyncFixtureProvider : ContentProvider() {
    private var loading = false
    private var changed = false
    private var removed = false

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(
            method in listOf("fixture-loading", "fixture-reset", "fixture-change", "fixture-remove")
        )
        loading = method == "fixture-loading"
        if (method == "fixture-reset") {
            changed = false
            removed = false
        }
        if (method == "fixture-change") changed = true
        if (method == "fixture-remove") removed = true
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns =
            projection
                ?: arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
        val children = uri.pathSegments.last() == "children"
        val id =
            if (children) uri.pathSegments[uri.pathSegments.lastIndex - 1]
            else uri.lastPathSegment!!
        val ids =
            if (children)
                when (id) {
                    "root" -> listOf("folder", "top")
                    "folder" -> if (removed) emptyList() else listOf("nested")
                    else -> emptyList()
                }
            else listOf(id)
        return MatrixCursor(columns).apply {
            ids.forEach { item ->
                addRow(
                    columns
                        .map<String, Any?> { col ->
                            when (col) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> item
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                                    when (item) {
                                        "folder" -> "Photos"
                                        "nested" -> "nested.png"
                                        "top" -> "top.pdf"
                                        else -> "root"
                                    }
                                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                                    if (item in listOf("folder", "root"))
                                        DocumentsContract.Document.MIME_TYPE_DIR
                                    else if (item == "nested") "image/png" else "application/pdf"
                                DocumentsContract.Document.COLUMN_SIZE ->
                                    if (item in listOf("folder", "root")) 0L
                                    else bytes(item).size.toLong()
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L
                                else -> null
                            }
                        }
                        .toTypedArray()
                )
            }
            extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, loading) }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val id = uri.lastPathSegment!!
        require(id in listOf("top", "nested"))
        val file = File(context!!.cacheDir, "own-sync-provider-$id")
        file.writeBytes(bytes(id))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun bytes(id: String) =
        if (id == "top") "%PDF fixture".toByteArray()
        else if (changed) byteArrayOf(9, 8, 7, 6) else byteArrayOf(1, 2, 3, 4)

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
