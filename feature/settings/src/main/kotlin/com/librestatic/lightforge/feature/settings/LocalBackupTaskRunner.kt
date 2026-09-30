package com.librestatic.lightforge.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Durable phase/entry checkpoints. A partial file is restarted, never falsely reported resumed. */
class LocalBackupTaskRunner(
    context: Context,
    private val port: LocalBackupDurableOrganizationPort?,
) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val store = LocalBackupTaskStore(this.context)

    private class Pause : IOException()

    private class Cancel : IOException()

    private class Review : IOException("Changed or ambiguous output requires review")

    /** false means another executor still owns the task; scheduler should retry. */
    suspend fun run(id: String): Boolean =
        withContext(Dispatchers.IO) {
            if (store.read(id) == null) return@withContext true
            RandomAccessFile(store.lockFile(id), "rw").channel.use { channel ->
                val lease =
                    try {
                        channel.tryLock()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        null
                    } ?: return@withContext false
                lease.use {
                    val initial = store.read(id) ?: return@withContext true
                    if (initial.terminal) return@withContext true
                    if (initial.pauseRequested && !initial.cancelRequested) {
                        store.update(id) { it.copy(status = LocalBackupTaskStatus.Paused) }
                        return@withContext true
                    }
                    val coroutine = currentCoroutineContext()
                    var lastChecked = 0L
                    val check = {
                        coroutine.ensureActive()
                        val now = System.nanoTime()
                        if (now - lastChecked > 50_000_000L) {
                            lastChecked = now
                            val task = requireNotNull(store.read(id))
                            if (task.cancelRequested) throw Cancel()
                            if (task.pauseRequested) throw Pause()
                        }
                    }
                    try {
                        if (initial.cancelRequested) {
                            finishCancellation(id)
                        } else {
                            store.update(id) {
                                if (it.cancelRequested) throw Cancel()
                                if (it.pauseRequested) throw Pause()
                                it.copy(status = LocalBackupTaskStatus.Running, failure = null)
                            }
                            if (initial.kind != LocalBackupTaskKind.Backup)
                                prepareRestoreSnapshot(id, check)
                            when (initial.kind) {
                                LocalBackupTaskKind.Backup -> backup(id, check)
                                LocalBackupTaskKind.RestoreFolder -> restoreFolder(id, check)
                                LocalBackupTaskKind.RestoreGallery -> restoreGallery(id, check)
                            }
                            store.update(id) {
                                if (it.kind != LocalBackupTaskKind.RestoreGallery) {
                                    if (it.cancelRequested) throw Cancel()
                                    if (it.pauseRequested) throw Pause()
                                }
                                it.copy(
                                    status = LocalBackupTaskStatus.Completed,
                                    phase = LocalBackupTaskPhase.Done,
                                    pauseRequested = false,
                                    cancelRequested = false,
                                )
                            }
                        }
                    } catch (_: Pause) {
                        store.update(id) { it.copy(status = LocalBackupTaskStatus.Paused) }
                    } catch (_: Cancel) {
                        withContext(NonCancellable) { finishCancellation(id) }
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            store.update(id) {
                                it.copy(
                                    status =
                                        if (it.pauseRequested) LocalBackupTaskStatus.Paused
                                        else LocalBackupTaskStatus.Queued
                                )
                            }
                        }
                        throw error
                    } catch (_: SecurityException) {
                        store.update(id) {
                            it.copy(
                                status = LocalBackupTaskStatus.WaitingPermission,
                                failure = "permission",
                            )
                        }
                    } catch (_: Review) {
                        store.update(id) {
                            it.copy(status = LocalBackupTaskStatus.NeedsReview, failure = "changed")
                        }
                    } catch (_: Exception) {
                        store.update(id) {
                            it.copy(status = LocalBackupTaskStatus.Failed, failure = "io")
                        }
                    }
                }
            }
            true
        }

    private fun prepareRestoreSnapshot(id: String, check: () -> Unit) {
        val task = requireNotNull(store.read(id))
        if (task.phase != LocalBackupTaskPhase.Preparing) return
        val archive = store.archive(id)
        if (!archive.exists()) {
            val source =
                LocalBackupTaskSnapshots.privateSource(
                    context,
                    File(requireNotNull(task.snapshotSource)),
                )
            val temporary = store.partialArchive(id)
            source.inputStream().use { input ->
                java.io.FileOutputStream(temporary).use { output ->
                    LocalBackupArchive.copyChecked(
                        input,
                        output,
                        BackupManifest.MAX_TOTAL_BYTES + 1024L * 1024 * 1024,
                        check,
                    )
                    output.fd.sync()
                }
            }
            check()
            if (!temporary.renameTo(archive)) throw IOException("Task snapshot checkpoint failed")
        }
        val manifest = LocalBackupArchive.inspect(archive, check)
        if (LocalBackupTaskSnapshots.manifestSha(manifest) != task.snapshotManifestSha)
            throw Review()
        store.update(id) { it.copy(phase = LocalBackupTaskPhase.ArchiveReady) }
    }

    private suspend fun backup(id: String, check: () -> Unit) {
        val task = requireNotNull(store.read(id))
        val archive = store.archive(id)
        if (task.phase == LocalBackupTaskPhase.Preparing) {
            val temporary = store.partialArchive(id)
            temporary.delete()
            val storage = LocalBackupStorage(context)
            try {
                val sources =
                    task.sources.map {
                        storage.source(Uri.parse(it)).let { source ->
                            source.copy(open = { CheckedInput(source.open(), check) })
                        }
                    }
                val progress: (LocalBackupArchive.Progress) -> Unit = { value ->
                    check()
                    store.update(id) {
                        it.copy(
                            filesDone = value.completed,
                            filesTotal = value.total,
                            bytesDone = value.bytes,
                        )
                    }
                }
                if (task.organization)
                    LocalBackupArchive.createOrganized(
                        sources,
                        temporary,
                        requireNotNull(port),
                        progress,
                    )
                else LocalBackupArchive.create(sources, temporary, check, progress)
                check()
                java.io.FileOutputStream(temporary, true).use { it.fd.sync() }
                if (!temporary.renameTo(archive)) throw IOException("Archive checkpoint failed")
                store.update(id) { it.copy(phase = LocalBackupTaskPhase.ArchiveReady) }
            } finally {
                storage.close()
            }
        }
        LocalBackupArchive.inspect(archive, check)
        val destination = Uri.parse(requireNotNull(task.destination))
        val known = requireNotNull(store.read(id)).outputs.single()
        // A completed matching publication is accepted after a lost success response.
        val archiveHash = digest(archive, check)
        if (matches(destination, archive.length(), archiveHash, check)) {
            store.update(id) {
                it.copy(
                    outputs =
                        listOf(
                            known.copy(
                                bytes = archive.length(),
                                sha256 = archiveHash,
                                verified = true,
                            )
                        )
                )
            }
            return
        }
        if (known.verified) throw Review()
        verifyPrefix(destination, { archive.inputStream() }, check)
        store.update(id) { it.copy(phase = LocalBackupTaskPhase.Publishing) }
        archive.inputStream().use { input ->
            (resolver.openOutputStream(destination, "wt")
                    ?: throw IOException("Destination unavailable"))
                .use { output ->
                    LocalBackupArchive.copyChecked(input, output, archive.length(), check)
                }
        }
        val sha = digest(archive, check)
        if (!matches(destination, archive.length(), sha, check)) throw Review()
        store.update(id) {
            it.copy(
                outputs =
                    listOf(known.copy(bytes = archive.length(), sha256 = sha, verified = true))
            )
        }
    }

    private suspend fun restoreGallery(id: String, check: () -> Unit) {
        val adapter = requireNotNull(port)
        adapter.committedResult(id)?.let { result ->
            store.update(id) {
                it.copy(filesDone = result.files, importedObjects = result.importedObjects)
            }
            return
        }
        val archive = store.archive(id)
        val manifest = LocalBackupArchive.inspect(archive, check)
        val sidecar = LocalBackupArchive.readOrganization(archive, manifest, check)
        val task = requireNotNull(store.read(id))
        val session = adapter.resumeRestore(id, sidecar, manifest, task.options)
        var committed = false
        try {
            store.update(id) {
                it.copy(phase = LocalBackupTaskPhase.Restoring, filesTotal = manifest.entries.size)
            }
            ZipFile(archive).use { zip ->
                manifest.entries.forEachIndexed { index, entry ->
                    check()
                    var retried = false
                    while (true) {
                        try {
                            zip.getInputStream(requireNotNull(zip.getEntry(entry.path))).use { input
                                ->
                                session.stage(entry, CheckedInput(input, check))
                            }
                            break
                        } catch (retry: LocalBackupTaskRetryEntry) {
                            if (retried) throw retry
                            retried = true
                        }
                    }
                    store.update(id) {
                        it.copy(
                            filesDone = index + 1,
                            bytesDone =
                                manifest.entries.take(index + 1).sumOf { item -> item.bytes },
                        )
                    }
                }
            }
            check()
            store.update(id) { it.copy(phase = LocalBackupTaskPhase.Committing) }
            val result = session.commit()
            committed = true
            store.update(id) {
                it.copy(filesDone = result.files, importedObjects = result.importedObjects)
            }
        } finally {
            if (!committed) withContext(NonCancellable) { session.pause() }
        }
    }

    private fun restoreFolder(id: String, check: () -> Unit) {
        val archive = store.archive(id)
        val manifest = LocalBackupArchive.inspect(archive, check)
        val task = requireNotNull(store.read(id))
        val tree = Uri.parse(requireNotNull(task.destination))
        val folder = task.folder?.let(Uri::parse) ?: createFolder(id, tree)
        store.update(id) {
            it.copy(phase = LocalBackupTaskPhase.Restoring, filesTotal = manifest.entries.size)
        }
        assertKnownChildren(id, folder)
        ZipFile(archive).use { zip ->
            manifest.entries.forEachIndexed { index, entry ->
                check()
                val previous =
                    requireNotNull(store.read(id)).outputs.singleOrNull { it.index == index }
                val document =
                    previous?.uri?.let(Uri::parse)
                        ?: run {
                            val created =
                                DocumentsContract.createDocument(
                                    resolver,
                                    folder,
                                    entry.mime,
                                    entry.name,
                                ) ?: throw IOException("Restore output unavailable")
                            store.update(id) {
                                it.copy(
                                    outputs =
                                        it.outputs +
                                            LocalBackupTaskOutput(created.toString(), index)
                                )
                            }
                            created
                        }
                if (!matches(document, entry.bytes, entry.sha256, check)) {
                    if (previous?.verified == true) throw Review()
                    verifyPrefix(
                        document,
                        { zip.getInputStream(requireNotNull(zip.getEntry(entry.path))) },
                        check,
                    )
                    zip.getInputStream(requireNotNull(zip.getEntry(entry.path))).use { input ->
                        (resolver.openOutputStream(document, "wt")
                                ?: throw IOException("Restore output unavailable"))
                            .use { output ->
                                LocalBackupArchive.copyChecked(input, output, entry.bytes, check)
                            }
                    }
                    if (!matches(document, entry.bytes, entry.sha256, check)) throw Review()
                }
                store.update(id) {
                    it.copy(
                        outputs =
                            it.outputs.map { output ->
                                if (output.index == index)
                                    output.copy(
                                        bytes = entry.bytes,
                                        sha256 = entry.sha256,
                                        verified = true,
                                    )
                                else output
                            },
                        filesDone = index + 1,
                        bytesDone =
                            it.outputs
                                .filter { output -> output.verified && output.index != index }
                                .sumOf { output -> output.bytes } + entry.bytes,
                    )
                }
            }
        }
        assertKnownChildren(id, folder)
    }

    private fun createFolder(id: String, tree: Uri): Uri {
        val root =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        val name = "Lightforge-restored-$id"
        // A folder created before a crash but not journaled is not silently replaced or deleted.
        if (children(root).any { it.second == name }) throw Review()
        val folder =
            DocumentsContract.createDocument(
                resolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: throw IOException("Restore folder unavailable")
        store.update(id) { it.copy(folder = folder.toString()) }
        return folder
    }

    private fun children(folder: Uri): List<Pair<Uri, String>> {
        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                folder,
                DocumentsContract.getDocumentId(folder),
            )
        return (resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("Folder inventory unavailable"))
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(
                        DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)) to
                            cursor.getString(1)
                    )
                }
            }
    }

    private fun assertKnownChildren(id: String, folder: Uri) {
        val owned = requireNotNull(store.read(id)).outputs.map { Uri.parse(it.uri) }.toSet()
        if (children(folder).any { it.first !in owned }) throw Review()
    }

    private suspend fun finishCancellation(id: String) {
        try {
            cancel(id)
        } catch (_: SecurityException) {
            store.update(id) {
                it.copy(status = LocalBackupTaskStatus.WaitingPermission, failure = "permission")
            }
        } catch (_: Exception) {
            store.update(id) {
                it.copy(status = LocalBackupTaskStatus.NeedsReview, failure = "cleanup")
            }
        }
    }

    private suspend fun cancel(id: String) {
        val before = requireNotNull(store.read(id))
        if (
            before.phase == LocalBackupTaskPhase.Preparing &&
                before.kind != LocalBackupTaskKind.Backup
        ) {
            check(before.outputs.isEmpty() && before.folder == null)
            store.partialArchive(id).delete()
            store.update(id) {
                it.copy(
                    status = LocalBackupTaskStatus.Cancelled,
                    phase = LocalBackupTaskPhase.Done,
                    cancelRequested = false,
                    pauseRequested = false,
                )
            }
            return
        }
        store.update(id) {
            it.copy(status = LocalBackupTaskStatus.Cancelling, phase = LocalBackupTaskPhase.Cleanup)
        }
        val task = requireNotNull(store.read(id))
        if (task.kind == LocalBackupTaskKind.RestoreGallery) {
            val adapter = requireNotNull(port)
            val committed = adapter.committedResult(id)
            if (committed != null) {
                store.update(id) {
                    it.copy(
                        status = LocalBackupTaskStatus.Completed,
                        phase = LocalBackupTaskPhase.Done,
                        filesDone = committed.files,
                        importedObjects = committed.importedObjects,
                        cancelRequested = false,
                    )
                }
                return
            }
            val archive = store.archive(id)
            val manifest = LocalBackupArchive.inspect(archive)
            val session =
                adapter.resumeRestore(
                    id,
                    LocalBackupArchive.readOrganization(archive, manifest),
                    manifest,
                    task.options,
                )
            try {
                ZipFile(archive).use { zip ->
                    manifest.entries.forEach { entry ->
                        zip.getInputStream(requireNotNull(zip.getEntry(entry.path))).use { input ->
                            session.verifyBeforeAbort(entry, input)
                        }
                    }
                }
                session.abort()
            } finally {
                session.pause()
            }
        } else {
            val archive = store.archive(id)
            if (task.kind == LocalBackupTaskKind.Backup) {
                task.outputs.forEach { output ->
                    if (output.verified) {
                        if (!matches(Uri.parse(output.uri), output.bytes, output.sha256, {}))
                            throw Review()
                    } else if (archive.exists())
                        verifyPrefix(Uri.parse(output.uri), { archive.inputStream() }, {})
                    else if (!matches(Uri.parse(output.uri), 0, EmptyHash, {})) throw Review()
                    delete(Uri.parse(output.uri))
                    store.update(id) {
                        it.copy(outputs = it.outputs.filterNot { item -> item.uri == output.uri })
                    }
                }
            } else {
                task.folder?.let { assertKnownChildren(id, Uri.parse(it)) }
                val manifest = LocalBackupArchive.inspect(archive)
                ZipFile(archive).use { zip ->
                    task.outputs.forEach { output ->
                        val entry = manifest.entries[output.index]
                        if (output.verified) {
                            if (!matches(Uri.parse(output.uri), output.bytes, output.sha256, {}))
                                throw Review()
                        } else
                            verifyPrefix(
                                Uri.parse(output.uri),
                                { zip.getInputStream(requireNotNull(zip.getEntry(entry.path))) },
                                {},
                            )
                        delete(Uri.parse(output.uri))
                        store.update(id) {
                            it.copy(
                                outputs = it.outputs.filterNot { item -> item.uri == output.uri }
                            )
                        }
                    }
                }
                task.folder?.let { folder ->
                    if (children(Uri.parse(folder)).isNotEmpty()) throw Review()
                    delete(Uri.parse(folder))
                    store.update(id) { it.copy(folder = null) }
                }
            }
        }
        store.partialArchive(id).delete()
        store.update(id) {
            it.copy(
                status = LocalBackupTaskStatus.Cancelled,
                phase = LocalBackupTaskPhase.Done,
                cancelRequested = false,
                pauseRequested = false,
            )
        }
    }

    private fun delete(uri: Uri) {
        if (!DocumentsContract.deleteDocument(resolver, uri)) throw IOException("Cleanup denied")
    }

    private fun matches(uri: Uri, size: Long, sha: String, check: () -> Unit): Boolean {
        val observed =
            (resolver.openInputStream(uri) ?: throw IOException("Output unavailable")).use {
                // Use bounded archive size instead of expected length: an unrelated longer output
                // must
                // become review, not an accidental retry that truncates it.
                LocalBackupArchive.copyChecked(
                    it,
                    Discard,
                    BackupManifest.MAX_TOTAL_BYTES + 1024L * 1024 * 1024,
                    check,
                )
            }
        return observed == (size to sha)
    }

    private fun verifyPrefix(uri: Uri, source: () -> InputStream, check: () -> Unit) {
        (resolver.openInputStream(uri) ?: throw IOException("Output unavailable")).use { actual ->
            source().use { expected ->
                val buffer = ByteArray(65536)
                val other = ByteArray(65536)
                while (true) {
                    check()
                    val count = actual.read(buffer)
                    if (count < 0) break
                    var offset = 0
                    while (offset < count) {
                        val read = expected.read(other, offset, count - offset)
                        if (read < 0) throw Review()
                        if (read > 0) offset += read
                    }
                    repeat(count) { if (buffer[it] != other[it]) throw Review() }
                }
            }
        }
    }

    private fun digest(file: File, check: () -> Unit): String =
        file.inputStream().use {
            LocalBackupArchive.copyChecked(
                    it,
                    Discard,
                    BackupManifest.MAX_TOTAL_BYTES + 1024L * 1024 * 1024,
                    check,
                )
                .second
        }

    private class CheckedInput(private val input: InputStream, private val check: () -> Unit) :
        InputStream() {
        override fun close() {
            input.close()
        }

        override fun read(): Int {
            check()
            return input.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check()
            return input.read(buffer, offset, length)
        }
    }

    companion object {
        private const val EmptyHash =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val Discard =
            object : OutputStream() {
                override fun write(value: Int) {}

                override fun write(bytes: ByteArray, offset: Int, length: Int) {}
            }
    }
}
