package com.librestatic.lightforge

import android.os.Bundle
import android.os.Build
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import com.librestatic.lightforge.feature.photos.PhotosRoute
import com.librestatic.lightforge.feature.photos.LibraryUiState
import com.librestatic.lightforge.core.designsystem.LocalShowVideoDuration
import com.librestatic.lightforge.core.designsystem.LocalThumbnailTileSettings
import com.librestatic.lightforge.core.designsystem.ThumbnailTileSettings
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.feature.permissions.PermissionCoordinator
import com.librestatic.lightforge.feature.settings.LegacyAppLanguage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var permissionCoordinator: PermissionCoordinator
    private val galleryViewModel: GalleryViewModel by viewModels()
    private var usesProductionRuntime = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LegacyAppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(permissionCoordinator)
        enableEdgeToEdge()
        val performanceBuild = BuildConfig.BUILD_TYPE.contains("benchmark", ignoreCase = true) ||
            BuildConfig.BUILD_TYPE.contains("nonMinified", ignoreCase = true)
        val benchmarkMlLoad = performanceBuild &&
            intent.getBooleanExtra(BENCHMARK_ML_LOAD_EXTRA, false)
        usesProductionRuntime = !performanceBuild ||
            intent.getBooleanExtra(PRODUCTION_TIMELINE_EXTRA, false)
        if (usesProductionRuntime) galleryViewModel.openExternal(intent)
        if (benchmarkMlLoad) galleryViewModel.startBenchmarkMlLoad()
        val benchmarkItemCount = if (performanceBuild) {
            intent.getIntExtra(BENCHMARK_ITEM_COUNT_EXTRA, 100_000).coerceIn(0, 250_000)
        } else {
            0
        }
        // Hold the star splash until the first screen is known instead of flashing a blank frame.
        splashScreen.setKeepOnScreenCondition {
            usesProductionRuntime &&
                galleryViewModel.externalMedia.value == null &&
                galleryViewModel.onboardingCompleted.value == null
        }
        setContent {
            LightforgeTheme {
                if (!usesProductionRuntime) {
                    val benchmarkMlRunning = if (benchmarkMlLoad) {
                        galleryViewModel.benchmarkMlRunning.collectAsState().value
                    } else false
                    PhotosRoute(
                        itemCount = benchmarkItemCount,
                        benchmarkMlRunning = benchmarkMlRunning,
                    )
                } else {
                    val thumbnails = galleryViewModel.gallerySettings.collectAsState().value.thumbnails
                    CompositionLocalProvider(
                        LocalShowVideoDuration provides thumbnails.showVideoDuration,
                        LocalThumbnailTileSettings provides ThumbnailTileSettings(
                            markFavorites = thumbnails.markFavorites,
                            showFileType = thumbnails.showFileType,
                            animateMedia = thumbnails.animateMedia,
                        ),
                    ) {
                        // Media opened from another app is shown right away; the wizard waits.
                        val external = galleryViewModel.externalMedia.collectAsState().value != null
                        val onboardingCompleted = galleryViewModel.onboardingCompleted.collectAsState().value
                        when {
                            external || onboardingCompleted == true ->
                                ProductionGalleryApp(galleryViewModel, permissionCoordinator)
                            onboardingCompleted == false -> OnboardingHost(galleryViewModel, permissionCoordinator)
                            // Settling the first-run flag takes one DataStore read; draw nothing meanwhile.
                            else -> Unit
                        }
                    }
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

    override fun onStop() {
        if (usesProductionRuntime) galleryViewModel.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (usesProductionRuntime) galleryViewModel.openExternal(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            galleryViewModel.onHardwareVolumeKey()
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val BENCHMARK_ITEM_COUNT_EXTRA = "com.librestatic.lightforge.extra.BENCHMARK_ITEM_COUNT"
        const val BENCHMARK_ML_LOAD_EXTRA = "com.librestatic.lightforge.extra.BENCHMARK_ML_LOAD"
        const val PRODUCTION_TIMELINE_EXTRA = "com.librestatic.lightforge.extra.PRODUCTION_TIMELINE"
    }
}

internal fun LibraryEngineState.toUiState(): LibraryUiState = when (this) {
    LibraryEngineState.Starting -> LibraryUiState.Starting
    LibraryEngineState.Indexing -> LibraryUiState.Indexing
    LibraryEngineState.Ready -> LibraryUiState.Ready
    LibraryEngineState.PermissionRequired -> LibraryUiState.PermissionRequired
    LibraryEngineState.Error -> LibraryUiState.Error
}
