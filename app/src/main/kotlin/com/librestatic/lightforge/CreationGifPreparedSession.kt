package com.librestatic.lightforge

import com.librestatic.lightforge.feature.collage.CreationGifSource

/** One atomic handoff: changing photos must never reuse another creation's saved draft. */
data class CreationGifPreparedSession(val id: String, val sources: List<CreationGifSource>, val sourcesAvailable: Boolean = true)
