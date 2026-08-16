package com.ugallery.core.mediastore

import android.content.Context
import android.provider.MediaStore

data class VolumeGeneration(
    val volumeName: String,
    val providerVersion: String,
    val generation: Long,
)

class MediaStoreGenerationProbe(private val context: Context) {
    fun snapshot(): List<VolumeGeneration> = MediaStore.getExternalVolumeNames(context)
        .sorted()
        .map { volume ->
            VolumeGeneration(
                volumeName = volume,
                providerVersion = MediaStore.getVersion(context, volume),
                generation = MediaStore.getGeneration(context, volume),
            )
        }

    companion object {
        val MinimalProjection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_ADDED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
        )
    }
}

