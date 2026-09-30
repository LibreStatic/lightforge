package com.librestatic.lightforge

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryRestoreReceiptEntity
import com.librestatic.lightforge.core.database.PeerImportSourceEntity
import com.librestatic.lightforge.core.database.PeerImportVersionEntity
import com.librestatic.lightforge.core.mediastore.MediaStoreReader
import com.librestatic.lightforge.feature.localsharing.*
import com.librestatic.lightforge.feature.settings.BackupManifest
import com.librestatic.lightforge.feature.settings.LocalBackupTaskRetryEntry
import com.librestatic.lightforge.feature.settings.LocalRestoreGalleryResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * A completed Room receipt, not an enqueued placeholder. Every new MediaStore destination comes
 * from the proven journalled writer. Private request hashes bind peer, transfer, rendition and
 * choices; previous revisions are never overwritten, deleted, or rebound by content/name alone.
 */
class GalleryLocalSharingImportPort(context: Context, private val database: GalleryDatabase) :
    LocalSharingImportPort {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, ROOT).apply { mkdirs() }
    private val reader = MediaStoreReader(this.context.contentResolver)

    override suspend fun review(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
    ): List<LocalSharingImportItem> =
        withContext(Dispatchers.IO) {
            validate(peerId, transferId, manifest)
            manifest.entries.map { e ->
                val version = database.peerImportDao().version(peerId, e.sourceId, e.revision)
                val active = database.peerImportDao().source(peerId, e.sourceId)
                if (version != null)
                    require(version.sha256 == e.sha256 && version.sizeBytes == e.bytes) {
                        "Peer reused revision with different bytes"
                    }
                LocalSharingImportItem(
                    e.sourceId,
                    when {
                        version != null -> LocalSharingImportDisposition.AlreadyReceived
                        active != null -> LocalSharingImportDisposition.Conflict
                        else -> LocalSharingImportDisposition.Add
                    },
                    active?.modifiedMillis,
                )
            }
        }

    override suspend fun existing(transferId: String): String? =
        withContext(Dispatchers.IO) {
            uuid(transferId)
            val request = readRequest(transferId) ?: return@withContext null
            receipt(request)?.operationId
        }

    override suspend fun enqueueOnce(
        peerId: String,
        transferId: String,
        manifest: LocalSharingManifest,
        files: List<LocalSharingReceivedFile>,
        choices: Map<String, LocalSharingConflictChoice>,
    ): String =
        withContext(Dispatchers.IO) {
            gate.withLock {
                validate(peerId, transferId, manifest)
                val coroutine = currentCoroutineContext()
                val check = { coroutine.ensureActive() }
                val offered = payload(peerId, transferId, manifest, choices)
                val existingRequest = readRequest(transferId)
                if (existingRequest != null) {
                    require(existingRequest.payload.toString() == offered.toString()) {
                        "Peer request identity changed"
                    }
                    receipt(existingRequest)?.let {
                        return@withLock it.operationId
                    }
                }
                require(
                    files.size == manifest.entries.size &&
                        files.map { it.entry } == manifest.entries
                )
                files.forEachIndexed { index, item ->
                    require(item.file.canonicalPath == received(transferId, index).canonicalPath) {
                        "Input is not this transfer's private received file"
                    }
                    verify(item.file, item.entry, check)
                }
                val request =
                    existingRequest
                        ?: run {
                            val reviewed = review(peerId, transferId, manifest)
                            require(
                                choices.keys ==
                                    reviewed
                                        .filter {
                                            it.disposition == LocalSharingImportDisposition.Conflict
                                        }
                                        .map { it.sourceId }
                                        .toSet()
                            )
                            val pending =
                                reviewed
                                    .filter {
                                        it.disposition !=
                                            LocalSharingImportDisposition.AlreadyReceived
                                    }
                                    .map { it.sourceId }
                            Request(
                                    peerId,
                                    transferId,
                                    operation(peerId, transferId),
                                    manifest,
                                    choices,
                                    pending,
                                    offered,
                                )
                                .also(::writeRequest)
                        }
                RandomAccessFile(File(root, "publication.lease"), "rw").use { file ->
                    val lease =
                        try {
                            file.channel.tryLock()
                        } catch (_: java.nio.channels.OverlappingFileLockException) {
                            null
                        }
                    lease?.use {
                        receipt(request)?.let {
                            return@withLock it.operationId
                        }
                        if (request.pending.isEmpty()) {
                            commit(request, emptyList())
                            return@withLock request.operationId
                        }
                        var writer: GalleryRestoreMediaSession? = null
                        try {
                            writer = session(request)
                            request.manifest.entries.forEachIndexed { index, entry ->
                                if (entry.sourceId in request.pending) {
                                    check()
                                    val backup = entry.backup(index, request.peer)
                                    try {
                                        received(transferId, index).inputStream().use {
                                            writer!!.stage(backup, it)
                                        }
                                    } catch (_: LocalBackupTaskRetryEntry) {
                                        received(transferId, index).inputStream().use {
                                            writer!!.stage(backup, it)
                                        }
                                    }
                                }
                            }
                            check()
                            writer!!.commit()
                            require(receipt(request) != null)
                            request.operationId
                        } catch (error: Throwable) {
                            // Process/worker interruption keeps original input and owned MediaStore
                            // journal for exact resume. Only explicit cancel calls abort().
                            withContext(NonCancellable) {
                                runCatching { writer?.pause() }
                                    .exceptionOrNull()
                                    ?.let(error::addSuppressed)
                            }
                            throw error
                        }
                    } ?: throw IOException("Another local import is publishing")
                }
            }
        }

    override suspend fun cancel(transferId: String) =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val request = readRequest(transferId) ?: return@withLock
                if (receipt(request) != null) return@withLock
                val journal =
                    File(context.filesDir, "gallery-restore-journal/${request.operationId}.json")
                if (!journal.exists() && !File(journal.path + ".bak").exists()) return@withLock
                var writer: GalleryRestoreMediaSession? = null
                try {
                    writer = session(request)
                    val coroutine = currentCoroutineContext()
                    request.manifest.entries.forEachIndexed { index, e ->
                        if (e.sourceId in request.pending) {
                            val input = received(transferId, index)
                            verify(input, e) { coroutine.ensureActive() }
                            input.inputStream().use {
                                writer!!.verifyBeforeAbort(e.backup(index, request.peer), it)
                            }
                        }
                    }
                    writer!!.abort()
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        runCatching { writer?.pause() }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                    throw error
                }
            }
        }

    private fun session(request: Request) =
        GalleryRestoreMediaSession(
            context,
            request.operationId,
            emptyMap(),
            commitMetadata = { id, media ->
                require(id == request.operationId)
                commit(request, media)
            },
            isCommitted = { id ->
                require(id == request.operationId)
                receipt(request) != null
            },
            resume = true,
        )

    private suspend fun commit(
        request: Request,
        media: List<GalleryRestoredMedia>,
    ): LocalRestoreGalleryResult =
        database.withTransaction {
            receipt(request)?.let {
                return@withTransaction LocalRestoreGalleryResult(
                    it.files,
                    it.importedObjects,
                    it.skippedObjects,
                )
            }
            val expected = request.manifest.entries.filter { it.sourceId in request.pending }
            require(
                media.size == expected.size &&
                    media.map { it.entry.sourceId }.toSet() ==
                        expected.map { sourceUuid(request.peer, it) }.toSet()
            )
            // Recheck every historical revision and conflict after publication, in the same
            // transaction
            // as inventory and receipt. This never overwrites a concurrent user or peer import
            // decision.
            request.manifest.entries.forEach { entry ->
                val old =
                    database.peerImportDao().version(request.peer, entry.sourceId, entry.revision)
                if (entry.sourceId !in request.pending)
                    require(
                        old != null && old.sha256 == entry.sha256 && old.sizeBytes == entry.bytes
                    )
                else require(old == null) { "Revision was imported concurrently" }
            }
            media.forEach { item ->
                val entry = expected.single { sourceUuid(request.peer, it) == item.entry.sourceId }
                val key = item.key
                if (key != null) {
                    val record =
                        reader.readOne(key) ?: throw IOException("Published peer media missing")
                    require(
                        record.generationAdded == item.generationAdded &&
                            record.generationModified == item.generationModified &&
                            record.sizeBytes == entry.bytes &&
                            !record.isTrashed
                    )
                    database.libraryDao().upsertMedia(listOf(record.portableEntity()))
                }
                database
                    .peerImportDao()
                    .insertVersion(
                        PeerImportVersionEntity(
                            request.peer,
                            entry.sourceId,
                            entry.revision,
                            request.operationId,
                            item.uri.toString(),
                            key?.volumeName,
                            key?.mediaStoreId,
                            item.generationAdded,
                            item.generationModified,
                            entry.sha256,
                            entry.bytes,
                            entry.modifiedMillis,
                        )
                    )
                val active = database.peerImportDao().source(request.peer, entry.sourceId)
                require(active == null || entry.sourceId in request.choices) {
                    "A new conflict requires review"
                }
                if (
                    active == null ||
                        request.choices[entry.sourceId] == LocalSharingConflictChoice.UseNewest &&
                            entry.modifiedMillis > active.modifiedMillis
                ) {
                    database
                        .peerImportDao()
                        .putSource(
                            PeerImportSourceEntity(
                                request.peer,
                                entry.sourceId,
                                entry.revision,
                                entry.modifiedMillis,
                            )
                        )
                }
            }
            val skipped = request.manifest.entries.size - media.size
            database
                .galleryRestoreReceiptDao()
                .insert(
                    GalleryRestoreReceiptEntity(
                        request.operationId,
                        request.fingerprint,
                        media.size,
                        media.size,
                        skipped,
                        System.currentTimeMillis(),
                    )
                )
            LocalRestoreGalleryResult(media.size, media.size, skipped)
        }

    private suspend fun receipt(r: Request) =
        database.galleryRestoreReceiptDao().get(r.operationId)?.also {
            require(
                it.snapshotId == r.fingerprint &&
                    it.files + it.skippedObjects == r.manifest.entries.size
            ) {
                "Receipt belongs to another request"
            }
        }

    private data class Request(
        val peer: String,
        val transfer: String,
        val operationId: String,
        val manifest: LocalSharingManifest,
        val choices: Map<String, LocalSharingConflictChoice>,
        val pending: List<String>,
        val payload: JSONObject,
    ) {
        val fingerprint
            get() =
                "peer-import-v1:" +
                    sha((payload.toString() + "\n" + JSONArray(pending).toString()).toByteArray())
    }

    private fun writeRequest(r: Request) {
        val directory = File(root, r.transfer).apply { mkdirs() }
        require(
            directory.listFiles().orEmpty().none {
                it.name.endsWith(".json") || it.name.endsWith(".json.bak")
            }
        )
        val json =
            JSONObject()
                .put("v", 1)
                .put("payload", r.payload)
                .put("pending", JSONArray(r.pending))
                .put("fingerprint", r.fingerprint)
        val a = AtomicFile(File(directory, "${r.operationId}.json"))
        var output: FileOutputStream? = null
        try {
            output = a.startWrite()
            output.write(json.toString().toByteArray())
            a.finishWrite(output)
        } catch (e: Throwable) {
            a.failWrite(output)
            throw e
        }
    }

    private fun readRequest(id: String): Request? {
        uuid(id)
        val directory = File(root, id)
        val names =
            directory
                .listFiles()
                .orEmpty()
                .map { it.name.removeSuffix(".bak") }
                .filter { it.endsWith(".json") }
                .distinct()
        if (names.isEmpty()) return null
        require(names.size == 1)
        val operation = names.single().removeSuffix(".json").also(::uuid)
        val text =
            AtomicFile(File(directory, names.single())).openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val b = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(b)
                    if (count < 0) break
                    total += count
                    require(total <= 8 * 1024 * 1024)
                    out.write(b, 0, count)
                }
                out.toString("UTF-8")
            }
        val j = JSONObject(text)
        require(j.getInt("v") == 1)
        val payload = j.getJSONObject("payload")
        val peer = payload.getString("peer")
        require(payload.getString("transfer") == id && operation(peer, id) == operation)
        val array = payload.getJSONArray("entries")
        require(array.length() in 1..LOCAL_SHARING_MAX_FILES)
        val entries =
            List(array.length()) { index ->
                val e = array.getJSONObject(index)
                LocalSharingEntry(
                    e.getString("source"),
                    e.getString("revision"),
                    e.getString("name"),
                    e.getString("mime"),
                    e.getLong("bytes"),
                    e.getString("sha"),
                    e.getLong("modified"),
                    e.getBoolean("sanitized"),
                )
            }
        val manifest = LocalSharingManifest(entries, payload.getBoolean("strip"))
        validate(peer, id, manifest)
        val rawChoices = payload.getJSONObject("choices")
        require(rawChoices.length() <= entries.size)
        val choices =
            rawChoices.keys().asSequence().associateWith {
                LocalSharingConflictChoice.valueOf(rawChoices.getString(it))
            }
        val pending = j.getJSONArray("pending")
        require(pending.length() <= entries.size)
        val ids = List(pending.length()) { pending.getString(it) }
        require(
            ids.distinct().size == ids.size && ids.all { it in entries.map { e -> e.sourceId } }
        )
        return Request(peer, id, operation, manifest, choices, ids, payload).also {
            require(it.fingerprint == j.getString("fingerprint"))
            require(payload.toString() == payload(peer, id, manifest, choices).toString())
        }
    }

    private fun received(id: String, index: Int) =
        File(context.filesDir, "local-sharing/tasks/$id/received-$index.partial")

    private fun payload(
        peer: String,
        id: String,
        m: LocalSharingManifest,
        choices: Map<String, LocalSharingConflictChoice>,
    ) =
        JSONObject()
            .put("peer", peer)
            .put("transfer", id)
            .put("strip", m.stripLocation)
            .put(
                "entries",
                JSONArray().apply {
                    m.entries.forEach { e ->
                        put(
                            JSONObject()
                                .put("source", e.sourceId)
                                .put("revision", e.revision)
                                .put("name", e.name)
                                .put("mime", e.mime)
                                .put("bytes", e.bytes)
                                .put("sha", e.sha256)
                                .put("modified", e.modifiedMillis)
                                .put("sanitized", e.sanitized)
                        )
                    }
                },
            )
            .put(
                "choices",
                JSONObject().apply { choices.toSortedMap().forEach { (k, v) -> put(k, v.name) } },
            )

    private fun LocalSharingEntry.backup(index: Int, peer: String) =
        BackupManifest.Entry(
            BackupManifest.path(index),
            name,
            mime,
            bytes,
            sha256,
            sourceUuid(peer, this),
        )

    private fun validate(peer: String, id: String, m: LocalSharingManifest) {
        require(peer.matches(Regex("[a-f0-9]{64}")))
        uuid(id)
        m.validate()
    }

    private fun verify(file: File, entry: LocalSharingEntry, check: () -> Unit) {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                check()
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                require(size <= entry.bytes)
                md.update(buffer, 0, n)
            }
        }
        require(
            size == entry.bytes &&
                md.digest().joinToString("") { "%02x".format(it) } == entry.sha256
        ) {
            "Received peer copy changed"
        }
    }

    companion object {
        private const val ROOT = "peer-import-requests"
        private val gate = Mutex()

        private fun uuid(id: String) {
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        }

        private fun operation(peer: String, id: String) =
            UUID.nameUUIDFromBytes("lightforge-peer-import-v1\u0000$peer\u0000$id".toByteArray())
                .toString()

        private fun sourceUuid(peer: String, e: LocalSharingEntry) =
            UUID.nameUUIDFromBytes(
                    "lightforge-peer-source-v1\u0000$peer\u0000${e.sourceId}\u0000${e.revision}"
                        .toByteArray()
                )
                .toString()

        private fun sha(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }

        /** Corrupt descriptors retain only their explicit operation UUID for manual review. */
        fun retainedOperations(context: Context): Set<String> =
            File(context.applicationContext.filesDir, ROOT)
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && runCatching { uuid(it.name) }.isSuccess }
                .flatMap { dir ->
                    dir.listFiles()
                        .orEmpty()
                        .map { it.name.removeSuffix(".bak").removeSuffix(".json") }
                        .filter { runCatching { uuid(it) }.isSuccess }
                }
                .toSet()
    }
}
