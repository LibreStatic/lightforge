package com.ugallery.feature.remotebackup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

class RemoteBackupFixtureProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/pdf"

    private fun file(uri: Uri): File {
        val id = requireNotNull(uri.lastPathSegment)
        require(UUID.fromString(id).toString() == id)
        return File(context!!.cacheDir, "remote-source-$id")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) =
        MatrixCursor(projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .apply {
                addRow(
                    columnNames.map {
                        if (it == OpenableColumns.DISPLAY_NAME) "fixture.pdf"
                        else file(uri).length()
                    }
                )
            }

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
