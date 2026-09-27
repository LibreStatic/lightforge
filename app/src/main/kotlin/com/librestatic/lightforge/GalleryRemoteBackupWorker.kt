package com.librestatic.lightforge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.remotestorage.AndroidRemoteCredentialVault
import com.librestatic.lightforge.core.remotestorage.OwnStorageConnectionFactory
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupController
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupRunner
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupGrants
import com.librestatic.lightforge.feature.remotebackup.RemoteBackupServices
import com.librestatic.lightforge.feature.remotebackup.RemoteRestoreBridge
import com.librestatic.lightforge.feature.settings.RemoteRestoreTaskBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val LocalNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"

internal fun ownStorageRequiresLanPermission(sdk: Int, target: Int): Boolean =
    sdk >= 37 && target >= 37

internal fun ownStorageNetworkAllowed(context: Context): Boolean =
    !ownStorageRequiresLanPermission(
        Build.VERSION.SDK_INT,
        context.applicationInfo.targetSdkVersion,
    ) || context.checkSelfPermission(LocalNetworkPermission) == PackageManager.PERMISSION_GRANTED

class GalleryRemoteBackupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val id = inputData.getString("task") ?: return@withContext Result.failure()
            if (!com.librestatic.lightforge.feature.settings.LocalBackupTaskStore.validId(id))
                return@withContext Result.failure()
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            val title = applicationContext.getString(R.string.remote_storage_title)
            manager.createNotificationChannel(
                NotificationChannel(ChannelId, title, NotificationManager.IMPORTANCE_LOW)
            )
            val open =
                PendingIntent.getActivity(
                    applicationContext,
                    id.hashCode(),
                    Intent(applicationContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat.Builder(applicationContext, ChannelId)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setContentTitle(title)
                    .setContentText(applicationContext.getString(R.string.remote_storage_working))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .build()
            setForeground(
                ForegroundInfo(
                    (id.hashCode() and 0x1fffffff) or 0x40000000,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            )
            val database = GalleryDatabaseFactory.open(applicationContext)
            val runner =
                RemoteBackupRunner(applicationContext, services(applicationContext, database))
            try {
                if (runner.run(id)) Result.success() else Result.retry()
            } finally {
                runner.cancel()
                database.close()
            }
        }

    companion object {
        private const val ChannelId = "remote-backup-tasks"

        fun schedule(context: Context, id: String) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "remote-backup-task-$id",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<GalleryRemoteBackupWorker>()
                        .setInputData(workDataOf("task" to id))
                        .build(),
                )
        }

        fun services(context: Context, database: GalleryDatabase): RemoteBackupServices {
            val bridge =
                RemoteRestoreTaskBridge(context, RemoteBackupGrants(context)) {
                    GalleryBackupTaskWorker.schedule(context, it)
                }
            return RemoteBackupServices(
                OwnStorageConnectionFactory(),
                AndroidRemoteCredentialVault(context),
                GalleryOrganizationBackupAdapter(context, database),
                object : RemoteRestoreBridge {
                    override suspend fun enqueueOnce(
                        requestId: String,
                        archive: java.io.File,
                        manifest: com.librestatic.lightforge.feature.settings.BackupManifest,
                        destination: android.net.Uri?,
                        gallery: Boolean,
                        options: com.librestatic.lightforge.feature.settings.LocalRestoreOrganizationOptions,
                    ): String =
                        bridge.enqueueOnce(
                            requestId,
                            archive,
                            manifest,
                            destination,
                            gallery,
                            options,
                        )

                    override suspend fun existing(requestId: String): String? =
                        bridge.existing(requestId)
                },
                { ownStorageNetworkAllowed(context) },
            )
        }

        fun controller(context: Context, database: GalleryDatabase) =
            RemoteBackupController(context, services(context, database)) { schedule(context, it) }
    }
}
