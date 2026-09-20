package com.ugallery.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.*
import com.ugallery.core.data.MediaMetadataRepository
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.model.MediaKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class PlacesLocationReadState(val running:Boolean=false,val indexed:Int=0,val failed:Int=0)

/** Explicit local EXIF discovery; zero GPS network requests and no dependency on visiting Details. */
class GalleryPlacesLocationWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        if(!allowed(applicationContext)) return@withContext Result.success()
        setForeground(offlineWorkForeground(applicationContext,id.toString(),"photo-locations",com.ugallery.feature.places.R.string.places_title))
        val database=GalleryDatabaseFactory.open(applicationContext)
        var indexed=0;var failed=0
        fun progress()=workDataOf("indexed" to indexed,"failed" to failed)
        try {
            val metadata=MediaMetadataRepository(applicationContext.contentResolver,database)
            var volume="";var mediaId=-1L
            while(allowed(applicationContext)) {
                currentCoroutineContext().ensureActive()
                val page=database.placesDao().pendingExifPage(volume,mediaId,64)
                if(page.isEmpty()) break
                for(row in page) {
                    currentCoroutineContext().ensureActive()
                    if(!allowed(applicationContext)) return@withContext Result.success(progress())
                    try {
                        metadata.exifDetails(MediaKey(row.volumeName,row.mediaStoreId),true)
                        val cached=database.libraryDao().exif(row.volumeName,row.mediaStoreId)
                        if(cached?.generationModified==row.generationModified && cached.locationReadWithPermission) indexed++ else failed++
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:Exception) { failed++ }
                    volume=row.volumeName;mediaId=row.mediaStoreId
                    if((indexed+failed)%8==0) setProgress(progress())
                }
                setProgress(progress())
            }
            Result.success(progress())
        } finally { database.close() }
    }
    companion object {
        private const val Name="places-read-photo-locations"
        fun allowed(context:Context)=context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED
        fun schedule(context:Context) {
            if(!allowed(context)) return
            WorkManager.getInstance(context).enqueueUniqueWork(Name,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<GalleryPlacesLocationWorker>().build())
        }
        internal fun observe(context:Context)=WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(Name).map { jobs ->
            val active=jobs.firstOrNull { !it.state.isFinished }
            val last=active ?: jobs.lastOrNull()
            val data=if(last?.state?.isFinished==true) last.outputData else last?.progress
            PlacesLocationReadState(active!=null,data?.getInt("indexed",0) ?: 0,data?.getInt("failed",0) ?: 0)
        }
    }
}
