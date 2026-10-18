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
                providerVersion = MediaStore.getVersion(context, volume) + IndexFormatSuffix,
                generation = MediaStore.getGeneration(context, volume),
            )
        }

    companion object {
        /**
         * Bumped when MediaStoreReader starts filling columns it used to copy verbatim, so the next
         * sync reconciles the whole volume once (v2: duration/size probed for FLV and similar).
         */
        private const val IndexFormatSuffix = "+lf2"

        val MinimalProjection: Array<String> get() = MediaStoreProjection.Columns.clone()
    }
}
