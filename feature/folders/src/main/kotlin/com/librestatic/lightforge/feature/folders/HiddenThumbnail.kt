package com.librestatic.lightforge.feature.folders

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * A thumbnail decoded straight from the file, for a hidden item the media index cannot make one for (the app has
 * All files access, so the path is readable). Null when the file cannot be decoded. Blocking: call it off the main thread.
 */
internal fun decodeFileThumbnail(path: String, isVideo: Boolean, sizePx: Int): Bitmap? = runCatching {
    val file = File(path)
    if (isVideo) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx, sizePx)
        } finally {
            retriever.release()
        }
    } else {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val divisor = max(1, ceil(max(info.size.width, info.size.height).toDouble() / sizePx).toInt())
            decoder.setTargetSampleSize(divisor)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
}.getOrNull()
