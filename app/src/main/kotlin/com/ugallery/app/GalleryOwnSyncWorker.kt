package com.ugallery.app

import android.content.Context
import androidx.work.*
import com.ugallery.core.remotestorage.AndroidRemoteCredentialVault
import com.ugallery.core.remotestorage.OwnStorageConnectionFactory
import com.ugallery.feature.ownsync.*
import com.ugallery.feature.remotebackup.RemoteBackupProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryOwnSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id=inputData.getString("run") ?: return@withContext Result.failure()
        if (!validOfflineWorkId(id)) return@withContext Result.failure()
        setForeground(offlineWorkForeground(applicationContext,id,"folder-sync",com.ugallery.feature.ownsync.R.string.own_sync_title))
        val active=OwnSyncRunner(applicationContext,services(applicationContext))
        try { active.run(id); if (active.needsRetry(id)) Result.retry() else Result.success() }
        finally { active.cancel() }
    }
    companion object {
        fun services(context: Context) = OwnSyncServices(
            { RemoteBackupProfiles.read(context) }, OwnStorageConnectionFactory(),
            AndroidRemoteCredentialVault(context), { ownStorageNetworkAllowed(context) })
        fun controller(context: Context) = OwnSyncController(context,services(context)) { schedule(context,it) }
        fun schedule(context: Context,id: String) {
            require(validOfflineWorkId(id))
            WorkManager.getInstance(context).enqueueUniqueWork("folder-sync-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<GalleryOwnSyncWorker>().setInputData(workDataOf("run" to id)).build())
        }
    }
}
