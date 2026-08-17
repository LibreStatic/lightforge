package com.ugallery.app

import android.app.Application
import com.ugallery.core.ml.DetectedContentRuntime
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class UGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DetectedContentRuntime.install(this)
    }
}
