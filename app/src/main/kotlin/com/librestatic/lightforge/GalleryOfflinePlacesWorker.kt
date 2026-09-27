package com.librestatic.lightforge

import android.content.Context
import androidx.work.*
import com.librestatic.lightforge.feature.places.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryOfflinePlacesWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id=inputData.getString("task") ?: return@withContext Result.failure()
        if (!validOfflineWorkId(id)) return@withContext Result.failure()
        setForeground(offlineWorkForeground(applicationContext,id,"offline-maps",com.librestatic.lightforge.feature.places.R.string.places_title))
        val controller=controller(applicationContext)
        try {
            when(controller.run(id)) {
                OfflineMapTaskStatus.Queued, OfflineMapTaskStatus.Copying, OfflineMapTaskStatus.Downloading,
                OfflineMapTaskStatus.Verifying, OfflineMapTaskStatus.WaitingWifi, OfflineMapTaskStatus.WaitingCharging,
                OfflineMapTaskStatus.WaitingStorage, OfflineMapTaskStatus.WaitingHardware -> Result.retry()
                else -> Result.success()
            }
        } finally { controller.close() }
    }
    companion object {
        private val recoverable=setOf(OfflineMapTaskStatus.Queued,OfflineMapTaskStatus.Copying,OfflineMapTaskStatus.Downloading,
            OfflineMapTaskStatus.Verifying,OfflineMapTaskStatus.WaitingWifi,OfflineMapTaskStatus.WaitingCharging,
            OfflineMapTaskStatus.WaitingStorage,OfflineMapTaskStatus.WaitingHardware)
        fun controller(context: Context) = OfflinePlacesController(context, { schedule(context,it) })
        fun reconcile(context: Context,controller: OfflinePlacesController) {
            controller.refresh()
            controller.tasks.value.filter { it.status in recoverable }.forEach { schedule(context,it.id) }
        }
        fun schedule(context: Context,id: String) {
            require(validOfflineWorkId(id))
            WorkManager.getInstance(context).enqueueUniqueWork("offline-map-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<GalleryOfflinePlacesWorker>().setInputData(workDataOf("task" to id)).build())
        }
    }
}
