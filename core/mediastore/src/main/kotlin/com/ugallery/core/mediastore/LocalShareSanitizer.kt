package com.ugallery.core.mediastore

import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ugallery.core.editing.image.PhotoImageRenderer
import com.ugallery.core.editing.image.PhotoExportOutcome
import com.ugallery.core.editing.video.Media3VideoExporter
import com.ugallery.core.editing.video.VideoEditRecipe
import com.ugallery.core.editing.video.VideoExportRequest
import com.ugallery.core.model.EditOperation
import com.ugallery.core.model.EditRecipe
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Creates bounded local share copies; originals and their EXIF/container metadata are untouched. */
class LocalShareSanitizer(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver,
    private val registry: ShareTempRegistry = ShareTempRegistry(context.cacheDir),
) {
    suspend fun prepare(candidate: ShareCandidate): PreparedShareAsset = withContext(Dispatchers.IO) {
        val source = candidate.target.uri()
        when (candidate.target.kind) {
            MediaKind.Image -> prepareImage(source, candidate.target.key)
            MediaKind.Video -> prepareVideo(source)
        }
    }

    fun cleanupExpired(): Int = registry.cleanupExpired()

    private suspend fun prepareImage(source: Uri, key: MediaKey): PreparedShareAsset {
        val destination = registry.allocate("jpg")
        val recipe = EditRecipe(
            recipeId = "share:${key.volumeName}:${key.mediaStoreId}",
            source = key,
            sourceGenerationModified = 0,
            // Re-encoding through the renderer strips EXIF/GPS rather than copying it.
            operations = listOf(EditOperation.Filter("none")),
        )
        return when (val result = PhotoImageRenderer(resolver, maxExportPixels = 12_000_000L)
            .export(source, recipe, destination)
        ) {
            is PhotoExportOutcome.Completed -> PreparedShareAsset(destination.toUri(), "image/jpeg")
            is PhotoExportOutcome.Failure -> throw IllegalStateException(result.reason)
        }
    }

    private suspend fun prepareVideo(source: Uri): PreparedShareAsset {
        val destination = registry.allocate("mp4")
        Media3VideoExporter(context).export(
            VideoExportRequest(source, destination, VideoEditRecipe()),
        )
        check(destination.isFile && destination.length() > 0) { "Video sanitization produced an empty file" }
        return PreparedShareAsset(destination.toUri(), "video/mp4")
    }

    private fun MediaActionTarget.uri(): Uri = ContentUris.withAppendedId(
        when (kind) {
            MediaKind.Image -> MediaStore.Images.Media.getContentUri(key.volumeName)
            MediaKind.Video -> MediaStore.Video.Media.getContentUri(key.volumeName)
        },
        key.mediaStoreId,
    )

    private fun File.toUri(): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        this,
    )
}
