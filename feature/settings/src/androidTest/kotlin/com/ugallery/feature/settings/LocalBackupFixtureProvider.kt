package com.ugallery.feature.settings

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/** Test APK only. Its complete namespace lives under its private cache directory. */
class LocalBackupFixtureProvider : DocumentsProvider() {
    private val root
        get() = File(requireNotNull(context).cacheDir, "local-backup-fixture").apply { mkdirs() }

    private var corruptReads = false
    private var rejectWrites = false
    private var denyAccess = false
    @Volatile private var holdSource = false
    @Volatile private var sourceOpened = false
    private var sourceGate = java.util.concurrent.CountDownLatch(0)

    override fun onCreate() = true

    private fun file(id: String): File {
        require(id == "root" || id.startsWith("root/"))
        val path = if (id == "root") root else File(root, id.removePrefix("root/"))
        require(
            path.canonicalPath == root.canonicalPath ||
                path.canonicalPath.startsWith(root.canonicalPath + "/")
        )
        return path
    }

    private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).path

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(
                projection
                    ?: arrayOf(
                        DocumentsContract.Root.COLUMN_ROOT_ID,
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Root.COLUMN_TITLE,
                        DocumentsContract.Root.COLUMN_FLAGS,
                    )
            )
            .apply {
                val values =
                    mapOf<String, Any>(
                        DocumentsContract.Root.COLUMN_ROOT_ID to "fixture",
                        DocumentsContract.Root.COLUMN_DOCUMENT_ID to "root",
                        DocumentsContract.Root.COLUMN_TITLE to "UGallery isolated test",
                        DocumentsContract.Root.COLUMN_FLAGS to
                            DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
                    )
                addRow(columnNames.map { values[it] }.toTypedArray())
            }

    private fun cursor(projection: Array<out String>?, files: List<File>): Cursor =
        MatrixCursor(
                projection
                    ?: arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_FLAGS,
                    )
            )
            .apply {
                files.forEach { file ->
                    val values =
                        mapOf<String, Any>(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID to id(file),
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                            DocumentsContract.Document.COLUMN_MIME_TYPE to
                                if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
                                else "application/octet-stream",
                            DocumentsContract.Document.COLUMN_SIZE to file.length(),
                            DocumentsContract.Document.COLUMN_FLAGS to
                                (DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                    DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                    if (file.isDirectory)
                                        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                    else 0),
                        )
                    addRow(columnNames.map { values[it] }.toTypedArray())
                }
            }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        cursor(projection, listOf(file(documentId)))

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = cursor(projection, file(parentDocumentId).listFiles()?.toList() ?: emptyList())

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        if (denyAccess) throw SecurityException("Injected destination revocation")
        check(!rejectWrites)
        require(
            displayName.isNotBlank() &&
                displayName !in setOf(".", "..") &&
                displayName.none { it == '/' || it == '\\' || it.code < 32 }
        )
        var name = displayName
        var counter = 0
        while (File(file(parentDocumentId), name).exists()) {
            counter++
            name = "$counter-$displayName"
        }
        val target = File(file(parentDocumentId), name)
        check(
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) target.mkdir()
            else target.createNewFile()
        )
        return id(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + "/")

    override fun deleteDocument(documentId: String) {
        if (denyAccess) throw SecurityException("Injected destination revocation")
        require(documentId != "root")
        check(file(documentId).deleteRecursively())
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (denyAccess && mode != "r") throw SecurityException("Injected destination revocation")
        if (mode != "r") check(!rejectWrites)
        if (mode == "r" && holdSource && documentId.endsWith("slow.jpg")) {
            val pipe = ParcelFileDescriptor.createPipe()
            sourceOpened = true
            val gate = sourceGate
            Thread {
                    try {
                        gate.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                            file(documentId).inputStream().use { it.copyTo(output) }
                        }
                    } catch (_: Exception) {
                        runCatching { pipe[1].close() }
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
            return pipe[0]
        }

        val target =
            if (mode == "r" && corruptReads && documentId.contains("UGallery-restored-"))
                File(requireNotNull(context).cacheDir, "corrupt-read").apply {
                    writeBytes(byteArrayOf(99))
                }
            else file(documentId)
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        when (method) {
            "fixture-reset" -> {
                sourceGate.countDown()
                holdSource = false
                sourceOpened = false
                root.deleteRecursively()
                root.mkdirs()
                corruptReads = false
                rejectWrites = false
                denyAccess = false
            }
            "fixture-hold-source" -> {
                sourceGate = java.util.concurrent.CountDownLatch(1)
                holdSource = true
            }
            "fixture-release-source" -> sourceGate.countDown()
            "fixture-source-state" -> return Bundle().apply { putBoolean("opened", sourceOpened) }
            "fixture-corrupt-restored-reads" -> corruptReads = true
            "fixture-reject-writes" -> rejectWrites = true
            "fixture-deny-access" -> denyAccess = true
            "fixture-allow-access" -> denyAccess = false
            else -> return super.call(method, arg, extras) ?: Bundle.EMPTY
        }
        return Bundle.EMPTY
    }
}
