package com.ugallery.app

import android.os.Bundle
import android.os.Build
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ugallery.feature.photos.PhotosRoute
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
        if (usesProductionRuntime) galleryViewModel.openExternal(intent)
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
                    ProductionGalleryApp(galleryViewModel, permissionCoordinator)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (usesProductionRuntime) galleryViewModel.openExternal(intent)
    }

    private companion object {
        const val BENCHMARK_ITEM_COUNT_EXTRA = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"
        const val PRODUCTION_TIMELINE_EXTRA = "com.ugallery.app.extra.PRODUCTION_TIMELINE"
    }
}

internal fun LibraryEngineState.toUiState(): LibraryUiState = when (this) {
    LibraryEngineState.Starting -> LibraryUiState.Starting
    LibraryEngineState.Indexing -> LibraryUiState.Indexing
    LibraryEngineState.Ready -> LibraryUiState.Ready
    LibraryEngineState.PermissionRequired -> LibraryUiState.PermissionRequired
    LibraryEngineState.Error -> LibraryUiState.Error
}
