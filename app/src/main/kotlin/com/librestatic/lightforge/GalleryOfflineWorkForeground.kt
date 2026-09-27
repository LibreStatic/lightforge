package com.librestatic.lightforge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import java.util.UUID

internal fun validOfflineWorkId(id: String) = runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)
internal fun offlineWorkForeground(context: Context,id: String,channel: String,titleResource: Int): ForegroundInfo {
    val title=context.getString(titleResource)
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,title,NotificationManager.IMPORTANCE_LOW))
    val open=PendingIntent.getActivity(context,id.hashCode(),Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(0,0,true).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    return ForegroundInfo((id.hashCode() and 0x1fffffff) or 0x20000000,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
