@file:Suppress("UnsafeOptInUsageError")

package com.ugallery.core.editing.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoExportRequest(
    val input: Uri,
    val output: File,
    val recipe: VideoEditRecipe,
    val videoMimeType: String = MimeTypes.VIDEO_H264,
    val audioMimeType: String = MimeTypes.AUDIO_AAC,
)

/** Media3 Transformer wrapper with trim, speed and PCM volume processing. */
class Media3VideoExporter(private val context: Context) {
    suspend fun export(request: VideoExportRequest): VideoExportResult = withContext(Dispatchers.Main.immediate) {
        request.output.parentFile?.mkdirs()
        request.output.delete()
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(request.recipe.startMillis)
            .apply { request.recipe.endMillis?.let(::setEndPositionMs) }
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(request.input)
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setSpeed(
                SpeedParameters(
                    ConstantSpeedProvider(request.recipe.speed),
                    /* shouldMaintainPitch = */ true,
                ),
            )
            .setEffects(
                androidx.media3.transformer.Effects(
                    listOf(VolumeAudioProcessor(request.recipe.originalAudioVolume)),
                    emptyList(),
                ),
            )
            .build()
        val fallbackWarning = if (request.recipe.musicUri != null) {
            "Music track is kept local and previewable; Media3 export currently preserves original audio only"
        } else null
        suspendCancellableCoroutine { continuation ->
            lateinit var transformer: Transformer
            transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(request.videoMimeType)
                .setAudioMimeType(request.audioMimeType)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(
                            VideoExportResult(
                                request.output,
                                durationMillis = request.recipe.endMillis?.minus(request.recipe.startMillis)
                                    ?.let { (it.toDouble() / request.recipe.speed.toDouble()).toLong() }
                                    ?.coerceAtLeast(0L) ?: 0L,
                                videoMimeType = request.videoMimeType,
                                audioMimeType = request.audioMimeType,
                                fallbackWarning = fallbackWarning,
                            ),
                        )
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                transformer.cancel()
                request.output.delete()
            }
            try {
                transformer.start(edited, request.output.absolutePath)
            } catch (failure: Throwable) {
                request.output.delete()
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }
}

private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = Long.MAX_VALUE
}

private class VolumeAudioProcessor(private val volume: Float) : AudioProcessor {
    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var output = ByteBuffer.allocate(0)
    private var ended = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        require(inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            "Only PCM16 audio volume adjustment is supported"
        }
        format = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = volume != 1f

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (volume == 1f) {
            output = inputBuffer.slice()
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val bytes = inputBuffer.remaining()
        output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val source = inputBuffer.order(ByteOrder.nativeOrder())
        while (source.remaining() >= 2) {
            val sample = (source.short.toInt() * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(sample.toShort())
        }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer {
        val result = output
        output = ByteBuffer.allocate(0)
        return result
    }
    override fun isEnded(): Boolean = ended && !output.hasRemaining()
    override fun flush() { output = ByteBuffer.allocate(0); ended = false }
    override fun reset() { format = AudioProcessor.AudioFormat.NOT_SET; flush() }
}
