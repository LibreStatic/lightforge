package com.librestatic.lightforge.feature.ownsync

import android.content.Context
import android.net.Uri
import com.librestatic.lightforge.core.remotestorage.*
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Executes one job under a process/file lease. Publication and reversible moves have durable
 * intent.
 */
class OwnSyncRunner(context: Context, private val services: OwnSyncServices) {
    private val context = context.applicationContext
    private val store = OwnSyncStore(this.context)
    @Volatile private var cancellation: RemoteCancellation? = null
    @Volatile private var operation: Job? = null

    fun cancel() {
        cancellation?.cancel()
        operation?.cancel()
    }

    /**
     * Waiting for a user is not a scheduler retry; only an unfinished runnable lease is retried.
     */
    fun needsRetry(runId: String): Boolean =
        store
            .runs()
            .firstOrNull { it.id == runId }
            ?.let {
                it.status in listOf(OwnSyncStatus.Queued, OwnSyncStatus.Running) &&
                    !it.pauseRequested &&
                    !it.cancelRequested
            } ?: false

    suspend fun run(runId: String): Boolean =
        withContext(Dispatchers.IO) {
            val first = runCatching { store.run(runId) }.getOrNull() ?: return@withContext false
            if (first.failure == "corrupt") return@withContext false
            RandomAccessFile(store.lease(first.jobId), "rw").use { leaseFile ->
                val lease =
                    runCatching { leaseFile.channel.tryLock() }.getOrNull()
                        ?: return@withContext false
                lease.use {
                    val current = store.run(runId) ?: return@withContext false
                    if (current.terminal) return@withContext true
                    if (current.cancelRequested) {
                        store.update(runId) { it.copy(status = OwnSyncStatus.Cancelled) }
                        return@withContext true
                    }
                    if (
                        current.status in
                            listOf(
                                OwnSyncStatus.AwaitingReview,
                                OwnSyncStatus.Paused,
                                OwnSyncStatus.NeedsReview,
                                OwnSyncStatus.WaitingPermission,
                            )
                    )
                        return@withContext false
                    val token = RemoteCancellation()
                    cancellation = token
                    try {
                        coroutineScope {
                            val work = async {
                                operation = currentCoroutineContext()[Job]
                                val job = store.job(current.jobId) ?: throw IOException()
                                fun check() {
                                    try {
                                        token.check()
                                    } catch (error: RemoteStorageException) {
                                        if (error.failure == RemoteFailure.CANCELLED)
                                            throw CancellationException()
                                        else throw error
                                    }
                                    if (operation?.isActive == false) throw CancellationException()
                                    if (OwnSyncStore.interrupted(runId))
                                        throw CancellationException()
                                }
                                store.update(runId) {
                                    it.copy(status = OwnSyncStatus.Running, failure = null)
                                }
                                if (!services.networkAllowed()) throw SecurityException()
                                val credentials =
                                    services.credentials.load(job.profile.id)
                                        ?: throw RemoteStorageException(
                                            RemoteFailure.AUTHENTICATION
                                        )
                                credentials.use { secret ->
                                    services.connections.connect(job.profile, secret, token).use {
                                        connection ->
                                        val managed =
                                            connection as? RemoteManagedConnection
                                                ?: throw RemoteStorageException(
                                                    RemoteFailure.UNSUPPORTED
                                                )
                                        if (!managed.capabilities.atomicPublish)
                                            throw RemoteStorageException(RemoteFailure.UNSUPPORTED)
                                        val source = services.sourceFactory(context)
                                        if (!current.approved) {
                                            val snapshot = source.scan(Uri.parse(job.tree), ::check)
                                            val plan = preview(job, snapshot, managed, ::check)
                                            store.update(runId) {
                                                it.copy(
                                                    status = OwnSyncStatus.AwaitingReview,
                                                    snapshot = snapshot,
                                                    plan = plan,
                                                )
                                            }
                                        } else execute(runId, job, source, managed, ::check)
                                    }
                                }
                            }
                            val watcher = launch {
                                try {
                                    OwnSyncStore.changes.collectLatest {
                                        if (OwnSyncStore.interrupted(runId)) {
                                            token.cancel()
                                            work.cancel()
                                        }
                                    }
                                } finally {
                                    // Parent/Worker cancellation must close blocking sockets before
                                    // the scope joins work.
                                    token.cancel()
                                }
                            }
                            try {
                                work.await()
                            } finally {
                                watcher.cancelAndJoin()
                            }
                        }
                        true
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            runCatching {
                                store.update(runId) { state ->
                                    if (state.terminal) state
                                    else state.copy(status = state.interruptedStatus)
                                }
                            }
                        }
                        false
                    } catch (error: Exception) {
                        if (
                            error is RemoteStorageException &&
                                error.failure == RemoteFailure.CANCELLED ||
                                !currentCoroutineContext().isActive ||
                                OwnSyncStore.interrupted(runId)
                        ) {
                            withContext(NonCancellable) {
                                store.update(runId) { state ->
                                    if (state.terminal) state
                                    else
                                        state.copy(status = state.interruptedStatus, failure = null)
                                }
                            }
                            return@withContext false
                        }
                        val permission =
                            error is SecurityException ||
                                error is RemoteStorageException &&
                                    error.failure == RemoteFailure.PERMISSION
                        val failure =
                            when (error) {
                                is RemoteStorageException -> error.failure.name
                                is OwnSyncChanged -> "changed"
                                else -> if (permission) "permission" else "io"
                            }
                        store.update(runId) {
                            if (it.terminal) it
                            else
                                it.copy(
                                    status =
                                        if (permission) OwnSyncStatus.WaitingPermission
                                        else OwnSyncStatus.NeedsReview,
                                    failure = failure,
                                    residuals =
                                        (it.residuals +
                                                (error as? RemoteStorageException)
                                                    ?.residualNames
                                                    .orEmpty())
                                            .distinct(),
                                )
                        }
                        false
                    } finally {
                        token.cancel()
                        cancellation = null
                        operation = null
                        store.stage(runId).delete()
                    }
                }
            }
        }

    private fun remoteDigest(
        root: RemoteManagedConnection,
        path: List<String>,
        check: () -> Unit,
    ): RemoteDigest? =
        try {
            directory(root, path.dropLast(1), false) { dir ->
                val stat = dir.stat(path.last()) ?: return@directory null
                if (!stat.regularFile) return@directory null
                dir.openRead(path.last())
                    .use { OwnSyncIO.copy(it, check = check) }
                    .also { if (it.size != stat.size) throw OwnSyncChanged() }
            }
        } catch (error: RemoteStorageException) {
            if (error.failure == RemoteFailure.NOT_FOUND) null else throw error
        }

    private fun remotePresent(root: RemoteManagedConnection, path: List<String>): Boolean =
        try {
            directory(root, path.dropLast(1), false) { it.stat(path.last()) != null }
        } catch (error: RemoteStorageException) {
            if (error.failure == RemoteFailure.NOT_FOUND) false else throw error
        }

    private fun preview(
        job: OwnSyncJob,
        snapshot: OwnSyncSnapshot,
        root: RemoteManagedConnection,
        check: () -> Unit,
    ): List<OwnSyncPlanEntry> {
        val result = mutableListOf<OwnSyncPlanEntry>()
        val used = mutableSetOf<String>()
        val retained = mutableSetOf<String>()
        val caseCollisions =
            snapshot.entries
                .groupBy { it.path.joinToString("/").lowercase(java.util.Locale.ROOT) }
                .filterValues { it.size > 1 }
                .keys
        // Directory case aliases would merge two source folders on case-insensitive servers.
        val directoryCollisions =
            snapshot.directories
                .groupBy { it.joinToString("/").lowercase(java.util.Locale.ROOT) }
                .filterValues { it.distinct().size > 1 }
                .values
                .flatten()
                .toSet()
        snapshot.entries.forEach { entry ->
            check()
            if (
                entry.path.joinToString("/").lowercase(java.util.Locale.ROOT) in caseCollisions ||
                    directoryCollisions.any { entry.path.take(it.size) == it }
            ) {
                result +=
                    OwnSyncPlanEntry(
                        ownSyncUuid(),
                        OwnSyncAction.Inaccessible,
                        entry,
                        path = listOf(job.namespace) + entry.path,
                    )
                return@forEach
            }
            val existing =
                job.outputs
                    .filter {
                        it.sourceKey == entry.key &&
                            it.digest == entry.digest &&
                            it.quarantine == null
                    }
                    .firstOrNull { remoteDigest(root, it.path, check) == it.digest }
            if (existing != null) {
                retained += existing.id
                used += existing.path.joinToString("/")
                result +=
                    OwnSyncPlanEntry(
                        ownSyncUuid(),
                        OwnSyncAction.Verified,
                        entry,
                        existing,
                        existing.path,
                    )
                return@forEach
            }
            val desired = listOf(job.namespace) + entry.path
            val occupied = remotePresent(root, desired) || desired.joinToString("/") in used
            val path =
                if (occupied)
                    desired.dropLast(1) +
                        ownSyncVersionName(desired.last(), entry.key, entry.digest)
                else desired
            if (occupied)
                result +=
                    OwnSyncPlanEntry(ownSyncUuid(), OwnSyncAction.Conflict, entry, path = desired)
            // A stable alternate name already occupied is not adopted, even if bytes match.
            if (path.joinToString("/") in used || (occupied && remotePresent(root, path))) {
                result +=
                    OwnSyncPlanEntry(ownSyncUuid(), OwnSyncAction.Inaccessible, entry, path = path)
            } else {
                used += path.joinToString("/")
                result += OwnSyncPlanEntry(ownSyncUuid(), OwnSyncAction.Add, entry, path = path)
            }
        }
        snapshot.issues.forEach {
            result +=
                OwnSyncPlanEntry(
                    ownSyncUuid(),
                    OwnSyncAction.Inaccessible,
                    path = listOf(job.namespace),
                    done = false,
                )
        }
        if (
            job.policy == OwnSyncPolicy.ManagedMirror &&
                snapshot.complete &&
                result.none { it.action == OwnSyncAction.Inaccessible }
        ) {
            job.outputs
                .filter { it.quarantine == null && it.id !in retained }
                .forEach { output ->
                    check()
                    if (remoteDigest(root, output.path, check) == output.digest)
                        result +=
                            OwnSyncPlanEntry(
                                ownSyncUuid(),
                                OwnSyncAction.Quarantine,
                                output = output,
                                path = output.path,
                            )
                    else
                        result +=
                            OwnSyncPlanEntry(
                                ownSyncUuid(),
                                OwnSyncAction.Conflict,
                                output = output,
                                path = output.path,
                            )
                }
        }
        return result
    }

    private suspend fun execute(
        runId: String,
        initialJob: OwnSyncJob,
        source: OwnSyncSourcePort,
        root: RemoteManagedConnection,
        check: () -> Unit,
    ) {
        var run = store.run(runId)!!
        if (!run.restoration) {
            val currentSnapshot = source.scan(Uri.parse(initialJob.tree), check)
            if (
                currentSnapshot.fingerprint != run.snapshot?.fingerprint ||
                    currentSnapshot.issues != run.snapshot?.issues
            )
                throw OwnSyncChanged()
        }
        run.plan
            .filter { it.action == OwnSyncAction.Add }
            .forEach { original ->
                check()
                val entry = store.run(runId)!!.plan.single { it.id == original.id }
                val saved = store.job(initialJob.id)!!.outputs.singleOrNull { it.id == entry.id }
                if (saved != null) {
                    if (remoteDigest(root, saved.path, check) != saved.digest)
                        throw OwnSyncChanged()
                    markDone(runId, entry.id)
                    return@forEach
                }
                if (entry.done) throw OwnSyncChanged()
                if (remotePresent(root, entry.path)) throw OwnSyncChanged()
                val item = entry.source!!
                val local = store.stage(runId)
                val digest =
                    source.open(item).use { input ->
                        FileOutputStream(local).use { output ->
                            OwnSyncIO.copy(input, output, check).also { output.fd.sync() }
                        }
                    }
                if (digest != item.digest) throw OwnSyncChanged()
                val staging = ".lightforge-sync-${runId}-${ownSyncUuid()}.partial"
                store.update(runId) { state ->
                    state.copy(
                        plan =
                            state.plan.map {
                                if (it.id == entry.id) it.copy(staging = staging) else it
                            },
                        residuals =
                            (state.residuals +
                                    entry.path.dropLast(1).plus(staging).joinToString("/"))
                                .distinct(),
                    )
                }
                directory(root, entry.path.dropLast(1), true) { dir ->
                    if (dir.stat(entry.path.last()) != null) throw OwnSyncChanged()
                    dir.createExclusive(staging).use { output ->
                        local.inputStream().use { input ->
                            if (OwnSyncIO.copy(input, output, check) != digest)
                                throw OwnSyncChanged()
                        }
                    }
                    check()
                    dir.publishNoReplace(staging, entry.path.last(), digest)
                    if (
                        dir.openRead(entry.path.last()).use { OwnSyncIO.copy(it, check = check) } !=
                            digest
                    )
                        throw OwnSyncChanged()
                    val output = OwnSyncOutput(entry.id, item.key, item.path, entry.path, digest)
                    // Receipt first; a process death before plan.done is reconciled by its exact
                    // output id.
                    store.updateJob(initialJob.id) { it.copy(outputs = it.outputs + output) }
                    markDone(runId, entry.id)
                }
                local.delete()
            }
        run = store.run(runId)!!
        run.plan
            .filter { it.action == OwnSyncAction.Verified }
            .forEach { entry ->
                check()
                if (remoteDigest(root, entry.path, check) != entry.output!!.digest)
                    throw OwnSyncChanged()
            }
        val moves =
            run.plan.filter {
                it.action == OwnSyncAction.Quarantine || it.action == OwnSyncAction.Restore
            }
        if (moves.isNotEmpty()) {
            require(run.mirrorApproved)
            if (!run.restoration) {
                val after = source.scan(Uri.parse(initialJob.tree), check)
                if (!after.complete || after.fingerprint != run.snapshot?.fingerprint)
                    throw OwnSyncChanged()
            }
            moves.forEach { original ->
                check()
                val entry = store.run(runId)!!.plan.single { it.id == original.id }
                if (entry.done) return@forEach
                val output = entry.output!!
                val restore = entry.action == OwnSyncAction.Restore
                val quarantine =
                    if (restore) output.quarantine!!
                    else entry.staging ?: ".lightforge-quarantine-${runId}-${output.id}"
                val from = if (restore) quarantine else output.path.last()
                val to = if (restore) output.path.last() else quarantine
                store.update(runId) { state ->
                    state.copy(
                        plan =
                            state.plan.map {
                                if (it.id == entry.id) it.copy(staging = quarantine) else it
                            }
                    )
                }
                val result =
                    directory(root, output.path.dropLast(1), false) { dir ->
                        dir.moveManagedNoReplace(from, to, output.digest)
                    }
                if (!result.verified) throw OwnSyncChanged()
                store.updateJob(initialJob.id) { job ->
                    job.copy(
                        outputs =
                            job.outputs.map {
                                if (it.id == output.id)
                                    it.copy(quarantine = if (restore) null else quarantine)
                                else it
                            }
                    )
                }
                markDone(runId, entry.id)
            }
        }
        store.update(runId) { it.copy(status = OwnSyncStatus.Completed) }
    }

    private fun markDone(runId: String, entryId: String) {
        store.update(runId) { state ->
            state.copy(plan = state.plan.map { if (it.id == entryId) it.copy(done = true) else it })
        }
    }

    private fun <T> directory(
        root: RemoteManagedConnection,
        path: List<String>,
        create: Boolean,
        action: (RemoteManagedConnection) -> T,
    ): T {
        if (path.isEmpty()) return action(root)
        return root.directory(path, create).use(action)
    }

    private class OwnSyncChanged : IOException()
}
