package com.librestatic.lightforge.core.mediastore

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
        val MinimalProjection: Array<String> get() = MediaStoreProjection.Columns.clone()
    }
}
