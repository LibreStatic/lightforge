package com.librestatic.lightforge.core.legacyvideo

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/** Media source factory whose FLV demuxer understands Sorenson Spark / VP6 video. */
@UnstableApi
object LegacyMediaSourceFactory {
    @JvmStatic
    fun create(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(context, LegacyExtractorsFactory.create())
}
