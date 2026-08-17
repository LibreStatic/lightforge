package com.ugallery.core.thumbnail

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import androidx.annotation.WorkerThread
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

class NativeImageDecoder(private val resolver: ContentResolver) {
    @WorkerThread
    fun thumbnail(uri: Uri, size: Size, signal: CancellationSignal): Bitmap =
        resolver.loadThumbnail(uri, size, signal)

    @WorkerThread
    fun screenPreview(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        require(targetWidth > 0 && targetHeight > 0) { "Preview bounds must be positive" }
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Returns null for formats/providers that cannot expose a seekable region decoder. */
    @WorkerThread
    fun tile(uri: Uri, region: Rect, sampleSize: Int): Bitmap? {
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                try {
                    if (!Rect(0, 0, decoder.width, decoder.height).contains(region)) return null
                    decoder.decodeRegion(
                        region,
                        BitmapFactory.Options().apply {
                            inSampleSize = sampleSize.coerceAtLeast(1)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                    )
                } finally {
                    @Suppress("DEPRECATION")
                    decoder.recycle()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
