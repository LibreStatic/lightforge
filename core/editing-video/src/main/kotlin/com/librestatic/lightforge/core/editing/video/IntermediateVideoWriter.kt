package com.librestatic.lightforge.core.editing.video

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * Streams ARGB frames into an MP4 at a constant [frameRate]: sample N is stamped N / frameRate, so
 * playing it at speed 1 shows every frame for exactly one source frame interval.
 *
 * Frames are fed as BT.709 limited-range YUV buffers rather than through an input Surface: a Surface
 * input cannot carry our timestamps (Canvas draws are stamped with the wall clock, which also skews
 * rate control) and leaves the colour matrix to the driver. The bitrate is high on purpose, since
 * this file is only a lossless-ish intermediate that Transformer re-encodes.
 */
internal class IntermediateVideoWriter(
    file: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Float,
) : Closeable {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var inputFrames = 0
    private var outputFrames = 0
    private var finished = false
    private var closed = false
    private val pixels = IntArray(width * height)
    private val row = ByteArray(width)

    /** Length of the written video; valid after [finish]. */
    val durationMillis: Long get() = (outputFrames * 1_000.0 / frameRate).toLong()

    init {
        require(width % 2 == 0 && height % 2 == 0) { "Even frame size required" }
        val bitrate = (BitsPerPixelPerFrame * width * height * frameRate).toLong()
            .coerceIn(MinBitrate, MaxBitrate).toInt()
        codec = createEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, bitrate)
            ?: createEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, bitrate)
            ?: run { muxer.release(); error("No encoder for the interpolated frames") }
        codec.start()
    }

    private fun createEncoder(mime: String, bitrate: Int): MediaCodec? {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.roundToInt())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) } }
            .sortedWith(compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated }.thenBy { it.isSoftwareOnly })
        for (candidate in candidates) {
            var created: MediaCodec? = null
            try {
                created = MediaCodec.createByCodecName(candidate.name)
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return created
            } catch (failure: Exception) {
                Log.w(Tag, "Encoder ${candidate.name} rejected $mime", failure)
                runCatching { created?.release() }
            }
        }
        return null
    }

    fun write(frame: Bitmap) {
        check(!finished) { "Writer already finished" }
        require(frame.width == width && frame.height == height) { "Frame size changed mid-range" }
        val index = awaitInputBuffer()
        val image = checkNotNull(codec.getInputImage(index)) { "Encoder exposes no input image" }
        fill(image, frame)
        val capacity = codec.getInputBuffer(index)?.capacity() ?: (width * height * 3 / 2)
        codec.queueInputBuffer(index, 0, capacity, presentationTimeUs(inputFrames), 0)
        inputFrames += 1
    }

    fun finish() {
        if (finished) return
        val index = awaitInputBuffer()
        codec.queueInputBuffer(index, 0, 0, presentationTimeUs(inputFrames), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        finished = true
        var spins = 0
        while (!drain()) {
            check(++spins < MaxDrainSpins) { "Encoder never signalled the end of the stream" }
        }
        check(muxerStarted && outputFrames > 0) { "Encoder produced no frames" }
        muxer.stop()
    }

    private fun awaitInputBuffer(): Int {
        var spins = 0
        while (true) {
            drain()
            val index = codec.dequeueInputBuffer(DequeueTimeoutUs)
            if (index >= 0) return index
            check(++spins < MaxDrainSpins) { "Encoder stopped accepting frames" }
        }
    }

    /** Moves ready encoder output to the muxer. Returns true once the end of the stream has passed. */
    private fun drain(): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (finished) DequeueTimeoutUs else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (info.size > 0 && !config && muxerStarted) {
                        val buffer = checkNotNull(codec.getOutputBuffer(index))
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // Encode order equals display order (no B-frames), so the Nth sample is frame N.
                        info.presentationTimeUs = presentationTimeUs(outputFrames)
                        muxer.writeSampleData(track, buffer, info)
                        outputFrames += 1
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (end) return true
                }
            }
        }
    }

    private fun presentationTimeUs(frameIndex: Int): Long = (frameIndex * 1_000_000.0 / frameRate).toLong()

    private fun fill(image: Image, frame: Bitmap) {
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                row[x] = (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte()
            }
            if (yPlane.pixelStride == 1) {
                yBuffer.position(y * yPlane.rowStride)
                yBuffer.put(row, 0, width)
            } else {
                for (x in 0 until width) yBuffer.put(y * yPlane.rowStride + x * yPlane.pixelStride, row[x])
            }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (cy in 0 until height / 2) {
            for (cx in 0 until width / 2) {
                val i = 2 * cy * width + 2 * cx
                val p0 = pixels[i]
                val p1 = pixels[i + 1]
                val p2 = pixels[i + width]
                val p3 = pixels[i + width + 1]
                val r = (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF) + ((p2 shr 16) and 0xFF) + ((p3 shr 16) and 0xFF) + 2) shr 2
                val g = (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF) + ((p2 shr 8) and 0xFF) + ((p3 shr 8) and 0xFF) + 2) shr 2
                val b = ((p0 and 0xFF) + (p1 and 0xFF) + (p2 and 0xFF) + (p3 and 0xFF) + 2) shr 2
                val u = (((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
                val v = (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).coerceIn(16, 240)
                uBuffer.put(cy * uPlane.rowStride + cx * uPlane.pixelStride, u.toByte())
                vBuffer.put(cy * vPlane.rowStride + cx * vPlane.pixelStride, v.toByte())
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        // A muxer that was never stopped throws from release(); the half-written file is discarded anyway.
        runCatching { muxer.release() }
    }

    private companion object {
        const val Tag = "IntermediateVideoWriter"
        const val BitsPerPixelPerFrame = 0.25
        const val MinBitrate = 8_000_000L
        const val MaxBitrate = 60_000_000L
        const val DequeueTimeoutUs = 10_000L
        const val MaxDrainSpins = 1_000
    }
}

/**
 * The largest frame size, at most [width] × [height] and with the same aspect ratio, that an HEVC or
 * AVC encoder on this device accepts at [frameRate]. Interpolated ranges keep the source resolution
 * whenever the hardware can encode it; only devices that cannot (say, 4K on a 1080p encoder) step down.
 */
internal fun encodableFrameSize(width: Int, height: Int, frameRate: Float): Pair<Int, Int> {
    val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.flatMap { info ->
        listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            .filter { mime -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .mapNotNull { mime -> runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() }
    }
    fun supported(w: Int, h: Int) = encoders.any { capabilities ->
        runCatching { capabilities.areSizeAndRateSupported(w, h, frameRate.toDouble()) }.getOrDefault(false)
    }
    val longEdge = maxOf(width, height)
    return (listOf(longEdge) + FallbackLongEdges.filter { it < longEdge })
        .map { edge -> fitFrameSize(width, height, edge) }
        .firstOrNull { (w, h) -> supported(w, h) }
        ?: fitFrameSize(width, height, FallbackLongEdges.last())
}

/** [width] × [height] scaled so the long edge is at most [longEdge], never upscaled, both sides even. */
internal fun fitFrameSize(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
    val scale = minOf(1.0, longEdge.toDouble() / maxOf(width, height))
    fun even(value: Double) = (value.toInt() / 2 * 2).coerceAtLeast(2)
    return even(width * scale) to even(height * scale)
}

private val FallbackLongEdges = listOf(3_840, 2_560, 1_920, 1_280)
