package com.ugallery.feature.remotebackup

import android.content.Context
import android.net.Uri
import com.ugallery.core.remotestorage.*
import com.ugallery.feature.settings.*
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import kotlinx.coroutines.*

/**
 * One durable task lease. All network resources are closed on pause, cancellation or scheduler
 * stop.
 */
class RemoteBackupRunner(context: Context, private val services: RemoteBackupServices) {
    private val context = context.applicationContext
    private val store = RemoteBackupStore(this.context)
    @Volatile private var activeToken: RemoteCancellation? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var stopped = false

    fun cancel() {
        stopped = true
        activeToken?.cancel()
        activeJob?.cancel()
    }

    suspend fun run(id: String): Boolean =
        withContext(Dispatchers.IO) { leased(id).also { releaseFinishedGrants(id) } }

    /** A completed or cancelled task no longer reads its sources or writes its destination. */
    private fun releaseFinishedGrants(id: String) {
        val task = runCatching { store.get(id) }.getOrNull() ?: return
        // RestoringLocally transferred the destination grant to the local restore ledger.
        if (
            task.status == RemoteBackupStatus.Completed ||
                task.status == RemoteBackupStatus.Cancelled
        )
            runCatching { RemoteBackupGrants(context).release(id) }
    }

    private suspend fun leased(id: String): Boolean =
        withContext(Dispatchers.IO) {
            RandomAccessFile(store.lease(id), "rw").use { lockFile ->
                val lease =
                    try {
                        lockFile.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (lease == null) return@withContext false
                lease.use {
                    val initial =
                        try {
                            store.get(id)
                        } catch (_: Exception) {
                            return@withContext true
                        } ?: return@withContext true
                    if (initial.terminal) return@withContext true
                    if (initial.cancelRequested) {
                        cancelled(id)
                        return@withContext true
                    }
                    if (initial.status == RemoteBackupStatus.NeedsReview) return@withContext true
                    if (
                        initial.pauseRequested ||
                            initial.status in
                                setOf(
                                    RemoteBackupStatus.AwaitingUploadReview,
                                    RemoteBackupStatus.AwaitingRestoreReview,
                                    RemoteBackupStatus.WaitingIdentity,
                                )
                    )
                        return@withContext true
                    val token = RemoteCancellation()
                    activeToken = token
                    if (stopped) throw CancellationException("Worker stopped")
                    try {
                        coroutineScope {
                            val operation =
                                async(Dispatchers.IO) {
                                    activeJob = currentCoroutineContext().job
                                    store.update(id) {
                                        if (it.cancelRequested || it.pauseRequested)
                                            throw CancellationException()
                                        it.copy(status = RemoteBackupStatus.Running, failure = null)
                                    }
                                    execute(id, token)
                                }
                            val watcher =
                                launch(Dispatchers.IO) {
                                    try {
                                        RemoteBackupStore.changes.collect {
                                            val task = store.get(id)
                                            if (
                                                task?.pauseRequested == true ||
                                                    task?.cancelRequested == true
                                            ) {
                                                token.cancel()
                                                operation.cancel()
                                            }
                                        }
                                    } finally {
                                        token.cancel()
                                    }
                                }
                            try {
                                operation.await()
                            } finally {
                                watcher.cancel()
                            }
                        }
                    } catch (error: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            val state = store.get(id) ?: return@withContext
                            if (!state.terminal) {
                                when {
                                    state.cancelRequested -> cancelled(id)
                                    state.pauseRequested ->
                                        store.update(id) {
                                            it.copy(status = RemoteBackupStatus.Paused)
                                        }
                                    error is CancellationException ->
                                        store.update(id) {
                                            it.copy(status = RemoteBackupStatus.Queued)
                                        }
                                    error is RemoteContentChanged ->
                                        store.update(id) {
                                            it.copy(
                                                status = RemoteBackupStatus.NeedsReview,
                                                failure = "CONFLICT",
                                            )
                                        }
                                    error is SecurityException ->
                                        store.update(id) {
                                            it.copy(
                                                status = RemoteBackupStatus.WaitingPermission,
                                                failure = "PERMISSION",
                                            )
                                        }
                                    error is RemoteStorageException ->
                                        store.update(id) {
                                            it.copy(
                                                status =
                                                    when (error.failure) {
                                                        RemoteFailure.AUTHENTICATION ->
                                                            RemoteBackupStatus.WaitingCredentials
                                                        RemoteFailure.IDENTITY_REQUIRED,
                                                        RemoteFailure.IDENTITY_CHANGED ->
                                                            RemoteBackupStatus.WaitingIdentity
                                                        RemoteFailure.PERMISSION ->
                                                            RemoteBackupStatus.WaitingPermission
                                                        RemoteFailure.CONFLICT,
                                                        RemoteFailure.ALREADY_EXISTS ->
                                                            RemoteBackupStatus.NeedsReview
                                                        RemoteFailure.CONNECTION,
                                                        RemoteFailure.CANCELLED ->
                                                            RemoteBackupStatus.WaitingConnection
                                                        else -> RemoteBackupStatus.Failed
                                                    },
                                                failure = error.failure.name,
                                                observedHostKey = error.observedHostKey,
                                                residuals =
                                                    (it.residuals + error.residualNames)
                                                        .distinct()
                                                        .take(1000),
                                            )
                                        }
                                    else ->
                                        store.update(id) {
                                            it.copy(
                                                status = RemoteBackupStatus.Failed,
                                                failure = "IO",
                                            )
                                        }
                                }
                            }
                        }
                        if (error is CancellationException && !currentCoroutineContext().isActive)
                            throw error
                    } finally {
                        token.cancel()
                        activeToken = null
                        activeJob = null
                    }
                    true
                }
            }
        }

    private suspend fun cancelled(id: String) {
        val task = store.get(id) ?: return
        if (task.phase == RemoteBackupPhase.RestoreHandoff) {
            val child =
                try {
                    services.restore.existing(id)
                } catch (_: Exception) {
                    store.update(id) {
                        it.copy(
                            status = RemoteBackupStatus.NeedsReview,
                            failure = "RESTORE_RECEIPT",
                        )
                    }
                    return
                }
            if (child != null) {
                store.update(id) {
                    it.copy(
                        localTaskId = child,
                        status = RemoteBackupStatus.RestoringLocally,
                        phase = RemoteBackupPhase.Done,
                    )
                }
                return
            }
        }
        // No remote deletion API: server objects, including uncertain publications, remain listed.
        // Local archives also remain with history for inspection and an in-flight local restore.
        store.update(id) { it.copy(status = RemoteBackupStatus.Cancelled, failure = null) }
    }

    private suspend fun execute(id: String, token: RemoteCancellation) {
        val coroutine = currentCoroutineContext()
        val check = {
            coroutine.ensureActive()
            token.check()
        }
        val task = store.get(id)!!
        if (task.phase == RemoteBackupPhase.RestoreHandoff) {
            val archive = store.archive(id)
            require(
                RemoteBackupIO.digest(archive, check) ==
                    RemoteDigest(task.totalBytes, requireNotNull(task.archiveSha))
            )
            val manifest = LocalBackupArchive.inspect(archive, check)
            withContext(NonCancellable + Dispatchers.IO) {
                val child =
                    services.restore.enqueueOnce(
                        task.id,
                        archive,
                        manifest,
                        task.restoreDestination?.let(Uri::parse),
                        task.restoreGallery,
                        task.restoreOptions,
                    )
                // Receipt/handoff succeeded: cancellation must not orphan or undo the local child.
                store.update(id) {
                    it.copy(
                        localTaskId = child,
                        status = RemoteBackupStatus.RestoringLocally,
                        phase = RemoteBackupPhase.Done,
                    )
                }
            }
            return
        }
        if (
            task.direction == RemoteBackupDirection.Upload &&
                task.phase == RemoteBackupPhase.Preparing
        ) {
            val storage = LocalBackupStorage(context)
            try {
                val sources =
                    task.sources.map { uri ->
                        val source = storage.source(Uri.parse(uri))
                        LocalBackupArchive.Source(source.name, source.mime, source.sourceUri) {
                            checked(source.open(), check)
                        }
                    }
                val partial = store.partial(id)
                if (partial.exists() && !partial.delete())
                    throw IOException("Private staging unavailable")
                val manifest =
                    if (task.organization)
                        LocalBackupArchive.createOrganized(
                            sources,
                            partial,
                            requireNotNull(services.organization),
                        )
                    else LocalBackupArchive.create(sources, partial, check)
                check()
                val digest = RemoteBackupIO.digest(partial, check)
                FileOutputStream(partial, true).use { it.fd.sync() }
                if (!partial.renameTo(store.archive(id)))
                    throw IOException("Private archive publication failed")
                store.update(id) {
                    it.copy(
                        status = RemoteBackupStatus.AwaitingUploadReview,
                        totalBytes = digest.size,
                        archiveSha = digest.sha256,
                        files = manifest.entries.size,
                        bytesDone = 0,
                    )
                }
            } finally {
                storage.close()
            }
            return
        }
        if (!services.networkAllowed()) throw RemoteStorageException(RemoteFailure.PERMISSION)
        val credentials =
            services.credentials.load(task.profile.id)
                ?: throw RemoteStorageException(RemoteFailure.AUTHENTICATION)
        credentials.use { secret ->
            services.connections.connect(task.profile, secret, token).use { connection ->
                try {
                    if (task.direction == RemoteBackupDirection.Upload)
                        upload(id, connection, check)
                    else download(id, connection, check)
                } finally {
                    if (connection.residualNames.isNotEmpty())
                        store.update(id) {
                            it.copy(
                                residuals =
                                    (it.residuals + connection.residualNames).distinct().take(1000)
                            )
                        }
                }
            }
        }
    }

    private fun upload(id: String, connection: RemoteConnection, check: () -> Unit) {
        RemoteBackupIO.requireUploadCapability(connection)
        var task = store.get(id)!!
        val expected = RemoteDigest(task.totalBytes, requireNotNull(task.archiveSha))
        val archive = store.archive(id)
        if (RemoteBackupIO.digest(archive, check) != expected) throw RemoteContentChanged()
        if (task.phase == RemoteBackupPhase.Publish && connection.stat(task.name) != null) {
            val actual = connection.openRead(task.name).use { RemoteBackupIO.digest(it, check) }
            if (actual != expected) throw RemoteContentChanged()
            store.update(id) {
                it.copy(
                    status = RemoteBackupStatus.Completed,
                    phase = RemoteBackupPhase.Done,
                    bytesDone = expected.size,
                )
            }
            return
        }
        if (connection.stat(task.name) != null)
            throw RemoteStorageException(RemoteFailure.ALREADY_EXISTS)
        val staging = ".ugallery-${task.id}-${UUID.randomUUID()}.partial"
        task =
            store.update(id) {
                require(it.residuals.size < 998)
                it.copy(
                    staging = staging,
                    residuals = (it.residuals + staging).distinct(),
                    phase = RemoteBackupPhase.Transfer,
                    bytesDone = 0,
                )
            }
        var lastSaved = 0L
        connection.createExclusive(staging).use { output ->
            archive.inputStream().use { input ->
                val copied =
                    RemoteBackupIO.copy(input, output, check, expected.size) { bytes ->
                        if (bytes - lastSaved >= 1024 * 1024 || bytes == expected.size) {
                            store.update(id) { it.copy(bytesDone = bytes) }
                            lastSaved = bytes
                        }
                    }
                if (copied != expected) throw RemoteContentChanged()
            }
        }
        check()
        store.update(id) {
            it.copy(
                phase = RemoteBackupPhase.Publish,
                residuals = (it.residuals + it.name).distinct(),
            )
        }
        connection.publishNoReplace(staging, task.name, expected)
        // Transport contract includes full remote destination readback before returning.
        store.update(id) {
            it.copy(
                status = RemoteBackupStatus.Completed,
                phase = RemoteBackupPhase.Done,
                bytesDone = expected.size,
            )
        }
    }

    private fun download(id: String, connection: RemoteConnection, check: () -> Unit) {
        val task = store.get(id)!!
        val remote = requireNotNull(task.remote)
        RemoteBackupIO.requireSame(remote, connection.stat(remote.name))
        val partial = store.partial(id)
        var lastSaved = 0L
        connection.openRead(remote.name).use { input ->
            val prefix = RemoteBackupIO.comparePrefix(partial, input, check)
            if (prefix > remote.size) throw RemoteContentChanged()
            FileOutputStream(partial, true).use { output ->
                val appended =
                    RemoteBackupIO.copy(input, output, check, remote.size - prefix) { bytes ->
                        val done = prefix + bytes
                        if (done - lastSaved >= 1024 * 1024 || done == remote.size) {
                            store.update(id) { it.copy(bytesDone = done) }
                            lastSaved = done
                        }
                    }
                if (prefix + appended.size != remote.size) throw RemoteContentChanged()
                output.fd.sync()
            }
        }
        check()
        RemoteBackupIO.requireSame(remote, connection.stat(remote.name))
        store.update(id) { it.copy(phase = RemoteBackupPhase.Verify) }
        val manifest = LocalBackupArchive.inspect(partial, check)
        val digest = RemoteBackupIO.digest(partial, check)
        if (!partial.renameTo(store.archive(id)))
            throw IOException("Private download publication failed")
        store.update(id) {
            it.copy(
                status = RemoteBackupStatus.AwaitingRestoreReview,
                totalBytes = digest.size,
                bytesDone = digest.size,
                archiveSha = digest.sha256,
                files = manifest.entries.size,
            )
        }
    }

    private fun checked(input: InputStream, check: () -> Unit): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                check()
                return super.read()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                check()
                return `in`.read(bytes, offset, length)
            }
        }
}
