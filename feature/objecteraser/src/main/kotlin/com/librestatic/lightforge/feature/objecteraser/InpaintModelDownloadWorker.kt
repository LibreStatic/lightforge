package com.librestatic.lightforge.feature.objecteraser

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.librestatic.lightforge.core.ml.ModelDownloadPausedException
import com.librestatic.lightforge.core.ml.ModelDownloadWait
import com.librestatic.lightforge.core.ml.ModelDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException

/** Downloads and installs the pinned inpainting model under the shared Wi-Fi and battery policy. */
class InpaintModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = InpaintModelStore(applicationContext)
        if (store.installed()) return@withContext finished(Result.success())
        val gate = ModelDownloads.gate(applicationContext)
        if (gate.currentWait() != null) return@withContext Result.retry()
        val signal = CancellationSignal()
        // WorkManager stops the worker when Wi-Fi is lost; the signal aborts the blocking read at once.
        val stop = coroutineContext.job.invokeOnCompletion { signal.cancel() }
        try {
            var reported = 0L
            store.download(signal, gate::checkpoint) { bytes ->
                // Each report is a WorkManager database write; one per read chunk floods it and the UI.
                if (bytes - reported >= ProgressStepBytes || bytes >= InpaintModelStore.PackageBytes) {
                    reported = bytes
                    setProgressAsync(Data.Builder().putLong(KeyDownloadedBytes, bytes).build())
                }
            }
            finished(Result.success())
        } catch (_: ModelDownloadPausedException) {
            Result.retry()
        } catch (_: IOException) {
            // Dropped connections are transient; the partial file resumes with a Range request.
            Result.retry()
        } catch (_: OperationCanceledException) {
            Result.retry()
        } catch (_: Throwable) {
            finished(Result.failure())
        } finally {
            stop.dispose()
        }
    }

    private fun finished(result: Result): Result {
        ModelDownloads.finished(applicationContext, UniqueName)
        return result
    }

    companion object {
        const val UniqueName = "inpaint-model-download"
        const val KeyDownloadedBytes = "downloaded_bytes"
        private const val ProgressStepBytes = 256 * 1024L

        suspend fun enqueue(context: Context) =
            ModelDownloads.enqueue(context, UniqueName, InpaintModelDownloadWorker::class.java, Data.EMPTY)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UniqueName)
            ModelDownloads.finished(context, UniqueName)
            InpaintModelStore(context).discardPartialDownload()
        }

        /** The model's availability for the eraser UI; survives the editor because the worker does. */
        fun status(context: Context): Flow<InpaintModelStatus> {
            val app = context.applicationContext
            val store = InpaintModelStore(app)
            return WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(UniqueName)
                .combine(InpaintModelStore.changes) { infos, _ -> infos }
                .map { infos ->
                val active = infos.firstOrNull { !it.state.isFinished }
                when {
                    store.installed() -> InpaintModelStatus.Installed
                    active?.state == WorkInfo.State.RUNNING -> InpaintModelStatus.Downloading(
                        active.progress.getLong(KeyDownloadedBytes, store.partialBytes()), InpaintModelStore.PackageBytes,
                    )
                    active != null -> InpaintModelStatus.Queued(ModelDownloads.currentWait(app))
                    infos.any { it.state == WorkInfo.State.FAILED } -> InpaintModelStatus.Failed
                    else -> InpaintModelStatus.NotInstalled
                }
            }.distinctUntilChanged()
        }
    }
}

sealed interface InpaintModelStatus {
    data object NotInstalled : InpaintModelStatus
    /** Waiting to start; [wait] says why when a download policy holds it back. */
    data class Queued(val wait: ModelDownloadWait?) : InpaintModelStatus
    data class Downloading(val bytes: Long, val total: Long) : InpaintModelStatus
    data object Failed : InpaintModelStatus
    data object Installed : InpaintModelStatus
}
