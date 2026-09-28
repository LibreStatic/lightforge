package com.librestatic.lightforge.feature.semanticsearch

import android.content.ContentUris
import android.content.Context
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import com.librestatic.lightforge.core.database.SemanticEmbeddingEntity
import com.librestatic.lightforge.core.ml.AndroidFullAnalysisEligibility
import com.librestatic.lightforge.core.ml.UserHardwareWorkloadGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.FileNotFoundException
import java.io.IOException

class SemanticIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KeyModelId) ?: return@withContext Result.failure()
        val indexId = inputData.getString(KeyIndexId) ?: return@withContext Result.failure()
        val mode = inputData.getString(KeyMode)
            ?.let { runCatching { SemanticIndexMode.valueOf(it) }.getOrNull() }
            ?: SemanticIndexMode.FullLibrary
        if (UserHardwareWorkloadGate.isActive()) return@withContext Result.success()
        val fullAnalysisEligibility = AndroidFullAnalysisEligibility(applicationContext)
        if (mode == SemanticIndexMode.FullLibrary && !fullAnalysisEligibility.isEligible()) {
            return@withContext Result.retry()
        }
        val descriptor = SemanticModelCatalog.models.firstOrNull { it.id == modelId } ?: return@withContext Result.failure()
        val installed = SemanticModelStorage(applicationContext).installedModel(descriptor) ?: return@withContext Result.failure()
        val database = GalleryDatabaseFactory.open(applicationContext)
        val dao = database.semanticDao()
        try {
            LiteRtSemanticEmbeddingInference(applicationContext, installed).use { inference ->
                var processed = 0L
                while (!isStopped && processed < MaxItemsPerRun) {
                    if (UserHardwareWorkloadGate.isActive()) return@withContext Result.success()
                    if (mode == SemanticIndexMode.FullLibrary && !fullAnalysisEligibility.isEligible()) {
                        return@withContext Result.retry()
                    }
                    val media = dao.pendingMedia(indexId, descriptor.version, ChunkSize)
                    if (media.isEmpty()) {
                        complete(indexId, descriptor, dao, dao.embeddingCount(indexId))
                        return@withContext Result.success()
                    }
                    val embeddings = media.mapNotNull { item ->
                        val uri = ContentUris.withAppendedId(
                            if (item.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                                MediaStore.Video.Media.getContentUri(item.volumeName)
                            } else {
                                MediaStore.Images.Media.getContentUri(item.volumeName)
                            },
                            item.mediaStoreId,
                        )
                        val bitmap = try {
                            applicationContext.contentResolver.loadThumbnail(uri, Size(ImagePixels, ImagePixels), CancellationSignal())
                        } catch (failure: SecurityException) {
                            throw failure
                        } catch (failure: IOException) {
                            // Missing files, undecodable bytes (ImageDecoder.DecodeException) and
                            // videos without a usable thumbnail are permanent for this generation.
                            // Store an empty vector so the item leaves the pending set; failing
                            // here would mark the whole (possibly active) index failed.
                            if (failure !is FileNotFoundException) {
                                Log.w(Tag, "Skipping ${item.volumeName}/${item.mediaStoreId}: ${failure.javaClass.simpleName}")
                            }
                            return@mapNotNull skipped(indexId, item, descriptor)
                        }
                        try {
                            val vector = CompactSemanticEmbedding.quantize(inference.embedImage(bitmap))
                            val bands = SemanticLsh.bands(vector, descriptor.version)
                            SemanticEmbeddingEntity(
                                indexId, item.volumeName, item.mediaStoreId, item.generationModified, descriptor.version,
                                vector, bands[0], bands[1], bands[2], bands[3], bands[4], bands[5], bands[6], bands[7],
                                System.currentTimeMillis(),
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (embeddings.isNotEmpty()) SemanticIndexCommitGate.mutex.withLock {
                        if (isStopped) throw CancellationException("Semantic index cancelled")
                        dao.upsertCurrentEmbeddings(indexId, embeddings)
                    }
                    processed += media.size
                    val count = dao.embeddingCount(indexId)
                    SemanticIndexCommitGate.mutex.withLock {
                        dao.index(indexId)?.let { dao.upsertIndex(it.copy(embeddedCount = count, updatedAtMillis = System.currentTimeMillis())) }
                    }
                    setProgress(workDataOf(KeyCompletedItems to count))
                }
            }
            if (isStopped) {
                Result.retry()
            } else {
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    uniqueName(indexId),
                    ExistingWorkPolicy.APPEND,
                    request(modelId, indexId, mode),
                ).result.get()
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Only permission loss or a model/LiteRT failure reaches this point.
            Log.w(Tag, "Semantic index $indexId failed", failure)
            SemanticIndexCommitGate.mutex.withLock {
                dao.index(indexId)?.let {
                    dao.upsertIndex(it.copy(status = "failed", updatedAtMillis = System.currentTimeMillis()))
                }
            }
            applicationContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE).run {
                if (getString("pending_index", null) == indexId) {
                    edit().remove("pending_model").remove("pending_index")
                        .putString("index_error", failure.message ?: failure::class.java.simpleName)
                        .apply()
                }
            }
            Result.failure(workDataOf(KeyError to (failure.message ?: failure::class.java.simpleName)))
        }
    }

    private fun skipped(
        indexId: String,
        item: com.librestatic.lightforge.core.database.MediaItemEntity,
        model: SemanticModelDescriptor,
    ): SemanticEmbeddingEntity {
        val vector = ByteArray(CompactSemanticEmbedding.Dimensions)
        val bands = SemanticLsh.bands(vector, model.version)
        return SemanticEmbeddingEntity(
            indexId, item.volumeName, item.mediaStoreId, item.generationModified, model.version,
            vector, bands[0], bands[1], bands[2], bands[3], bands[4], bands[5], bands[6], bands[7],
            System.currentTimeMillis(),
        )
    }

    private suspend fun complete(
        indexId: String,
        model: SemanticModelDescriptor,
        dao: com.librestatic.lightforge.core.database.SemanticDao,
        count: Long,
    ) {
        SemanticIndexCommitGate.mutex.withLock {
            if (isStopped) throw CancellationException("Semantic index cancelled")
            val preferences = applicationContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE)
            if (!preferences.getBoolean("enabled", false) ||
                (preferences.getString("pending_index", null) != indexId && preferences.getString("active_index", null) != indexId)) return
            if (dao.markIndexActiveIfPresent(indexId, count, System.currentTimeMillis()) != 1) return
            val previous = preferences.getString("active_index", null)
            check(preferences.edit()
                .putString("active_model", model.id).putString("active_index", indexId)
                .remove("pending_model").remove("pending_index").remove("index_error").commit()) {
                "Unable to persist active semantic index"
            }
            if (previous != null && previous != indexId) dao.deleteIndex(previous)
        }
    }

    companion object {
        private const val Tag = "SemanticIndexWorker"
        const val KeyModelId = "model_id"
        const val KeyIndexId = "index_id"
        const val KeyMode = "index_mode"
        const val KeyCompletedItems = "completed_items"
        const val KeyError = "error"
        const val ImagePixels = 224
        const val ChunkSize = 8
        const val MaxItemsPerRun = 64
        fun uniqueName(indexId: String) = "semantic-index-$indexId"

        internal fun request(
            modelId: String,
            indexId: String,
            mode: SemanticIndexMode,
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .setRequiresCharging(false)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    KeyModelId to modelId,
                    KeyIndexId to indexId,
                    KeyMode to mode.name,
                ),
            )
            .build()
    }
}

internal enum class SemanticIndexMode { FullLibrary, Incremental }
