package com.librestatic.lightforge

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.*
import com.librestatic.lightforge.core.data.DocumentAutoArchiveRepository
import com.librestatic.lightforge.core.database.GalleryDatabase
import com.librestatic.lightforge.core.database.GalleryDatabaseFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One persistent device-local job; a missing/paused rule performs no archival. */
class DocumentAutoArchiveWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                runOnce(applicationContext, GalleryDatabaseFactory.open(applicationContext))
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Result.retry()
            }
        }

    companion object {
        const val WorkName = "document-auto-archive"

        fun install(context: Context) {
            if (Application.getProcessName() != context.packageName) return
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WorkName,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<DocumentAutoArchiveWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build()
                        )
                        .build(),
                )
        }

        /**
         * Verify actual current MediaStore visibility/generation/favorite/trash before committing.
         */
        internal suspend fun runOnce(context: Context, database: GalleryDatabase): Int =
            DocumentAutoArchiveRepository(database).runScheduled { candidate ->
                try {
                    val uri =
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.getContentUri(candidate.volumeName),
                            candidate.mediaStoreId,
                        )
                    context.contentResolver
                        .query(
                            uri,
                            arrayOf(
                                MediaStore.MediaColumns.GENERATION_MODIFIED,
                                MediaStore.MediaColumns.IS_FAVORITE,
                                MediaStore.MediaColumns.IS_TRASHED,
                            ),
                            null,
                            null,
                            null,
                        )
                        ?.use { cursor ->
                            cursor.moveToFirst() &&
                                cursor.getLong(0) == candidate.generationModified &&
                                cursor.getInt(1) == 0 &&
                                cursor.getInt(2) == 0
                        } ?: false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
    }
}
