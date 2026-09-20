package com.ugallery.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.editing.video.HdrVideoExportUnsupportedException
import com.ugallery.core.editing.video.Media3VideoExporter
import com.ugallery.core.editing.video.VideoEditRecipe
import com.ugallery.core.editing.video.VideoEditRecipeCodec
import com.ugallery.core.editing.video.VideoExportPhase
import com.ugallery.core.editing.video.VideoExportRequest
import com.ugallery.core.editing.video.videoExportDiagnostic
import com.ugallery.core.mediastore.MediaWriteSpec
import com.ugallery.core.mediastore.PendingMediaWriter
import com.ugallery.core.ml.MlScheduler
import com.ugallery.core.ml.UserHardwareWorkload
import com.ugallery.core.ml.UserHardwareWorkloadGate
import com.ugallery.core.model.MediaKind
import com.ugallery.feature.semanticsearch.SemanticAnalysisPriority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class VideoExportJobStatus { Queued, Running, Completed, Failed, Cancelled }

data class VideoExportJob(
    val id: String,
    val workId: String,
    val inputUri: String,
    val encodedRecipe: String,
    val displayName: String,
    val status: VideoExportJobStatus,
    val phase: VideoExportPhase,
    val progressPermille: Int,
    val outputUri: String? = null,
    val pendingUri: String? = null,
    val error: String? = null,
    val usedSoftwareCodec: Boolean = false,
    val codecName: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

internal fun VideoExportJob.afterWorkerInterruption(): VideoExportJob =
    if (status == VideoExportJobStatus.Cancelled) {
        copy(error = null)
    } else {
        copy(
            status = VideoExportJobStatus.Queued,
            phase = VideoExportPhase.Preparing,
            progressPermille = 0,
            pendingUri = null,
            error = null,
        )
    }

/** Durable file-backed export state; WorkManager owns execution and FIFO ordering. */
class VideoExportStore private constructor(private val context: Context) {
    private val directory = File(context.filesDir, "video-export-jobs").apply { mkdirs() }
    private val mutableJobs = MutableStateFlow(readAll())
    val jobs = mutableJobs.asStateFlow()

    @Synchronized
    fun enqueue(input: Uri, recipe: VideoEditRecipe): VideoExportJob {
        val id = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<VideoExportWorker>()
            .setInputData(workDataOf(VideoExportWorker.JobIdKey to id))
            .addTag(VideoExportWorker.ExportTag)
            .build()
        val now = System.currentTimeMillis()
        val job = VideoExportJob(
            id = id,
            workId = request.id.toString(),
            inputUri = input.toString(),
            encodedRecipe = VideoEditRecipeCodec.encode(recipe),
            displayName = "UGallery-edited-$now.mp4",
            status = VideoExportJobStatus.Queued,
            phase = VideoExportPhase.Preparing,
            progressPermille = 0,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        write(job)
        WorkManager.getInstance(context).beginUniqueWork(
            VideoExportWorker.QueueName,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
        return job
    }

    fun cancel(job: VideoExportJob) {
        update(job.id) { it.copy(status = VideoExportJobStatus.Cancelled) }
    }

    @Synchronized
    fun get(id: String): VideoExportJob? = read(File(directory, "$id.json"))

    @Synchronized
    fun update(id: String, block: (VideoExportJob) -> VideoExportJob): VideoExportJob? {
        val current = get(id) ?: return null
        val updated = block(current).copy(updatedAtMillis = System.currentTimeMillis())
        write(updated)
        return updated
    }

    @Synchronized
    private fun write(job: VideoExportJob) {
        val destination = File(directory, "${job.id}.json")
        val temporary = File(directory, "${job.id}.tmp")
        temporary.writeText(job.toJson().toString())
        check(temporary.renameTo(destination) || temporary.copyTo(destination, overwrite = true).let { temporary.delete(); true })
        mutableJobs.value = readAll()
    }

    private fun readAll(): List<VideoExportJob> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull(::read)
        ?.sortedByDescending(VideoExportJob::createdAtMillis)
        .orEmpty()

    private fun read(file: File): VideoExportJob? = runCatching {
        val json = JSONObject(file.readText())
        VideoExportJob(
            id = json.getString("id"),
            workId = json.getString("workId"),
            inputUri = json.getString("inputUri"),
            encodedRecipe = json.getString("encodedRecipe"),
            displayName = json.getString("displayName"),
            status = VideoExportJobStatus.valueOf(json.getString("status")),
            phase = VideoExportPhase.valueOf(json.getString("phase")),
            progressPermille = json.getInt("progressPermille"),
            outputUri = json.optString("outputUri").takeIf(String::isNotBlank),
            pendingUri = json.optString("pendingUri").takeIf(String::isNotBlank),
            error = json.optString("error").takeIf(String::isNotBlank),
            usedSoftwareCodec = json.optBoolean("usedSoftwareCodec"),
            codecName = json.optString("codecName").takeIf(String::isNotBlank),
            createdAtMillis = json.getLong("createdAtMillis"),
            updatedAtMillis = json.getLong("updatedAtMillis"),
        )
    }.getOrNull()

    private fun VideoExportJob.toJson() = JSONObject().apply {
        put("id", id); put("workId", workId); put("inputUri", inputUri)
        put("encodedRecipe", encodedRecipe); put("displayName", displayName)
        put("status", status.name); put("phase", phase.name); put("progressPermille", progressPermille)
        put("outputUri", outputUri ?: ""); put("pendingUri", pendingUri ?: ""); put("error", error ?: "")
        put("usedSoftwareCodec", usedSoftwareCodec); put("codecName", codecName ?: "")
        put("createdAtMillis", createdAtMillis); put("updatedAtMillis", updatedAtMillis)
    }

    companion object {
        @SuppressLint("StaticFieldLeak") // Holds only the process-lifetime application context.
        @Volatile private var instance: VideoExportStore? = null
        fun get(context: Context): VideoExportStore = instance ?: synchronized(this) {
            instance ?: VideoExportStore(context.applicationContext).also { instance = it }
        }
    }
}

class VideoExportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val store = VideoExportStore.get(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastNotifiedPermille = -1
    private var lastPersistedPermille = -1
    private var lastPersistedPhase: VideoExportPhase? = null

    override suspend fun doWork(): Result {
        val id = inputData.getString(JobIdKey) ?: return Result.success()
        val original = store.get(id) ?: return Result.success()
        if (original.status == VideoExportJobStatus.Completed && original.outputUri != null) return Result.success()
        if (original.status == VideoExportJobStatus.Cancelled) return Result.success()
        createChannel()
        setForeground(foreground(original, 0, VideoExportPhase.Preparing))
        return supervisorScope {
            val export = async { runExport(id, original) }
            val cancellationMonitor = launch {
                while (export.isActive) {
                    delay(CancellationPollMillis)
                    if (store.get(id)?.status == VideoExportJobStatus.Cancelled) {
                        export.cancel(CancellationException("Video export cancelled by user"))
                    }
                }
            }
            try {
                export.await()
            } catch (cancelled: CancellationException) {
                if (store.get(id)?.status == VideoExportJobStatus.Cancelled) Result.success()
                else throw cancelled
            } finally {
                cancellationMonitor.cancel()
            }
        }
    }

    private suspend fun runExport(id: String, original: VideoExportJob): Result {
        val lease = UserHardwareWorkloadGate.acquire(UserHardwareWorkload.VideoExport)
        if (lease.activatedGate) {
            MlScheduler(applicationContext).suspendForUserWork()
            SemanticAnalysisPriority.suspendForUserWork(applicationContext)
        }
        val output = File(applicationContext.cacheDir, "video-export-$id.mp4")
        try {
            original.pendingUri?.let { stale ->
                runCatching { applicationContext.contentResolver.delete(stale.toUri(), null, null) }
                store.update(id) { it.copy(pendingUri = null) }
            }
            update(id, VideoExportJobStatus.Running, VideoExportPhase.Preparing, 10)
            val recipe = VideoEditRecipeCodec.decode(original.encodedRecipe)
            val database = GalleryDatabaseFactory.open(applicationContext)
            val customLut = try {
                recipe.colorGrade.lut.customId?.let { customId ->
                    runCatching {
                        LutRepository(
                            applicationContext.contentResolver,
                            database.colorEditDao(),
                            File(applicationContext.filesDir, "luts"),
                        ).load(customId)
                    }.getOrNull() ?: error(
                        applicationContext.getString(
                            com.ugallery.feature.videoeditor.R.string.video_editor_lut_unavailable,
                        ),
                    )
                }
            } finally {
                database.close()
            }
            val exportResult = Media3VideoExporter(applicationContext).export(
                VideoExportRequest(original.inputUri.toUri(), output, recipe, customLut = customLut) { progress ->
                    val renderBase = if (recipe.slowMotionSegments.isEmpty()) 100 else 350
                    val permille = when (progress.phase) {
                        VideoExportPhase.Preparing -> 10
                        VideoExportPhase.GeneratingFrames -> 50 + ((progress.fraction ?: 0f) * 300).toInt()
                        VideoExportPhase.Rendering -> renderBase +
                            ((progress.fraction ?: 0f) * (840 - renderBase)).toInt()
                        else -> 840
                    }
                    update(id, VideoExportJobStatus.Running, progress.phase, permille)
                },
            )
            publicationObservers[original.inputUri]?.invoke(original, output)
            requirePublicationAccess(id, original)
            update(id, VideoExportJobStatus.Running, VideoExportPhase.Publishing, 850)
            val size = output.length().coerceAtLeast(1L)
            val published = PendingMediaWriter(
                resolver = applicationContext.contentResolver,
                persistPending = { snapshot ->
                    store.update(id) { it.copy(pendingUri = snapshot?.pendingUri) }
                },
            ).publishFile(
                output,
                MediaWriteSpec(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    MediaKind.Video,
                    original.displayName,
                    "video/mp4",
                    "Movies/UGallery",
                ),
                onProgress = { bytes ->
                    update(id, VideoExportJobStatus.Running, VideoExportPhase.Publishing, 850 + (bytes * 100L / size).toInt())
                },
                onVerifying = {
                    update(id, VideoExportJobStatus.Running, VideoExportPhase.Verifying, 970)
                },
                beforePublish = { requirePublicationAccess(id, original) },
            )
            val completed = store.update(id) {
                it.copy(
                    status = VideoExportJobStatus.Completed,
                    phase = VideoExportPhase.Completed,
                    progressPermille = 1000,
                    outputUri = published.uri.toString(),
                    pendingUri = null,
                    usedSoftwareCodec = exportResult.usedSoftwareCodec,
                    codecName = exportResult.videoEncoderName ?: exportResult.videoDecoderName,
                    error = null,
                )
            } ?: return Result.success()
            notifications.notify(notificationId(id), completionNotification(completed))
            return Result.success(workDataOf(OutputUriKey to published.uri.toString()))
        } catch (cancelled: CancellationException) {
            // Only the explicit store flag represents a user cancellation. WorkManager can also
            // stop a CoroutineWorker when constraints or system scheduling change; treating that
            // interruption as a completed user cancellation would permanently lose the export.
            store.update(id, VideoExportJob::afterWorkerInterruption)
            throw cancelled
        } catch (failure: Throwable) {
            val causes = generateSequence(failure) { it.cause }.toList()
            val message = if (causes.any { it is HdrVideoExportUnsupportedException }) {
                applicationContext.getString(R.string.video_export_hdr_unsupported)
            } else {
                videoExportDiagnostic(failure, applicationContext.getString(R.string.video_export_failed))
            }
            Log.e("VideoExportWorker", "Video export $id failed: $message", failure)
            val failed = store.update(id) {
                it.copy(status = VideoExportJobStatus.Failed, error = message)
            }
            if (failed != null) notifications.notify(notificationId(id), failureNotification(failed))
            // A failed item must not cancel later dependants in the FIFO WorkManager chain.
            return Result.success(Data.Builder().putString("error", message).build())
        } finally {
            output.delete()
            UserHardwareWorkloadGate.release(lease)
            if (!UserHardwareWorkloadGate.isActive()) {
                MlScheduler(applicationContext).resumeAfterUserWork()
                SemanticAnalysisPriority.resumeAfterUserWork(applicationContext)
            }
        }
    }

    private suspend fun requirePublicationAccess(id: String, original: VideoExportJob) {
        currentCoroutineContext().ensureActive()
        if (store.get(id)?.status == VideoExportJobStatus.Cancelled) {
            throw CancellationException("Video export cancelled by user")
        }
        // Existing renderer descriptors may outlive a revoked READ grant. Opening afresh is
        // required both before inserting a destination and after its complete verification.
        applicationContext.contentResolver.openFileDescriptor(original.inputUri.toUri(), "r")?.use { }
            ?: throw java.io.FileNotFoundException()
        currentCoroutineContext().ensureActive()
        if (store.get(id)?.status == VideoExportJobStatus.Cancelled) {
            throw CancellationException("Video export cancelled by user")
        }
    }

    private fun update(id: String, status: VideoExportJobStatus, phase: VideoExportPhase, permille: Int) {
        val boundedPermille = permille.coerceIn(0, 1000)
        if (status == VideoExportJobStatus.Running && phase == lastPersistedPhase &&
            boundedPermille < lastPersistedPermille + 5
        ) return
        val job = store.update(id) {
            if (it.status == VideoExportJobStatus.Cancelled && status == VideoExportJobStatus.Running) it
            else it.copy(status = status, phase = phase, progressPermille = boundedPermille, error = null)
        } ?: return
        if (job.status == VideoExportJobStatus.Cancelled) return
        lastPersistedPermille = boundedPermille
        lastPersistedPhase = phase
        if (boundedPermille == 0 || boundedPermille >= lastNotifiedPermille + 10) {
            lastNotifiedPermille = boundedPermille
            notifications.notify(notificationId(id), progressNotification(job))
        }
    }

    @SuppressLint("InlinedApi")
    private fun foreground(job: VideoExportJob, permille: Int, phase: VideoExportPhase): ForegroundInfo {
        val current = job.copy(progressPermille = permille, phase = phase)
        val serviceType = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(notificationId(job.id), progressNotification(current), serviceType)
    }

    private fun progressNotification(job: VideoExportJob): Notification = base(job)
        .setContentText(applicationContext.getString(job.phase.stringResource()))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(1000, job.progressPermille, false)
        .addAction(0, applicationContext.getString(R.string.video_export_cancel), cancelIntent(job))
        .build()

    private fun cancelIntent(job: VideoExportJob): PendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        notificationId(job.id),
        Intent(applicationContext, VideoExportCancelReceiver::class.java)
            .putExtra(JobIdKey, job.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun completionNotification(job: VideoExportJob): Notification {
        val uri = requireNotNull(job.outputUri).toUri()
        val view = Intent(applicationContext, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(applicationContext, notificationId(job.id), view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (job.usedSoftwareCodec) R.string.video_export_complete_software else R.string.video_export_complete
        return base(job).setContentText(applicationContext.getString(text)).setContentIntent(pending).setAutoCancel(true)
            .addAction(0, applicationContext.getString(R.string.video_export_view), pending).build()
    }

    private fun failureNotification(job: VideoExportJob): Notification = base(job)
        .setContentText(job.error ?: applicationContext.getString(R.string.video_export_failed))
        .setAutoCancel(true).build()

    private fun base(job: VideoExportJob) = NotificationCompat.Builder(applicationContext, ChannelId)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(applicationContext.getString(R.string.video_export_title))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun createChannel() {
        notifications.createNotificationChannel(NotificationChannel(ChannelId, applicationContext.getString(R.string.video_export_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun VideoExportPhase.stringResource() = when (this) {
        VideoExportPhase.Preparing -> R.string.video_export_preparing
        VideoExportPhase.GeneratingFrames -> R.string.video_export_generating_frames
        VideoExportPhase.Rendering -> R.string.video_export_rendering
        VideoExportPhase.Publishing -> R.string.video_export_publishing
        VideoExportPhase.Verifying -> R.string.video_export_verifying
        VideoExportPhase.Completed -> R.string.video_export_complete
    }

    private fun notificationId(id: String) = id.hashCode() and Int.MAX_VALUE

    companion object {
        private val publicationObservers = java.util.concurrent.ConcurrentHashMap<
            String, suspend (VideoExportJob, File) -> Unit,
        >()

        /** Observes a real rendered file; it does not replace rendering or publication results. */
        internal fun observeBeforePublication(
            inputUri: String,
            observer: suspend (VideoExportJob, File) -> Unit,
        ): AutoCloseable {
            require(inputUri.isNotBlank())
            check(publicationObservers.putIfAbsent(inputUri, observer) == null)
            return AutoCloseable { publicationObservers.remove(inputUri, observer) }
        }

        const val JobIdKey = "video-export-job-id"
        const val OutputUriKey = "video-export-output-uri"
        const val ExportTag = "video-export"
        const val QueueName = "video-export-fifo"
        private const val ChannelId = "video-export"
        private const val CancellationPollMillis = 250L
    }
}

class VideoExportCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(VideoExportWorker.JobIdKey)?.let { id ->
            VideoExportStore.get(context).get(id)?.let(VideoExportStore.get(context)::cancel)
        }
    }
}
