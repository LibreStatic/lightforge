package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.content.ContentValues
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3ExportDeviceTest {
    @Test
    fun exportedCopyUsesFreshCreationDate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = copyAssetToCache(context, "m0_h264.mp4")
        val output = File(context.cacheDir, "fresh-date-${System.nanoTime()}.mp4")
        val startedAt = System.currentTimeMillis()
        var published: Uri? = null
        try {
            Media3VideoExporter(context).export(
                VideoExportRequest(
                    input = Uri.fromFile(input),
                    output = output,
                    recipe = VideoEditRecipe(startMillis = 0, endMillis = 1_000),
                ),
            )
            published = PendingMediaPublisher(context.contentResolver).publishValidatedVideo(
                tempFile = output,
                displayName = "Lightforge-fresh-date-${System.nanoTime()}.mp4",
                relativePath = "Movies/LightforgeBenchmark",
            )

            val dateTaken = mediaLong(context, published, MediaStore.MediaColumns.DATE_TAKEN)
            assertTrue(
                "export retained a stale source timestamp: $dateTaken",
                dateTaken in (startedAt - 1_000L)..(System.currentTimeMillis() + 1_000L),
            )
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            input.delete()
            output.delete()
        }
    }

    @Test
    fun logInSdrContainerExportsHlgAndHdr10WithHardwareCodecs() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = VideoOutputCapabilities.hdr(context)
        assumeTrue("Device has no hardware HLG export path", capabilities.hlg)
        assumeTrue("Device has no hardware HDR10 export path", capabilities.hdr10)
        val input = copyAssetToCache(context, "m0_h264.mp4")
        try {
            listOf(
                VideoDynamicRange.HdrHlg to C.COLOR_TRANSFER_HLG,
                VideoDynamicRange.Hdr10Pq to C.COLOR_TRANSFER_ST2084,
            ).forEach { (dynamicRange, expectedTransfer) ->
                val output = File(context.cacheDir, "hdr-${dynamicRange.name}-${System.nanoTime()}.mp4")
                try {
                    val result = Media3VideoExporter(context).export(
                        VideoExportRequest(
                            input = Uri.fromFile(input),
                            output = output,
                            recipe = VideoEditRecipe(
                                startMillis = 0,
                                endMillis = 1_000,
                                dynamicRange = dynamicRange,
                                colorGrade = VideoColorGrade(inputProfile = LogInputProfile.SonySLog3),
                            ),
                        ),
                    )

                    assertTrue(output.isFile && output.length() > 0)
                    assertFalse("HDR export used a software codec", result.usedSoftwareCodec)
                    val format = videoTrackFormat(output)
                    assertEquals(MimeTypes.VIDEO_H265, format.getString(MediaFormat.KEY_MIME))
                    assertEquals(C.COLOR_SPACE_BT2020, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
                    assertEquals(expectedTransfer, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
                } finally {
                    output.delete()
                }
            }
        } finally {
            input.delete()
        }
    }

    @Test
    fun logColorGradeIsRenderedThroughMedia3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = copyAssetToCache(context, "m0_h264.mp4")
        val output = File(context.cacheDir, "log-grade-${System.nanoTime()}.mp4")
        try {
            Media3VideoExporter(context).export(
                VideoExportRequest(
                    input = Uri.fromFile(input),
                    output = output,
                    recipe = VideoEditRecipe(
                        startMillis = 0,
                        endMillis = 1_000,
                        colorGrade = VideoColorGrade(
                            inputProfile = LogInputProfile.SonySLog3,
                            exposureEv = 0.75f,
                            saturation = 0.2f,
                            lut = LutReference(builtIn = BuiltInLook.Monochrome, intensity = 1f),
                        ),
                    ),
                ),
            )

            assertTrue(output.isFile && output.length() > 0)
            val meanPixelDelta = frameMeanAbsoluteRgbDelta(input, output, 500_000)
            assertTrue("LOG grade was not visible: delta=$meanPixelDelta", meanPixelDelta > 12.0)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun timedInkAndRedactionAnnotationsExportThroughMedia3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = copyAssetToCache(context, "m0_h264.mp4")
        val output = File(context.cacheDir, "annotations-${System.nanoTime()}.mp4")
        try {
            Media3VideoExporter(context).export(
                VideoExportRequest(
                    input = Uri.fromFile(input),
                    output = output,
                    recipe = VideoEditRecipe(
                        startMillis = 0,
                        endMillis = 1_000,
                        annotations = listOf(
                            VideoAnnotationLayer(
                                shape = VideoAnnotationShape.Freehand,
                                points = listOf(
                                    NormalizedPoint(0.1f, 0.1f),
                                    NormalizedPoint(0.5f, 0.5f),
                                    NormalizedPoint(0.9f, 0.2f),
                                ),
                                startMillis = 0,
                                endMillis = 1_000,
                            ),
                            VideoAnnotationLayer(
                                shape = VideoAnnotationShape.Rectangle,
                                points = listOf(NormalizedPoint(0.25f, 0.25f), NormalizedPoint(0.6f, 0.6f)),
                                style = VideoAnnotationStyle(
                                    appearance = VideoAnnotationAppearance.Mosaic,
                                    intensity = 0.8f,
                                ),
                                startMillis = 250,
                                endMillis = 750,
                            ),
                        ),
                    ),
                ),
            )

            assertTrue(output.isFile && output.length() > 0)
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(output.absolutePath)
                assertNotNull(retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST))
            }
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun aiSlowMotionSegmentProducesExtendedVideo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = copyAssetToCache(context, "m0_h264.mp4")
        val output = File(context.cacheDir, "ai-slow-${System.nanoTime()}.mp4")
        try {
            val result = Media3VideoExporter(context).export(
                VideoExportRequest(
                    input = Uri.fromFile(input),
                    output = output,
                    recipe = VideoEditRecipe(
                        startMillis = 0,
                        endMillis = 1_000,
                        slowMotionSegments = listOf(
                            SlowMotionSegment(startMillis = 250, endMillis = 500, speed = 0.25f),
                        ),
                    ),
                ),
            )

            assertTrue(output.isFile && output.length() > 0)
            assertTrue("unexpected recipe duration", result.durationMillis in 1_700..1_800)
            assertTrue("unexpected encoded duration", mediaDurationMs(output) in 1_500..2_100)
            println("LIGHTFORGE_RIFE_EXPORT size=${output.length()} durationMs=${mediaDurationMs(output)}")
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun h264AndHevcInputsProduceValidatedPublishedCopies() {
        listOf("m0_h264.mp4", "m0_hevc.mp4").forEach { assetName ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val input = copyAssetToCache(context, assetName)
            val output = File(context.cacheDir, "trimmed-$assetName").apply { delete() }
            val result = transformTrimmed(input, output)

            assertTrue("empty export for $assetName", output.length() > 0)
            assertTrue("duration missing for $assetName", mediaDurationMs(output) in 1_000..2_500)
            assertTrue("no video frames for $assetName", result.videoFrameCount > 0)

            val displayName = "lightforge-${System.nanoTime()}-$assetName"
            val published = PendingMediaPublisher(context.contentResolver).publishValidatedVideo(
                tempFile = output,
                displayName = displayName,
                relativePath = "Movies/LightforgeBenchmark",
            )
            try {
                val publishedSize = context.contentResolver.openFileDescriptor(published, "r")!!.use { it.statSize }
                assertEquals(output.length(), publishedSize)
                assertTrue(mediaDurationMs(context, published) in 1_000..2_500)
                assertEquals(0, pendingFlag(context, published))
            } finally {
                context.contentResolver.delete(published, null, null)
                input.delete()
                output.delete()
            }
            println(
                "LIGHTFORGE_MEDIA3_METRICS input=$assetName size=${result.fileSizeBytes} durationMs=${result.durationMs} " +
                    "videoMime=${result.videoMimeType} encoder=${result.videoEncoderName} optimization=${result.optimizationResult}",
            )
        }
    }

    @Test
    fun cancellationDoesNotPublishPartialOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = copyAssetToCache(context, "m0_hevc.mp4")
        val output = File(context.cacheDir, "cancelled-${System.nanoTime()}.mp4")
        val forbiddenDisplayName = "lightforge-cancelled-${System.nanoTime()}.mp4"
        val transformerRef = AtomicReference<Transformer>()

        instrumentation.runOnMainSync {
            transformerRef.set(
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .build()
                    .also { it.start(clippedItem(input), output.absolutePath) },
            )
            transformerRef.get().cancel()
        }
        output.delete()

        assertFalse(output.exists())
        assertEquals(0, mediaRowsNamed(context, forbiddenDisplayName))
        input.delete()
    }

    @Test
    fun compositionMixesLocalMusicIntoMutedVideoAudio() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = copyAssetToCache(context, "m0_h264.mp4")
        val music = writeToneWav(context, durationMillis = 3_000)
        val output = File(context.cacheDir, "mixed-${System.nanoTime()}.mp4")
        try {
            val result = Media3VideoExporter(context).export(
                VideoExportRequest(
                    input = Uri.fromFile(input),
                    output = output,
                    recipe = VideoEditRecipe(
                        startMillis = 0,
                        endMillis = 2_500,
                        originalAudioVolume = 0f,
                        musicUri = Uri.fromFile(music),
                        musicVolume = 1f,
                    ),
                ),
            )
            assertNotNull(result)
            assertTrue(kotlin.math.abs(result.durationMillis - 2_500L) <= 100L)
            assertTrue(result.fallbackWarning == null)
            assertTrue(output.isFile && output.length() > 0)
            assertTrue("unexpected output duration", mediaDurationMs(output) in 2_300..2_800)
            assertTrue("mixed track was silent", decodedAudioMeanAbs(output) > 500)
        } finally {
            input.delete()
            music.delete()
            output.delete()
        }
    }

    @Test
    fun startupRecoveryDeletesAnOwnedPendingRowLeftByProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = "Movies/LightforgeBenchmark/Recovery-${System.nanoTime()}/"
        val pending = checkNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "abandoned.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        resolver.openOutputStream(pending, "w")!!.use { it.write(byteArrayOf(0, 1, 2, 3)) }
        resolver.query(
            pending,
            arrayOf(
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.IS_PENDING,
            ),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            println(
                "LIGHTFORGE_PENDING_RECOVERY owner=${cursor.getString(0)} expected=${context.packageName} " +
                    "path=${cursor.getString(1)} added=${cursor.getLong(2)} pending=${cursor.getInt(3)}",
            )
        }

        val deleted = PendingMediaPublisher(resolver).recoverAbandonedExports(
            ownerPackageName = context.packageName,
            relativePathPrefix = path,
            olderThanEpochSeconds = Long.MAX_VALUE,
        )

        assertEquals(1, deleted)
        assertEquals(0, resolver.query(pending, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count })
    }

    private fun transformTrimmed(input: File, output: File): ExportResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        val result = AtomicReference<ExportResult>()
        val failure = AtomicReference<ExportException>()
        val fallback = AtomicReference<String>()

        instrumentation.runOnMainSync {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        result.set(exportResult)
                        latch.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        failure.set(exportException)
                        latch.countDown()
                    }

                    override fun onFallbackApplied(
                        composition: Composition,
                        originalTransformationRequest: TransformationRequest,
                        fallbackTransformationRequest: TransformationRequest,
                    ) {
                        fallback.set("$originalTransformationRequest -> $fallbackTransformationRequest")
                    }
                })
                .build()
                .start(clippedItem(input), output.absolutePath)
        }

        check(latch.await(60, TimeUnit.SECONDS)) { "Media3 export timed out" }
        failure.get()?.let { throw AssertionError("Media3 export failed", it) }
        fallback.get()?.let { println("LIGHTFORGE_MEDIA3_FALLBACK $it") }
        return checkNotNull(result.get())
    }

    private fun clippedItem(input: File): MediaItem = MediaItem.Builder()
        .setUri(Uri.fromFile(input))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(500)
                .setEndPositionMs(2_500)
                .build(),
        )
        .build()

    private fun copyAssetToCache(context: Context, name: String): File =
        File(context.cacheDir, "${System.nanoTime()}-$name").also { destination ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }

    private fun mediaDurationMs(file: File): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
    }

    private fun frameMeanAbsoluteRgbDelta(first: File, second: File, timeUs: Long): Double {
        val firstFrame = framePixels(first, timeUs)
        val secondFrame = framePixels(second, timeUs)
        check(firstFrame.first == secondFrame.first)
        var delta = 0L
        firstFrame.second.indices.forEach { index ->
            val firstColor = firstFrame.second[index]
            val secondColor = secondFrame.second[index]
            delta += kotlin.math.abs(Color.red(firstColor) - Color.red(secondColor))
            delta += kotlin.math.abs(Color.green(firstColor) - Color.green(secondColor))
            delta += kotlin.math.abs(Color.blue(firstColor) - Color.blue(secondColor))
        }
        return delta.toDouble() / firstFrame.second.size / 3.0
    }

    private fun framePixels(file: File, timeUs: Long): Pair<Pair<Int, Int>, IntArray> = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val bitmap = checkNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            (bitmap.width to bitmap.height) to pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun mediaDurationMs(context: Context, uri: Uri): Long = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
    }

    private fun pendingFlag(context: Context, uri: Uri): Int =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null,
        )!!.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun mediaLong(context: Context, uri: Uri, column: String): Long =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)!!.use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun mediaRowsNamed(context: Context, displayName: String): Int =
        context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )!!.use { it.count }

    private fun writeToneWav(context: Context, durationMillis: Int): File {
        val sampleRate = 48_000
        val channels = 1
        val bitsPerSample = 16
        val sampleCount = sampleRate * durationMillis / 1_000
        val dataSize = sampleCount * channels * bitsPerSample / 8
        val output = File(context.cacheDir, "music-${System.nanoTime()}.wav")
        DataOutputStream(BufferedOutputStream(FileOutputStream(output))).use { data ->
            fun ascii(value: String) { data.write(value.toByteArray(Charsets.US_ASCII)) }
            fun littleInt(value: Int) { data.writeInt(Integer.reverseBytes(value)) }
            fun littleShort(value: Int) { data.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt()) }
            ascii("RIFF"); littleInt(36 + dataSize); ascii("WAVE")
            ascii("fmt "); littleInt(16); littleShort(1); littleShort(channels)
            littleInt(sampleRate); littleInt(sampleRate * channels * bitsPerSample / 8)
            littleShort(channels * bitsPerSample / 8); littleShort(bitsPerSample)
            ascii("data"); littleInt(dataSize)
            repeat(sampleCount) { index ->
                val phase = index.toDouble() / sampleRate.toDouble()
                val sample = (kotlin.math.sin(phase * 2.0 * Math.PI * 880.0) * Short.MAX_VALUE * 0.7).toInt()
                littleShort(sample)
            }
        }
        return output
    }

    private fun decodedAudioMeanAbs(file: File): Double {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in mixed output")
        val format = extractor.getTrackFormat(audioTrack)
        extractor.selectTrack(audioTrack)
        val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var absoluteSum = 0L
        var sampleCount = 0L
        val deadline = System.nanoTime() + 20_000_000_000L
        try {
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val outputBuffer = checkNotNull(codec.getOutputBuffer(outputIndex)).duplicate()
                            .order(ByteOrder.LITTLE_ENDIAN)
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        while (outputBuffer.remaining() >= 2) {
                            absoluteSum += kotlin.math.abs(outputBuffer.short.toInt()).toLong()
                            sampleCount++
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        check(outputEnded) { "audio decoder timed out" }
        return if (sampleCount == 0L) 0.0 else absoluteSum.toDouble() / sampleCount.toDouble()
    }

    private fun videoTrackFormat(file: File): MediaFormat = MediaExtractor().let { extractor ->
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("No video track in ${file.name}")
            extractor.getTrackFormat(videoTrack)
        } finally {
            extractor.release()
        }
    }
}
