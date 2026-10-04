package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationBackend
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
        var interpolator = try {
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
                var intermediates = interpolator.interpolateFactor(left, right, segment.interpolationFactor)
                // Some GPU drivers (emulated ones in particular) run RIFE "successfully" yet return blank
                // frames. Redo the pair on the CPU backend, and stay there, instead of exporting a
                // slow-motion clip where most frames are black.
                if (interpolator.capability.backend == FrameInterpolationBackend.Vulkan &&
                    intermediates.any { it.isBlankComparedTo(left, right) }
                ) {
                    intermediates.forEach(Bitmap::recycle)
                    interpolator.close()
                    interpolator = RifeFrameInterpolator(context, preferVulkan = false)
                    intermediates = interpolator.interpolateFactor(left, right, segment.interpolationFactor)
                }
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

/** Average luma (0..255) over a coarse grid of pixels. */
internal fun Bitmap.sampledLuma(): Float {
    var sum = 0f
    var count = 0
    for (row in 0 until SampleGrid) {
        for (column in 0 until SampleGrid) {
            val pixel = getPixel(
                (column * width / SampleGrid + width / (2 * SampleGrid)).coerceAtMost(width - 1),
                (row * height / SampleGrid + height / (2 * SampleGrid)).coerceAtMost(height - 1),
            )
            sum += 0.2126f * ((pixel shr 16) and 0xFF) + 0.7152f * ((pixel shr 8) and 0xFF) + 0.0722f * (pixel and 0xFF)
            count += 1
        }
    }
    return sum / count
}

/** True when this frame is near-black although both of its neighbours clearly are not. */
internal fun Bitmap.isBlankComparedTo(first: Bitmap, second: Bitmap): Boolean =
    isBlank(sampledLuma(), minOf(first.sampledLuma(), second.sampledLuma()))

internal fun isBlank(frameLuma: Float, neighbourLuma: Float): Boolean =
    frameLuma < BlankFrameLuma && neighbourLuma >= NeighbourMinLuma

private const val SampleGrid = 16
private const val BlankFrameLuma = 3f
private const val NeighbourMinLuma = 12f
