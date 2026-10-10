package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.librestatic.lightforge.core.frameinterpolation.FrameInterpolationEngine
import com.librestatic.lightforge.core.frameinterpolation.RifeFrameInterpolator
import com.librestatic.lightforge.core.frameinterpolation.VideoFrameReader
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactor
import com.librestatic.lightforge.core.frameinterpolation.interpolateFactorGuided
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An interpolated [range] rendered to [file], a constant-rate video holding [durationMillis] of frames. */
internal data class GeneratedRange(
    val range: PlannedRange,
    val file: File,
    val durationMillis: Long,
)

/**
 * Renders every interpolated [PlannedRange] to its own intermediate video: decode, synthesize the
 * in-between frames and encode straight away, so no frame is ever stored on its own.
 *
 * Frames come from [VideoFrameReader], which tone-maps HDR sources to SDR: an HDR export that uses
 * interpolation therefore carries SDR pictures in those ranges (known limitation, not blocked).
 */
internal class SlowMotionFrameGenerator(
    private val context: Context,
    private val engine: FrameInterpolationEngine = FrameInterpolationEngine.Automatic,
) {
    private var interpolator: RifeFrameInterpolator? = null

    suspend fun generate(
        input: Uri,
        ranges: List<PlannedRange>,
        frameRate: Float,
        destination: File,
        onProgress: (Float) -> Unit,
    ): List<GeneratedRange> = withContext(Dispatchers.Default) {
        require(ranges.all { it.interpolationFactor != null })
        destination.mkdirs()
        val fps = frameRate.coerceIn(MinFrameRate, MaxFrameRate)
        val totalPairs = ranges.sumOf { pairCount(it, fps) }.coerceAtLeast(1)
        var donePairs = 0
        // Sequential decoding: per-frame getFrameAtTime re-decodes a whole GOP for every frame.
        // Full source resolution unless this device cannot encode it: a long wait is fine, a softer video is not.
        val (outputWidth, outputHeight) = sourceFrameSize(input)
            ?.let { (width, height) -> encodableFrameSize(width, height, fps) }
            ?: (FallbackLongEdge to FallbackLongEdge)
        val reader = VideoFrameReader(
            context,
            input,
            maxLongEdge = maxOf(outputWidth, outputHeight),
            maxShortEdge = minOf(outputWidth, outputHeight),
        )
        try {
            // One interpolator for all ranges: creating it loads the model and the Vulkan pipelines.
            interpolator = RifeFrameInterpolator(context, engine = engine)
            ranges.mapIndexed { index, range ->
                val file = File(destination, "range-$index.mp4")
                val durationMillis = renderRange(reader, range, fps, file) { pairs ->
                    onProgress(((donePairs + pairs).toFloat() / totalPairs).coerceIn(0f, 1f))
                }
                donePairs += pairCount(range, fps)
                GeneratedRange(range, file, durationMillis)
            }
        } finally {
            reader.close()
            interpolator?.close()
            interpolator = null
        }
    }

    private suspend fun renderRange(
        reader: VideoFrameReader,
        range: PlannedRange,
        fps: Float,
        file: File,
        onPairDone: (Int) -> Unit,
    ): Long {
        val factor = checkNotNull(range.interpolationFactor)
        val pairs = pairCount(range, fps)
        val stepMillis = 1_000.0 / fps
        var left = reader.frameAt(range.startMillis) ?: error("Could not decode the slow-motion range")
        var writer: IntermediateVideoWriter? = null
        var leftLow: Bitmap? = null
        try {
            writer = IntermediateVideoWriter(file, left.width, left.height, fps)
            leftLow = lowResolutionOrNull(left)
            for (pair in 1..pairs) {
                coroutineContext.ensureActive()
                val rightTime = (range.startMillis + (pair * stepMillis).roundToInt()).coerceAtMost(range.endMillis)
                val right = reader.frameAt(rightTime) ?: break
                var rightLow: Bitmap? = null
                try {
                    rightLow = lowResolutionOrNull(right)
                    writer.write(left)
                    val intermediates = interpolatePair(left, right, leftLow, rightLow, factor)
                    try {
                        intermediates.forEach(writer::write)
                    } finally {
                        intermediates.forEach(Bitmap::recycle)
                    }
                } catch (failure: Throwable) {
                    right.recycle()
                    rightLow?.recycle()
                    throw failure
                }
                left.recycle()
                leftLow?.recycle()
                left = right
                leftLow = rightLow
                onPairDone(pair)
            }
            writer.write(left)
            writer.finish()
            return writer.durationMillis
        } finally {
            left.recycle()
            leftLow?.takeIf { !it.isRecycled }?.recycle()
            writer?.close()
        }
    }

    /** The in-between frames of one pair: guided RIFE on Vulkan, plain RIFE on the CPU backend. */
    private fun interpolatePair(
        left: Bitmap,
        right: Bitmap,
        leftLow: Bitmap?,
        rightLow: Bitmap?,
        factor: Int,
    ): List<Bitmap> {
        var rife = checkNotNull(interpolator)
        var intermediates = if (leftLow != null && rightLow != null) {
            rife.interpolateFactorGuided(leftLow, rightLow, left, right, factor)
        } else rife.interpolateFactor(left, right, factor)
        // Some GPU drivers (emulated ones in particular) run RIFE "successfully" yet return blank
        // frames. Redo the pair on the CPU backend, and stay there, instead of exporting a
        // slow-motion clip where most frames are black.
        if (rife.capability.supportsGuided &&
            intermediates.any { it.isBlankComparedTo(left, right) }
        ) {
            intermediates.forEach(Bitmap::recycle)
            rife.close()
            rife = RifeFrameInterpolator(context, preferVulkan = false)
            interpolator = rife
            intermediates = rife.interpolateFactor(left, right, factor)
        }
        return intermediates
    }

    /** Low-resolution copy for the guided flow pass; null on a backend that cannot run it. */
    private fun lowResolutionOrNull(frame: Bitmap): Bitmap? =
        if (interpolator?.capability?.supportsGuided == true) frame.toLowResolution() else null

    /** Always a new, even-sized bitmap whose long edge is at most [FlowLongEdge]. */
    private fun Bitmap.toLowResolution(): Bitmap {
        val scale = minOf(1f, FlowLongEdge.toFloat() / maxOf(width, height))
        val lowWidth = ((width * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val lowHeight = ((height * scale).toInt() and 1.inv()).coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(this, lowWidth, lowHeight, true)
        return if (scaled === this) copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    /** The displayed size of [input]'s video track: [VideoFrameReader] hands out rotation-corrected frames. */
    private fun sourceFrameSize(input: Uri): Pair<Int, Int>? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input, null)
            (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { format ->
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                    if (rotation % 180 == 0) width to height else height to width
                }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    internal companion object {
        const val MinFrameRate = 15f
        const val MaxFrameRate = 60f

        /** RIFE flow and mask run at this long edge; the frames are composed at the full output size. */
        const val FlowLongEdge = 512
        /** Bound for a source whose size cannot be read up front; the reader still never upscales. */
        const val FallbackLongEdge = 1_920

        /** Source frame pairs stepped through for [range]; every pair yields `factor` frames. */
        fun pairCount(range: PlannedRange, fps: Float): Int =
            ceil(range.sourceMillis * fps / 1_000.0).toInt().coerceAtLeast(1)
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
