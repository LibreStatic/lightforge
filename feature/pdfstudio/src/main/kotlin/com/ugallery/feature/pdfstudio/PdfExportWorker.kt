package com.ugallery.feature.pdfstudio

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfExportWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    private val queue = PdfExportQueue(context)
    internal var engine: PdfEngine = IsolatedPdfEngine(context)
    internal var publisher: suspend (java.io.File, Uri) -> Unit = { file, uri ->
        PdfProjectRepository(context).publish(file, uri, deleteOnFailure = false)
    }

    internal var portableWriter: suspend (PdfProject, java.io.File, (Int, Int) -> Unit) -> Unit =
        { project, output, progress ->
            PdfProjectRepository(context).writePortable(project, output) { n, total ->
                progress(n, total)
            }
        }

    private suspend fun intact(
        file: java.io.File,
        project: PdfProject,
        portable: Boolean,
    ): Boolean {
        return if (portable) {
            PdfPortableArchive.verify(file, project)
            true
        } else engine.inspect(file).size == project.pages.size
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val row = queue.get(jobId) ?: return Result.success()
        if (
            row.workId != id.toString() ||
                row.phase in
                    setOf(
                        PdfExportPhase.Cancelled,
                        PdfExportPhase.Cancelling,
                        PdfExportPhase.Published,
                        PdfExportPhase.Ready,
                    )
        )
            return Result.success()
        return try {
            setForeground(foreground(row))
            PdfWorkLocks.forJob(jobId).withLock { gate.withLock { execute(jobId) } }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                queue.update(jobId, id.toString()) { it.interrupted() }
            }
            throw e
        } catch (e: Exception) {
            queue.update(jobId, id.toString()) {
                it.copy(status = PdfExportPhase.Failed.name, error = PdfFailure.from(e).name)
            }
            // A job failure is durable and retryable; it does not poison unrelated queue items.
            Result.success()
        }
    }

    private suspend fun execute(jobId: String): Result =
        withContext(Dispatchers.IO) {
            val row = queue.get(jobId) ?: return@withContext Result.success()
            if (
                row.workId != id.toString() ||
                    row.phase in setOf(PdfExportPhase.Cancelled, PdfExportPhase.Cancelling)
            )
                return@withContext Result.success()
            val project = PdfCodec.decode(row.manifest)
            val repository = PdfProjectRepository(applicationContext)
            val part = queue.partial(jobId, id.toString())
            val output = queue.output(jobId)
            try {
                val intact =
                    if (
                        output.isFile &&
                            row.outputHash != null &&
                            output.length() == row.outputBytes &&
                            PdfProjectRepository.sha256(output) == row.outputHash
                    ) {
                        try {
                            intact(output, project, row.portable)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    } else false
                if (!intact) {
                    output.delete()
                    queue.update(jobId, id.toString()) {
                        it.copy(status = PdfExportPhase.Running.name, completed = 0, error = null)
                    }
                    val completed = AtomicInteger(0)
                    coroutineScope {
                        val rendering = this
                        val monitor = launch {
                            while (isActive) {
                                val n = completed.get().coerceIn(0, row.total)
                                val updated =
                                    queue.update(jobId, id.toString()) {
                                        it.copy(completed = maxOf(it.completed, n))
                                    }
                                if (
                                    updated?.phase in
                                        setOf(
                                            PdfExportPhase.Cancelled,
                                            PdfExportPhase.Cancelling,
                                        ) || updated?.workId != id.toString()
                                )
                                    rendering.cancel("Cancelled or replaced")
                                setProgress(workDataOf("completed" to n, "total" to row.total))
                                delay(400)
                            }
                        }
                        try {
                            if (row.portable)
                                portableWriter(project, part) { n, _ -> completed.set(n) }
                            else
                                engine.export(
                                    project,
                                    project.assets.map { repository.file(it.hash) },
                                    part,
                                    row.compact,
                                ) { n, _ ->
                                    completed.set(n)
                                }
                        } finally {
                            monitor.cancelAndJoin()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    require(part.length() > 0 && intact(part, project, row.portable))
                    FileOutputStream(part, true).use { it.fd.sync() }
                    queue.commitLocal(jobId, id.toString(), part, PdfProjectRepository.sha256(part))
                }
                currentCoroutineContext().ensureActive()
                if (row.destination == null) {
                    queue.update(jobId, id.toString()) {
                        it.copy(
                            status = PdfExportPhase.Ready.name,
                            completed = row.total,
                            error = null,
                        )
                    }
                } else {
                    queue.update(jobId, id.toString()) {
                        it.copy(
                            status = PdfExportPhase.Publishing.name,
                            completed = row.total,
                            error = null,
                        )
                    }
                    val current = requireNotNull(queue.get(jobId))
                    when (
                        inspectPdfDestination(
                            applicationContext.contentResolver,
                            Uri.parse(row.destination),
                            current,
                            output,
                        )
                    ) {
                        PdfDestinationState.Complete -> Unit
                        PdfDestinationState.Empty,
                        PdfDestinationState.Partial -> {
                            publisher(output, Uri.parse(row.destination))
                            check(
                                inspectPdfDestination(
                                    applicationContext.contentResolver,
                                    Uri.parse(row.destination),
                                    current,
                                    output,
                                ) == PdfDestinationState.Complete
                            ) {
                                "DestinationVerificationFailed"
                            }
                        }
                        PdfDestinationState.Missing ->
                            throw java.io.FileNotFoundException("Destination missing")
                        PdfDestinationState.Different ->
                            throw IllegalStateException("DestinationChanged")
                    }
                    currentCoroutineContext().ensureActive()
                    val finished =
                        queue.update(jobId, id.toString()) {
                            it.copy(status = PdfExportPhase.Published.name, error = null)
                        }
                    if (finished?.phase == PdfExportPhase.Published) {
                        output.delete()
                        queue.releaseUnusedGrants()
                    }
                }
                Result.success()
            } finally {
                part.delete()
            }
        }

    private fun foreground(row: PdfExportJob): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                applicationContext.getString(R.string.pdf_queue),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val cancel =
            PendingIntent.getBroadcast(
                applicationContext,
                row.id.hashCode(),
                Intent(applicationContext, PdfExportCancelReceiver::class.java)
                    .setAction(row.id)
                    .putExtra("jobId", row.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(row.projectName)
                .setContentText(
                    applicationContext.getString(
                        if (row.portable) R.string.pdf_queue_packaging
                        else R.string.pdf_queue_running
                    )
                )
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    applicationContext.getString(R.string.pdf_cancel),
                    cancel,
                )
                .build()
        return ForegroundInfo(
            0x50000000 or (row.id.hashCode() and 0x0fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private val gate = Mutex()
        private const val CHANNEL = "pdf-exports"
    }
}

class PdfExportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("jobId") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                PdfExportQueue(context.applicationContext).cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}
