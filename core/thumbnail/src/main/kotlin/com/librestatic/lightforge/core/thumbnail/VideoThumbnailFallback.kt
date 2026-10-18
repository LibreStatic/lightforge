package com.librestatic.lightforge.core.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.annotation.OptIn
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.inspector.MediaExtractorCompat
import com.librestatic.lightforge.core.legacyvideo.LegacyExtractorsFactory
import com.librestatic.lightforge.core.legacyvideo.LegacyVideoMimeTypes
import com.librestatic.lightforge.core.legacyvideo.ffmpeg.FfmpegVideoDecoder
import java.nio.ByteBuffer
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

    @OptIn(UnstableApi::class)
    @WorkerThread
    fun decodeThumbnail(uri: Uri, size: Size, signal: CancellationSignal): Bitmap {
        signal.throwIfCanceled()
        decodeLegacyThumbnail(uri, size, signal)?.let { return it }
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

    /**
     * Sorenson Spark / VP6 have no MediaCodec decoder and FrameExtractor cannot take custom
     * renderers, so decode the first picture with the bundled software decoder. Returns null when
     * the file is not one of those codecs or yields no frame, leaving it to FrameExtractor.
     */
    @OptIn(UnstableApi::class)
    private fun decodeLegacyThumbnail(uri: Uri, size: Size, signal: CancellationSignal): Bitmap? {
        if (!FfmpegVideoDecoder.isAvailable) return null
        val extractor = MediaExtractorCompat(LegacyExtractorsFactory.create(), FileDataSource.Factory())
        try {
            val bitmap = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                extractor.setDataSource(descriptor.fileDescriptor)
                val track = (0 until extractor.trackCount).firstOrNull {
                    LegacyVideoMimeTypes.isLegacy(extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME))
                } ?: return null
                val format = extractor.getTrackFormat(track)
                val codec = when (format.getString(MediaFormat.KEY_MIME)) {
                    LegacyVideoMimeTypes.VIDEO_FLV1 -> FfmpegVideoDecoder.Codec.FLV1
                    LegacyVideoMimeTypes.VIDEO_VP6F -> FfmpegVideoDecoder.Codec.VP6F
                    else -> FfmpegVideoDecoder.Codec.VP6A
                }
                val extradata = format.getByteBuffer("csd-0")?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                }
                extractor.selectTrack(track)
                decodeFirstFrame(extractor, codec, extradata, signal)
            }
            return bitmap?.let { fit(it, size) }
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            if (e is OperationCanceledException) throw e
            return null
        } finally {
            extractor.release()
        }
    }

    @OptIn(UnstableApi::class)
    private fun decodeFirstFrame(
        extractor: MediaExtractorCompat,
        codec: FfmpegVideoDecoder.Codec,
        extradata: ByteArray?,
        signal: CancellationSignal,
    ): Bitmap? {
        val decoder = FfmpegVideoDecoder(codec, extradata)
        try {
            val buffer = ByteBuffer.allocateDirect(MAX_PACKET_BYTES)
            for (i in 0 until MAX_PACKETS) {
                signal.throwIfCanceled()
                buffer.clear()
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) return null
                val key = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                buffer.limit(read)
                buffer.position(0)
                if (decoder.sendPacket(buffer, read, extractor.sampleTime, key) == FfmpegVideoDecoder.STATUS_ERROR) return null
                if (decoder.receiveFrame() == FfmpegVideoDecoder.STATUS_OK) {
                    val bitmap = Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
                    return if (decoder.copyToBitmap(bitmap)) bitmap else null
                }
                if (!extractor.advance()) return null
            }
            return null
        } finally {
            decoder.close()
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
        const val MAX_PACKETS = 60
        const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    }
}
