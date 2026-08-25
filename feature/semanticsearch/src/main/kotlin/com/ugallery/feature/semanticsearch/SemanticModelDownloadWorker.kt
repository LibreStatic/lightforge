package com.ugallery.feature.semanticsearch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class SemanticModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KeyModelId) ?: return@withContext Result.failure()
        val model = SemanticModelCatalog.models.firstOrNull { it.id == modelId } ?: return@withContext Result.failure()
        if (inputData.getBoolean(KeyWifiOnly, false) && !isUnmeteredWifi()) return@withContext Result.retry()
        try {
            SemanticModelStorage(applicationContext).download(model) { bytes ->
                if (isStopped) throw CancellationException("Model download cancelled")
                setProgressAsync(Data.Builder().putLong(KeyDownloadedBytes, bytes).build())
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
        const val KeyModelId = "model_id"
        const val KeyWifiOnly = "wifi_only"
        const val KeyDownloadedBytes = "downloaded_bytes"
        const val KeyError = "error"
        fun uniqueName(modelId: String) = "semantic-model-download-$modelId"
    }
}
