package com.ugallery.feature.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.CancellationSignal
import com.ugallery.core.thumbnail.NativeImageDecoder
import java.io.File

/**
 * Local-only slideshow exporter.
 * Renders selected images as an MP4 (H.264) video with configurable duration per image.
 * No cloud, no network — all encoding is on-device.
 * Uses MediaCodec for hardware-accelerated H.264 encoding.
 */
class SlideshowExporter(
    private val context: Context,
) {

    data class SlideshowConfig(
        val imageUris: List<Uri>,
        val durationPerImageMs: Long = 3000L,
        val outputWidth: Int = 1920,
        val outputHeight: Int = 1080,
        val frameRate: Int = 30,
        val bitRate: Int = 8_000_000,
    )

    data class SlideshowResult(
        val outputFile: File,
        val frameCount: Int,
        val durationMs: Long,
    )

    /**
     * Exports a slideshow as MP4. Memory is bounded: each image is loaded as a
     * sampled bitmap at output resolution, never at full source resolution.
     */
    fun export(
        config: SlideshowConfig,
        outputDir: File,
        cancellationSignal: CancellationSignal? = null,
    ): SlideshowResult {
        require(config.imageUris.isNotEmpty()) { "At least one image required" }
        outputDir.mkdirs()
        val outputFile = File(outputDir, "slideshow_\${System.currentTimeMillis()}.mp4")

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.outputWidth,
            config.outputHeight,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var frameCount = 0
        val framesPerImage = (config.durationPerImageMs * config.frameRate / 1000).toInt()

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val decoder = NativeImageDecoder(context.contentResolver)

        for (uri in config.imageUris) {
            if (cancellationSignal?.isCanceled == true) break

            val bitmap = try {
                decoder.screenPreview(uri, config.outputWidth, config.outputHeight)
            } catch (e: Exception) {
                continue
            }

            for (frame in 0 until framesPerImage) {
                if (cancellationSignal?.isCanceled == true) break

                val canvas = inputSurface.lockCanvas(null)
                drawFit(canvas, bitmap, config.outputWidth, config.outputHeight, paint)
                inputSurface.unlockCanvasAndPost(canvas)
                frameCount++
            }

            bitmap.recycle()

            // Drain encoder after each image
            drainEncoder(encoder, bufferInfo, muxer) { idx, started ->
                trackIndex = idx; muxerStarted = started
            }
        }

        encoder.signalEndOfInputStream()
        drainEncoder(encoder, bufferInfo, muxer) { idx, started ->
            trackIndex = idx; muxerStarted = started
        }

        encoder.stop()
        encoder.release()
        if (muxerStarted) muxer.stop()
        muxer.release()

        return SlideshowResult(outputFile, frameCount, config.imageUris.size * config.durationPerImageMs)
    }

    private fun drawFit(
        canvas: Canvas,
        bitmap: Bitmap,
        outW: Int,
        outH: Int,
        paint: Paint,
    ) {
        canvas.drawColor(Color.BLACK)

        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = outW.toFloat() / outH.toFloat()

        val dstRect = if (srcAspect > dstAspect) {
            val scaledH = (outW / srcAspect).toInt()
            val offsetY = (outH - scaledH) / 2
            Rect(0, offsetY, outW, offsetY + scaledH)
        } else {
            val scaledW = (outH * srcAspect).toInt()
            val offsetX = (outW - scaledW) / 2
            Rect(offsetX, 0, offsetX + scaledW, outH)
        }

        canvas.drawBitmap(bitmap, null, dstRect, paint)
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        setTrack: (Int, Boolean) -> Unit,
    ) {
        var trackIndex = -1
        var started = false
        while (true) {
            val outBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    started = true
                    setTrack(trackIndex, true)
                }
                outBufferIndex >= 0 -> {
                    val encodedBuffer = encoder.getOutputBuffer(outBufferIndex) ?: continue
                    if (bufferInfo.size > 0 && started) {
                        encodedBuffer.position(bufferInfo.offset)
                        encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

