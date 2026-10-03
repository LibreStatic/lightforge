package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.RifeFrameInterpolator
import com.librestatic.lightforge.core.frameinterpolation.VideoFrameReader
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
        // Sequential decoding: per-frame getFrameAtTime re-decodes a whole GOP for every frame.
        val reader = VideoFrameReader(context, input, maxLongEdge = 1_920, maxShortEdge = 1_080)
        val interpolator = try {
            RifeFrameInterpolator(context)
        } catch (failure: Throwable) {
            reader.close()
            throw failure
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.roundToInt()?.coerceIn(15, 60) ?: 30
            val sourceStepMillis = (1_000f / fps).roundToInt().coerceAtLeast(16)
            val frameDurationMillis = (1_000f / fps).roundToInt().toLong().coerceAtLeast(1)
            val pairCount = ((segment.endMillis - segment.startMillis) / sourceStepMillis)
                .toInt().coerceAtLeast(1)
            val files = ArrayList<File>(pairCount * segment.interpolationFactor + 1)
            var leftTime = segment.startMillis
            var left = reader.frameAt(leftTime)
                ?: error("Could not decode the slow-motion segment")
            var pairIndex = 0
            while (leftTime < segment.endMillis) {
                coroutineContext.ensureActive()
                val rightTime = (leftTime + sourceStepMillis).coerceAtMost(segment.endMillis)
                if (rightTime <= leftTime) break
                val right = reader.frameAt(rightTime) ?: break
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
            reader.close()
            interpolator.close()
            retriever.release()
        }
    }

    private fun writeFrame(directory: File, index: Int, frame: Bitmap): File {
        val output = File(directory, "frame-${index.toString().padStart(7, '0')}.jpg")
        FileOutputStream(output).use { stream ->
            check(frame.compress(Bitmap.CompressFormat.JPEG, 95, stream))
        }
        return output
    }
}
