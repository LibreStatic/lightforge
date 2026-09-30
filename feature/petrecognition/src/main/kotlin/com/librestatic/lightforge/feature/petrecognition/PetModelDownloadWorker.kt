package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.librestatic.lightforge.core.ml.ModelDownloadPausedException
import com.librestatic.lightforge.core.ml.ModelDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.IOException

/** Downloads and installs the pinned pet models under the shared Wi-Fi and battery policy. */
class PetModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = PetModelStore(applicationContext)
        if (store.installed()) return@withContext finished(Result.success())
        val gate = ModelDownloads.gate(applicationContext)
        if (gate.currentWait() != null) return@withContext Result.retry()
        val signal = CancellationSignal()
        // WorkManager stops the worker when Wi-Fi is lost; the signal aborts the blocking read at once.
        val stop = coroutineContext.job.invokeOnCompletion { signal.cancel() }
        try {
            store.download(signal, gate::checkpoint) { bytes ->
                setProgressAsync(Data.Builder().putLong(KeyDownloadedBytes, bytes).build())
            }
            finished(Result.success())
        } catch (_: ModelDownloadPausedException) {
            Result.retry()
        } catch (_: IOException) {
            // Dropped connections are transient; the partial files resume with Range requests.
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
        const val UniqueName = "pet-model-download"
        const val KeyDownloadedBytes = "downloaded_bytes"

        suspend fun enqueue(context: Context) =
            ModelDownloads.enqueue(context, UniqueName, PetModelDownloadWorker::class.java, Data.EMPTY)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UniqueName)
            ModelDownloads.finished(context, UniqueName)
            PetModelStore(context).discardPartialDownload()
        }

        fun state(context: Context): Flow<List<androidx.work.WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UniqueName)

        /** Brings an installed older model generation up to date without asking again. */
        suspend fun updateIfNeeded(context: Context) {
            if (PetModelStore(context).needsUpdate()) enqueue(context)
        }
    }
}
