package com.ugallery.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ugallery.feature.photos.PhotosRoute
import com.ugallery.core.designsystem.UGalleryTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UGalleryTheme {
                PhotosRoute(itemCount = if (BuildConfig.BUILD_TYPE == "benchmark") 100_000 else 0)
            }
        }
    }
}
