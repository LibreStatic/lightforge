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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.librestatic.lightforge.feature.onboarding.OnboardingSplashHandoff
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import com.librestatic.lightforge.core.designsystem.rememberGalleryReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var permissionCoordinator: PermissionCoordinator
    private val galleryViewModel: GalleryViewModel by viewModels()
    private var usesProductionRuntime = false

    // Only a cold start shows the system splash; a recreated activity has nothing to hand off.
    private val splashHandoff = mutableStateOf(OnboardingSplashHandoff(onScreen = true))

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LegacyAppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) splashHandoff.value = OnboardingSplashHandoff(onScreen = false)
        // Hand the star over to the first-run intro: note where the icon sits, then fade the
        // splash away over the identical logo the wizard draws in that same spot.
        splashScreen.setOnExitAnimationListener { provider ->
            val icon = provider.iconView
            val location = IntArray(2).also(icon::getLocationInWindow)
            splashHandoff.value = OnboardingSplashHandoff(
                onScreen = false,
                // An icon-less splash hands back a detached, empty stand-in view; its zero bounds
                // would draw the intro logo at 0px, so the wizard centres it instead.
                iconBounds = if (icon.isAttachedToWindow && icon.width > 0 && icon.height > 0) {
                    Rect(
                        offset = Offset(location[0].toFloat(), location[1].toFloat()),
                        size = Size(icon.width.toFloat(), icon.height.toFloat()),
                    )
                } else {
                    null
                },
            )
            provider.view.animate()
                .alpha(0f)
                .setDuration(SPLASH_FADE_MILLIS)
                .withEndAction(provider::remove)
                .start()
        }
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
            val appearance = galleryViewModel.gallerySettings.collectAsState().value.appearance
            LightforgeTheme(appearance) {
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
                        val root = when {
                            external || onboardingCompleted == true -> RootScreen.Gallery
                            onboardingCompleted == false -> RootScreen.Onboarding
                            // Settling the first-run flag takes one DataStore read; draw nothing meanwhile.
                            else -> RootScreen.Pending
                        }
                        val reducedMotion = rememberGalleryReducedMotion()
                        AnimatedContent(
                            targetState = root,
                            // An opaque themed floor: crossfades and the pending frame must never
                            // reveal the window background underneath.
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                            transitionSpec = {
                                if (initialState == RootScreen.Onboarding && targetState == RootScreen.Gallery && !reducedMotion) {
                                    // Leaving the wizard: it swells slightly and fades while the
                                    // gallery settles in from just below full size.
                                    (fadeIn(tween(durationMillis = 420, delayMillis = 120)) +
                                        scaleIn(tween(durationMillis = 600, easing = FastOutSlowInEasing), initialScale = 0.94f)) togetherWith
                                        (fadeOut(tween(durationMillis = 300)) +
                                            scaleOut(tween(durationMillis = 450, easing = FastOutSlowInEasing), targetScale = 1.06f))
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }
                            },
                            label = "root-screen",
                        ) { screen ->
                            when (screen) {
                                RootScreen.Gallery -> ProductionGalleryApp(galleryViewModel, permissionCoordinator)
                                RootScreen.Onboarding ->
                                    OnboardingHost(galleryViewModel, permissionCoordinator, splashHandoff.value)
                                RootScreen.Pending -> Unit
                            }
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
        const val SPLASH_FADE_MILLIS = 200L
    }
}

internal fun LibraryEngineState.toUiState(): LibraryUiState = when (this) {
    LibraryEngineState.Starting -> LibraryUiState.Starting
    LibraryEngineState.Indexing -> LibraryUiState.Indexing
    LibraryEngineState.Ready -> LibraryUiState.Ready
    LibraryEngineState.PermissionRequired -> LibraryUiState.PermissionRequired
    LibraryEngineState.Error -> LibraryUiState.Error
}

/** Top-level content of the activity; switching from the wizard to the gallery is animated. */
private enum class RootScreen { Pending, Onboarding, Gallery }
