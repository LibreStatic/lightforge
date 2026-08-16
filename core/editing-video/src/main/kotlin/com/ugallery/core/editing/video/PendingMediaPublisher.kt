package com.ugallery.core.editing.video

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** Publishes a validated Media3 temp export without exposing a partial shared file. */
class PendingMediaPublisher(private val resolver: ContentResolver) {
    fun publishValidatedVideo(tempFile: File, displayName: String, relativePath: String): Uri {
        require(tempFile.isFile && tempFile.length() > 0) { "Export must be validated before publication" }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("MediaStore rejected pending output")

        try {
            resolver.openOutputStream(pending, "w")!!.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output) }
            }
            check(resolver.openFileDescriptor(pending, "r")?.use { it.statSize } == tempFile.length())
            resolver.update(pending, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return pending
        } catch (failure: Throwable) {
            resolver.delete(pending, null, null)
            throw failure
        }
    }
}

