package com.ugallery.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** SAF is user selected. No network client, credential, cloud service or MediaStore mutation. */
class LocalBackupStorage(context: Context, sessionId: String = UUID.randomUUID().toString()) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val session = LocalBackupSession(File(this.context.cacheDir, "local-backup"), sessionId)
    private val cache = session.directory

    fun saveSelection(values: List<String>) = session.saveSelection(values)

    fun readSelection(): List<String> = session.readSelection()

    fun close() = session.close()

    fun newArchive(): File = File(cache, UUID.randomUUID().toString() + ".ugallery.zip")

    fun source(uri: Uri): LocalBackupArchive.Source {
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
        val fileName =
            name?.takeIf(BackupManifest::validName) ?: throw IOException("Invalid source name")
        return LocalBackupArchive.Source(
            fileName,
            resolver.getType(uri) ?: "application/octet-stream",
            uri.toString(),
        ) {
            resolver.openInputStream(uri) ?: throw IOException("Source unavailable")
        }
    }

    /** Verify the actual destination bytes, not just the source ZIP, before success. */
    fun publish(archive: File, destination: Uri, checkCancelled: () -> Unit) {
        val copied =
            archive.inputStream().use { input ->
                (resolver.openOutputStream(destination, "wt")
                        ?: throw IOException("Destination unavailable"))
                    .use {
                        LocalBackupArchive.copyChecked(input, it, MAX_ARCHIVE_BYTES, checkCancelled)
                    }
            }
        val verified =
            (resolver.openInputStream(destination) ?: throw IOException("Destination unreadable"))
                .use {
                    LocalBackupArchive.copyChecked(it, DISCARD, MAX_ARCHIVE_BYTES, checkCancelled)
                }
        if (copied != verified) throw IOException("Destination verification failed")
    }

    fun deleteCreatedDocument(destination: Uri) {
        if (!DocumentsContract.deleteDocument(resolver, destination))
            throw IOException("Incomplete backup cleanup failed")
    }

    fun importArchive(source: Uri, target: File, checkCancelled: () -> Unit): BackupManifest {
        try {
            (resolver.openInputStream(source) ?: throw IOException("Backup unavailable")).use {
                input ->
                target.outputStream().use {
                    LocalBackupArchive.copyChecked(input, it, MAX_ARCHIVE_BYTES, checkCancelled)
                }
            }
            return LocalBackupArchive.inspect(target, checkCancelled)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun restoreTarget(
        tree: Uri,
        manifest: BackupManifest,
        checkCancelled: () -> Unit,
    ): LocalRestoreFolder = LocalRestoreFolder(tree, manifest, checkCancelled)

    inner class LocalRestoreFolder(
        private val tree: Uri,
        private val manifest: BackupManifest,
        private val checkCancelled: () -> Unit,
    ) : LocalBackupArchive.RestoreTarget {
        private var folder: Uri? = null
        private val children = mutableListOf<Uri>()
        private var committed = false
        private var aborted = false
        val folderName = "UGallery-restored-" + UUID.randomUUID().toString()

        override fun open(name: String, mime: String): OutputStream {
            check(!committed && !aborted)
            BackupManifest.checkFormat(BackupManifest.validName(name))
            val directory =
                folder
                    ?: run {
                        val root =
                            DocumentsContract.buildDocumentUriUsingTree(
                                tree,
                                DocumentsContract.getTreeDocumentId(tree),
                            )
                        (DocumentsContract.createDocument(
                                resolver,
                                root,
                                DocumentsContract.Document.MIME_TYPE_DIR,
                                folderName,
                            ) ?: throw IOException("Restore folder unavailable"))
                            .also { folder = it }
                    }
            val document =
                DocumentsContract.createDocument(resolver, directory, mime, name)
                    ?: throw IOException("Restore file unavailable")
            children += document
            return resolver.openOutputStream(document, "wt")
                ?: throw IOException("Restore file unavailable")
        }

        override fun commit() {
            // Re-open every published original; providers may silently truncate or corrupt writes.
            verify(manifest, checkCancelled)
            committed = true
        }

        private fun verify(manifest: BackupManifest, checkCancelled: () -> Unit) {
            BackupManifest.checkFormat(children.size == manifest.entries.size)
            children.zip(manifest.entries).forEach { (uri, expected) ->
                val actual =
                    (resolver.openInputStream(uri)
                            ?: throw IOException("Restored file unavailable"))
                        .use {
                            LocalBackupArchive.copyChecked(
                                it,
                                DISCARD,
                                expected.bytes,
                                checkCancelled,
                            )
                        }
                if (actual != (expected.bytes to expected.sha256))
                    throw IOException("Restored file verification failed")
            }
        }

        override fun abort() {
            if (aborted) return
            var failed = false
            children.reversed().forEach { uri ->
                try {
                    if (!DocumentsContract.deleteDocument(resolver, uri)) failed = true
                } catch (_: Exception) {
                    failed = true
                }
            }
            folder?.let { uri ->
                try {
                    if (DocumentsContract.deleteDocument(resolver, uri)) failed = false
                    else failed = true
                } catch (_: Exception) {
                    failed = true
                }
            }
            if (failed) throw IOException("Restore cleanup incomplete: $folderName")
            aborted = true
        }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = BackupManifest.MAX_TOTAL_BYTES + 1024L * 1024 * 1024
        private val DISCARD =
            object : OutputStream() {
                override fun write(value: Int) {}

                override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            }
    }
}
