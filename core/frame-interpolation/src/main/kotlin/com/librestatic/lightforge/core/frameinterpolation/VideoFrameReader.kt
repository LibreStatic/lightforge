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
import android.os.Build
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
    /**
     * Asks HDR decoders to tone-map to SDR themselves (API 33+). Off by default: on the razr fold the
     * Qualcomm decoder's mapping is brighter than what `MediaMetadataRetriever` shows (mean luma
     * +10, shadows lifted), while the software conversion matches the platform within ~1 level.
     * A decoder that honours the request is detected from its output format either way.
     */
    private val decoderToneMapping: Boolean = false,
) : Closeable {
    private val retriever = MediaMetadataRetriever()
    private val rotation: Int
    val durationMillis: Long

    private var extractor: MediaExtractor? = null
    private var trackFormat: MediaFormat? = null
    /** [trackFormat] plus a request for the decoder to tone-map HDR to SDR (API 33+, HLG/PQ only). */
    private var toneMapFormat: MediaFormat? = null
    private var decoderNames: List<String> = emptyList()
    private var decoderIndex = 0
    private var codec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private var colors = YuvColors.unspecified()
    private var inputDone = false
    private var outputDone = false
    private var skipBeforeUs = 0L
    private var lastDeliveredUs = -1L

    /** Duration of the most recent YUV → ARGB conversion, for benchmarks. */
    internal var lastConvertNanos = 0L
        private set

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
        toneMapFormat = format.takeIf { decoderToneMapping && colors.transfer != HdrTransfer.Sdr && Build.VERSION.SDK_INT >= 33 }
            ?.let { MediaFormat(it).apply { setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) } }
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
            // Ask for decoder tone mapping first; a decoder that rejects the key gets the plain
            // format and the software HDR→SDR conversion in yuvToArgb takes over.
            for (candidate in listOfNotNull(toneMapFormat, format)) {
                try {
                    codec = MediaCodec.createByCodecName(name).apply {
                        configure(candidate, null, null, 0)
                        start()
                    }
                    colors = YuvColors.from(format)
                    return true
                } catch (_: Exception) {
                    releaseDecoder()
                }
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
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    colors = YuvColors.from(codec.outputFormat, assumedTransfer = YuvColors.from(trackFormat!!).transfer)
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
                        val started = SystemClock.elapsedRealtimeNanos()
                        convert(image).also { lastConvertNanos = SystemClock.elapsedRealtimeNanos() - started }
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
        check(image.format == ImageFormat.YUV_420_888 || image.format == YcbcrP010) {
            "Unsupported decoder output ${image.format}"
        }
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

/** `ImageFormat.YCBCR_P010` (API 33): 10-bit samples in the high bits of little-endian 16-bit words. */
private const val YcbcrP010 = 54

/** Transfer function of the decoded samples; HLG and PQ need converting to SDR in software. */
internal enum class HdrTransfer { Sdr, Hlg, Pq }

/** Y'CbCr → R'G'B' coefficients for the decoder's signalled matrix and range. */
internal class YuvColors(
    kr: Float,
    kb: Float,
    val fullRange: Boolean,
    val transfer: HdrTransfer = HdrTransfer.Sdr,
    /** Wide-gamut BT.2020 primaries, which the SDR (BT.709) display needs converted. */
    val bt2020: Boolean = false,
) {
    private val kg = 1f - kr - kb
    val crToR = 2f * (1f - kr)
    val cbToB = 2f * (1f - kb)
    val cbToG = 2f * kb * (1f - kb) / kg
    val crToG = 2f * kr * (1f - kr) / kg

    companion object {
        /** Untagged streams (most CCTV exports) are BT.601, matching the platform's decoders. */
        fun unspecified(fullRange: Boolean = false) = YuvColors(0.299f, 0.114f, fullRange)

        /**
         * [assumedTransfer] applies when [format] does not carry a transfer, e.g. a decoder output
         * format that says nothing about a tone-mapping request it may have ignored.
         */
        fun from(format: MediaFormat, assumedTransfer: HdrTransfer = HdrTransfer.Sdr): YuvColors {
            val fullRange = format.optionalInt(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            val transfer = when (format.optionalInt(MediaFormat.KEY_COLOR_TRANSFER)) {
                null -> assumedTransfer
                MediaFormat.COLOR_TRANSFER_HLG -> HdrTransfer.Hlg
                MediaFormat.COLOR_TRANSFER_ST2084 -> HdrTransfer.Pq
                else -> HdrTransfer.Sdr
            }
            return when (format.optionalInt(MediaFormat.KEY_COLOR_STANDARD)) {
                MediaFormat.COLOR_STANDARD_BT709 -> YuvColors(0.2126f, 0.0722f, fullRange, transfer)
                MediaFormat.COLOR_STANDARD_BT601_PAL,
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> YuvColors(0.299f, 0.114f, fullRange, transfer)
                MediaFormat.COLOR_STANDARD_BT2020 -> YuvColors(0.2627f, 0.0593f, fullRange, transfer, bt2020 = true)
                else -> YuvColors(0.299f, 0.114f, fullRange, transfer)
            }
        }

        private fun MediaFormat.optionalInt(key: String): Int? =
            if (containsKey(key)) getInteger(key) else null
    }
}

/**
 * Software HDR → SDR for the frames of decoders that ignore the tone-mapping request:
 * nonlinear BT.2020 R'G'B' → display-referred linear light (HLG inverse OETF + OOTF with the
 * display peak at 1.0, which is what the platform's own HDR frame retrieval produces; or the PQ EOTF
 * with 203 nit diffuse white at 1.0 and a highlight roll-off) → BT.709 primaries → sRGB OETF.
 * Everything per-sample is a table lookup; only the 3×3 gamut matrix is computed per pixel.
 */
internal class HdrToSdr private constructor(private val transfer: HdrTransfer) {
    /** Nonlinear code (10-bit steps over 8-bit video range: index = code * 4) → scene/display linear. */
    private val linear = FloatArray(SignalSteps) { index ->
        val signal = index / (SignalSteps - 1f)
        if (transfer == HdrTransfer.Hlg) hlgInverseOetf(signal) else pqEotfNits(signal) / DiffuseWhiteNits
    }

    /** HLG system gamma gain `Ys^(γ-1)` indexed by scene luminance, relative to the display peak. */
    private val ootfGain = FloatArray(GainSteps) { index ->
        val y = index / (GainSteps - 1f)
        Math.pow(y.toDouble(), HlgGamma - 1.0).toFloat()
    }
    private val applyOotf = transfer == HdrTransfer.Hlg
    private val output = ByteArray(OutputSteps) { index ->
        val v = (index + 0.5f) / OutputStepsPerUnit
        (srgbOetf(if (transfer == HdrTransfer.Pq) rollOff(v) else minOf(v, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /** Maps full-range 8-bit R'G'B' given in 1/4 code steps (0..1020) to packed opaque ARGB. */
    fun map(r4: Int, g4: Int, b4: Int, bt2020: Boolean): Int {
        var r = linear[r4]
        var g = linear[g4]
        var b = linear[b4]
        if (applyOotf) {
            val y = 0.2627f * r + 0.6780f * g + 0.0593f * b
            val gain = ootfGain[(y * (GainSteps - 1) + 0.5f).toInt().coerceIn(0, GainSteps - 1)]
            r *= gain; g *= gain; b *= gain
        }
        if (bt2020) {
            val nr = 1.6605f * r - 0.5876f * g - 0.0728f * b
            val ng = -0.1246f * r + 1.1329f * g - 0.0083f * b
            val nb = -0.0182f * r - 0.1006f * g + 1.1187f * b
            r = nr; g = ng; b = nb
        }
        return (0xff shl 24) or (out(r) shl 16) or (out(g) shl 8) or out(b)
    }

    private fun out(v: Float): Int {
        // Negative floats truncate toward zero or below; the max keeps index 0 for out-of-gamut values.
        val index = (v * OutputStepsPerUnit).toInt()
        return output[if (index < 0) 0 else if (index >= OutputSteps) OutputSteps - 1 else index].toInt() and 0xff
    }

    companion object {
        private const val SignalSteps = 1021
        private const val GainSteps = 4096
        private const val OutputStepsPerUnit = 4096
        private const val OutputSteps = 4 * OutputStepsPerUnit
        private const val DiffuseWhiteNits = 203f
        private const val HlgGamma = 1.2
        /** Where the roll-off starts, as a fraction of diffuse white. */
        private const val Knee = 0.8f

        private val hlg by lazy { HdrToSdr(HdrTransfer.Hlg) }
        private val pq by lazy { HdrToSdr(HdrTransfer.Pq) }

        /** The converter for [transfer], or null for SDR. Tables are built once per transfer. */
        fun forTransfer(transfer: HdrTransfer): HdrToSdr? = when (transfer) {
            HdrTransfer.Sdr -> null
            HdrTransfer.Hlg -> hlg
            HdrTransfer.Pq -> pq
        }

        /** BT.2100 HLG inverse OETF: signal 0..1 → normalised scene light 0..1. */
        fun hlgInverseOetf(signal: Float): Float {
            val a = 0.17883277f
            val b = 0.28466892f
            val c = 0.55991073f
            return if (signal <= 0.5f) signal * signal / 3f
            else ((Math.exp(((signal - c) / a).toDouble()) + b) / 12.0).toFloat()
        }

        /** SMPTE ST 2084 EOTF: signal 0..1 → absolute luminance in nits. */
        fun pqEotfNits(signal: Float): Float {
            val m1 = 0.1593017578125
            val m2 = 78.84375
            val c1 = 0.8359375
            val c2 = 18.8515625
            val c3 = 18.6875
            val p = Math.pow(signal.toDouble(), 1.0 / m2)
            return (10_000.0 * Math.pow(maxOf(p - c1, 0.0) / (c2 - c3 * p), 1.0 / m1)).toFloat()
        }

        /** sRGB OETF (what the ARGB bitmaps are tagged with), linear 0..1 → signal 0..1. */
        fun srgbOetf(v: Float): Float =
            if (v <= 0.0031308f) 12.92f * v else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

        /** Identity up to the knee, then a smooth shoulder that approaches 1.0 for bright highlights. */
        fun rollOff(v: Float): Float {
            if (v <= Knee) return v
            val room = 1f - Knee
            return Knee + room * Math.tanh(((v - Knee) / room).toDouble()).toFloat()
        }
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
    // Bulk-copied arrays are several times faster to index than direct ByteBuffer.get(), which
    // matters at full preview resolution (1280 px frames are converted on every pair).
    val yBytes = yPlane.buffer.toByteArray()
    val uBytes = uPlane.buffer.toByteArray()
    val vBytes = vPlane.buffer.toByteArray()
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
    // P010 keeps its 10-bit samples in the high bits of little-endian words: the second byte is the
    // 8-bit value this conversion works in. HDR frames the decoder did not tone-map go through HdrToSdr.
    val sampleOffset = if (image.format == YcbcrP010) 1 else 0
    val hdr = HdrToSdr.forTransfer(colors.transfer)
    val lumaOffset = if (colors.fullRange) 0 else 16
    // 16.16 fixed point with the range expansion folded into the coefficients.
    val chromaScale = if (colors.fullRange) 1f else 255f / 224f
    val lumaFixed = ((if (colors.fullRange) 1f else 255f / 219f) * 65536f).roundToInt()
    val crToR = (colors.crToR * chromaScale * 65536f).roundToInt()
    val cbToB = (colors.cbToB * chromaScale * 65536f).roundToInt()
    val cbToG = (colors.cbToG * chromaScale * 65536f).roundToInt()
    val crToG = (colors.crToG * chromaScale * 65536f).roundToInt()
    val pixels = IntArray(width * height)
    val bandRows = 16
    java.util.stream.IntStream.range(0, (height + bandRows - 1) / bandRows).parallel().forEach { band ->
        for (y in band * bandRows until minOf(height, (band + 1) * bandRows)) {
            val cy = ys[y * taps] / 2
            for (x in 0 until width) {
                var luma = 0
                for (ty in 0 until taps) {
                    val row = ys[y * taps + ty] * yRow
                    for (tx in 0 until taps) {
                        luma += yBytes[row + xs[x * taps + tx] * yPixel + sampleOffset].toInt() and 0xff
                    }
                }
                val cx = xs[x * taps] / 2
                val cb = (uBytes[cy * uRow + cx * uPixel + sampleOffset].toInt() and 0xff) - 128
                val cr = (vBytes[cy * vRow + cx * vPixel + sampleOffset].toInt() and 0xff) - 128
                val l = (luma / tapCount - lumaOffset) * lumaFixed + 32768
                if (hdr != null) {
                    // Quarter code steps keep the HDR shadows from banding on the 8-bit samples.
                    pixels[y * width + x] = hdr.map(
                        ((l + crToR * cr) shr 14).coerceIn(0, 1020),
                        ((l - cbToG * cb - crToG * cr) shr 14).coerceIn(0, 1020),
                        ((l + cbToB * cb) shr 14).coerceIn(0, 1020),
                        colors.bt2020,
                    )
                    continue
                }
                val r = ((l + crToR * cr) shr 16).coerceIn(0, 255)
                val g = ((l - cbToG * cb - crToG * cr) shr 16).coerceIn(0, 255)
                val b = ((l + cbToB * cb) shr 16).coerceIn(0, 255)
                pixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    return pixels
}

private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val source = duplicate().apply { rewind() }
    return ByteArray(source.remaining()).also { source.get(it) }
}
