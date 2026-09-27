package com.librestatic.lightforge

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.feature.places.*

@Composable
internal fun OfflinePlacesScreen(controller: OfflinePlacesController,source: GalleryPlacesSource,onBack: () -> Unit,
    onPhotoClick: (MediaKey) -> Unit,thumbnail: suspend (MediaKey) -> Bitmap?) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val locationRead by remember(context) { GalleryPlacesLocationWorker.observe(context) }.collectAsState(PlacesLocationReadState())
    var allowed by remember { mutableStateOf(source.hasLocationAccess()) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun refresh() { allowed=source.hasLocationAccess(); source.refreshPermission(); if(allowed) GalleryPlacesLocationWorker.schedule(context) }
    LaunchedEffect(source) { refresh() }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    DisposableEffect(lifecycle,source) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PlacesContent(controller,source,onBack,onPhotoClick,thumbnail,locationAccessGranted=allowed,
        onRequestLocationAccess={ request.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
        locationIndexing=locationRead.running,locationIndexed=locationRead.indexed,locationFailed=locationRead.failed,
        onRefreshLocations={ GalleryPlacesLocationWorker.schedule(context) })
}
