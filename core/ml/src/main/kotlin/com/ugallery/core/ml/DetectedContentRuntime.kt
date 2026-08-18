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
    private var duplicateEngine: ExactDuplicateMlEngine? = null
    private var similarityEngine: SimilarityMlEngine? = null
    private var faceDetectionEngine: FaceDetectionMlEngine? = null

    @Synchronized
    fun install(context: Context) {
        if (application != null) return
        application = context.applicationContext
        MlRuntimeRegistry.register(
            LazyEngine(MlTaskType.ImageLabels, ImageLabelMlEngine.ModelVersion),
        )
        MlRuntimeRegistry.register(LazyEngine(MlTaskType.Ocr, OcrMlEngine.ModelVersion))
        MlRuntimeRegistry.register(
            LazyEngine(MlTaskType.ExactDuplicates, ExactDuplicateMlEngine.HashVersion),
        )
        MlRuntimeRegistry.register(LazyEngine(MlTaskType.Similarity, SimilarityMlEngine.AlgorithmVersion))
        MlRuntimeRegistry.register(LazyEngine(MlTaskType.FaceDetection, FaceDetectionMlEngine.ModelVersion))
    }

    @Synchronized
    internal fun resetForTest() {
        MlRuntimeRegistry.unregister(MlTaskType.ImageLabels)
        MlRuntimeRegistry.unregister(MlTaskType.Ocr)
        MlRuntimeRegistry.unregister(MlTaskType.ExactDuplicates)
        MlRuntimeRegistry.unregister(MlTaskType.Similarity)
        MlRuntimeRegistry.unregister(MlTaskType.FaceDetection)
        labelEngine?.close()
        ocrEngine?.close()
        database?.close()
        application = null
        labelEngine = null
        ocrEngine = null
        duplicateEngine = null
        similarityEngine = null
        faceDetectionEngine = null
        database = null
    }

    @Synchronized
    private fun resolved(task: MlTaskType): MlTaskEngine {
        labelEngine?.takeIf { task == MlTaskType.ImageLabels }?.let { return it }
        ocrEngine?.takeIf { task == MlTaskType.Ocr }?.let { return it }
        duplicateEngine?.takeIf { task == MlTaskType.ExactDuplicates }?.let { return it }
        similarityEngine?.takeIf { task == MlTaskType.Similarity }?.let { return it }
        faceDetectionEngine?.takeIf { task == MlTaskType.FaceDetection }?.let { return it }
        val context = checkNotNull(application) { "DetectedContentRuntime is not installed" }
        val activeDatabase = activeDatabase(context)
        val imagePermission = { context.hasReadableImages() }
        return when (task) {
            MlTaskType.ImageLabels -> ImageLabelMlEngine(
                context.contentResolver,
                activeDatabase,
                AppSearchMediaIndex(context),
                imagePermission,
            ).also { labelEngine = it }
            MlTaskType.Ocr -> OcrMlEngine(
                context.contentResolver,
                activeDatabase,
                AppSearchMediaIndex(context),
                imagePermission,
            ).also { ocrEngine = it }
            MlTaskType.ExactDuplicates -> ExactDuplicateMlEngine(
                activeDatabase,
                ResolverDuplicateContentHasher(context.contentResolver),
                { context.hasReadableMedia() },
            ).also { duplicateEngine = it }
            MlTaskType.Similarity -> SimilarityMlEngine(
                activeDatabase,
                NativeSimilarityFeatureExtractor(context.contentResolver),
                imagePermission,
            ).also { similarityEngine = it }
            MlTaskType.FaceDetection -> FaceDetectionMlEngine(
                context.contentResolver,
                activeDatabase,
                imagePermission,
            ).also { faceDetectionEngine = it }
        }
    }

    @Synchronized
    private fun activeDatabase(context: Context = checkNotNull(application)): GalleryDatabase =
        database ?: GalleryDatabaseFactory.open(context).also { database = it }

    private suspend fun purgeWithoutLoadingModel(task: MlTaskType) {
        val context = checkNotNull(application) { "DetectedContentRuntime is not installed" }
        val dao = activeDatabase(context).libraryDao()
        when (task) {
            MlTaskType.ImageLabels -> {
                dao.purgeLabels()
                dao.purgeLabelRuns()
                AppSearchMediaIndex(context).also { index ->
                    try { index.clear() } finally { index.close() }
                }
            }
            MlTaskType.Ocr -> {
                dao.purgeOcr()
                AppSearchMediaIndex(context).also { index ->
                    try { index.clear() } finally { index.close() }
                }
            }
            MlTaskType.ExactDuplicates -> dao.purgeDuplicateHashes()
            MlTaskType.Similarity -> {
                dao.purgeSimilarityFeatures()
                dao.purgeSimilarityExclusions()
            }
            MlTaskType.FaceDetection -> dao.purgeFaceDetections()
        }
    }

    private class LazyEngine(
        override val task: MlTaskType,
        override val modelVersion: String,
    ) : MlTaskEngine {
        override fun hasCurrentPermission(): Boolean = application?.let { context ->
            if (task == MlTaskType.ExactDuplicates) context.hasReadableMedia()
            else context.hasReadableImages()
        } == true
        override suspend fun process(afterExclusive: com.ugallery.core.model.MediaKey?, limit: Int) =
            resolved(task).process(afterExclusive, limit)
        override suspend fun purgeDerivedData() = purgeWithoutLoadingModel(task)
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

private fun Context.hasReadableMedia(): Boolean = hasReadableImages() || when {
    Build.VERSION.SDK_INT >= 33 ->
        checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
