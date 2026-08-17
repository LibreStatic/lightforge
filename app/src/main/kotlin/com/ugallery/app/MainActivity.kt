package com.ugallery.app

import android.os.Bundle
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.paging.compose.collectAsLazyPagingItems
import com.ugallery.feature.photos.PhotosRoute
import com.ugallery.feature.photos.LibraryPhotosRoute
import com.ugallery.feature.photos.LibraryUiState
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.feature.permissions.PermissionCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var permissionCoordinator: PermissionCoordinator
    private val galleryViewModel: GalleryViewModel by viewModels()
    private var usesProductionRuntime = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(permissionCoordinator)
        enableEdgeToEdge()
        val performanceBuild = BuildConfig.BUILD_TYPE.contains("benchmark", ignoreCase = true) ||
            BuildConfig.BUILD_TYPE.contains("nonMinified", ignoreCase = true)
        usesProductionRuntime = !performanceBuild ||
            intent.getBooleanExtra(PRODUCTION_TIMELINE_EXTRA, false)
        val benchmarkItemCount = if (performanceBuild) {
            intent.getIntExtra(BENCHMARK_ITEM_COUNT_EXTRA, 100_000).coerceIn(0, 250_000)
        } else {
            0
        }
        setContent {
            UGalleryTheme {
                if (!usesProductionRuntime) {
                    PhotosRoute(itemCount = benchmarkItemCount)
                } else {
                    val access by galleryViewModel.access.collectAsState()
                    val engineState by galleryViewModel.engineState.collectAsState()
                    val thumbnailLoader by galleryViewModel.thumbnailLoader.collectAsState()
                    val entries = galleryViewModel.timeline.collectAsLazyPagingItems()
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { galleryViewModel.onForeground() }
                    LibraryPhotosRoute(
                        access = access,
                        engineState = engineState.toUiState(),
                        entries = entries,
                        thumbnailLoader = thumbnailLoader,
                        onRequestAccess = {
                            val plan = if (Build.VERSION.SDK_INT >= 34 && access.isLimited) {
                                permissionCoordinator.reselectionRequest()
                            } else {
                                permissionCoordinator.initialMediaRequest()
                            }
                            permissionLauncher.launch(plan.permissions.toTypedArray())
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (usesProductionRuntime && ::permissionCoordinator.isInitialized) {
            galleryViewModel.onForeground()
        }
    }

    private companion object {
        const val BENCHMARK_ITEM_COUNT_EXTRA = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"
        const val PRODUCTION_TIMELINE_EXTRA = "com.ugallery.app.extra.PRODUCTION_TIMELINE"
    }
}

private fun LibraryEngineState.toUiState(): LibraryUiState = when (this) {
    LibraryEngineState.Starting -> LibraryUiState.Starting
    LibraryEngineState.Indexing -> LibraryUiState.Indexing
    LibraryEngineState.Ready -> LibraryUiState.Ready
    LibraryEngineState.PermissionRequired -> LibraryUiState.PermissionRequired
    LibraryEngineState.Error -> LibraryUiState.Error
}
