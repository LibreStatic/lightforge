package com.librestatic.lightforge.core.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import androidx.media3.inspector.frame.FrameExtractor
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * MediaProvider extracts video frames with the platform retriever, which has no FLV parser (and
 * misses other containers ExoPlayer handles). Media3's extractor stack plays them, so decode the
 * thumbnail through it instead.
 */
internal class VideoThumbnailFallback(context: Context) {
    private val context = context.applicationContext

    fun isVideo(uri: Uri): Boolean = try {
        context.contentResolver.getType(uri).orEmpty().lowercase().startsWith("video/")
    } catch (_: RuntimeException) {
        false
    }

    @WorkerThread
    fun decodeThumbnail(uri: Uri, size: Size, signal: CancellationSignal): Bitmap {
        signal.throwIfCanceled()
        FrameExtractor.Builder(context, MediaItem.fromUri(uri)).build().use { extractor ->
            val future = extractor.getThumbnail()
            signal.setOnCancelListener { future.cancel(true) }
            val frame = try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: CancellationException) {
                throw OperationCanceledException()
            } catch (e: ExecutionException) {
                throw IOException("Video frame extraction failed", e.cause ?: e)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw IOException("Video frame extraction timed out", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OperationCanceledException()
            } finally {
                signal.setOnCancelListener(null)
            }
            return fit(frame.bitmap, size)
        }
    }

    /** Grid tiles center-crop, so cover the requested size like MediaProvider thumbnails do. */
    private fun fit(source: Bitmap, size: Size): Bitmap {
        val scale = minOf(1.0, maxOf(size.width.toDouble() / source.width, size.height.toDouble() / source.height))
        val bitmap = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        if (scale >= 1.0) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
