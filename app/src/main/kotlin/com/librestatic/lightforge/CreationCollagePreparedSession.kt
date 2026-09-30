package com.librestatic.lightforge

import com.librestatic.lightforge.feature.collage.CreationCollageSource

/** Exact draft identity; unavailable originals may still have an independently recoverable output. */
data class CreationCollagePreparedSession(
    val id: String,
    val sources: List<CreationCollageSource>,
    val sourcesAvailable: Boolean = true,
)
