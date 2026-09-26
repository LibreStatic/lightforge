package com.ugallery.app

import android.app.Application
import android.content.Context
import com.ugallery.core.ml.DetectedContentRuntime
import com.ugallery.feature.settings.LegacyAppLanguage
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class UGalleryApplication : Application() {
    // Below Android 13 the per-app language is applied here too, so workers and notifications
    // started after the next process start use it.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LegacyAppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DetectedContentRuntime.install(this)
        DocumentAutoArchiveWorker.install(this)
    }
}
