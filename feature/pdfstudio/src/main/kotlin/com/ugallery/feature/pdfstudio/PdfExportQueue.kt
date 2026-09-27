package com.ugallery.feature.pdfstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Room owns immutable job snapshots; WorkManager owns execution, not source lifetime. */
class PdfExportQueue(private val context: Context) {
    private val dao = PdfProjectDatabase.get(context).exports()
    private val grants = PdfProjectDatabase.get(context).destinationGrants()
    private val manager
        get() = WorkManager.getInstance(context)

    private val lock = PdfStorageLock.mutex
    private val directory = File(context.filesDir, "pdf-studio/exports").apply { mkdirs() }
    val jobs = dao.observe()

    fun output(id: String): File {
        UUID.fromString(id)
        return File(directory, "$id.pdf")
    }

    fun partial(id: String, workId: String): File {
        UUID.fromString(id)
        UUID.fromString(workId)
        return File(directory, "$id-$workId.part")
    }

    suspend fun get(id: String): PdfExportJob? = dao.get(id)

    /**
     * @param destination when non-null, the job is created already bound to this document (the
     *   "destination first" flow): it skips [PdfExportPhase.Ready] entirely and the worker
     *   publishes directly once rendering finishes. This reuses the same grant acquisition and
     *   DestinationInUse guard as [publish], so a destination picked before rendering starts is
     *   just as safe as one picked afterwards.
     */
    suspend fun enqueue(
        project: PdfProject,
        compact: Boolean,
        portable: Boolean = false,
        destination: Uri? = null,
    ): PdfExportJob =
        withContext(Dispatchers.IO) {
            val snapshot =
                project
                    .copy(assets = project.assets.filter { it.hash in project.usedAssets() })
                    .validate()
            val manifest = PdfExportManifest.encode(snapshot).toString(Charsets.UTF_8)
            val now = System.currentTimeMillis()
            val row =
                PdfExportJob(
                    newId(),
                    newId(),
                    snapshot.name,
                    manifest,
                    compact,
                    PdfExportPhase.Queued.name,
                    0,
                    if (portable) snapshot.assets.size + 1 else snapshot.pages.size,
                    now,
                    now,
                    destination = destination?.toString(),
                    portable = portable,
                )
            lock.withLock {
                val repo = PdfProjectRepository(context)
                require(snapshot.usedAssets().all { repo.file(it).isFile })
                if (destination != null) {
                    require(
                        context.contentResolver.openInputStream(destination)?.use {
                            it.read() == -1
                        } == true
                    ) {
                        "DestinationNotEmpty"
                    }
                    require(
                        dao.all().none { it.destination == destination.toString() && it.keepsSources }
                    ) {
                        "DestinationInUse"
                    }
                    acquireGrant(destination)
                }
                dao.put(row)
            }
            schedule(row)
            row
        }

    private fun schedule(row: PdfExportJob) {
        val request =
            OneTimeWorkRequestBuilder<PdfExportWorker>()
                .setId(UUID.fromString(row.workId))
                .setInputData(workDataOf("jobId" to row.id))
                // Expedited work may start its foreground service from the background (API 31+).
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
        manager
            .enqueueUniqueWork(
                "pdf-export-${row.id}-${row.workId}",
                ExistingWorkPolicy.KEEP,
                request,
            )
            .result
            .get()
    }

    /** Reconcile only known terminal/missing work, never a timeout or an active worker. */
    suspend fun reconcile() =
        withContext(Dispatchers.IO) {
            dao.all()
                .filter {
                    it.phase in
                        setOf(
                            PdfExportPhase.Queued,
                            PdfExportPhase.Running,
                            PdfExportPhase.Publishing,
                        )
                }
                .forEach { row ->
                    val work = manager.getWorkInfoById(UUID.fromString(row.workId)).get()
                    if (work == null || work.state.isFinished) {
                        val next =
                            lock.withLock {
                                val current = dao.get(row.id) ?: return@withLock null
                                if (
                                    current.workId != row.workId ||
                                        current.phase !in
                                            setOf(
                                                PdfExportPhase.Queued,
                                                PdfExportPhase.Running,
                                                PdfExportPhase.Publishing,
                                            )
                                )
                                    return@withLock null
                                current
                                    .copy(
                                        workId = if (work == null) current.workId else newId(),
                                        status = PdfExportPhase.Queued.name,
                                        updated = System.currentTimeMillis(),
                                    )
                                    .also { dao.put(it) }
                            }
                        next?.let(::schedule)
                    }
                }
            dao.all()
                .filter { it.phase == PdfExportPhase.Cancelling }
                .forEach { row ->
                    manager.cancelWorkById(UUID.fromString(row.workId)).result.get()
                    finishCancellation(row.id)
                }
            releaseUnusedGrants()
        }

    suspend fun update(
        id: String,
        workId: String,
        change: (PdfExportJob) -> PdfExportJob,
    ): PdfExportJob? =
        lock.withLock {
            val current = dao.get(id) ?: return@withLock null
            if (
                current.workId != workId ||
                    current.phase in
                        setOf(
                            PdfExportPhase.Cancelled,
                            PdfExportPhase.Cancelling,
                            PdfExportPhase.Published,
                        )
            )
                return@withLock current
            change(current).copy(updated = System.currentTimeMillis()).also { dao.put(it) }
        }

    suspend fun cancel(id: String) =
        withContext(Dispatchers.IO) {
            val current =
                lock.withLock {
                    val row = dao.get(id) ?: return@withLock null
                    if (row.phase in setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled))
                        return@withLock null
                    row.copy(
                            status = PdfExportPhase.Cancelling.name,
                            error = null,
                            updated = System.currentTimeMillis(),
                        )
                        .also { dao.put(it) }
                } ?: return@withContext
            manager.cancelWorkById(UUID.fromString(current.workId)).result.get()
            finishCancellation(id)
        }

    private suspend fun finishCancellation(id: String) =
        PdfWorkLocks.forJob(id).withLock {
            val row = dao.get(id) ?: return@withLock
            if (row.phase != PdfExportPhase.Cancelling) return@withLock
            try {
                val destination = row.destination?.let { Uri.parse(it) }
                val state =
                    destination?.let {
                        inspectPdfDestination(context.contentResolver, it, row, output(id))
                    }
                if (state == PdfDestinationState.Empty || state == PdfDestinationState.Partial) {
                    check(
                        android.provider.DocumentsContract.deleteDocument(
                            context.contentResolver,
                            destination,
                        )
                    )
                }
                lock.withLock {
                    val current = dao.get(id) ?: return@withLock
                    if (current.phase == PdfExportPhase.Cancelling)
                        dao.put(
                            current.copy(
                                status =
                                    if (state == PdfDestinationState.Complete)
                                        PdfExportPhase.Published.name
                                    else PdfExportPhase.Cancelled.name,
                                error =
                                    if (state == PdfDestinationState.Different)
                                        "DestinationPreserved"
                                    else null,
                                updated = System.currentTimeMillis(),
                            )
                        )
                }
                output(id).delete()
                releaseUnusedGrants()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lock.withLock {
                    dao.get(id)
                        ?.takeIf { it.phase == PdfExportPhase.Cancelling }
                        ?.let { dao.put(it.copy(error = "CleanupPending")) }
                }
            }
        }

    suspend fun keepDestination(id: String) =
        withContext(Dispatchers.IO) {
            PdfWorkLocks.forJob(id).withLock {
                lock.withLock {
                    val row = requireNotNull(dao.get(id))
                    require(row.phase == PdfExportPhase.Cancelling)
                    dao.put(
                        row.copy(
                            status = PdfExportPhase.Cancelled.name,
                            error = "DestinationPreserved",
                            updated = System.currentTimeMillis(),
                        )
                    )
                }
                output(id).delete()
                releaseUnusedGrants()
            }
        }

    suspend fun retry(id: String) =
        withContext(Dispatchers.IO) {
            val next =
                lock.withLock {
                    val row = requireNotNull(dao.get(id))
                    require(row.phase == PdfExportPhase.Failed)
                    row.copy(
                            workId = newId(),
                            status = PdfExportPhase.Queued.name,
                            completed = 0,
                            error = null,
                            updated = System.currentTimeMillis(),
                        )
                        .also { dao.put(it) }
                }
            schedule(next)
        }

    suspend fun publish(id: String, uri: Uri) =
        withContext(Dispatchers.IO) {
            PdfWorkLocks.forJob(id).withLock {
                val row = requireNotNull(dao.get(id))
                require(row.phase in setOf(PdfExportPhase.Ready, PdfExportPhase.Failed))
                val different = row.destination != uri.toString()
                if (different) {
                    // CreateDocument returns a fresh document. Never adopt non-empty unrelated
                    // output.
                    require(
                        context.contentResolver.openInputStream(uri)?.use { it.read() == -1 } ==
                            true
                    ) {
                        "DestinationNotEmpty"
                    }
                    row.destination?.let { old ->
                        when (
                            inspectPdfDestination(
                                context.contentResolver,
                                Uri.parse(old),
                                row,
                                output(id),
                            )
                        ) {
                            PdfDestinationState.Empty,
                            PdfDestinationState.Partial ->
                                check(
                                    android.provider.DocumentsContract.deleteDocument(
                                        context.contentResolver,
                                        Uri.parse(old),
                                    )
                                )
                            else -> Unit // Complete or changed destinations remain intact.
                        }
                    }
                }
                try {
                    val next =
                        lock.withLock {
                            val current = requireNotNull(dao.get(id))
                            require(
                                current.workId == row.workId &&
                                    current.phase in
                                        setOf(PdfExportPhase.Ready, PdfExportPhase.Failed)
                            ) {
                                "JobChanged"
                            }
                            require(
                                dao.all().none {
                                    it.id != id &&
                                        it.destination == uri.toString() &&
                                        it.keepsSources
                                }
                            ) {
                                "DestinationInUse"
                            }
                            acquireGrant(uri)
                            row.copy(
                                    workId = newId(),
                                    destination = uri.toString(),
                                    status = PdfExportPhase.Queued.name,
                                    error = null,
                                    updated = System.currentTimeMillis(),
                                )
                                .also { dao.put(it) }
                        }
                    schedule(next)
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { releaseUnusedGrants() }
                }
            }
        }

    /** Export rows are still serialized by storage; permission ownership has its own short lock. */
    internal suspend fun acquireGrant(
        uri: Uri,
        flags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) = PdfPermissionLock.mutex.withLock { acquireGrantUnlocked(uri, flags) }

    /** Call only under PdfPermissionLock; write ownership before the Android permission call. */
    internal suspend fun acquireGrantUnlocked(uri: Uri, flags: Int) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val existing = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val previous =
            (if (existing?.isReadPermission == true) read else 0) or
                (if (existing?.isWritePermission == true) write else 0)
        val owned = (grants.get(uri.toString())?.ownedFlags ?: 0) or (flags and previous.inv())
        grants.put(PdfDestinationGrant(uri.toString(), owned))
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    suspend fun releaseUnusedGrants() =
        withContext(Dispatchers.IO) {
            lock.withLock {
                PdfPermissionLock.mutex.withLock {
                    val required =
                        dao.all().filter { it.keepsSources }.mapNotNull { it.destination }.toSet() +
                            PdfProjectDatabase.get(context).importDeliveries().all().flatMap {
                                it.request().uris
                            }
                    grants
                        .all()
                        .filter { it.uri !in required }
                        .forEach { grant ->
                            if (grant.ownedFlags != 0) {
                                try {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(grant.uri),
                                        grant.ownedFlags,
                                    )
                                } catch (e: SecurityException) {
                                    /* Permission was already revoked. */
                                }
                            }
                            grants.delete(grant.uri)
                        }
                }
            }
        }

    suspend fun commitLocal(id: String, workId: String, part: File, hash: String) =
        lock.withLock {
            val current =
                dao.get(id) ?: throw kotlinx.coroutines.CancellationException("Job removed")
            if (
                current.workId != workId ||
                    current.phase in setOf(PdfExportPhase.Cancelling, PdfExportPhase.Cancelled)
            )
                throw kotlinx.coroutines.CancellationException("Cancelled or replaced")
            val bytes = part.length()
            check(part.renameTo(output(id)))
            dao.put(
                current.copy(
                    outputHash = hash,
                    outputBytes = bytes,
                    updated = System.currentTimeMillis(),
                )
            )
        }

    suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            // Removing a failed row must not strand a partial external document or its grant.
            if (dao.get(id)?.phase in setOf(PdfExportPhase.Ready, PdfExportPhase.Failed)) cancel(id)
            lock.withLock {
                val row = dao.get(id) ?: return@withLock
                require(row.phase in setOf(PdfExportPhase.Published, PdfExportPhase.Cancelled)) {
                    "CleanupPending"
                }
                dao.delete(id)
                output(id).delete()
            }
            releaseUnusedGrants()
        }

    companion object {
        const val TAG = "ugallery-pdf-export"
    }
}
