package com.ugallery.feature.collage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Registered only in the isolated module test APK; exposes only explicitly registered fixtures. */
class CreationCollageTestProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(Binder.getCallingUid() == Process.myUid() && mode == "r")
        if (uri.toString() in revoked) throw SecurityException("Owned fixture access revoked")
        val file = files[uri.toString()] ?: throw java.io.FileNotFoundException("Unknown fixture")
        gates.remove(uri.toString())?.let { gate ->
            gate.entered.countDown()
            check(gate.release.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Fixture read gate timed out" }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check(Binder.getCallingUid() == Process.myUid())
        val file = files[uri.toString()] ?: throw java.io.FileNotFoundException("Unknown fixture")
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(file.name, file.length()))
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        class ReadGate {
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
        }
        private val gates = ConcurrentHashMap<String, ReadGate>()
        fun pauseNextRead(uri: Uri) = ReadGate().also { gates[uri.toString()] = it }
        private val files = ConcurrentHashMap<String, File>()
        private val revoked = ConcurrentHashMap.newKeySet<String>()
        fun register(context: Context, file: File): Uri {
            val canonical = file.canonicalFile
            require(canonical.parentFile?.parentFile == context.cacheDir.canonicalFile &&
                canonical.parentFile?.name?.startsWith("creation-collage-provider-") == true)
            val uri = Uri.parse("content://com.ugallery.feature.collage.test.creationfixture/${UUID.randomUUID()}")
            files[uri.toString()] = canonical
            return uri
        }
        fun revoke(uri: Uri) { revoked += uri.toString() }
        fun remove(uri: Uri) { gates.remove(uri.toString())?.release?.countDown(); revoked -= uri.toString(); files.remove(uri.toString()) }
    }
}
