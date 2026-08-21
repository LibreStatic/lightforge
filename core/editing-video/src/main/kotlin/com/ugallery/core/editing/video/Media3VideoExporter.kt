@file:Suppress("UnsafeOptInUsageError")

package com.ugallery.core.editing.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.TransformationRequest
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
    val customLut: CubeLut? = null,
)

/** Media3 Transformer wrapper with trim, speed and PCM volume processing. */
class Media3VideoExporter(private val context: Context) {
    suspend fun export(request: VideoExportRequest): VideoExportResult {
        val clipEndMillis = request.recipe.endMillis ?: withContext(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context.applicationContext, request.input)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            }
        } ?: throw IllegalArgumentException("Video duration is unavailable")
        return withContext(Dispatchers.Main.immediate) {
            exportOnMain(request, clipEndMillis)
        }
    }

    private suspend fun exportOnMain(
        request: VideoExportRequest,
        clipEndMillis: Long,
    ): VideoExportResult {
        request.output.parentFile?.mkdirs()
        request.output.delete()
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(request.recipe.startMillis)
            .setEndPositionMs(clipEndMillis)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(request.input)
            .setClippingConfiguration(clipping)
            .build()
        val editedBuilder = EditedMediaItem.Builder(mediaItem)
        if (request.recipe.speed != 1f) {
            editedBuilder.setSpeed(
                SpeedParameters(
                    ConstantSpeedProvider(request.recipe.speed),
                    /* shouldMaintainPitch = */ true,
                ),
            )
        }
        val edited = editedBuilder.setEffects(
                androidx.media3.transformer.Effects(
                    listOf(VolumeAudioProcessor(request.recipe.originalAudioVolume)),
                    VideoColorGradeEffects.create(request.recipe.colorGrade, request.customLut),
                ),
            )
            .build()
        val clipDurationMillis = (clipEndMillis - request.recipe.startMillis).coerceAtLeast(0L)
        val outputDurationMillis = (clipDurationMillis.toDouble() / request.recipe.speed.toDouble())
            .toLong().coerceAtLeast(0L)
        val composition = request.recipe.musicUri?.let { musicUri ->
            val musicItem = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(musicUri).build(),
            )
                .setRemoveVideo(true)
                .setDurationUs(outputDurationMillis * 1_000L)
                .setEffects(
                    androidx.media3.transformer.Effects(
                        listOf(VolumeAudioProcessor(request.recipe.musicVolume)),
                        emptyList(),
                    ),
                )
                .build()
            Composition.Builder(
                listOf(
                    EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                        .addItem(edited)
                        .build(),
                    EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                        .addItem(musicItem)
                        .setIsLooping(true)
                        .build(),
                ),
            )
                .build()
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var transformer: Transformer
            var fallbackWarning: String? = null
            val transformerBuilder = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(
                    if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10) {
                        MimeTypes.VIDEO_H265
                    } else request.videoMimeType,
                )
                .setAudioMimeType(request.audioMimeType)
            if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10) {
                transformerBuilder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context.applicationContext)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                    MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                                )
                                .build(),
                        )
                        .setEnableFallback(true)
                        .build(),
                )
            }
            transformer = transformerBuilder
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(
                            VideoExportResult(
                                request.output,
                                durationMillis = outputDurationMillis,
                                videoMimeType = if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10) {
                                    MimeTypes.VIDEO_H265
                                } else request.videoMimeType,
                                audioMimeType = request.audioMimeType,
                                fallbackWarning = fallbackWarning,
                            ),
                        )
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest,
                    ) {
                        fallbackWarning = "Encoder fallback: $fallbackTransformationRequest"
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
                if (composition != null) {
                    transformer.start(composition, request.output.absolutePath)
                } else {
                    transformer.start(edited, request.output.absolutePath)
                }
            } catch (failure: Throwable) {
                request.output.delete()
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }
}

private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_END_OF_SOURCE
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
