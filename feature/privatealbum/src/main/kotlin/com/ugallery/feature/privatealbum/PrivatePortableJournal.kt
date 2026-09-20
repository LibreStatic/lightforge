package com.ugallery.feature.privatealbum

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.*
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import kotlinx.coroutines.*

/** Filesystem journal contains ownership only, never passwords, media names or decrypted keys. */
internal class PrivatePortableJournal private constructor(
    val directory: File, val id: String, private val lockFile: RandomAccessFile, private val lock: FileLock,
    private var state: JSONObject,
) {
    private var leaseClosed = false
    var retainForRecovery = false
    fun beforeRename(count: Int) { require(count in 1..2000); state.put("targets", count); persist() }
    fun beforePublish(uri: Uri) { state.put("destination", uri.toString()); persist() }
    fun published() { state.put("published", true); persist() }
    @Synchronized fun closeLease() { if (!leaseClosed) { try { lock.release() } finally { lockFile.close(); leaseClosed = true } } }
    fun discard() {
        if (retainForRecovery) { closeLease(); return }
        // Do not drop the ownership journal before every other byte was actually removed.
        directory.listFiles().orEmpty().filter { it.name !in setOf("journal.json", "lease") }.forEach(::deleteChecked)
        deleteChecked(File(directory, "journal.json"))
        closeLease()
        deleteChecked(File(directory, "lease"))
        deleteChecked(directory)
    }
    private fun persist() {
        val pending = File(directory, "journal.next")
        FileOutputStream(pending).use { it.write(state.toString().toByteArray()); it.fd.sync() }
        require(pending.renameTo(File(directory, "journal.json"))) { "Private ownership journal publication failed" }
        syncDirectory(directory)
    }
    companion object {
        fun create(root: File, kind: String): PrivatePortableJournal {
            require(kind == "export" || kind == "restore")
            val id = UUID.randomUUID().toString()
            val dir = File(root, "portable-$kind-$id").also { require(it.mkdir()) }
            val file = RandomAccessFile(File(dir, "lease"), "rw")
            val lease = file.channel.lock()
            val journal = PrivatePortableJournal(dir, id, file, lease, JSONObject().put("version", 1).put("id", id).put("kind", kind).put("targets", 0).put("published", false))
            try { journal.persist(); syncDirectory(root); return journal }
            catch (error: Exception) { journal.retainForRecovery = true; journal.closeLease(); throw error }
        }
        suspend fun recover(context: Context, root: File, dao: PrivatePortableRestoreDao): List<String> {
            val residuals = mutableListOf<String>()
            val directories = root.listFiles().orEmpty().filter { it.name.startsWith("portable-export-") || it.name.startsWith("portable-restore-") }
            require(directories.size <= 4096) { "Private recovery requires manual review of excessive staging entries" }
            directories.forEach { dir ->
                if (!dir.isDirectory || java.nio.file.Files.isSymbolicLink(dir.toPath()) || dir.canonicalFile.parentFile != root.canonicalFile) { residuals += dir.name; return@forEach }
                val kind = if (dir.name.startsWith("portable-export-")) "export" else "restore"
                val id = dir.name.removePrefix("portable-$kind-")
                if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return@forEach
                val owner = File(dir, "journal.json")
                if (!owner.isFile || owner.length() !in 1..8192) { residuals += dir.name; return@forEach }
                val file = RandomAccessFile(File(dir, "lease"), "rw")
                val lease = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lease == null) { file.close(); return@forEach }
                var journal: PrivatePortableJournal? = null
                try {
                    val state = JSONObject(owner.readText())
                    require(state.getInt("version") == 1 && state.getString("id") == id && state.getString("kind") == kind)
                    val count = state.getInt("targets"); require(count in 0..2000)
                    journal = PrivatePortableJournal(dir, id, file, lease, state)
                    repeat(count) { index ->
                        val target = File(root, "restored-$id-$index.ugpc")
                        if (!dao.isContainerPathReferenced(target.absolutePath)) deleteChecked(target)
                    }
                    val destination = state.optString("destination")
                    if (destination.isNotEmpty() && !state.optBoolean("published")) {
                        requireOwnedPrefix(context, Uri.parse(destination), File(dir, "private-backup.ugpb"))
                        if (!DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(destination))) throw IOException("Incomplete encrypted document cleanup failed: $destination")
                    }
                    journal.discard(); journal = null
                } catch (error: Exception) {
                    residuals += "${dir.name}: ${error.message}"
                    if (journal != null) { val held = journal; held.retainForRecovery = true; runCatching { held.closeLease() } }
                    else { runCatching { lease.release() }; file.close() }
                    currentCoroutineContext().ensureActive()
                }
            }
            return residuals
        }
        /** Same unique encrypted archive prefix, never delete a document whose bytes changed independently. */
        suspend fun requireOwnedPrefix(context: Context, destination: Uri, archive: File, timeoutMillis: Long = 30_000) {
            require(timeoutMillis in 1..30_000)
            withTimeout(timeoutMillis) { withContext(Dispatchers.IO) { coroutineScope {
                require(archive.isFile)
                val operation = currentCoroutineContext()
                val signal = android.os.CancellationSignal()
                var asset: android.content.res.AssetFileDescriptor? = null
                var client: android.content.ContentProviderClient? = null
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { if (!operation.isActive) { runCatching { signal.cancel() }; runCatching { asset?.close() } } }
                }
                var primary: Throwable? = null
                try {
                    asset = if (destination.scheme == "content") {
                        // DocumentsProvider's typed */* route drops its signal on Android.
                        client = context.contentResolver.acquireUnstableContentProviderClient(destination)
                            ?: throw FileNotFoundException(destination.toString())
                        requireNotNull(client).openAssetFile(destination, "r", signal)
                    } else context.contentResolver.openAssetFileDescriptor(destination, "r", signal)
                    if (asset == null) throw FileNotFoundException(destination.toString())
                    operation.ensureActive()
                    require(requireNotNull(asset).startOffset == 0L) { "Destination ownership changed" }
                    FileInputStream(archive).use { expected ->
                        requireNotNull(asset).createInputStream().use { actual ->
                            val a = ByteArray(64 * 1024); val b = ByteArray(64 * 1024)
                            while (true) {
                                operation.ensureActive()
                                val count = actual.read(a)
                                if (count < 0) break
                                if (count == 0) continue
                                var offset = 0
                                while (offset < count) { val n = expected.read(b, offset, count - offset); require(n > 0) { "Destination ownership changed" }; offset += n }
                                require(a.copyOf(count).contentEquals(b.copyOf(count))) { "Destination ownership changed" }
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    try { operation.ensureActive() }
                    catch (cancelled: CancellationException) {
                        if (cancelled !== failure) cancelled.addSuppressed(failure)
                        primary = cancelled
                        throw cancelled
                    }
                    primary = failure
                    throw failure
                }
                finally {
                    try { asset?.close() } catch (close: Exception) { if (primary != null) primary?.addSuppressed(close) else throw close }
                    finally {
                        try { client?.close() } catch (close: Exception) { if (primary != null) primary?.addSuppressed(close) else throw close }
                        finally { closer.cancel() }
                    }
                }
            } } }
        }
        fun deleteChecked(file: File) {
            if (!file.exists()) return
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == file.absoluteFile.parentFile.canonicalFile) { "Unexpected private staging link" }
            if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteChecked)
            if (!file.delete() && file.exists()) throw IOException("Private cleanup incomplete: ${file.name}")
        }
        fun syncDirectory(directory: File) {
            require(directory.isDirectory)
            val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        }
    }
}
