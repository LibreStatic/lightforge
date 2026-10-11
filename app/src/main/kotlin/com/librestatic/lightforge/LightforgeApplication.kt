package com.librestatic.lightforge

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import com.librestatic.lightforge.core.editing.image.grade.PhotoGraders
import com.librestatic.lightforge.core.ml.DetectedContentRuntime
import com.librestatic.lightforge.core.ml.ModelDownloads
import com.librestatic.lightforge.feature.petrecognition.PetModelDownloadWorker
import com.librestatic.lightforge.feature.settings.LegacyAppLanguage
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LightforgeApplication : Application() {
    // Below Android 13 the per-app language is applied here too, so workers and notifications
    // started after the next process start use it.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LegacyAppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DetectedContentRuntime.install(this)
        DocumentAutoArchiveWorker.install(this)
        ModelDownloads.install(this, listOf(PetModelDownloadWorker::updateIfNeeded))
    }

    // The photo editor's GL context is rebuilt on demand, so it goes as soon as the UI is hidden.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) PhotoGraders.trim(this)
    }
}
