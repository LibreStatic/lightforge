package com.ugallery.feature.semanticsearch

import android.content.ContentUris
import android.content.Context
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ugallery.core.database.GalleryDatabaseFactory
import com.ugallery.core.database.SemanticEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

class SemanticIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KeyModelId) ?: return@withContext Result.failure()
        val indexId = inputData.getString(KeyIndexId) ?: return@withContext Result.failure()
        val descriptor = SemanticModelCatalog.models.firstOrNull { it.id == modelId } ?: return@withContext Result.failure()
        val installed = SemanticModelStorage(applicationContext).installedModel(descriptor) ?: return@withContext Result.failure()
        val database = GalleryDatabaseFactory.open(applicationContext)
        val dao = database.semanticDao()
        try {
            LiteRtSemanticEmbeddingInference(applicationContext, installed).use { inference ->
                var processed = 0L
                while (!isStopped && processed < MaxItemsPerRun) {
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
                        } catch (_: FileNotFoundException) {
                            val vector = ByteArray(CompactSemanticEmbedding.Dimensions)
                            val bands = SemanticLsh.bands(vector, descriptor.version)
                            return@mapNotNull SemanticEmbeddingEntity(
                                indexId, item.volumeName, item.mediaStoreId, item.generationModified, descriptor.version,
                                vector, bands[0], bands[1], bands[2], bands[3], bands[4], bands[5], bands[6], bands[7],
                                System.currentTimeMillis(),
                            )
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
                    if (embeddings.isNotEmpty()) dao.upsertEmbeddings(embeddings)
                    processed += media.size
                    val count = dao.embeddingCount(indexId)
                    dao.index(indexId)?.let { dao.upsertIndex(it.copy(embeddedCount = count, updatedAtMillis = System.currentTimeMillis())) }
                    setProgress(workDataOf(KeyCompletedItems to count))
                }
            }
            if (isStopped) {
                Result.retry()
            } else {
                val continuation = OneTimeWorkRequestBuilder<SemanticIndexWorker>()
                    .setInputData(workDataOf(KeyModelId to modelId, KeyIndexId to indexId))
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    uniqueName(indexId),
                    ExistingWorkPolicy.APPEND,
                    continuation,
                ).result.get()
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dao.index(indexId)?.let {
                dao.upsertIndex(it.copy(status = "failed", updatedAtMillis = System.currentTimeMillis()))
            }
            applicationContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE).run {
                if (getString("pending_index", null) == indexId) {
                    edit().remove("pending_model").remove("pending_index")
                        .putString("index_error", failure.message ?: failure::class.java.simpleName)
                        .apply()
                }
            }
            Result.failure(workDataOf(KeyError to (failure.message ?: failure::class.java.simpleName)))
        } finally {
            database.close()
        }
    }

    private suspend fun complete(
        indexId: String,
        model: SemanticModelDescriptor,
        dao: com.ugallery.core.database.SemanticDao,
        count: Long,
    ) {
        dao.index(indexId)?.let { dao.upsertIndex(it.copy(status = "active", embeddedCount = count, updatedAtMillis = System.currentTimeMillis())) }
        val preferences = applicationContext.getSharedPreferences("semantic-model-settings", Context.MODE_PRIVATE)
        val previous = preferences.getString("active_index", null)
        check(preferences.edit()
            .putString("active_model", model.id)
            .putString("active_index", indexId)
            .remove("pending_model")
            .remove("pending_index")
            .remove("index_error")
            .commit()) { "Unable to persist active semantic index" }
        if (previous != null && previous != indexId) dao.deleteIndex(previous)
    }

    companion object {
        const val KeyModelId = "model_id"
        const val KeyIndexId = "index_id"
        const val KeyCompletedItems = "completed_items"
        const val KeyError = "error"
        const val ImagePixels = 224
        const val ChunkSize = 8
        const val MaxItemsPerRun = 64
        fun uniqueName(indexId: String) = "semantic-index-$indexId"
    }
}
