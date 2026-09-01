@file:Suppress("UnsafeOptInUsageError")

package com.ugallery.core.editing.video

import android.content.Context
import android.media.metrics.LogSessionId
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Clock
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ProgressHolder
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

enum class VideoExportPhase { Preparing, GeneratingFrames, Rendering, Publishing, Verifying, Completed }

data class VideoExportProgress(
    val phase: VideoExportPhase,
    val fraction: Float?,
) {
    init { require(fraction == null || fraction in 0f..1f) }
}

data class VideoExportRequest(
    val input: Uri,
    val output: File,
    val recipe: VideoEditRecipe,
    val videoMimeType: String = MimeTypes.VIDEO_H264,
    val audioMimeType: String = MimeTypes.AUDIO_AAC,
    val customLut: CubeLut? = null,
    val onProgress: (VideoExportProgress) -> Unit = {},
)

/** Media3 Transformer wrapper with trim, speed and PCM volume processing. */
class Media3VideoExporter(private val context: Context) {
    suspend fun export(request: VideoExportRequest): VideoExportResult {
        if (request.recipe.dynamicRange != VideoDynamicRange.SdrRec709) {
            val capabilities = VideoOutputCapabilities.hdr(context.applicationContext)
            val supported = when (request.recipe.dynamicRange) {
                VideoDynamicRange.SdrRec709 -> true
                VideoDynamicRange.HdrHlg -> capabilities.hlg
                VideoDynamicRange.Hdr10Pq -> capabilities.hdr10
            }
            if (!supported) throw HdrVideoExportUnsupportedException(request.recipe.dynamicRange)
        }
        request.onProgress(VideoExportProgress(VideoExportPhase.Preparing, 0f))
        val clipEndMillis = request.recipe.endMillis ?: withContext(Dispatchers.IO) {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context.applicationContext, request.input)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            }
        } ?: throw IllegalArgumentException("Video duration is unavailable")
        if (request.recipe.slowMotionSegments.isEmpty()) {
            return withContext(Dispatchers.Main.immediate) {
                exportOnMain(request, clipEndMillis, emptyList())
            }.also { request.onProgress(VideoExportProgress(VideoExportPhase.Completed, 1f)) }
        }
        val frameRoot = File(context.cacheDir, "rife-export-${System.nanoTime()}")
        return try {
            val segments = request.recipe.slowMotionSegments.mapIndexed { index, segment ->
                SlowMotionFrameGenerator(context.applicationContext).generate(
                    input = request.input,
                    segment = segment,
                    destination = File(frameRoot, segment.id),
                    onProgress = { segmentProgress ->
                        request.onProgress(
                            VideoExportProgress(
                                VideoExportPhase.GeneratingFrames,
                                (index + segmentProgress) / request.recipe.slowMotionSegments.size,
                            ),
                        )
                    },
                )
            }
            withContext(Dispatchers.Main.immediate) {
                exportOnMain(request, clipEndMillis, segments)
            }.also { request.onProgress(VideoExportProgress(VideoExportPhase.Completed, 1f)) }
        } finally {
            withContext(Dispatchers.IO) { frameRoot.deleteRecursively() }
        }
    }

    private suspend fun exportOnMain(
        request: VideoExportRequest,
        clipEndMillis: Long,
        generatedSlowSegments: List<GeneratedSlowSegment>,
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
        val videoEffects = buildList {
            addAll(VideoColorGradeEffects.geometryEffects(request.recipe.geometry))
            if (request.recipe.dynamicRange == VideoDynamicRange.SdrRec709) {
                addAll(VideoColorGradeEffects.create(request.recipe.colorGrade, request.customLut))
            } else {
                add(HdrVideoColorGradeEffect(request.recipe.colorGrade, request.customLut))
            }
            if (request.recipe.annotations.isNotEmpty()) {
                add(VideoAnnotationEffect(request.recipe.annotations))
            }
        }
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
                    videoEffects,
                ),
            )
            .build()
        val clipDurationMillis = (clipEndMillis - request.recipe.startMillis).coerceAtLeast(0L)
        val outputDurationMillis = outputDurationMillis(request.recipe, clipEndMillis)
        val composition = if (generatedSlowSegments.isNotEmpty()) {
            buildSlowMotionComposition(
                request,
                clipEndMillis,
                generatedSlowSegments,
                outputDurationMillis,
                videoEffects,
            )
        } else request.recipe.musicUri?.let { musicUri ->
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
            var decoderName: String? = null
            val mainHandler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            var progressPolling = true
            val progressPoll = object : Runnable {
                override fun run() {
                    if (!progressPolling) return
                    val fraction = when (transformer.getProgress(progressHolder)) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> progressHolder.progress / 100f
                        else -> null
                    }
                    request.onProgress(VideoExportProgress(VideoExportPhase.Rendering, fraction))
                    mainHandler.postDelayed(this, ProgressPollMillis)
                }
            }
            val transformerBuilder = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(
                    if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10 ||
                        request.recipe.dynamicRange != VideoDynamicRange.SdrRec709
                    ) {
                        MimeTypes.VIDEO_H265
                    } else request.videoMimeType,
                )
                .setAudioMimeType(request.audioMimeType)
            val encoderBuilder = DefaultEncoderFactory.Builder(context.applicationContext)
                .setVideoEncoderSelector(HardwareCodecSelectors.encoder)
                .setEnableFallback(true)
            if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10 &&
                request.recipe.dynamicRange == VideoDynamicRange.SdrRec709
            ) {
                encoderBuilder.setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setEncodingProfileLevel(
                            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                            MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                        )
                        .build(),
                )
            }
            val encoderFactory = encoderBuilder.build()
            if (request.recipe.dynamicRange == VideoDynamicRange.SdrRec709) {
                transformerBuilder.setEncoderFactory(encoderFactory)
            } else {
                val hdrColorInfo = request.recipe.dynamicRange.toColorInfo()
                transformerBuilder
                    .setVideoFrameProcessorFactory(ForcedColorInfoVideoFrameProcessorFactory(hdrColorInfo))
                    .setEncoderFactory(ForcedColorInfoEncoderFactory(encoderFactory, hdrColorInfo))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val decoderFactory = DefaultDecoderFactory.Builder(context.applicationContext)
                    .setMediaCodecSelector(HardwareCodecSelectors.decoder)
                    .setEnableDecoderFallback(true)
                    .setListener { codecName, _ -> decoderName = codecName }
                    .build()
                transformerBuilder.setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context.applicationContext,
                        decoderFactory,
                        Clock.DEFAULT,
                        LogSessionId.LOG_SESSION_ID_NONE,
                    ),
                )
            }
            transformer = transformerBuilder
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        progressPolling = false
                        mainHandler.removeCallbacks(progressPoll)
                        if (continuation.isActive) continuation.resume(
                            VideoExportResult(
                                request.output,
                                durationMillis = outputDurationMillis,
                                videoMimeType = if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10 ||
                                    request.recipe.dynamicRange != VideoDynamicRange.SdrRec709
                                ) {
                                    MimeTypes.VIDEO_H265
                                } else request.videoMimeType,
                                audioMimeType = request.audioMimeType,
                                fallbackWarning = fallbackWarning,
                                videoEncoderName = exportResult.videoEncoderName,
                                videoDecoderName = decoderName,
                                usedSoftwareCodec = HardwareCodecSelectors.isSoftwareCodec(exportResult.videoEncoderName) ||
                                    HardwareCodecSelectors.isSoftwareCodec(decoderName),
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
                        progressPolling = false
                        mainHandler.removeCallbacks(progressPoll)
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                progressPolling = false
                mainHandler.post {
                    mainHandler.removeCallbacks(progressPoll)
                    transformer.cancel()
                    request.output.delete()
                }
            }
            try {
                val exportComposition = composition ?: if (
                    request.recipe.dynamicRange != VideoDynamicRange.SdrRec709
                ) {
                    Composition.Builder(
                        listOf(
                            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                                .addItem(edited)
                                .build(),
                        ),
                    ).setHdrMode(Composition.HDR_MODE_KEEP_HDR).build()
                } else null
                if (exportComposition != null) {
                    transformer.start(exportComposition, request.output.absolutePath)
                } else {
                    transformer.start(edited, request.output.absolutePath)
                }
                progressPoll.run()
            } catch (failure: Throwable) {
                progressPolling = false
                mainHandler.removeCallbacks(progressPoll)
                request.output.delete()
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private companion object { const val ProgressPollMillis = 250L }

    private fun buildSlowMotionComposition(
        request: VideoExportRequest,
        clipEndMillis: Long,
        generated: List<GeneratedSlowSegment>,
        outputDurationMillis: Long,
        videoEffects: List<androidx.media3.common.Effect>,
    ): Composition {
        val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        val audio = if (sourceHasAudio(request.input)) {
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        } else null
        var cursor = request.recipe.startMillis
        generated.sortedBy { it.segment.startMillis }.forEach { generatedSegment ->
            val segment = generatedSegment.segment
            if (segment.startMillis > cursor) {
                video.addItem(sourceVideoItem(request, cursor, segment.startMillis, request.recipe.speed, videoEffects))
                audio?.addItem(sourceAudioItem(request, cursor, segment.startMillis, request.recipe.speed, true))
            }
            generatedSegment.frames.forEach { frame ->
                val image = MediaItem.Builder()
                    .setUri(Uri.fromFile(frame))
                    .setMimeType("image/jpeg")
                    .setImageDurationMs(generatedSegment.frameDurationMillis)
                    .build()
                video.addItem(
                    EditedMediaItem.Builder(image)
                        .setFrameRate(generatedSegment.frameRate)
                        .setRemoveAudio(true)
                        .setEffects(androidx.media3.transformer.Effects(emptyList(), videoEffects))
                        .build(),
                )
            }
            when (segment.audioMode) {
                SlowMotionAudioMode.Muted -> audio?.addGap(
                    ((segment.endMillis - segment.startMillis) / segment.speed * 1_000L).toLong(),
                )
                SlowMotionAudioMode.PreservePitch -> audio?.addItem(
                    sourceAudioItem(request, segment.startMillis, segment.endMillis, segment.speed, true),
                )
                SlowMotionAudioMode.Varispeed -> audio?.addItem(
                    sourceAudioItem(request, segment.startMillis, segment.endMillis, segment.speed, false),
                )
            }
            cursor = segment.endMillis
        }
        if (cursor < clipEndMillis) {
            video.addItem(sourceVideoItem(request, cursor, clipEndMillis, request.recipe.speed, videoEffects))
            audio?.addItem(sourceAudioItem(request, cursor, clipEndMillis, request.recipe.speed, true))
        }
        val sequences = mutableListOf(video.build())
        audio?.let { sequences += it.build() }
        request.recipe.musicUri?.let { musicUri ->
            val musicItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(musicUri).build())
                .setRemoveVideo(true)
                .setDurationUs(outputDurationMillis * 1_000L)
                .setEffects(
                    androidx.media3.transformer.Effects(
                        listOf(VolumeAudioProcessor(request.recipe.musicVolume)),
                        emptyList(),
                    ),
                )
                .build()
            sequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(musicItem)
                .setIsLooping(true)
                .build()
        }
        return Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
            .build()
    }

    private fun sourceVideoItem(
        request: VideoExportRequest,
        startMillis: Long,
        endMillis: Long,
        speed: Float,
        videoEffects: List<androidx.media3.common.Effect>,
    ): EditedMediaItem {
        val item = clippedMediaItem(request.input, startMillis, endMillis)
        return EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setSpeed(SpeedParameters(ConstantSpeedProvider(speed), true))
            .setEffects(androidx.media3.transformer.Effects(emptyList(), videoEffects))
            .build()
    }

    private fun sourceAudioItem(
        request: VideoExportRequest,
        startMillis: Long,
        endMillis: Long,
        speed: Float,
        preservePitch: Boolean,
    ): EditedMediaItem = EditedMediaItem.Builder(clippedMediaItem(request.input, startMillis, endMillis))
        .setRemoveVideo(true)
        .setSpeed(SpeedParameters(ConstantSpeedProvider(speed), preservePitch))
        .setEffects(
            androidx.media3.transformer.Effects(
                listOf(VolumeAudioProcessor(request.recipe.originalAudioVolume)),
                emptyList(),
            ),
        )
        .build()

    private fun clippedMediaItem(uri: Uri, startMillis: Long, endMillis: Long): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMillis)
                    .setEndPositionMs(endMillis)
                    .build(),
            )
            .build()

    private fun outputDurationMillis(recipe: VideoEditRecipe, clipEndMillis: Long): Long {
        var cursor = recipe.startMillis
        var duration = 0.0
        recipe.slowMotionSegments.forEach { segment ->
            duration += (segment.startMillis - cursor).coerceAtLeast(0) / recipe.speed.toDouble()
            duration += (segment.endMillis - segment.startMillis) / segment.speed.toDouble()
            cursor = segment.endMillis
        }
        duration += (clipEndMillis - cursor).coerceAtLeast(0) / recipe.speed.toDouble()
        return duration.toLong().coerceAtLeast(0)
    }

    private fun sourceHasAudio(uri: Uri): Boolean = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context.applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        }
    }.getOrDefault(false)

}

class HdrVideoExportUnsupportedException(
    val dynamicRange: VideoDynamicRange,
) : IllegalStateException("${dynamicRange.name} hardware export is not supported by this device")

private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
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
