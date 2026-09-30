package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.RifeFrameInterpolator
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactor
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal data class GeneratedSlowSegment(
    val segment: SlowMotionSegment,
    val frames: List<File>,
    val frameRate: Int,
    val frameDurationMillis: Long,
)

internal class SlowMotionFrameGenerator(
    private val context: Context,
) {
    suspend fun generate(
        input: Uri,
        segment: SlowMotionSegment,
        destination: File,
        onProgress: (Float) -> Unit,
    ): GeneratedSlowSegment = withContext(Dispatchers.Default) {
        destination.mkdirs()
        val retriever = MediaMetadataRetriever()
        val interpolator = RifeFrameInterpolator(context)
        try {
            retriever.setDataSource(context, input)
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.roundToInt()?.coerceIn(15, 60) ?: 30
            val sourceStepMillis = (1_000f / fps).roundToInt().coerceAtLeast(16)
            val frameDurationMillis = (1_000f / fps).roundToInt().toLong().coerceAtLeast(1)
            val dimensions = outputDimensions(retriever)
            val pairCount = ((segment.endMillis - segment.startMillis) / sourceStepMillis)
                .toInt().coerceAtLeast(1)
            val files = ArrayList<File>(pairCount * segment.interpolationFactor + 1)
            var leftTime = segment.startMillis
            var left = frameAt(retriever, leftTime, dimensions.first, dimensions.second)
                ?: error("Could not decode the slow-motion segment")
            var pairIndex = 0
            while (leftTime < segment.endMillis) {
                coroutineContext.ensureActive()
                val rightTime = (leftTime + sourceStepMillis).coerceAtMost(segment.endMillis)
                if (rightTime <= leftTime) break
                val right = frameAt(retriever, rightTime, dimensions.first, dimensions.second) ?: break
                files += writeFrame(destination, files.size, left)
                val intermediates = interpolator.interpolateFactor(left, right, segment.interpolationFactor)
                intermediates.forEach { frame ->
                    try {
                        files += writeFrame(destination, files.size, frame)
                    } finally {
                        frame.recycle()
                    }
                }
                left.recycle()
                left = right
                leftTime = rightTime
                pairIndex += 1
                onProgress((pairIndex.toFloat() / pairCount).coerceIn(0f, 1f))
            }
            files += writeFrame(destination, files.size, left)
            left.recycle()
            GeneratedSlowSegment(segment, files, fps, frameDurationMillis)
        } finally {
            interpolator.close()
            retriever.release()
        }
    }

    private fun outputDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int> {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.coerceAtLeast(2) ?: 1_280
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.coerceAtLeast(2) ?: 720
        val scale = minOf(1f, 1_920f / maxOf(width, height), 1_080f / minOf(width, height))
        return ((width * scale).roundToInt().coerceAtLeast(2) and -2) to
            ((height * scale).roundToInt().coerceAtLeast(2) and -2)
    }

    private fun frameAt(
        retriever: MediaMetadataRetriever,
        timeMillis: Long,
        width: Int,
        height: Int,
    ): Bitmap? = retriever.getScaledFrameAtTime(
        timeMillis * 1_000,
        MediaMetadataRetriever.OPTION_CLOSEST,
        width,
        height,
    )?.asArgb8888()

    private fun writeFrame(directory: File, index: Int, frame: Bitmap): File {
        val output = File(directory, "frame-${index.toString().padStart(7, '0')}.jpg")
        FileOutputStream(output).use { stream ->
            check(frame.compress(Bitmap.CompressFormat.JPEG, 95, stream))
        }
        return output
    }
}

private fun Bitmap.asArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    val converted = copy(Bitmap.Config.ARGB_8888, false)
    recycle()
    return converted
}
