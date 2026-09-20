package com.ugallery.app

import android.content.Context
import androidx.work.*
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.feature.localsharing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Durable parent owns preparation, transfer and exact Gallery publication receipts. */
class GalleryLocalSharingWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val id=inputData.getString("transfer") ?: return@withContext Result.failure()
        if (!validOfflineWorkId(id)) return@withContext Result.failure()
        setForeground(offlineWorkForeground(applicationContext,id,"android-sharing",com.ugallery.feature.localsharing.R.string.peer_title))
        val database=GalleryDatabaseFactory.open(applicationContext)
        val runner=LocalSharingRunner(applicationContext,services(applicationContext,database))
        try { runner.run(id); if(runner.needsRetry(id)) Result.retry() else Result.success() }
        finally { runner.cancel();database.close() }
    }
    companion object {
        fun services(context:Context,database:GalleryDatabase)=LocalSharingServices(
            GalleryLocalSharingSourcePort(context),GalleryLocalSharingImportPort(context,database),
            { ownStorageNetworkAllowed(context) })
        fun controller(context:Context,database:GalleryDatabase)=LocalSharingController(
            context,services(context,database),{ schedule(context,it) },
            { GalleryLocalSharingReceiveService.start(context,it) },
            { GalleryLocalSharingReceiveService.stop(context) })
        fun schedule(context:Context,id:String) {
            require(validOfflineWorkId(id))
            WorkManager.getInstance(context).enqueueUniqueWork("android-sharing-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<GalleryLocalSharingWorker>().setInputData(workDataOf("transfer" to id)).build())
        }
    }
}
