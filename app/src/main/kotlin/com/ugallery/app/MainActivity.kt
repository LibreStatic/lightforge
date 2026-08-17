package com.ugallery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ugallery.feature.photos.PhotosRoute
import com.ugallery.core.designsystem.UGalleryTheme
import com.ugallery.feature.permissions.PermissionCoordinator
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val permissionCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PermissionCoordinator(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(permissionCoordinator)
        enableEdgeToEdge()
        val benchmarkItemCount = if (BuildConfig.BUILD_TYPE == "benchmark") {
            intent.getIntExtra(BENCHMARK_ITEM_COUNT_EXTRA, 100_000).coerceIn(0, 250_000)
        } else {
            0
        }
        setContent {
            UGalleryTheme {
                PhotosRoute(itemCount = benchmarkItemCount)
            }
        }
    }

    private companion object {
        const val BENCHMARK_ITEM_COUNT_EXTRA = "com.ugallery.app.extra.BENCHMARK_ITEM_COUNT"
    }
}
