package com.librestatic.lightforge.feature.semanticsearch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.librestatic.lightforge.core.ml.ModelDownloadPausedException
import com.librestatic.lightforge.core.ml.ModelDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SemanticModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KeyModelId) ?: return@withContext finished(Result.failure())
        val model = SemanticModelCatalog.models.firstOrNull { it.id == modelId } ?: return@withContext finished(Result.failure())
        // Downloads queued automatically must not touch the network once semantic search is off.
        if (!SemanticDownloadConsent.downloadAllowed(
                SemanticModelManager.isEnabled(applicationContext),
                inputData.getBoolean(KeyUserInitiated, false),
            )) return@withContext finished(Result.success())
        val gate = ModelDownloads.gate(applicationContext)
        if (gate.currentWait() != null) return@withContext Result.retry()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val cancellation = SemanticDownloadCancellation()
                val future = Downloads.submit {
                    try {
                        SemanticModelStorage(applicationContext).download(model, cancellation) { bytes ->
                            cancellation.checkCurrent()
                            gate.checkpoint()
                            setProgressAsync(Data.Builder().putLong(KeyDownloadedBytes, bytes).build())
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
                continuation.invokeOnCancellation { cancellation.cancel(); future.cancel(true) }
            }
            finished(Result.success())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ModelDownloadPausedException) {
            // Wi-Fi or battery gate closed mid-transfer; the partial package resumes on the next attempt.
            Result.retry()
        } catch (_: IOException) {
            // A dropped connection is transient; the partial package resumes with a Range request.
            Result.retry()
        } catch (failure: Throwable) {
            finished(Result.failure(Data.Builder().putString(KeyError, failure.message).build()))
        }
    }

    private fun finished(result: Result): Result {
        ModelDownloads.finished(applicationContext, uniqueName(inputData.getString(KeyModelId).orEmpty()))
        return result
    }

    companion object {
        private val Downloads = Executors.newFixedThreadPool(2) { task -> Thread(task, "semantic-package-download").apply { isDaemon = true } }
        const val KeyModelId = "model_id"
        const val KeyUserInitiated = "user_initiated"
        const val KeyDownloadedBytes = "downloaded_bytes"
        const val KeyError = "error"
        fun uniqueName(modelId: String) = "semantic-model-download-$modelId"
    }
}
