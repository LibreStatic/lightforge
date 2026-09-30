package com.librestatic.lightforge.feature.privatealbum

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.withTransaction
import com.librestatic.lightforge.core.mediastore.MediaWriteSpec
import com.librestatic.lightforge.core.mediastore.PendingMediaWriter
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.security.PrivateAlbumCrypto
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener

/** A proof describes the exact read-only review. Actions never adopt a refreshed observation. */
enum class PrivateExportStatus { Ready, Published, Partial, Missing, Conflict, Unknown }
data class PrivateExportRecovery(
    val id: String, val displayName: String, val status: PrivateExportStatus,
    val uri: String?, val proof: String,
)

internal data class PrivateExportDestination(
    val uri: String, val owner: String, val name: String, val path: String, val mime: String,
    val added: Long, val modified: Long, val size: Long?, val pending: Boolean, val trashed: Boolean,
) {
    fun identityEquals(other: PrivateExportDestination) = uri == other.uri && owner == other.owner &&
        name == other.name && path == other.path && mime == other.mime && added == other.added
    fun fields() = listOf(uri, owner, name, path, mime, added.toString(), modified.toString(),
        size?.toString(), pending.toString(), trashed.toString())
}

/** Pure predicates used by the real publication and CAS paths, not parallel test arithmetic. */
internal object PrivateExportPolicy {
    fun canForget(status: PrivateExportStatus) = status !in setOf(PrivateExportStatus.Ready, PrivateExportStatus.Partial)
    fun mayObserve(anchor: PrivateExportDestination, current: PrivateExportDestination): Boolean =
        anchor.identityEquals(current) && !current.trashed && current.modified >= anchor.modified
    fun requireProof(expected: String, actual: String) {
        require(Regex("[0-9a-f]{64}").matches(expected) && expected == actual) { "Private export review changed; check again" }
    }
    fun proof(fields: List<String?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field?.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Receipts live only in the authenticated SQLCipher index. No plaintext spool or new key format. */
internal class PrivateExportPublication(
    private val context: Context,
    private val database: PrivateAlbumDatabase,
    private val checkpoint: (suspend (PrivateExportReceiptEntity) -> Unit)? = null,
) {
    private val resolver = context.contentResolver
    private val dao get() = database.privateExportDao()
    private val lock get() = locks.getOrPut(context.getDatabasePath(requireNotNull(database.openHelper.databaseName)).canonicalPath) { Mutex() }
    private data class Snapshot(val destination: PrivateExportDestination, val bytes: Long? = null, val digest: String? = null)
    private data class Observation(
        val receipt: PrivateExportReceiptEntity, val status: PrivateExportStatus,
        val destination: PrivateExportDestination? = null, val bytes: Long? = null,
        val digest: String? = null, val error: String? = null,
    ) {
        val proof: String get() = PrivateExportPolicy.proof(listOf(receipt.id, receipt.mediaId.toString(),
            receipt.displayName, receipt.mimeType, receipt.mediaKind, receipt.expectedSha256,
            receipt.phase, receipt.snapshotJson, receipt.createdAtMillis.toString(), status.name) +
            (destination?.fields() ?: listOf(null)) + listOf(bytes?.toString(), digest, error))
        fun public() = PrivateExportRecovery(receipt.id, receipt.displayName, status,
            destination?.uri?.takeIf { status == PrivateExportStatus.Ready || status == PrivateExportStatus.Published }, proof)
    }

    suspend fun list(): List<PrivateExportRecovery> = lock.withLock {
        dao.list().map { inspect(it).public() }
    }

    suspend fun export(mediaId: Long, masterKey: SecretKey): Uri? = lock.withLock {
        dao.getForMedia(mediaId)?.let { old ->
            val observed = inspect(old)
            check(observed.status == PrivateExportStatus.Published) { "Resolve the existing private export before creating another copy" }
            return@withLock Uri.parse(requireNotNull(observed.destination).uri)
        }
        val source = database.privateMediaDao().getById(mediaId) ?: return@withLock null
        require(source.mediaKind in setOf("image", "video") && source.sha256.size == 32)
        val file = File(source.containerPath)
        requireSource(file)
        val key = PrivateAlbumCrypto.decryptDataKey(PrivateAlbumCrypto.EncryptedDataKey(source.encryptedDataKey, source.dataKeyIv), masterKey)
        val id = UUID.randomUUID().toString()
        val extension = source.originalDisplayName.substringAfterLast('.', "bin").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
        val intent = PrivateExportReceiptEntity(id = id, mediaId = mediaId,
            displayName = "Lightforge-Private-$id.$extension", mimeType = source.originalMimeType,
            mediaKind = source.mediaKind, expectedSha256 = source.sha256.hex(), phase = "Intent",
            snapshotJson = null, createdAtMillis = System.currentTimeMillis())
        val spec = MediaWriteSpec(MediaStore.VOLUME_EXTERNAL_PRIMARY,
            if (source.mediaKind == "video") MediaKind.Video else MediaKind.Image,
            intent.displayName, intent.mimeType, outputPath(intent))
        currentCoroutineContext().ensureActive()
        database.withTransaction { check(dao.getForMedia(mediaId) == null); dao.insert(intent) }
        var inserted: PrivateExportReceiptEntity? = null
        val job = currentCoroutineContext()
        suspend fun beforeWrite(uri: Uri) {
            job.ensureActive()
            val receipt = requireNotNull(inserted)
            requireUnchanged(receipt)
            check(metadata(uri) == decode(requireNotNull(receipt.snapshotJson)).destination) { "Private pending identity changed before write" }
            requireSource(file)
            job.ensureActive()
        }
        val staged = PendingMediaWriter(resolver).stageStream(spec, intent.expectedSha256,
            onInserted = { uri ->
                val metadata = requireNotNull(metadata(uri)) { "Private pending output is unavailable" }
                validateDestination(intent, metadata)
                check(metadata.pending && !metadata.trashed)
                val next = intent.copy(phase = "Inserted", snapshotJson = encode(Snapshot(metadata)))
                replace(intent, next)
                inserted = next
                checkpoint?.invoke(next)
            },
            write = { output ->
                beforeWrite(Uri.parse(decode(requireNotNull(requireNotNull(inserted).snapshotJson)).destination.uri))
                FileInputStream(file).use { input ->
                    check(OsConstants.S_ISREG(Os.fstat(input.fd).st_mode)) { "Private source is not regular" }
                    PrivateAlbumCrypto.decryptStream(input, output, key, source.sha256) { job.ensureActive() }
                }
            }, beforeOpen = { beforeWrite(it) })
        val previous = requireNotNull(inserted)
        val first = decode(requireNotNull(previous.snapshotJson)).destination
        val current = requireNotNull(metadata(staged.uri))
        check(PrivateExportPolicy.mayObserve(first, current) && current.pending)
        check(staged.sha256 == intent.expectedSha256 && staged.bytes > 0)
        val ready = previous.copy(phase = "Ready", snapshotJson = encode(Snapshot(current, staged.bytes, staged.sha256)))
        replace(previous, ready)
        checkpoint?.invoke(ready)
        completeObserved(inspect(ready))
    }

    suspend fun complete(id: String, proof: String): Uri = lock.withLock {
        val observed = inspect(requireReceipt(id))
        PrivateExportPolicy.requireProof(proof, observed.proof)
        completeObserved(observed)
    }

    private suspend fun completeObserved(observed: Observation): Uri {
        check(observed.status in setOf(PrivateExportStatus.Ready, PrivateExportStatus.Published)) { "Private export is not verified for completion" }
        val destination = requireNotNull(observed.destination)
        val uri = Uri.parse(destination.uri)
        currentCoroutineContext().ensureActive()
        requireUnchanged(observed.receipt)
        check(metadata(uri) == destination) { "Private destination changed" }
        if (observed.status == PrivateExportStatus.Ready) {
            currentCoroutineContext().ensureActive()
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                selection(destination), arguments(destination)) == 1) { "Private destination publication changed" }
        }
        // A possible provider commit is never deleted on cancellation/Room/callback failure.
        val published = inspect(observed.receipt)
        check(published.status == PrivateExportStatus.Published) { "Private output requires recovery" }
        val recorded = observed.receipt.copy(phase = "Published", snapshotJson = encode(Snapshot(
            requireNotNull(published.destination), published.bytes, published.digest)))
        if (recorded != observed.receipt) {
            replace(observed.receipt, recorded)
            checkpoint?.invoke(recorded)
        }
        currentCoroutineContext().ensureActive()
        return uri
    }

    suspend fun discard(id: String, proof: String) = lock.withLock {
        val observed = inspect(requireReceipt(id))
        PrivateExportPolicy.requireProof(proof, observed.proof)
        check(observed.status == PrivateExportStatus.Partial) { "Only reviewed partial pending output may be removed" }
        val destination = requireNotNull(observed.destination)
        val uri = Uri.parse(destination.uri)
        requireUnchanged(observed.receipt)
        currentCoroutineContext().ensureActive()
        check(metadata(uri) == destination)
        check(resolver.delete(uri, selection(destination), arguments(destination)) == 1) { "Private pending output changed" }
        check(metadata(uri) == null) { "Private pending output remains" }
        removeReceipt(observed.receipt)
    }

    suspend fun forget(id: String, proof: String) = lock.withLock {
        val observed = inspect(requireReceipt(id))
        PrivateExportPolicy.requireProof(proof, observed.proof)
        check(PrivateExportPolicy.canForget(observed.status)) { "Resolve the pending private export first" }
        removeReceipt(observed.receipt) // Tracking only: never deletes a public/provider object.
    }

    private suspend fun inspect(receipt: PrivateExportReceiptEntity): Observation {
        currentCoroutineContext().ensureActive()
        var observedDestination: PrivateExportDestination? = null
        try {
            require(UUID.fromString(receipt.id).toString() == receipt.id && receipt.mediaId > 0)
            require(receipt.phase in setOf("Intent", "Inserted", "Ready", "Published"))
            require(Regex("[0-9a-f]{64}").matches(receipt.expectedSha256))
            if (receipt.phase == "Intent") {
                require(receipt.snapshotJson == null)
                return Observation(receipt, PrivateExportStatus.Unknown) // Never adopt an insert by its name.
            }
            val snapshot = decode(requireNotNull(receipt.snapshotJson))
            val anchor = snapshot.destination
            validateDestination(receipt, anchor)
            require(!anchor.trashed && anchor.pending == (receipt.phase != "Published"))
            if (receipt.phase == "Inserted") require(snapshot.bytes == null && snapshot.digest == null)
            else require(snapshot.bytes != null && snapshot.bytes > 0 && snapshot.digest == receipt.expectedSha256)
            val current = metadata(Uri.parse(anchor.uri)) ?: return Observation(receipt, PrivateExportStatus.Missing)
            observedDestination = current
            val conflict = !PrivateExportPolicy.mayObserve(anchor, current) ||
                (receipt.phase == "Published" && current != anchor)
            val content = readDigest(Uri.parse(current.uri))
            val size = content?.first
            val hash = content?.second
            check(metadata(Uri.parse(current.uri)) == current) { "Private destination changed during verification" }
            requireUnchanged(receipt)
            val full = receipt.phase in setOf("Ready", "Published") && snapshot.bytes != null && snapshot.bytes > 0 &&
                size == snapshot.bytes && hash == receipt.expectedSha256 && snapshot.digest == hash
            val status = when {
                conflict -> PrivateExportStatus.Conflict
                content == null && receipt.phase == "Inserted" && current.pending -> PrivateExportStatus.Partial
                content == null -> PrivateExportStatus.Unknown
                !current.pending && full -> PrivateExportStatus.Published
                !current.pending -> PrivateExportStatus.Conflict
                receipt.phase == "Published" -> PrivateExportStatus.Conflict
                receipt.phase == "Ready" && current == anchor && full -> PrivateExportStatus.Ready
                receipt.phase == "Ready" -> PrivateExportStatus.Conflict
                else -> PrivateExportStatus.Partial
            }
            return Observation(receipt, status, current, size, hash, if (content == null) "backing-file-ENOENT" else null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            return Observation(receipt, PrivateExportStatus.Unknown, observedDestination, error = failure.javaClass.name + ":" + failure.message)
        }
    }

    private suspend fun requireReceipt(id: String): PrivateExportReceiptEntity {
        require(UUID.fromString(id).toString() == id)
        return requireNotNull(dao.get(id)) { "Private export tracking is absent" }
    }
    private suspend fun requireUnchanged(expected: PrivateExportReceiptEntity) {
        check(dao.get(expected.id) == expected) { "Private export receipt changed" }
    }
    private suspend fun replace(expected: PrivateExportReceiptEntity, next: PrivateExportReceiptEntity) {
        currentCoroutineContext().ensureActive()
        database.withTransaction { requireUnchanged(expected); check(dao.update(next) == 1) }
    }
    private suspend fun removeReceipt(expected: PrivateExportReceiptEntity) {
        currentCoroutineContext().ensureActive()
        database.withTransaction { requireUnchanged(expected); check(dao.delete(expected.id) == 1) }
    }

    private suspend fun metadata(uri: Uri): PrivateExportDestination? {
        currentCoroutineContext().ensureActive()
        val projection = arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED)
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return requireNotNull(resolver.query(uri, projection, args, null)) { "Private output query unavailable" }.use {
            if (!it.moveToFirst()) null else PrivateExportDestination(uri.toString(), it.getString(0), it.getString(1),
                it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), if (it.isNull(6)) null else it.getLong(6),
                it.getInt(7) == 1, it.getInt(8) == 1).also { _ -> check(!it.moveToNext()) }
        }
    }
    private suspend fun readDigest(uri: Uri): Pair<Long, String>? {
        val input = try { requireNotNull(resolver.openInputStream(uri)) { "Private output stream unavailable" } }
        catch (failure: FileNotFoundException) {
            val missing = generateSequence<Throwable>(failure) { it.cause }.any {
                it is ErrnoException && it.errno == OsConstants.ENOENT
            } || failure.message == "open failed: ENOENT (No such file or directory)"
            if (missing) return null else throw failure
        }
        return input.use {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var size = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer)
                if (count < 0) break
                if (count > 0) { size = Math.addExact(size, count.toLong()); digest.update(buffer, 0, count) }
            }
            size to digest.digest().hex()
        }
    }
    private fun requireSource(file: File) {
        // Android's own /data/user/0 ancestor may be an alias. Canonicalize that trusted
        // base, but reject symlinked container directories or leaves within it.
        val directory = File(context.filesDir.canonicalFile, "private-album")
        check(OsConstants.S_ISDIR(Os.lstat(directory.path).st_mode)) { "Private container directory changed" }
        check(file.parentFile?.canonicalFile == directory && file.canonicalFile.parentFile == directory &&
            OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) { "Private source unavailable or changed" }
    }
    private fun outputPath(receipt: PrivateExportReceiptEntity) = if (receipt.mediaKind == "video") "Movies/Lightforge/" else "Pictures/Lightforge/"
    private fun validateDestination(receipt: PrivateExportReceiptEntity, value: PrivateExportDestination) {
        require(receipt.mediaKind in setOf("image", "video"))
        val collection = if (receipt.mediaKind == "video") "video" else "images"
        require(Regex("content://media/external_primary/$collection/media/[1-9][0-9]*").matches(value.uri))
        require(value.owner == context.packageName && value.name == receipt.displayName && value.mime == receipt.mimeType && value.path == outputPath(receipt))
        require(value.added >= 0 && value.modified >= value.added && (value.size == null || value.size >= 0))
    }
    private fun selection(value: PrivateExportDestination): String =
        "${MediaStore.MediaColumns._ID}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.GENERATION_ADDED}=? AND " +
            "${MediaStore.MediaColumns.GENERATION_MODIFIED}=? AND ${MediaStore.MediaColumns.IS_PENDING}=? AND " +
            "${MediaStore.MediaColumns.IS_TRASHED}=? AND " +
            if (value.size == null) "${MediaStore.MediaColumns.SIZE} IS NULL" else "${MediaStore.MediaColumns.SIZE}=?"
    private fun arguments(value: PrivateExportDestination): Array<String> = (listOf(
        ContentUris.parseId(Uri.parse(value.uri)).toString(), value.owner, value.name, value.path, value.mime,
        value.added.toString(), value.modified.toString(), if (value.pending) "1" else "0", if (value.trashed) "1" else "0") +
        (value.size?.let { listOf(it.toString()) } ?: emptyList())).toTypedArray()
    private fun encode(value: Snapshot): String {
        val d = value.destination
        return JSONObject().put("version", 1).put("uri", d.uri).put("owner", d.owner).put("name", d.name)
            .put("path", d.path).put("mime", d.mime).put("added", d.added).put("modified", d.modified)
            .put("size", d.size ?: JSONObject.NULL).put("pending", d.pending).put("trashed", d.trashed)
            .put("bytes", value.bytes ?: JSONObject.NULL).put("digest", value.digest ?: JSONObject.NULL).toString()
    }
    private fun decode(json: String): Snapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
        val parser = JSONTokener(json)
        val value = parser.nextValue() as? JSONObject ?: error("Private snapshot must be an object")
        require(parser.nextClean() == '\u0000') { "Trailing private snapshot data" }
        listOf("version", "added", "modified").forEach { require(value.get(it) is Int || value.get(it) is Long) }
        listOf("size", "bytes").forEach { require(value.isNull(it) || value.get(it) is Int || value.get(it) is Long) }
        listOf("uri", "owner", "name", "path", "mime").forEach { require(value.get(it) is String) }
        require(value.isNull("digest") || value.get("digest") is String)
        require(value.get("pending") is Boolean && value.get("trashed") is Boolean)
        require(value.getInt("version") == 1)
        require(value.keys().asSequence().toSet() == setOf("version", "uri", "owner", "name", "path", "mime", "added", "modified", "size", "pending", "trashed", "bytes", "digest"))
        return Snapshot(PrivateExportDestination(value.getString("uri"), value.getString("owner"), value.getString("name"),
            value.getString("path"), value.getString("mime"), value.getLong("added"), value.getLong("modified"),
            if (value.isNull("size")) null else value.getLong("size"), value.getBoolean("pending"), value.getBoolean("trashed")),
            if (value.isNull("bytes")) null else value.getLong("bytes"), if (value.isNull("digest")) null else value.getString("digest"))
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
