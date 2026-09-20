package com.ugallery.feature.privatealbum

import androidx.room.withTransaction

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.ugallery.core.security.PrivateAlbumCrypto
import com.ugallery.core.security.PrivatePortableArchive
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/** Only UUID-owned encrypted staging files; commit publication is a single receipt+items Room transaction. */
class PrivatePortableTransfer private constructor(
    private val context: Context,
    private val fixedDatabase: PrivateAlbumDatabase?,
    private val access: PrivateDatabaseAccess?,
    private val journals: ConcurrentHashMap<File, PrivatePortableJournal>,
) {
    /** Fixed database constructor retained for isolated fixtures and explicit owners. */
    constructor(context: Context, database: PrivateAlbumDatabase) :
        this(context, database, null, ConcurrentHashMap())

    /** The transfer and its ciphertext journals outlive individual authenticated DB leases. */
    internal constructor(context: Context, access: PrivateDatabaseAccess) :
        this(context, null, access, ConcurrentHashMap())

    private val database: PrivateAlbumDatabase
        get() = checkNotNull(fixedDatabase) { "Private database operations require an active lease" }

    /** Each worker is confined to one leased operation; no DAO or database escapes in the result. */
    private suspend fun <T> withWorker(
        expectedEpoch: Long? = null,
        write: Boolean = false,
        block: suspend (PrivatePortableTransfer) -> T,
    ): T {
        val owner = checkNotNull(access)
        val epoch = expectedEpoch ?: owner.epoch
        return owner.withDatabase(expectedEpoch = epoch, write = write) { db ->
            block(PrivatePortableTransfer(context, db, null, journals))
        }
    }
    data class Export(val file: File, val sha256: String, val count: Int)
    data class Restore internal constructor(
        val id: String, val archiveSha256: String, val items: List<PrivatePortableArchive.Restored>,
        internal val directory: File, val previousReceipt: PrivateRestoreReceiptEntity? = null,
        internal val sessionEpoch: Long? = null,
    )
    data class Result(val count: Int, val alreadyCommitted: Boolean)
    /** Only after operation completion. Failed cleanup keeps its on-disk ownership journal for recovery. */
    fun clearOwnedStaging() {
        var error: Exception? = null
        journals.entries.toList().forEach { (directory, journal) ->
            try { journal.discard(); journals.remove(directory) }
            catch (failure: Exception) {
                journal.retainForRecovery = true
                runCatching { journal.closeLease() }.exceptionOrNull()?.let(failure::addSuppressed)
                if (error == null) error = failure else error?.addSuppressed(failure)
            }
        }
        error?.let { throw it }
    }
    suspend fun recoverAbandoned(): List<String> =
        if (access != null) withWorker { it.recoverAbandoned() }
        else withContext(Dispatchers.IO) {
            PrivatePortableJournal.recover(context, root, database.portableRestoreDao())
        }
    private suspend fun ensureRecovered() {
        val residuals = recoverAbandoned()
        require(residuals.isEmpty()) { "Private cleanup requires review: ${residuals.joinToString()}" }
    }
    private val root get() = File(context.filesDir, "private-album").apply { mkdirs() }

    /** Production handoff binds the resolved key to the session that authorized it. */
    suspend fun prepareExport(
        masterKey: PrivateAlbumRepository.MasterKey,
        password: CharArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Export = try {
        if (access != null) {
            val epoch = requireNotNull(masterKey.sessionEpoch) { "Private master key has no session binding" }
            withWorker(expectedEpoch = epoch) { it.prepareExport(masterKey.secretKey, password, onProgress) }
        } else prepareExport(masterKey.secretKey, password, onProgress)
    } finally { password.fill('\u0000') }

    /** Raw-key compatibility path for fixed-database fixtures. */
    suspend fun prepareExport(masterKey: SecretKey, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): Export = try {
        check(access == null) { "Session-backed private export requires a bound master key" }
        withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        ensureRecovered()
        val rows = database.privateMediaDao().getPage(2001, 0)
        require(rows.size in 1..2000) { "Select a private album containing 1 to 2000 items" }
        val size = rows.fold(0L) { total, row -> Math.addExact(total, row.containerSizeBytes) }
        require(root.usableSpace > Math.addExact(Math.multiplyExact(size, 3), 32L * 1024 * 1024)) { "Not enough private staging space" }
        val journal = PrivatePortableJournal.create(root, "export")
        val directory = journal.directory
        journals[directory] = journal
        val archive = File(directory, "private-backup.ugpb")
        var completed = false
        try {
            val sources = rows.map { row ->
                val file = File(row.containerPath)
                require(file.canonicalPath.startsWith(root.canonicalPath + File.separator) && file.isFile)
                val key = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(row.encryptedDataKey, row.dataKeyIv), masterKey)
                PrivatePortableArchive.Source(
                    PrivatePortableArchive.Metadata(row.originalDisplayName, row.originalMimeType, row.mediaKind, row.width, row.height, row.durationMillis, row.addedAtMillis),
                    file, key, row.sha256,
                )
            }
            FileOutputStream(archive).use { output ->
                PrivatePortableArchive.write(sources, output, password, { job.ensureActive() }, onProgress)
                output.fd.sync()
            }
            val verification = File(directory, "verify").also { require(it.mkdir()) }
            try {
                FileInputStream(archive).use { PrivatePortableArchive.read(it, password, verification, { job.ensureActive() }) }
            } finally { PrivatePortableJournal.deleteChecked(verification) }
            job.ensureActive()
            val hash = PrivatePortableArchive.hash(archive) { job.ensureActive() }.hex()
            completed = true
            Export(archive, hash, rows.size)
        } finally {
            password.fill('\u0000')
            if (!completed) discardDirectory(directory)
        }
    } } finally { password.fill('\u0000') }

    /** The destination must be a newly created SAF document. Readback verifies only encrypted bytes. */
    suspend fun publish(export: Export, destination: Uri): Uri = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val journal = requireNotNull(journals[export.file.parentFile])
        var ownedEmpty = false
        var success = false
        var primary: Throwable? = null
        try {
            require(DocumentsContract.isDocumentUri(context, destination)) { "New local document required" }
            withAsset(destination, "rw") { asset ->
                val stat = android.system.Os.fstat(asset.fileDescriptor)
                require(stat.st_size == 0L && asset.startOffset == 0L) { "Existing document preserved: destination is not empty" }
                require(android.system.Os.lseek(asset.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET) == 0L)
                // The caller obtained this URI from CreateDocument; rw + fstat avoids truncating preexisting bytes.
                ownedEmpty = true
                journal.beforePublish(destination)
                context.contentResolver.takePersistableUriPermission(destination, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                asset.createOutputStream().use { output -> FileInputStream(export.file).use { input -> copy(input, output) { job.ensureActive() } } }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            withAsset(destination, "r") { asset -> asset.createInputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { job.ensureActive(); val n = input.read(buffer); if (n < 0) break; if (n > 0) digest.update(buffer, 0, n) }
            } }
            require(digest.digest().hex() == export.sha256) { "Encrypted document readback mismatch" }
            journal.published()
            success = true
            destination
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanupFailure: Exception? = null
            if (!success && ownedEmpty) withContext(NonCancellable) {
                try {
                    PrivatePortableJournal.requireOwnedPrefix(context, destination, export.file)
                    if (!DocumentsContract.deleteDocument(context.contentResolver, destination)) throw IOException("Incomplete encrypted document retained: $destination")
                } catch (cleanup: Exception) { journal.retainForRecovery = true; cleanupFailure = cleanup }
            }
            try { discard(export) } catch (cleanup: Exception) { if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure?.addSuppressed(cleanup) }
            cleanupFailure?.let { cleanup -> if (primary != null) primary?.addSuppressed(cleanup) else throw cleanup }
        }
    }

    suspend fun prepareRestore(source: Uri, password: CharArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): Restore = try {
        if (access != null) {
            val epoch = access.epoch
            withWorker(expectedEpoch = epoch) {
                it.prepareRestore(source, password, onProgress).copy(sessionEpoch = epoch)
            }
        }
        else withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        ensureRecovered()
        val journal = PrivatePortableJournal.create(root, "restore")
        val id = journal.id
        val directory = journal.directory
        journals[directory] = journal
        var complete = false
        try {
            val archive = File(directory, "source.ugpb")
            withAsset(source, "r") { asset ->
                if (asset.length >= 0) require(asset.length <= 1024L * 1024 * 1024 * 1024 && root.usableSpace > Math.addExact(asset.length, 32L * 1024 * 1024)) { "Not enough private staging space" }
                asset.createInputStream().use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        job.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) {
                            size = Math.addExact(size, n.toLong())
                            require(size <= 1024L * 1024 * 1024 * 1024 && root.usableSpace > 32L * 1024 * 1024) { "Not enough private staging space" }
                            output.write(buffer, 0, n)
                        }
                    }
                    output.fd.sync()
                }
            } }
            val hash = PrivatePortableArchive.hash(archive) { job.ensureActive() }.hex()
            val previous = database.portableRestoreDao().getReceiptByArchiveSha(hash)
            if (previous != null) {
                PrivatePortableJournal.deleteChecked(archive)
                complete = true
                return@withContext Restore(id, hash, emptyList(), directory, previous)
            }
            require(root.usableSpace > Math.addExact(Math.multiplyExact(archive.length(), 2), 32L * 1024 * 1024)) { "Not enough private staging space" }
            val entries = File(directory, "entries").also { require(it.mkdir()) }
            val restored = FileInputStream(archive).use { PrivatePortableArchive.read(it, password, entries, { job.ensureActive() }, onProgress) }
            PrivatePortableJournal.deleteChecked(archive)
            job.ensureActive()
            complete = true
            Restore(id, hash, restored, directory)
        } finally {
            password.fill('\u0000')
            if (!complete) discardDirectory(directory)
        }
    } } finally { password.fill('\u0000') }

    suspend fun commit(restore: Restore, masterKey: PrivateAlbumRepository.MasterKey): Result =
        if (access != null) {
            val epoch = requireNotNull(masterKey.sessionEpoch) { "Private master key has no session binding" }
            check(restore.sessionEpoch == epoch) { "Private restore review belongs to an obsolete session" }
            withWorker(expectedEpoch = epoch, write = true) { it.commit(restore, masterKey.secretKey, masterKey.alias) }
        } else commit(restore, masterKey.secretKey, masterKey.alias)

    internal suspend fun commit(restore: Restore, masterKey: SecretKey, expectedKeyAlias: String? = null): Result = withContext(Dispatchers.IO) {
        check(access == null) { "Session-backed private restore requires a bound master key" }
        restore.previousReceipt?.let { discard(restore); return@withContext Result(it.itemCount, true) }
        currentCoroutineContext().ensureActive()
        val capturedKeyAlias = expectedKeyAlias ?: database.metadataDao().get()?.keyAlias
        val moved = mutableListOf<File>()
        val journal = requireNotNull(journals[restore.directory])
        journal.beforeRename(restore.items.size)
        var committed = false
        var commitOutcomeUncertain = false
        try {
            val entities = restore.items.mapIndexed { index, item ->
                val target = File(root, "restored-${restore.id}-$index.ugpc")
                require(!target.exists() && item.ciphertext.renameTo(target)) { "Private ciphertext publication failed" }
                moved += target
                val wrapped = PrivateAlbumCrypto.encryptDataKey(item.dataKey, masterKey)
                PrivateMediaEntity(
                    originalMediaKey = "portable:${restore.archiveSha256}:$index",
                    originalMimeType = item.metadata.mimeType, originalDisplayName = item.metadata.displayName,
                    containerPath = target.absolutePath, containerSizeBytes = target.length(),
                    encryptedDataKey = wrapped.encryptedKey, dataKeyIv = wrapped.iv,
                    chunkSize = item.container.chunkSize, totalChunks = item.container.totalChunks, ivBase = item.container.ivBase,
                    sha256 = item.container.sha256, addedAtMillis = item.metadata.addedAtMillis,
                    mediaKind = item.metadata.mediaKind, width = item.metadata.width, height = item.metadata.height, durationMillis = item.metadata.durationMillis,
                )
            }
            currentCoroutineContext().ensureActive()
            PrivatePortableJournal.syncDirectory(root)
            withContext(NonCancellable) {
                try {
                    commitOutcomeUncertain = true
                    database.withTransaction {
                        val current = database.metadataDao().get()
                        check(current?.keyAlias == capturedKeyAlias && (expectedKeyAlias == null || current?.isSetup == true)) {
                            "Private master key changed during restore"
                        }
                        database.portableRestoreDao().commitPortableRestore(
                            PrivateRestoreReceiptEntity(restore.id, restore.archiveSha256, entities.size, System.currentTimeMillis()), entities,
                        )
                    }
                    committed = true
                    Result(entities.size, false)
                } catch (already: PrivateRestoreAlreadyCommitted) {
                    commitOutcomeUncertain = false
                    Result(already.receipt.itemCount, true)
                } catch (error: Exception) {
                    val receipt = try { database.portableRestoreDao().getReceipt(restore.id) } catch (reconcile: Exception) {
                        error.addSuppressed(reconcile)
                        throw error // Preserve ciphertext when database commit cannot be reconciled.
                    }
                    commitOutcomeUncertain = false
                    if (receipt != null && receipt.archiveSha256 == restore.archiveSha256) {
                        committed = true
                        Result(receipt.itemCount, false)
                    } else throw error
                }
            }
        } finally {
            if (commitOutcomeUncertain && !committed) journal.retainForRecovery = true
            if (!committed && !commitOutcomeUncertain) {
                try { moved.forEach(PrivatePortableJournal::deleteChecked) }
                catch (cleanup: Exception) { journal.retainForRecovery = true; throw cleanup }
            }
            discard(restore)
        }
    }
    fun discard(export: Export) { export.file.parentFile?.let(::discardDirectory) }
    fun discard(restore: Restore) { discardDirectory(restore.directory) }
    private fun discardDirectory(directory: File) {
        val journal = journals[directory] ?: return
        try { journal.discard(); journals.remove(directory) }
        catch (error: Exception) {
            journal.retainForRecovery = true
            runCatching { journal.closeLease() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private suspend fun <T> withAsset(uri: Uri, mode: String, block: (android.content.res.AssetFileDescriptor) -> T): T {
        val signal = CancellationSignal()
        return coroutineScope {
            val job = currentCoroutineContext().job
            var asset: android.content.res.AssetFileDescriptor? = null
            var client: android.content.ContentProviderClient? = null
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!job.isActive) { runCatching { signal.cancel() }; runCatching { asset?.close() } } }
            }
            var primary: Throwable? = null
            try {
                asset = if (uri.scheme == "content") {
                    // Bypass DocumentsProvider's typed */* dispatch, which loses the signal.
                    client = context.contentResolver.acquireUnstableContentProviderClient(uri)
                        ?: throw FileNotFoundException("Private backup document unavailable")
                    requireNotNull(client).openAssetFile(uri, mode, signal)
                } else context.contentResolver.openAssetFileDescriptor(uri, mode, signal)
                if (asset == null) throw FileNotFoundException("Private backup document unavailable")
                currentCoroutineContext().ensureActive()
                block(requireNotNull(asset))
            } catch (failure: Throwable) {
                try { currentCoroutineContext().ensureActive() }
                catch (cancelled: CancellationException) {
                    if (cancelled !== failure) cancelled.addSuppressed(failure)
                    primary = cancelled
                    throw cancelled
                }
                primary = failure
                throw failure
            } finally {
                try { asset?.close() } catch (close: Exception) { if (primary != null) primary?.addSuppressed(close) else throw close }
                finally {
                    try { client?.close() } catch (close: Exception) { if (primary != null) primary?.addSuppressed(close) else throw close }
                    finally { cancellation.cancel() }
                }
            }
        }
    }
    private fun copy(input: InputStream, output: OutputStream, check: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) { check(); val n = input.read(buffer); if (n < 0) break; if (n > 0) output.write(buffer, 0, n) }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
