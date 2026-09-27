package com.librestatic.lightforge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.feature.localsharing.LocalSharingReceiver
import kotlinx.coroutines.*

/** Explicit Receive starts the listener. Process death/Stop/timeout never silently reopens it. */
class GalleryLocalSharingReceiveService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var database:GalleryDatabase?=null
    private var receiver:LocalSharingReceiver?=null
    private val ownership=Any()
    private var destroyed=false
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if (intent?.action!=Start || intent.getStringExtra("host").isNullOrBlank()) {
            stopSelf();return START_NOT_STICKY
        }
        val host=intent.getStringExtra("host")!!
        val title=getString(com.librestatic.lightforge.feature.localsharing.R.string.peer_title)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(Channel,title,NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,GalleryLocalSharingReceiveService::class.java).setAction(Stop),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,Channel).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText(getString(com.librestatic.lightforge.feature.localsharing.R.string.peer_receiving))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause,getString(com.librestatic.lightforge.feature.localsharing.R.string.peer_stop),stop).build()
        startForeground(NotificationId,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            try {
                synchronized(ownership) {
                    if(destroyed || receiver!=null) return@synchronized
                    val db=GalleryDatabaseFactory.open(applicationContext)
                    database=db
                    val listener=LocalSharingReceiver(applicationContext,GalleryLocalSharingWorker.services(applicationContext,db))
                    receiver=listener
                    listener.start(host)
                }
            } catch (cancelled:CancellationException) { throw cancelled }
            catch (_:Throwable) { closeListener();LocalSharingReceiver.reportStartFailure();stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun closeListener()=synchronized(ownership) {
        receiver?.close();receiver=null;database?.close();database=null
    }
    override fun onDestroy() {
        synchronized(ownership) { destroyed=true }
        scope.cancel();closeListener();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        closeListener();LocalSharingReceiver.reportStartFailure();stopSelf(startId)
    }
    companion object {
        private const val Start="com.librestatic.lightforge.localsharing.RECEIVE"
        private const val Stop="com.librestatic.lightforge.localsharing.STOP"
        private const val Channel="android-receive"
        private const val NotificationId=0x4a103
        fun start(context:Context,host:String) {
            ContextCompat.startForegroundService(context,Intent(context,GalleryLocalSharingReceiveService::class.java).setAction(Start).putExtra("host",host))
        }
        fun stop(context:Context) { context.stopService(Intent(context,GalleryLocalSharingReceiveService::class.java)) }
    }
}
