package com.librestatic.lightforge.feature.semanticsearch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SemanticModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KeyModelId) ?: return@withContext Result.failure()
        val model = SemanticModelCatalog.models.firstOrNull { it.id == modelId } ?: return@withContext Result.failure()
        // Downloads queued automatically must not touch the network once semantic search is off.
        if (!SemanticDownloadConsent.downloadAllowed(
                SemanticModelManager.isEnabled(applicationContext),
                inputData.getBoolean(KeyUserInitiated, false),
            )) return@withContext Result.success()
        if (inputData.getBoolean(KeyWifiOnly, false) && !isUnmeteredWifi()) return@withContext Result.retry()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val cancellation = SemanticDownloadCancellation()
                val future = Downloads.submit {
                    try {
                        SemanticModelStorage(applicationContext).download(model, cancellation) { bytes ->
                            cancellation.checkCurrent()
                            setProgressAsync(Data.Builder().putLong(KeyDownloadedBytes, bytes).build())
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
                continuation.invokeOnCancellation { cancellation.cancel(); future.cancel(true) }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(Data.Builder().putString(KeyError, failure.message).build())
        }
    }

    private fun isUnmeteredWifi(): Boolean {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private val Downloads = Executors.newFixedThreadPool(2) { task -> Thread(task, "semantic-package-download").apply { isDaemon = true } }
        const val KeyModelId = "model_id"
        const val KeyWifiOnly = "wifi_only"
        const val KeyUserInitiated = "user_initiated"
        const val KeyDownloadedBytes = "downloaded_bytes"
        const val KeyError = "error"
        fun uniqueName(modelId: String) = "semantic-model-download-$modelId"
    }
}
