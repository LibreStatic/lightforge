package com.ugallery.core.ml

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.search.AppSearchMediaIndex

/**
 * Process-lifetime owner for the bundled detected-content engines.
 *
 * Registration does not schedule work: [MlScheduler] remains the explicit opt-in boundary.
 */
object DetectedContentRuntime {
    private var application: Context? = null
    private var database: GalleryDatabase? = null
    private var labelEngine: ImageLabelMlEngine? = null
    private var ocrEngine: OcrMlEngine? = null

    @Synchronized
    fun install(context: Context) {
        if (application != null) return
        application = context.applicationContext
        MlRuntimeRegistry.register(
            LazyEngine(MlTaskType.ImageLabels, ImageLabelMlEngine.ModelVersion),
        )
        MlRuntimeRegistry.register(LazyEngine(MlTaskType.Ocr, OcrMlEngine.ModelVersion))
    }

    @Synchronized
    internal fun resetForTest() {
        MlRuntimeRegistry.unregister(MlTaskType.ImageLabels)
        MlRuntimeRegistry.unregister(MlTaskType.Ocr)
        labelEngine?.close()
        ocrEngine?.close()
        database?.close()
        application = null
        labelEngine = null
        ocrEngine = null
        database = null
    }

    @Synchronized
    private fun resolved(task: MlTaskType): MlTaskEngine {
        labelEngine?.takeIf { task == MlTaskType.ImageLabels }?.let { return it }
        ocrEngine?.takeIf { task == MlTaskType.Ocr }?.let { return it }
        val context = checkNotNull(application) { "DetectedContentRuntime is not installed" }
        val activeDatabase = database ?: GalleryDatabaseFactory.open(context).also { database = it }
        val permission = { context.hasReadableImages() }
        return when (task) {
            MlTaskType.ImageLabels -> ImageLabelMlEngine(
                context.contentResolver,
                activeDatabase,
                AppSearchMediaIndex(context),
                permission,
            ).also { labelEngine = it }
            MlTaskType.Ocr -> OcrMlEngine(
                context.contentResolver,
                activeDatabase,
                AppSearchMediaIndex(context),
                permission,
            ).also { ocrEngine = it }
            else -> error("Unsupported detected-content task: $task")
        }
    }

    private class LazyEngine(
        override val task: MlTaskType,
        override val modelVersion: String,
    ) : MlTaskEngine {
        override fun hasCurrentPermission(): Boolean = application?.hasReadableImages() == true
        override suspend fun process(afterExclusive: com.ugallery.core.model.MediaKey?, limit: Int) =
            resolved(task).process(afterExclusive, limit)
        override suspend fun purgeDerivedData() = resolved(task).purgeDerivedData()
    }
}

private fun Context.hasReadableImages(): Boolean = when {
    Build.VERSION.SDK_INT >= 34 -> {
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }
    Build.VERSION.SDK_INT >= 33 ->
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
