package com.ugallery.core.thumbnail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.annotation.WorkerThread
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

class NativeImageDecoder(private val resolver: ContentResolver) {
    @WorkerThread
    fun thumbnail(uri: Uri, size: Size, signal: CancellationSignal): Bitmap =
        resolver.loadThumbnail(uri, size, signal)

    /** Sample before decode, then orient/fit the small preview; never materialize the full bitmap. */
    @WorkerThread
    fun screenPreview(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0) { "Preview bounds must be positive" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openFileDescriptor(uri, "r")?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Image bounds unavailable")
        val requestedDivisor = max(1, max(bounds.outWidth / targetWidth, bounds.outHeight / targetHeight))
        var sampleSize = 1
        while (sampleSize * 2 <= requestedDivisor) sampleSize *= 2
        val decoded = resolver.openFileDescriptor(uri, "r")?.use {
            BitmapFactory.decodeFileDescriptor(
                it.fileDescriptor,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: throw IOException("Image decode failed")
        val oriented = applyExifOrientation(uri, decoded)
        val scale = minOf(
            1.0,
            targetWidth.toDouble() / oriented.width,
            targetHeight.toDouble() / oriented.height,
        )
        if (scale >= 1.0) return oriented
        return Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== oriented) oriented.recycle() }
    }

    /** Animated GIF/WebP stay animated; static formats use the bounded preview path. */
    @WorkerThread
    fun screenDrawable(uri: Uri, targetWidth: Int, targetHeight: Int): Drawable {
        require(targetWidth > 0 && targetHeight > 0) { "Preview bounds must be positive" }
        val mimeType = resolver.getType(uri).orEmpty().lowercase()
        if (mimeType != "image/gif" && mimeType != "image/webp") {
            return BitmapDrawable(null, screenPreview(uri, targetWidth, targetHeight))
        }
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val divisor = max(
                1,
                ceil(
                    max(
                        info.size.width.toDouble() / targetWidth,
                        info.size.height.toDouble() / targetHeight,
                    ),
                ).toInt(),
            )
            decoder.setTargetSampleSize(divisor)
            decoder.setOnPartialImageListener { false }
        }
    }

    @WorkerThread
    fun openTileSource(uri: Uri): LargeImageTileSource? = LargeImageTileSource.open(resolver, uri)

    @WorkerThread
    fun deepZoomAvailability(uri: Uri): DeepZoomAvailability = LargeImageTileSource.inspect(resolver, uri)

    /** Compatibility entry point; callers that need a session should use [openTileSource]. */
    @WorkerThread
    fun tile(uri: Uri, region: Rect, sampleSize: Int): Bitmap? =
        openTileSource(uri)?.use { it.decode(region, sampleSize) }

    private fun applyExifOrientation(uri: Uri, source: Bitmap): Bitmap {
        val orientation = try {
            resolver.openFileDescriptor(uri, "r")?.use {
                ExifInterface(it.fileDescriptor).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setScale(-1f, 1f); postRotate(270f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setScale(-1f, 1f); postRotate(90f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .also { if (it !== source) source.recycle() }
    }
}

/** Reuses one seekable descriptor/region decoder and closes both deterministically. */
class LargeImageTileSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val decoder: BitmapRegionDecoder,
    val width: Int,
    val height: Int,
) : AutoCloseable {
    @WorkerThread
    fun decode(region: Rect, sampleSize: Int): Bitmap? = synchronized(decoder) {
        if (closed || !Rect(0, 0, width, height).contains(region)) return null
        return try {
            decoder.decodeRegion(
                region,
                BitmapFactory.Options().apply {
                    inSampleSize = boundedTileSample(region, sampleSize)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun close() = synchronized(decoder) {
        if (closed) return
        closed = true
        @Suppress("DEPRECATION")
        decoder.recycle()
        descriptor.close()
    }

    private var closed = false

    companion object {
        @WorkerThread
        internal fun open(resolver: ContentResolver, uri: Uri): LargeImageTileSource? {
            val availability = inspect(resolver, uri) as? DeepZoomAvailability.Available ?: return null
            val descriptor = try {
                resolver.openFileDescriptor(uri, "r") ?: return null
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
            return try {
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                LargeImageTileSource(descriptor, decoder, availability.width, availability.height)
            } catch (_: IOException) {
                descriptor.close()
                null
            } catch (_: IllegalArgumentException) {
                descriptor.close()
                null
            }
        }

        internal fun inspect(resolver: ContentResolver, uri: Uri): DeepZoomAvailability = try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
                when {
                    options.outWidth <= 0 || options.outHeight <= 0 ->
                        DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.UnsupportedOrCorrupt)
                    isKnownFullFrameRegionDecoder(options.outWidth, options.outHeight) ->
                        DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.DeviceDecoderMemoryRisk)
                    else -> DeepZoomAvailability.Available(options.outWidth, options.outHeight)
                }
            } ?: DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.ProviderNotSeekable)
        } catch (_: IOException) {
            DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.ProviderNotSeekable)
        } catch (_: SecurityException) {
            DeepZoomAvailability.Unavailable(DeepZoomUnavailableReason.PermissionLost)
        }

        /** Physical API36 evidence shows this family expands 200MP JPEGs to a full-frame scratch buffer. */
        private fun isKnownFullFrameRegionDecoder(width: Int, height: Int): Boolean =
            width.toLong() * height >= 150_000_000L &&
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                Build.MODEL.startsWith("SM-X91") &&
                Build.VERSION.SDK_INT >= 36
    }
}

sealed interface DeepZoomAvailability {
    data class Available(val width: Int, val height: Int) : DeepZoomAvailability
    data class Unavailable(val reason: DeepZoomUnavailableReason) : DeepZoomAvailability
}

enum class DeepZoomUnavailableReason {
    AnimatedFormat,
    DeviceDecoderMemoryRisk,
    ProviderNotSeekable,
    PermissionLost,
    UnsupportedOrCorrupt,
}

private const val MaxDecodedTileEdge = 1_024

private fun boundedTileSample(region: Rect, requestedSample: Int): Int {
    val minimum = max(
        requestedSample.coerceAtLeast(1),
        ceil(max(region.width(), region.height()).toDouble() / MaxDecodedTileEdge).toInt(),
    )
    var powerOfTwo = 1
    while (powerOfTwo < minimum) powerOfTwo = powerOfTwo shl 1
    return powerOfTwo
}

fun Drawable.setViewerAnimationRunning(running: Boolean) {
    if (this is AnimatedImageDrawable) {
        if (running) start() else stop()
    }
}
