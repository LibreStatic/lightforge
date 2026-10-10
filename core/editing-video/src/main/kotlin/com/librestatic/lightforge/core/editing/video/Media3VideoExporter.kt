@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.media.metrics.LogSessionId
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Clock
import androidx.media3.container.Mp4TimestampData
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
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.TransformationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The stretches of an export that is not a plain Transformer speed change, and the videos rendered for them. */
internal class SlowMotionExport(val ranges: List<PlannedRange>, val generated: List<GeneratedRange>)

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

internal fun freshExportMetadataProvider(
    nowMillis: () -> Long = System::currentTimeMillis,
) = InAppMp4Muxer.MetadataProvider { entries ->
    val timestamp = Mp4TimestampData.unixTimeToMp4TimeSeconds(nowMillis())
    entries.removeAll { it is Mp4TimestampData }
    entries.add(Mp4TimestampData(timestamp, timestamp))
}

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
        val plan = outputPlan(request)
        val ranges = slowMotionRanges(request.recipe, clipEndMillis)
        val interpolated = ranges.filter { it.interpolationFactor != null }
        if (request.recipe.slowMotionSegments.isEmpty() && interpolated.isEmpty()) {
            return withContext(Dispatchers.Main.immediate) {
                exportOnMain(request, clipEndMillis, null, plan)
            }.also { request.onProgress(VideoExportProgress(VideoExportPhase.Completed, 1f)) }
        }
        val frameRoot = File(context.cacheDir, "rife-export-${System.nanoTime()}")
        return try {
            val generated = if (interpolated.isEmpty()) emptyList() else {
                request.onProgress(VideoExportProgress(VideoExportPhase.GeneratingFrames, 0f))
                val frameRate = VideoSourceInfoReader.read(context, request.input)?.frameRate
                    ?: DefaultSourceFrameRate
                SlowMotionFrameGenerator(context.applicationContext).generate(
                    input = request.input,
                    ranges = interpolated,
                    frameRate = frameRate,
                    destination = frameRoot,
                    onProgress = { fraction ->
                        request.onProgress(VideoExportProgress(VideoExportPhase.GeneratingFrames, fraction))
                    },
                )
            }
            withContext(Dispatchers.Main.immediate) {
                exportOnMain(request, clipEndMillis, SlowMotionExport(ranges, generated), plan)
            }.also { request.onProgress(VideoExportProgress(VideoExportPhase.Completed, 1f)) }
        } finally {
            // NonCancellable: a cancelled export must still drop its intermediate videos.
            withContext(NonCancellable + Dispatchers.IO) { frameRoot.deleteRecursively() }
        }
    }

    /**
     * Resolves the output settings for this device and source. Null when the source cannot be
     * probed; the export then keeps the pre-output-settings behaviour (H.264/HEVC by quality,
     * encoder-default bitrate, source size and frame rate, audio re-encoded).
     */
    private suspend fun outputPlan(request: VideoExportRequest): VideoOutputPlan? {
        val source = VideoSourceInfoReader.read(context, request.input) ?: return null
        val capabilities = withContext(Dispatchers.IO) { VideoOutputCapabilities.encoders() }
        val plan = VideoOutputPlan.resolve(source, request.recipe, capabilities)
        Log.d(
            Tag,
            "Output plan: ${plan.videoMimeType} ${plan.width}x${plan.height} ${plan.videoBitrate} bps " +
                "${plan.frameRate} fps cap=${plan.frameRateCap} audio=${plan.audio} remuxVideo=${plan.remuxVideo} " +
                "remuxAudio=${plan.remuxAudio} toneMap=${plan.toneMapToSdr} resizes=${plan.resizes} " +
                "adjustments=${plan.adjustments}",
        )
        return plan
    }

    private suspend fun exportOnMain(
        request: VideoExportRequest,
        clipEndMillis: Long,
        slowMotion: SlowMotionExport?,
        plan: VideoOutputPlan?,
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
        // Removed: the output has no audio track. Remove with added music drops only the source audio.
        val removeAudio = plan?.audio == VideoAudioPlan.Removed
        val removeSourceAudio = removeAudio || request.recipe.output.audio == VideoOutputAudio.Remove
        // A copied stream must see no processor at all, or Media3 decodes and re-encodes it.
        val audioProcessors = if (plan?.remuxAudio == true) {
            emptyList()
        } else listOf<AudioProcessor>(VolumeAudioProcessor(request.recipe.originalAudioVolume))
        val videoEffects = if (plan?.remuxVideo == true) emptyList() else buildList {
            // Dropping first spares the rest of the chain the frames that are discarded anyway.
            plan?.frameRateCap?.let { add(FrameDropEffect.createDefaultFrameDropEffect(it.toFloat())) }
            addAll(VideoColorGradeEffects.geometryEffects(request.recipe.geometry))
            if (request.recipe.dynamicRange == VideoDynamicRange.SdrRec709) {
                addAll(VideoColorGradeEffects.create(request.recipe.colorGrade, request.customLut))
            } else {
                add(HdrVideoColorGradeEffect(request.recipe.colorGrade, request.customLut))
            }
            if (request.recipe.annotations.isNotEmpty()) {
                add(VideoAnnotationEffect(request.recipe.annotations))
            }
            // Output size last: grading and annotations work on the edited frame, and Pad bars stay black.
            plan?.resizes?.forEach { add(it.toPresentation()) }
        }
        val editedBuilder = EditedMediaItem.Builder(mediaItem).setRemoveAudio(removeSourceAudio)
        if (request.recipe.speed != 1f) {
            editedBuilder.setSpeed(
                SpeedParameters(
                    ConstantSpeedProvider(request.recipe.speed),
                    /* shouldMaintainPitch = */ true,
                ),
            )
        }
        val edited = editedBuilder.setEffects(
                androidx.media3.transformer.Effects(audioProcessors, videoEffects),
            )
            .build()
        val clipDurationMillis = (clipEndMillis - request.recipe.startMillis).coerceAtLeast(0L)
        val outputDurationMillis = outputDurationMillis(request.recipe, clipEndMillis)
        val composition = if (slowMotion != null) {
            buildSlowMotionComposition(
                request,
                slowMotion,
                outputDurationMillis,
                videoEffects,
                removeSourceAudio,
            )
        } else request.recipe.musicUri?.takeUnless { removeAudio }?.let { musicUri ->
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
                    EditedMediaItemSequence.Builder(
                        if (removeSourceAudio) setOf(C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                    )
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
                .setMuxerFactory(InAppMp4Muxer.Factory(freshExportMetadataProvider()))
            // Leaving a MIME type unset lets Media3 copy that stream when nothing else changes it.
            if (plan?.remuxVideo != true) transformerBuilder.setVideoMimeType(requestedVideoMimeType(request, plan))
            if (plan?.remuxAudio != true) transformerBuilder.setAudioMimeType(request.audioMimeType)
            if (plan?.remuxVideo == true && isTrimmed(request.recipe, clipEndMillis)) {
                // Keeps a stream-copied trim frame accurate: the copy starts at the previous sync
                // sample and an MP4 edit list hides the frames before the trim point.
                transformerBuilder.experimentalSetMp4EditListTrimEnabled(true)
            }
            val encoderBuilder = DefaultEncoderFactory.Builder(context.applicationContext)
                .setVideoEncoderSelector(HardwareCodecSelectors.encoder)
                .setEnableFallback(true)
            val hevcMain10 = plan?.hevcMain10 ?: (
                request.recipe.outputQuality == VideoOutputQuality.HevcMain10 &&
                    request.recipe.dynamicRange == VideoDynamicRange.SdrRec709
                )
            // Any requested setting makes Media3 re-encode, so a copied stream gets none.
            if (plan?.remuxVideo != true && (hevcMain10 || plan != null)) {
                val videoSettings = VideoEncoderSettings.Builder()
                plan?.let { videoSettings.setBitrate(it.videoBitrate) }
                if (hevcMain10) {
                    videoSettings.setEncodingProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5,
                    )
                }
                encoderBuilder.setRequestedVideoEncoderSettings(videoSettings.build())
            }
            (plan?.audio as? VideoAudioPlan.Encode)?.takeIf { it.requested }?.let { audio ->
                encoderBuilder.setRequestedAudioEncoderSettings(
                    AudioEncoderSettings.Builder().setBitrate(audio.bitsPerSecond).build(),
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
                val assetLoaderFactory = DefaultAssetLoaderFactory(
                    context.applicationContext,
                    decoderFactory,
                    Clock.DEFAULT,
                    LogSessionId.LOG_SESSION_ID_NONE,
                )
                transformerBuilder.setAssetLoaderFactory(
                    if (request.recipe.dynamicRange == VideoDynamicRange.SdrRec709) {
                        assetLoaderFactory
                    } else {
                        HdrGraphInputAssetLoaderFactory(assetLoaderFactory)
                    },
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
                                // Media3 reports what it actually encoded, which differs from the
                                // request once encoder fallback applies.
                                videoMimeType = exportResult.videoMimeType
                                    ?: plan?.videoMimeType ?: requestedVideoMimeType(request, null),
                                audioMimeType = exportResult.audioMimeType
                                    ?: if (removeAudio) null else request.audioMimeType,
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
                    request.recipe.dynamicRange != VideoDynamicRange.SdrRec709 || plan?.toneMapToSdr == true
                ) {
                    Composition.Builder(
                        listOf(
                            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                                .addItem(edited)
                                .build(),
                        ),
                    ).setHdrMode(
                        // An explicit H.264 choice for an HDR source: tone-map instead of letting
                        // Media3 fall back to HEVC to keep the HDR.
                        if (plan?.toneMapToSdr == true && request.recipe.dynamicRange == VideoDynamicRange.SdrRec709) {
                            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
                        } else Composition.HDR_MODE_KEEP_HDR,
                    ).build()
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

    private companion object {
        const val ProgressPollMillis = 250L
        const val Tag = "Media3VideoExporter"
        const val DefaultSourceFrameRate = 30f
    }

    private fun requestedVideoMimeType(request: VideoExportRequest, plan: VideoOutputPlan?): String =
        plan?.videoMimeType ?: if (request.recipe.outputQuality == VideoOutputQuality.HevcMain10 ||
            request.recipe.dynamicRange != VideoDynamicRange.SdrRec709
        ) {
            MimeTypes.VIDEO_H265
        } else request.videoMimeType

    private fun isTrimmed(recipe: VideoEditRecipe, clipEndMillis: Long): Boolean =
        recipe.startMillis > 0 || recipe.endMillis != null

    private fun buildSlowMotionComposition(
        request: VideoExportRequest,
        slowMotion: SlowMotionExport,
        outputDurationMillis: Long,
        videoEffects: List<androidx.media3.common.Effect>,
        removeSourceAudio: Boolean,
    ): Composition {
        val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        val audio = if (!removeSourceAudio && sourceHasAudio(request.input)) {
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        } else null
        slowMotion.ranges.forEach { range ->
            val generated = slowMotion.generated.firstOrNull { it.range == range }
            video.addItem(
                if (generated != null) {
                    interpolatedVideoItem(generated, videoEffects)
                } else {
                    sourceVideoItem(request, range.startMillis, range.endMillis, range.speed, videoEffects)
                },
            )
            when (range.audioMode) {
                null -> audio?.addItem(sourceAudioItem(request, range.startMillis, range.endMillis, range.speed, true))
                SlowMotionAudioMode.Muted -> audio?.addGap((range.outputMillis * 1_000L).toLong())
                SlowMotionAudioMode.PreservePitch -> audio?.addItem(
                    sourceAudioItem(request, range.startMillis, range.endMillis, range.speed, true),
                )
                SlowMotionAudioMode.Varispeed -> audio?.addItem(
                    sourceAudioItem(request, range.startMillis, range.endMillis, range.speed, false),
                )
            }
        }
        val sequences = mutableListOf(video.build())
        audio?.let { sequences += it.build() }
        // Added music stays even when the source audio is removed.
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

    /**
     * The intermediate video already holds `factor` times the frames, so it plays at speed 1 (exact
     * power-of-two slow-downs) and is cut to the length the range must have.
     */
    private fun interpolatedVideoItem(
        generated: GeneratedRange,
        videoEffects: List<androidx.media3.common.Effect>,
    ): EditedMediaItem {
        val range = generated.range
        val wantedMillis = range.sourceMillis * checkNotNull(range.interpolationFactor)
        val builder = MediaItem.Builder().setUri(Uri.fromFile(generated.file))
        if (wantedMillis < generated.durationMillis) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(wantedMillis).build(),
            )
        }
        val item = EditedMediaItem.Builder(builder.build())
            .setRemoveAudio(true)
            .setEffects(androidx.media3.transformer.Effects(emptyList(), videoEffects))
        if (range.needsVideoSpeedChange()) {
            item.setSpeed(SpeedParameters(ConstantSpeedProvider(range.videoPlaybackSpeed), true))
        }
        return item.build()
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

    private fun outputDurationMillis(recipe: VideoEditRecipe, clipEndMillis: Long): Long =
        recipe.outputDurationMillis(clipEndMillis)

    private fun sourceHasAudio(uri: Uri): Boolean = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context.applicationContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        }
    }.getOrDefault(false)

}

private fun VideoFrameResize.toPresentation(): Presentation = Presentation.createForWidthAndHeight(
    width,
    height,
    when (mode) {
        VideoAspectMode.Stretch -> Presentation.LAYOUT_STRETCH_TO_FIT
        VideoAspectMode.Crop -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        VideoAspectMode.Pad -> Presentation.LAYOUT_SCALE_TO_FIT
    },
)

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
