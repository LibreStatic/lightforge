package com.librestatic.lightforge

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.feature.settings.LocalBackupTaskController
import com.librestatic.lightforge.feature.settings.LocalBackupTaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager owns scheduling; the private task/journals own progress and exactly-once receipts.
 */
class GalleryBackupTaskWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val id = inputData.getString("task") ?: return@withContext Result.failure()
            if (!com.librestatic.lightforge.feature.settings.LocalBackupTaskStore.validId(id)) return@withContext Result.failure()
            // Large local archives must not restart every ten minutes under the ordinary
            // worker deadline. Scheduler interruptions still resume from the durable journal.
            val notifications = applicationContext.getSystemService(NotificationManager::class.java)
            val title = applicationContext.getString(com.librestatic.lightforge.feature.settings.R.string.local_backup_tasks_title)
            notifications.createNotificationChannel(NotificationChannel(ChannelId, title, NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(applicationContext, id.hashCode(),
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, ChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(applicationContext.getString(com.librestatic.lightforge.feature.settings.R.string.local_backup_working))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(0, 0, true).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
            setForeground(ForegroundInfo(id.hashCode() and 0x3fffffff, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val database = GalleryDatabaseFactory.open(applicationContext)
            try {
                if (
                    LocalBackupTaskRunner(
                            applicationContext,
                            GalleryOrganizationBackupAdapter(applicationContext, database),
                        )
                        .run(id)
                )
                    Result.success()
                else Result.retry()
            } finally {
                database.close()
            }
        }

    companion object {
        private const val ChannelId = "local-backup-tasks"
        fun schedule(context: Context, id: String) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "local-backup-task-$id",
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<GalleryBackupTaskWorker>()
                        .setInputData(workDataOf("task" to id))
                        .build(),
                )
        }

        fun controller(context: Context) =
            LocalBackupTaskController(context) { schedule(context, it) }
    }
}
