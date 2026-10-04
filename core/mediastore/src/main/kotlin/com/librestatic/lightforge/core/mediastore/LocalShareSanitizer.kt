package com.librestatic.lightforge.core.mediastore

import android.content.Context
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.librestatic.lightforge.core.editing.image.PhotoImageRenderer
import com.librestatic.lightforge.core.editing.image.PhotoExportOutcome
import com.librestatic.lightforge.core.editing.video.Media3VideoExporter
import com.librestatic.lightforge.core.editing.video.VideoEditRecipe
import com.librestatic.lightforge.core.editing.video.VideoExportRequest
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.EditRecipe
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
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

    suspend fun prepare(source: Uri, kind: MediaKind): PreparedShareAsset = withContext(Dispatchers.IO) {
        when (kind) {
            MediaKind.Image -> prepareImage(source, key = null)
            MediaKind.Video -> prepareVideo(source)
        }
    }

    fun cleanupExpired(): Int = registry.cleanupExpired()

    private suspend fun prepareImage(source: Uri, key: MediaKey?): PreparedShareAsset {
        val destination = registry.allocate("out")
        val recipe = if (key == null) EditRecipe.ephemeral(source.toString().hashCode().toUInt().toString(16)).copy(
            operations = listOf(EditOperation.Filter("none")),
        ) else EditRecipe(
            recipeId = "share:${key.volumeName}:${key.mediaStoreId}",
            source = key,
            sourceGenerationModified = 0,
            // Re-encoding through the renderer strips EXIF/GPS rather than copying it.
            operations = listOf(EditOperation.Filter("none")),
        )
        return when (val result = PhotoImageRenderer(resolver, maxExportPixels = 12_000_000L)
            .export(source, recipe, destination)
        ) {
            is PhotoExportOutcome.Completed -> {
                val mime = result.mimeType ?: "image/jpeg"
                // FileProvider derives the type and display name from the extension, so an
                // extension-less ".out" copy reaches the target as an anonymous binary file.
                val named = registry.allocate(extensionFor(mime))
                val file = if (destination.renameTo(named)) named else destination
                PreparedShareAsset(file.toUri(), mime)
            }
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

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        else -> "jpg"
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
