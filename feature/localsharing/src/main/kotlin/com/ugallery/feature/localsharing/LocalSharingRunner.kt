package com.ugallery.feature.localsharing

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

/**
 * Per-file durable transfer; checkpoint comes from verified actual receiver prefixes, not guesses.
 */
class LocalSharingRunner(private val context: Context, private val services: LocalSharingServices) {
    private val store = LocalSharingStore(context)
    private val sockets = PeerSockets()

    fun cancel() {
        sockets.close()
    }

    fun needsRetry(id: String) =
        store.transfer(id)?.let {
            !it.terminal &&
                (it.cancelRequested ||
                    it.status in
                        setOf(
                            LocalSharingStatus.Preparing,
                            LocalSharingStatus.Queued,
                            LocalSharingStatus.Transferring,
                        ) && !it.pauseRequested)
        } ?: false

    suspend fun run(id: String): Boolean =
        withContext(Dispatchers.IO) {
            val initial = store.transfer(id) ?: return@withContext true
            if (initial.terminal) return@withContext true
            RandomAccessFile(store.lease(id), "rw").use { lease ->
                (try {
                        lease.channel.tryLock()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        null
                    })
                    ?.use {
                        coroutineScope {
                            val operation = currentCoroutineContext()
                            val watcher = launch {
                                try {
                                    LocalSharingStore.revision.collect {
                                        val state = store.transfer(id)
                                        if (
                                            state?.pauseRequested == true ||
                                                state?.cancelRequested == true
                                        )
                                            sockets.close()
                                    }
                                } finally {
                                    sockets.close()
                                }
                            }
                            try {
                                val task = store.transfer(id)!!
                                if (task.cancelRequested) {
                                    cancelTask(task)
                                    return@coroutineScope true
                                }
                                if (task.pauseRequested) return@coroutineScope true
                                val check = {
                                    operation.ensureActive()
                                    sockets.check()
                                    Unit
                                }
                                if (task.direction == LocalSharingDirection.Receive) {
                                    if (task.status == LocalSharingStatus.Importing)
                                        import(task, check)
                                    return@coroutineScope true
                                }
                                if (task.status == LocalSharingStatus.Preparing)
                                    prepare(task, check)
                                val prepared = store.transfer(id)!!
                                if (
                                    prepared.status == LocalSharingStatus.Queued ||
                                        prepared.status == LocalSharingStatus.Transferring
                                )
                                    send(prepared, check)
                                true
                            } catch (e: Throwable) {
                                withContext(NonCancellable) {
                                    val t = store.transfer(id)!!
                                    if (!t.terminal) {
                                        if (t.cancelRequested) {
                                            try {
                                                cancelTask(t)
                                            } catch (cleanup: Throwable) {
                                                store.update(id) {
                                                    it.copy(
                                                        status = LocalSharingStatus.NeedsReview,
                                                        failure = "cleanup",
                                                        cancelRequested = false,
                                                        pauseRequested = false,
                                                    )
                                                }
                                            }
                                        } else
                                            store.update(id) {
                                                it.copy(
                                                    resumeStatus = it.resumeStatus ?: it.status,
                                                    status =
                                                        when {
                                                            it.pauseRequested ||
                                                                e is CancellationException ||
                                                                e is PeerStopped ->
                                                                LocalSharingStatus.Paused
                                                            e is SecurityException ->
                                                                LocalSharingStatus.WaitingPeer
                                                            e is PeerFailure &&
                                                                e.reason in
                                                                    setOf(
                                                                        "consent",
                                                                        "paused",
                                                                        "busy",
                                                                        "missing",
                                                                    ) ->
                                                                LocalSharingStatus.WaitingPeer
                                                            e is java.net.ConnectException ||
                                                                e is
                                                                    java.net.SocketTimeoutException ||
                                                                e is java.net.SocketException ->
                                                                LocalSharingStatus.WaitingPeer
                                                            else -> LocalSharingStatus.NeedsReview
                                                        },
                                                    failure =
                                                        when (e) {
                                                            is PeerFailure -> e.reason
                                                            is SecurityException -> "permission"
                                                            else -> "transfer"
                                                        },
                                                )
                                            }
                                    }
                                }
                                true
                            } finally {
                                watcher.cancel()
                                sockets.close()
                            }
                        }
                    } ?: false
            }
        }

    private suspend fun prepare(t: LocalSharingTransfer, check: () -> Unit) {
        val dir = store.directory(t.id)
        dir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("attempt-[a-f0-9-]{36}")) }
            .forEach { it.deleteRecursively() }
        val attempt = File(dir, "attempt-${UUID.randomUUID()}").apply { mkdirs() }
        val prepared = services.sourcePort.prepare(t.selection, t.stripLocation, attempt, check)
        val manifest = LocalSharingManifest(prepared.map { it.entry }, t.stripLocation)
        manifest.validate()
        require(prepared.map { it.fileName }.distinct().size == prepared.size)
        val files =
            prepared.map { source ->
                check()
                require(peerName(source.fileName))
                val file = File(attempt, source.fileName)
                require(file.canonicalPath.startsWith(attempt.canonicalPath + File.separator))
                require(peerDigest(file, check) == (source.entry.bytes to source.entry.sha256))
                RandomAccessFile(file, "rw").use { it.fd.sync() }
                "${attempt.name}/${source.fileName}"
            }
        check()
        store.update(t.id) {
            it.copy(
                manifest = manifest,
                preparedFiles = files,
                status = LocalSharingStatus.AwaitingReview,
            )
        }
        // The private snapshots are all the transfer reads from now on.
        runCatching { services.sourcePort.release(t.id) }
    }

    private fun send(t: LocalSharingTransfer, check: () -> Unit) {
        if (!services.networkAllowed()) throw PeerFailure("permission")
        val peer =
            store.peers().singleOrNull { it.id == t.peerId && !it.revoked && it.canSend }
                ?: throw PeerFailure("revoked")
        val manifest = t.manifest!!
        manifest.validate()
        require(t.preparedFiles.size == manifest.entries.size)
        val client = PeerClient(context, sockets)
        val offer =
            client.authorized(
                peer,
                JSONObject()
                    .put("op", "offer")
                    .put("id", t.id)
                    .put("manifest", PeerCodec.manifest(manifest)),
            )
        var status = LocalSharingStatus.valueOf(offer.getString("status"))
        if (
            status in
                setOf(
                    LocalSharingStatus.ReadyToImport,
                    LocalSharingStatus.Importing,
                    LocalSharingStatus.LocalTaskCreated,
                )
        ) {
            store.update(t.id) {
                it.copy(status = LocalSharingStatus.Completed, bytesDone = manifest.totalBytes)
            }
            return
        }
        if (status != LocalSharingStatus.Transferring) throw PeerFailure("consent")
        store.update(t.id) { it.copy(status = LocalSharingStatus.Transferring) }
        val remote = client.authorized(peer, JSONObject().put("op", "status").put("id", t.id))
        status = LocalSharingStatus.valueOf(remote.getString("status"))
        if (status != LocalSharingStatus.Transferring) throw PeerFailure("consent")
        val offsets = remote.getJSONArray("files")
        require(offsets.length() == manifest.entries.size)
        var completed = 0L
        manifest.entries.forEachIndexed { index, entry ->
            check()
            val file = store.source(t.id, t.preparedFiles[index])
            require(peerDigest(file, check) == (entry.bytes to entry.sha256))
            val remoteFile = offsets.getJSONObject(index)
            var offset = remoteFile.getLong("bytes")
            require(offset in 0..entry.bytes)
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { input ->
                val buffer = ByteArray(PEER_CHUNK_LIMIT)
                var prefix = 0L
                while (prefix < offset) {
                    check()
                    val n =
                        input.read(buffer, 0, minOf(buffer.size.toLong(), offset - prefix).toInt())
                    require(n > 0)
                    digest.update(buffer, 0, n)
                    prefix += n
                }
                require(digest.digest().peerHex() == remoteFile.getString("sha"))
                while (offset < entry.bytes) {
                    check()
                    val n =
                        input.read(
                            buffer,
                            0,
                            minOf(buffer.size.toLong(), entry.bytes - offset).toInt(),
                        )
                    require(n > 0)
                    val payload = buffer.copyOf(n)
                    val reply =
                        client.authorized(
                            peer,
                            JSONObject()
                                .put("op", "chunk")
                                .put("id", t.id)
                                .put("index", index)
                                .put("offset", offset)
                                .put("size", n)
                                .put(
                                    "sha",
                                    MessageDigest.getInstance("SHA-256").digest(payload).peerHex(),
                                ),
                            payload,
                        )
                    require(reply.getLong("offset") == offset + n)
                    offset += n
                    store.update(t.id) { it.copy(bytesDone = completed + offset) }
                }
            }
            completed += entry.bytes
        }
        check()
        val finished = client.authorized(peer, JSONObject().put("op", "finish").put("id", t.id))
        require(finished.getString("status") == LocalSharingStatus.ReadyToImport.name)
        store.update(t.id) {
            it.copy(status = LocalSharingStatus.Completed, bytesDone = manifest.totalBytes)
        }
    }

    private suspend fun import(t: LocalSharingTransfer, check: () -> Unit) {
        services.importPort.existing(t.id)?.let { child ->
            store.update(t.id) {
                it.copy(localTaskId = child, status = LocalSharingStatus.LocalTaskCreated)
            }
            return
        }
        val manifest = t.manifest!!
        val files =
            manifest.entries.mapIndexed { index, e ->
                val f = store.received(t.id, index)
                require(peerDigest(f, check) == (e.bytes to e.sha256))
                LocalSharingReceivedFile(e, f)
            }
        check()
        val child = services.importPort.enqueueOnce(t.peerId, t.id, manifest, files, t.choices)
        withContext(NonCancellable) {
            store.update(t.id) {
                it.copy(localTaskId = child, status = LocalSharingStatus.LocalTaskCreated)
            }
        }
    }

    private suspend fun cancelTask(t: LocalSharingTransfer) {
        if (t.direction == LocalSharingDirection.Receive) {
            services.importPort.existing(t.id)?.let { child ->
                store.update(t.id) {
                    it.copy(status = LocalSharingStatus.LocalTaskCreated, localTaskId = child)
                }
                return
            }
        }
        if (t.direction == LocalSharingDirection.Receive) {
            services.importPort.cancel(t.id)
            services.importPort.existing(t.id)?.let { receipt ->
                store.update(t.id) {
                    it.copy(status = LocalSharingStatus.LocalTaskCreated, localTaskId = receipt)
                }
                return
            }
        }
        store.update(t.id) { it.copy(status = LocalSharingStatus.Cancelled) }
        if (t.direction == LocalSharingDirection.Send)
            runCatching { services.sourcePort.release(t.id) }
        store.cleanupPrivate(t.id)
    }
}
