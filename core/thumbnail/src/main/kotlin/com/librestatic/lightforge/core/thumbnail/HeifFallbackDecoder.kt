package com.librestatic.lightforge.core.thumbnail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import android.util.Log
import android.util.Size
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes HEIC/HEIF stills by parsing the container in-app ([HeifContainer]) and feeding the hvc1
 * items to MediaCodec. Used only when the platform decoders fail on a file (some iOS HDR HEICs).
 * Orientation comes from irot/imir; EXIF orientation must not be applied to the result.
 */
internal class HeifFallbackDecoder(private val resolver: ContentResolver) {
    /** Cheap brand/mime sniff; never throws. */
    fun isHeif(uri: Uri): Boolean {
        val mime = try {
            resolver.getType(uri).orEmpty().lowercase()
        } catch (_: RuntimeException) {
            ""
        }
        if (mime == "image/heic" || mime == "image/heif") return true
        return try {
            withContainerSource(uri) { source -> HeifContainer.readBrands(source)?.let(HeifContainer::isHeifBrands) == true }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Prefers the embedded thumbnail when it is big enough for [size], else decodes the primary. */
    @Throws(IOException::class)
    fun decodeThumbnail(uri: Uri, size: Size, signal: CancellationSignal?): Bitmap =
        withContainer(uri) { container ->
            val requestedLong = max(size.width, size.height)
            val thumb = container.thumbnails
                .filter { max(it.width, it.height) >= requestedLong * THUMB_ACCEPT_RATIO && it.width > 0 }
                .minByOrNull { max(it.width, it.height) }
            var used: HeifImage? = null
            val bitmap = thumb?.let {
                try {
                    decodeImage(container, it, size.width, size.height, signal).also { _ -> used = it }
                } catch (_: IOException) {
                    null
                }
            } ?: container.primary.let { primary ->
                primary ?: throw IOException("No decodable primary image")
                used = primary
                decodeImage(container, primary, size.width, size.height, signal)
            }
            // Tag last so no resize/transform step can drop the colour space.
            fitWithin(bitmap, size.width, size.height).tagged(used, container.primary)
        }

    @Throws(IOException::class)
    fun decodePrimary(uri: Uri, targetWidth: Int, targetHeight: Int, signal: CancellationSignal? = null): Bitmap =
        withContainer(uri) { container ->
            val image = container.primary ?: throw IOException("No decodable primary image")
            fitWithin(decodeImage(container, image, targetWidth, targetHeight, signal), targetWidth, targetHeight)
                .tagged(image, image)
        }

    /** Tags the bitmap with the image's colour space (own colr, else [fallback]'s); never throws. */
    private fun Bitmap.tagged(image: HeifImage?, fallback: HeifImage?): Bitmap {
        val colorSpace = try {
            val resolved = resolveColourSpace(image?.colour, image?.iccProfile)
                ?: if (image?.colour == null && image?.iccProfile == null) {
                    resolveColourSpace(fallback?.colour, fallback?.iccProfile)
                } else {
                    null
                }
            resolved?.toAndroidColorSpace()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Ignoring unusable HEIF colour profile", e)
            null
        }
        if (colorSpace != null) {
            try {
                setColorSpace(colorSpace)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Cannot tag bitmap colour space", e)
            }
        }
        return this
    }

    private inline fun <T> withContainer(uri: Uri, block: (HeifContainer) -> T): T =
        withContainerSource(uri) { source ->
            val container = HeifContainer.parse(source) ?: throw IOException("Not a HEIF container")
            block(container)
        }

    private inline fun <T> withContainerSource(uri: Uri, block: (HeifByteSource) -> T): T {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        descriptor.use {
            // The stream is deliberately not closed: the descriptor owns the fd.
            val channel = FileInputStream(it.fileDescriptor).channel
            return block(ChannelHeifSource(channel))
        }
    }

    private fun decodeImage(
        container: HeifContainer,
        image: HeifImage,
        targetWidth: Int,
        targetHeight: Int,
        signal: CancellationSignal?,
    ): Bitmap {
        val firstConfig = image.tiles.first().hevcConfig ?: throw IOException("Missing hvcC")
        val nclx = image.colour
        val tileWidth = image.tiles.first().width
        val tileHeight = image.tiles.first().height
        val sample = chooseSample(image.width, image.height, targetWidth, targetHeight, min(tileWidth, tileHeight))
        val codec = HevcStillDecoder(
            width = tileWidth.takeIf { it > 0 } ?: image.width,
            height = tileHeight.takeIf { it > 0 } ?: image.height,
            config = firstConfig,
        )
        var canvasBitmap: Bitmap? = null
        try {
            val grid = image.grid
            var composed: Bitmap
            if (grid == null) {
                signal?.throwIfCanceled()
                val tile = image.tiles.single()
                composed = codec.decode(container.readItem(tile), tile.hevcConfig ?: firstConfig, nclx, sample, signal)
            } else {
                val canvasWidth = ceilDiv(grid.outputWidth, sample)
                val canvasHeight = ceilDiv(grid.outputHeight, sample)
                canvasBitmap = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(canvasBitmap)
                var stepX = 0
                var stepY = 0
                image.tiles.forEachIndexed { index, tile ->
                    signal?.throwIfCanceled()
                    val tileBitmap = codec.decode(container.readItem(tile), tile.hevcConfig ?: firstConfig, nclx, sample, signal)
                    if (index == 0) {
                        stepX = tileBitmap.width
                        stepY = tileBitmap.height
                    }
                    val column = index % grid.columns
                    val row = index / grid.columns
                    canvas.drawBitmap(tileBitmap, (column * stepX).toFloat(), (row * stepY).toFloat(), null)
                    tileBitmap.recycle()
                }
                composed = canvasBitmap
            }
            canvasBitmap = null
            return applyTransforms(composed, image.transforms, sample)
        } finally {
            canvasBitmap?.recycle()
            codec.release()
        }
    }

    private fun applyTransforms(source: Bitmap, transforms: List<HeifProperty>, sample: Int): Bitmap {
        var bitmap = source
        for (transform in transforms) {
            val next = when (transform) {
                is HeifProperty.Clap -> crop(bitmap, transform, sample, sourceWidthFull = bitmap.width * sample, sourceHeightFull = bitmap.height * sample)
                is HeifProperty.Irot -> if (transform.ccwDegrees == 0) bitmap else transformed(bitmap, Matrix().apply {
                    postRotate(((360 - transform.ccwDegrees) % 360).toFloat())
                })
                is HeifProperty.Imir -> transformed(bitmap, Matrix().apply {
                    if (transform.horizontalFlip) setScale(-1f, 1f) else setScale(1f, -1f)
                })
                else -> bitmap
            }
            if (next !== bitmap) bitmap.recycle()
            bitmap = next
        }
        return bitmap
    }

    private fun transformed(source: Bitmap, matrix: Matrix): Bitmap =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)

    private fun crop(source: Bitmap, clap: HeifProperty.Clap, sample: Int, sourceWidthFull: Int, sourceHeightFull: Int): Bitmap {
        if (clap.widthDen == 0L || clap.heightDen == 0L || clap.horizOffDen == 0L || clap.vertOffDen == 0L) return source
        val cleanW = clap.widthNum.toDouble() / clap.widthDen
        val cleanH = clap.heightNum.toDouble() / clap.heightDen
        val centerX = clap.horizOffNum.toDouble() / clap.horizOffDen + (sourceWidthFull - 1) / 2.0
        val centerY = clap.vertOffNum.toDouble() / clap.vertOffDen + (sourceHeightFull - 1) / 2.0
        val left = ((centerX - (cleanW - 1) / 2.0) / sample).roundToInt().coerceIn(0, source.width - 1)
        val top = ((centerY - (cleanH - 1) / 2.0) / sample).roundToInt().coerceIn(0, source.height - 1)
        val width = (cleanW / sample).roundToInt().coerceIn(1, source.width - left)
        val height = (cleanH / sample).roundToInt().coerceIn(1, source.height - top)
        if (left == 0 && top == 0 && width == source.width && height == source.height) return source
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun fitWithin(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = min(1.0, min(maxWidth.toDouble() / source.width, maxHeight.toDouble() / source.height))
        if (scale >= 1.0) return source
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun CancellationSignal.throwIfCanceled() {
        if (isCanceled) throw OperationCanceledException()
    }

    /** One reusable HEVC decoder; the codec is created lazily and flushed between stills. */
    private class HevcStillDecoder(
        private val width: Int,
        private val height: Int,
        private val config: HevcConfig,
    ) {
        private var codec: MediaCodec? = null

        fun decode(
            itemData: ByteArray,
            itemConfig: HevcConfig,
            nclx: HeifProperty.Colour?,
            sample: Int,
            signal: CancellationSignal?,
        ): Bitmap {
            val sampleData = itemConfig.parameterSetsAnnexB() + lengthPrefixedToAnnexB(itemData, itemConfig.lengthSize)
            val codec = codec ?: createCodec().also { this.codec = it }
            val inputIndex = awaitInput(codec, signal)
            val input = codec.getInputBuffer(inputIndex) ?: throw IOException("No input buffer")
            if (input.capacity() < sampleData.size) {
                throw IOException("Input buffer too small for HEVC sample")
            }
            input.clear()
            input.put(sampleData)
            codec.queueInputBuffer(inputIndex, 0, sampleData.size, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
            var bitmap: Bitmap? = null
            while (true) {
                signal?.throwIfCanceled()
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("HEVC decode timed out")
                val index = codec.dequeueOutputBuffer(info, POLL_US)
                when {
                    index >= 0 -> {
                        try {
                            if (bitmap == null && info.size > 0) {
                                val image = codec.getOutputImage(index) ?: throw IOException("Decoder produced no image")
                                bitmap = image.use { yuvToBitmap(it, nclx, sample) }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> Unit // format/buffers changed
                }
            }
            // Ready for the next still.
            codec.flush()
            return bitmap ?: throw IOException("Decoder produced no frame")
        }

        private fun awaitInput(codec: MediaCodec, signal: CancellationSignal?): Int {
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
            while (true) {
                signal?.throwIfCanceled()
                val index = codec.dequeueInputBuffer(POLL_US)
                if (index >= 0) return index
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("No HEVC input buffer")
            }
        }

        private fun createCodec(): MediaCodec {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.parameterSetsAnnexB()))
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val codec = if (name != null) MediaCodec.createByCodecName(name)
            else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw IOException("HEVC decoder configuration failed", e)
            }
            return codec
        }

        fun release() {
            val current = codec ?: return
            codec = null
            try {
                current.stop()
            } catch (_: RuntimeException) {
            }
            current.release()
        }

        private inline fun <T> Image.use(block: (Image) -> T): T = try {
            block(this)
        } finally {
            close()
        }
    }

    private companion object {
        const val TAG = "HeifFallback"
        const val THUMB_ACCEPT_RATIO = 0.75
        const val DECODE_TIMEOUT_MS = 8_000L
        const val POLL_US = 10_000L
        const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    }
}

internal fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

/**
 * Largest power-of-two subsample whose result still covers the target on its long edge
 * (so it only ever scales down afterwards), bounded by [maxSample].
 */
internal fun chooseSample(width: Int, height: Int, targetWidth: Int, targetHeight: Int, maxSample: Int): Int {
    if (width <= 0 || height <= 0) return 1
    val limit = max(1, maxSample)
    var sample = 1
    val longEdge = max(width, height)
    val shortEdge = min(width, height)
    val targetLong = max(targetWidth, targetHeight)
    val targetShort = min(targetWidth, targetHeight)
    while (sample * 2 <= limit &&
        longEdge / (sample * 2) >= targetLong && shortEdge / (sample * 2) >= targetShort
    ) {
        sample *= 2
    }
    return sample
}

/** Converts a YUV_420_888 [image] to ARGB_8888, honoring strides and crop, keeping every [sample]th pixel. */
internal fun yuvToBitmap(image: Image, nclx: HeifProperty.Colour?, sample: Int): Bitmap {
    val crop = image.cropRect
    val cropLeft = crop.left
    val cropTop = crop.top
    val outWidth = max(1, crop.width() / sample)
    val outHeight = max(1, crop.height() / sample)
    val planes = image.planes
    val yPlane = planes[0].buffer
    val uPlane = planes[1].buffer
    val vPlane = planes[2].buffer
    val yRow = planes[0].rowStride
    val yPixel = planes[0].pixelStride
    val uRow = planes[1].rowStride
    val uPixel = planes[1].pixelStride
    val vRow = planes[2].rowStride
    val vPixel = planes[2].pixelStride

    val matrix = nclx?.matrixCoefficients
    val (kr, kb) = when (matrix) {
        1 -> 0.2126 to 0.0722
        9, 10 -> 0.2627 to 0.0593
        else -> 0.299 to 0.114
    }
    val fullRange = nclx?.fullRange ?: true
    val kg = 1.0 - kr - kb
    val yScale = if (fullRange) 1.0 else 255.0 / 219.0
    val cScale = if (fullRange) 1.0 else 255.0 / 224.0
    val yOffset = if (fullRange) 0 else 16
    val rv = (2.0 * (1.0 - kr) * cScale * FIXED).roundToInt()
    val bu = (2.0 * (1.0 - kb) * cScale * FIXED).roundToInt()
    val gu = (2.0 * (1.0 - kb) * kb / kg * cScale * FIXED).roundToInt()
    val gv = (2.0 * (1.0 - kr) * kr / kg * cScale * FIXED).roundToInt()
    val ys = (yScale * FIXED).roundToInt()

    val pixels = IntArray(outWidth * outHeight)
    for (oy in 0 until outHeight) {
        val sy = cropTop + oy * sample
        val chromaY = sy shr 1
        for (ox in 0 until outWidth) {
            val sx = cropLeft + ox * sample
            val chromaX = sx shr 1
            val y = (yPlane.get(sy * yRow + sx * yPixel).toInt() and 0xFF) - yOffset
            val u = (uPlane.get(chromaY * uRow + chromaX * uPixel).toInt() and 0xFF) - 128
            val v = (vPlane.get(chromaY * vRow + chromaX * vPixel).toInt() and 0xFF) - 128
            val yy = y * ys
            val r = (yy + rv * v + HALF) shr FIXED_BITS
            val g = (yy - gu * u - gv * v + HALF) shr FIXED_BITS
            val b = (yy + bu * u + HALF) shr FIXED_BITS
            pixels[oy * outWidth + ox] = (0xFF shl 24) or
                (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }
    return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
}

private const val FIXED_BITS = 16
private const val FIXED = 1 shl FIXED_BITS
private const val HALF = 1 shl (FIXED_BITS - 1)
