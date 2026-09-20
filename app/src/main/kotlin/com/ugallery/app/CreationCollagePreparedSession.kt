package com.ugallery.app

import com.ugallery.feature.collage.CreationCollageSource

/** Exact draft identity; unavailable originals may still have an independently recoverable output. */
data class CreationCollagePreparedSession(
    val id: String,
    val sources: List<CreationCollageSource>,
    val sourcesAvailable: Boolean = true,
)
