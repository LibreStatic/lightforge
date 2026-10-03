package com.librestatic.lightforge.core.frameinterpolation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

data class DecodedVideoFrame(val bitmap: Bitmap, val timeMillis: Long)

/**
 * Decodes a video forward from a seek point into downscaled, rotation-corrected ARGB frames.
 *
 * `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)` re-decodes from the previous keyframe on
 * every call, so stepping through consecutive frames costs a whole GOP each — over a second per
 * frame on CCTV exports with 10–20 s keyframe intervals, and the platform gives up entirely
 * (`FrameDecoder: WOULD_BLOCK`) deep inside such GOPs. Here consecutive frames cost one decode.
 *
 * A decoder that fails (lost hardware instance, no YUV_420_888 output for 10-bit streams) is
 * replaced by the next capable one, resuming after the last delivered frame; the retriever is the
 * last resort.
 *
 * Frames fit inside [maxLongEdge] × [maxShortEdge] (never upscaled, even dimensions).
 * Not thread-safe; callers own every returned bitmap.
 */
class VideoFrameReader(
    context: Context,
    uri: Uri,
    private val maxLongEdge: Int,
    private val maxShortEdge: Int = Int.MAX_VALUE,
) : Closeable {
    private val retriever = MediaMetadataRetriever()
    private val rotation: Int
    val durationMillis: Long

    private var extractor: MediaExtractor? = null
    private var trackFormat: MediaFormat? = null
    private var decoderNames: List<String> = emptyList()
    private var decoderIndex = 0
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var colors = YuvColors.unspecified()
    private var inputDone = false
    private var outputDone = false
    private var skipBeforeUs = 0L
    private var lastDeliveredUs = -1L

    private var current: DecodedVideoFrame? = null
    private var pending: DecodedVideoFrame? = null
    private var fallbackCursorMillis = 0L

    init {
        retriever.setDataSource(context, uri)
        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
        durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.coerceAtLeast(1L) ?: error("Video duration is unavailable")
        try {
            openExtractor(context, uri)
            startNextDecoder()
        } catch (_: Exception) {
            releaseDecoder()
        }
    }

    /** Positions the reader so [next] returns the first frame at or after [timeMillis]. */
    fun seekTo(timeMillis: Long) {
        clearFrames()
        val target = timeMillis.coerceAtLeast(0L)
        fallbackCursorMillis = target
        lastDeliveredUs = -1L
        restartAt(target * 1_000)
    }

    /** The next frame in presentation order, or null at the end of the stream. */
    suspend fun next(): DecodedVideoFrame? {
        pending?.let { pending = null; return it }
        while (codec != null) {
            try {
                return decodeNext()?.also { lastDeliveredUs = it.timeMillis * 1_000 }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                val resumeUs = if (lastDeliveredUs >= 0) lastDeliveredUs + 1 else skipBeforeUs
                if (!startNextDecoder()) {
                    fallbackCursorMillis = (resumeUs + 999) / 1_000
                    break
                }
                restartAt(resumeUs)
            }
        }
        val time = fallbackCursorMillis
        if (time >= durationMillis) return null
        fallbackCursorMillis += FallbackStepMillis
        return retrieverFrame(time)?.let { DecodedVideoFrame(it, time) }
    }

    /**
     * Drop-in for `getFrameAtTime(OPTION_CLOSEST)`: the frame nearest [timeMillis]. Increasing
     * timestamps decode forward from the previous call instead of seeking again.
     */
    suspend fun frameAt(timeMillis: Long): Bitmap? {
        if (codec == null) return retrieverFrame(timeMillis)
        val held = current
        if (held == null || timeMillis + SeekBackSlackMillis < held.timeMillis ||
            timeMillis - held.timeMillis > SeekAheadMillis
        ) {
            seekTo(timeMillis - SeekBackSlackMillis)
        }
        while (true) {
            val candidate = next() ?: break
            val previous = current
            if (candidate.timeMillis <= timeMillis) {
                previous?.bitmap?.recycle()
                current = candidate
                continue
            }
            if (previous == null || candidate.timeMillis - timeMillis < timeMillis - previous.timeMillis) {
                previous?.bitmap?.recycle()
                current = candidate
            } else {
                pending = candidate
            }
            break
        }
        return current?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun close() {
        clearFrames()
        releaseDecoder()
        extractor?.release()
        extractor = null
        retriever.release()
    }

    private fun openExtractor(context: Context, uri: Uri) {
        val extractor = MediaExtractor().also { this.extractor = it }
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("No video track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        trackFormat = format
        colors = YuvColors.from(format)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val preferred = runCatching { codecs.findDecoderForFormat(format) }.getOrNull()
        decoderNames = (listOfNotNull(preferred) + codecs.codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .map { it.name }).distinct()
    }

    /** Releases the current decoder and starts the next candidate; false once all have failed. */
    private fun startNextDecoder(): Boolean {
        releaseDecoder()
        val format = trackFormat ?: return false
        while (decoderIndex < decoderNames.size) {
            val name = decoderNames[decoderIndex++]
            try {
                codec = MediaCodec.createByCodecName(name).apply {
                    configure(format, null, null, 0)
                    start()
                }
                return true
            } catch (_: Exception) {
                releaseDecoder()
            }
        }
        return false
    }

    private fun restartAt(timeUs: Long) {
        val extractor = extractor ?: return
        val codec = codec ?: return
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        skipBeforeUs = timeUs
    }

    private suspend fun decodeNext(): DecodedVideoFrame? {
        val codec = codec ?: return null
        val extractor = extractor ?: return null
        var idleSince = SystemClock.elapsedRealtime()
        while (!outputDone) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val input = codec.dequeueInputBuffer(CodecTimeoutUs)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, CodecTimeoutUs)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> colors = YuvColors.from(codec.outputFormat)
                output >= 0 -> {
                    idleSince = SystemClock.elapsedRealtime()
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (endOfStream) outputDone = true
                    if (info.size <= 0 || info.presentationTimeUs < skipBeforeUs) {
                        codec.releaseOutputBuffer(output, false)
                        continue
                    }
                    val frame = try {
                        val image = codec.getOutputImage(output) ?: error("Decoder returned no image")
                        convert(image)
                    } finally {
                        codec.releaseOutputBuffer(output, false)
                    }
                    return DecodedVideoFrame(frame, info.presentationTimeUs / 1_000)
                }
                SystemClock.elapsedRealtime() - idleSince > StallTimeoutMillis -> error("Video decoder stalled")
            }
        }
        return null
    }

    private fun convert(image: Image): Bitmap {
        check(image.format == ImageFormat.YUV_420_888) { "Unsupported decoder output ${image.format}" }
        val crop = image.cropRect
        val (width, height) = fit(crop.width(), crop.height())
        val pixels = yuvToArgb(image, crop.left, crop.top, crop.width(), crop.height(), width, height, colors)
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { setRotate(rotation.toFloat()) }, false)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun retrieverFrame(timeMillis: Long): Bitmap? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val (scaledWidth, scaledHeight) = fit(width, height)
        val (boundsWidth, boundsHeight) = if (rotation % 180 == 0) scaledWidth to scaledHeight else scaledHeight to scaledWidth
        val frame = retriever.getScaledFrameAtTime(
            timeMillis.coerceAtLeast(0L) * 1_000,
            MediaMetadataRetriever.OPTION_CLOSEST,
            boundsWidth,
            boundsHeight,
        ) ?: return null
        if (frame.config == Bitmap.Config.ARGB_8888) return frame
        return frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() }
    }

    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(width, height), maxShortEdge.toFloat() / minOf(width, height))
        return ((width * scale).roundToInt() and -2).coerceAtLeast(2) to
            ((height * scale).roundToInt() and -2).coerceAtLeast(2)
    }

    private fun clearFrames() {
        current?.bitmap?.recycle()
        pending?.bitmap?.recycle()
        current = null
        pending = null
    }

    private fun releaseDecoder() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private companion object {
        const val CodecTimeoutUs = 10_000L
        const val StallTimeoutMillis = 5_000L
        const val SeekBackSlackMillis = 250L
        const val SeekAheadMillis = 30_000L
        const val FallbackStepMillis = 33L
    }
}

/** Y'CbCr → R'G'B' coefficients for the decoder's signalled matrix and range. */
internal class YuvColors(kr: Float, kb: Float, val fullRange: Boolean) {
    private val kg = 1f - kr - kb
    val crToR = 2f * (1f - kr)
    val cbToB = 2f * (1f - kb)
    val cbToG = 2f * kb * (1f - kb) / kg
    val crToG = 2f * kr * (1f - kr) / kg

    companion object {
        /** Untagged streams (most CCTV exports) are BT.601, matching the platform's decoders. */
        fun unspecified(fullRange: Boolean = false) = YuvColors(0.299f, 0.114f, fullRange)

        fun from(format: MediaFormat): YuvColors {
            val fullRange = format.optionalInt(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            return when (format.optionalInt(MediaFormat.KEY_COLOR_STANDARD)) {
                MediaFormat.COLOR_STANDARD_BT709 -> YuvColors(0.2126f, 0.0722f, fullRange)
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> YuvColors(0.299f, 0.114f, fullRange)
                MediaFormat.COLOR_STANDARD_BT2020 -> YuvColors(0.2627f, 0.0593f, fullRange)
                else -> unspecified(fullRange)
            }
        }

        private fun MediaFormat.optionalInt(key: String): Int? =
            if (containsKey(key)) getInteger(key) else null
    }
}

/** Area-sampled (2×2 luma taps when shrinking by 2× or more) YUV_420_888 → ARGB conversion. */
internal fun yuvToArgb(
    image: Image,
    left: Int,
    top: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    width: Int,
    height: Int,
    colors: YuvColors,
): IntArray {
    val (yPlane, uPlane, vPlane) = image.planes
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val yRow = yPlane.rowStride
    val yPixel = yPlane.pixelStride
    val uRow = uPlane.rowStride
    val uPixel = uPlane.pixelStride
    val vRow = vPlane.rowStride
    val vPixel = vPlane.pixelStride
    val taps = if (sourceWidth >= width * 2) 2 else 1
    val xs = IntArray(width * taps) { index ->
        val x = index / taps
        val tap = index % taps
        left + (((x + (tap + 0.5f) / taps) * sourceWidth / width).toInt()).coerceIn(0, sourceWidth - 1)
    }
    val ys = IntArray(height * taps) { index ->
        val y = index / taps
        val tap = index % taps
        top + (((y + (tap + 0.5f) / taps) * sourceHeight / height).toInt()).coerceIn(0, sourceHeight - 1)
    }
    val tapCount = taps * taps
    val lumaScale = if (colors.fullRange) 1f else 255f / 219f
    val chromaScale = if (colors.fullRange) 1f else 255f / 224f
    val lumaOffset = if (colors.fullRange) 0 else 16
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val cy = ys[y * taps] / 2
        for (x in 0 until width) {
            var luma = 0
            for (ty in 0 until taps) {
                val row = ys[y * taps + ty] * yRow
                for (tx in 0 until taps) {
                    luma += yBuffer.get(row + xs[x * taps + tx] * yPixel).toInt() and 0xff
                }
            }
            val cx = xs[x * taps] / 2
            val cb = ((uBuffer.get(cy * uRow + cx * uPixel).toInt() and 0xff) - 128) * chromaScale
            val cr = ((vBuffer.get(cy * vRow + cx * vPixel).toInt() and 0xff) - 128) * chromaScale
            val l = (luma / tapCount - lumaOffset) * lumaScale
            val r = (l + colors.crToR * cr).roundToInt().coerceIn(0, 255)
            val g = (l - colors.cbToG * cb - colors.crToG * cr).roundToInt().coerceIn(0, 255)
            val b = (l + colors.cbToB * cb).roundToInt().coerceIn(0, 255)
            pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return pixels
}
