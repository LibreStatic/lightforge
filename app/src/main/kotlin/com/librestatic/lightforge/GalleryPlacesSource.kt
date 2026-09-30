package com.librestatic.lightforge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.feature.places.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample

/** Current Room generations plus current Android EXIF permission; never reads live location. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
internal class GalleryPlacesSource(context: Context, private val database: GalleryDatabase) : PlacesSource {
    private val context = context.applicationContext
    private val resumes = MutableStateFlow(0L)
    private val sequence = AtomicLong()
    private val dao = database.placesDao()
    override val revision = combine(dao.observeInputs().sample(500), resumes) { _, _ -> sequence.incrementAndGet() }
    fun hasLocationAccess() = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun refreshPermission() { resumes.value = resumes.value + 1 }
    override suspend fun years(): List<Int> {
        if (!hasLocationAccess()) return emptyList()
        val result=dao.years()
        return if (hasLocationAccess()) result else emptyList()
    }
    override suspend fun query(bounds: PlaceBounds, year: Int?, limit: Int): PlacesPhotoPage {
        if (!hasLocationAccess()) return PlacesPhotoPage(emptyList(),0)
        require(year==null || year in 1..9998)
        val start=year?.let { LocalDate.of(it,1,1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val end=year?.let { LocalDate.of(it+1,1,1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val page=dao.snapshot(bounds.south,bounds.north,bounds.west,bounds.east,start,end,limit)
        if (!hasLocationAccess()) return PlacesPhotoPage(emptyList(),0)
        return PlacesPhotoPage(page.rows.map { PlacePhoto(MediaKey(it.volumeName,it.mediaStoreId),it.latitude,it.longitude,it.timelineSortMillis,it.generationModified) },page.total)
    }
}
