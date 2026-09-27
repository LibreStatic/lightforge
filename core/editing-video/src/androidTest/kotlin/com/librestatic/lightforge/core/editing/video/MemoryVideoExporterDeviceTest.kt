package com.librestatic.lightforge.core.editing.video

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class MemoryVideoExporterDeviceTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun silentVideoPreservesOrderPortraitFitAndOriginalHashes() = runBlocking {
        val images = listOf(image(Color.RED), orientedImage(Color.GREEN), image(Color.BLUE))
        val hashes = images.map(::hash)
        var uri: Uri? = null
        try {
            uri =
                MemoryVideoExporter(context)
                    .export(
                        MemoryVideoRequest(images.map { MemoryVideoSource(Uri.fromFile(it)) }, 1)
                    )
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, uri)
                assertTrue(
                    metadata
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
                        .toLong() in 2_950L..3_250L
                )
                images.indices.forEach { index ->
                    val bitmap =
                        metadata.getFrameAtTime(
                            index * 1_000_000L + 400_000L,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        )!!
                    val actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertDominant(actual, index)
                    if (index == 1)
                        assertTrue(
                            "portrait should fit with pillarbox, not crop",
                            Color.red(bitmap.getPixel(10, 360)) < 20,
                        )
                    bitmap.recycle()
                }
            } finally {
                metadata.release()
            }
            assertEquals(listOf("video/avc"), tracks(uri))
            assertPublished(uri)
            assertEquals(hashes, images.map(::hash))
            assertNoStaging()
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            images.forEach { it.delete() }
        }
    }

    @Test
    fun shortLocalMusicLoopsAndIsEncodedAsAac() = runBlocking {
        val image = image(Color.RED)
        val audio = wave()
        val hashes = listOf(hash(image), hash(audio))
        var uri: Uri? = null
        try {
            uri =
                MemoryVideoExporter(context)
                    .export(
                        MemoryVideoRequest(
                            listOf(MemoryVideoSource(Uri.fromFile(image))),
                            2,
                            Uri.fromFile(audio),
                        )
                    )
            assertEquals(setOf("video/avc", "audio/mp4a-latm"), tracks(uri).toSet())
            assertEquals(hashes, listOf(hash(image), hash(audio)))
            assertPublished(uri)
            assertNoStaging()
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            image.delete()
            audio.delete()
        }
    }

    @Test
    fun missingPhotoFailsWithoutSkippingOrPublishing() = runBlocking {
        val image = image(Color.GREEN)
        val before = publicationCount()
        try {
            expectFailure {
                MemoryVideoExporter(context)
                    .export(
                        MemoryVideoRequest(
                            listOf(
                                MemoryVideoSource(Uri.fromFile(image)),
                                MemoryVideoSource(
                                    Uri.fromFile(
                                        File(context.cacheDir, "missing-photo-${System.nanoTime()}")
                                    )
                                ),
                            ),
                            1,
                        )
                    )
            }
            assertEquals(before, publicationCount())
            assertNoStaging()
        } finally {
            image.delete()
        }
    }

    @Test
    fun nonAudioMusicFailsWithoutSilentFallback() = runBlocking {
        val image = image(Color.BLUE)
        val before = publicationCount()
        try {
            expectFailure {
                MemoryVideoExporter(context)
                    .export(
                        MemoryVideoRequest(
                            listOf(MemoryVideoSource(Uri.fromFile(image))),
                            1,
                            Uri.fromFile(image),
                        )
                    )
            }
            assertEquals(before, publicationCount())
            assertNoStaging()
        } finally {
            image.delete()
        }
    }

    @Test
    fun cancelActiveEncoderCleansFilesAndPublishesNothing() = runBlocking {
        val image = image(Color.RED)
        val before = publicationCount()
        val started = CompletableDeferred<Unit>()
        try {
            val job = launch {
                MemoryVideoExporter(context).export(
                    MemoryVideoRequest(List(10) { MemoryVideoSource(Uri.fromFile(image)) }, 5)
                ) {
                    if (it.phase == MemoryVideoPhase.Encoding) started.complete(Unit)
                }
            }
            withTimeout(60_000) { started.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(before, publicationCount())
            assertNoStaging()
        } finally {
            image.delete()
        }
    }

    @Test
    fun changedGenerationFailsAndLeavesOriginalUnmodified() = runBlocking {
        val file = image(Color.RED)
        val resolver = context.contentResolver
        val uri =
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "Memory-generation-${System.nanoTime()}.jpg",
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LightforgeTest")
                },
            )!!
        try {
            resolver.openOutputStream(uri)!!.use { stream ->
                file.inputStream().use { it.copyTo(stream) }
            }
            val generation =
                resolver
                    .query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED),
                        null,
                        null,
                        null,
                    )!!
                    .use {
                        assertTrue(it.moveToFirst())
                        it.getLong(0)
                    }
            val before = publicationCount()
            expectFailure {
                MemoryVideoExporter(context)
                    .export(MemoryVideoRequest(listOf(MemoryVideoSource(uri, generation + 1)), 1))
            }
            assertEquals(before, publicationCount())
            val actual = resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(file.readBytes(), actual)
            assertNoStaging()
        } finally {
            resolver.delete(uri, null, null)
            file.delete()
        }
    }

    @Test
    fun invalidDraftsAreRejectedBeforeWork() {
        val source = MemoryVideoSource(Uri.parse("content://media/external/images/media/1"))
        listOf(emptyList(), List(121) { source }).forEach { sources ->
            assertThrows(IllegalArgumentException::class.java) { MemoryVideoRequest(sources) }
        }
        assertEquals((1..5).toList(), MemoryVideoRequest.SupportedSecondsPerPhoto.toList())
        (1..5).forEach { seconds ->
            assertEquals(seconds * 1_000L, MemoryVideoRequest(listOf(source), seconds).durationMillis)
        }
        listOf(1, 3, 120).forEach { count ->
            assertEquals(count * 4_000L, MemoryVideoRequest(List(count) { source }, 4).durationMillis)
        }
        listOf(0, 6).forEach { seconds ->
            assertThrows(IllegalArgumentException::class.java) {
                MemoryVideoRequest(listOf(source), seconds)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryVideoRequest(
                listOf(MemoryVideoSource(Uri.parse("https://example.test/image.jpg")))
            )
        }
        assertEquals(600_000L, MemoryVideoRequest(List(120) { source }, 5).durationMillis)
    }

    private fun image(color: Int, width: Int = 120, height: Int = 60): File {
        val file = File(context.cacheDir, "fixture-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun orientedImage(color: Int): File {
        val file = image(color)
        val jpeg = file.readBytes()
        // APP1 Exif, little-endian TIFF, one orientation=6 (90-degree clockwise) entry.
        val exif =
            byteArrayOf(
                0xff.toByte(),
                0xe1.toByte(),
                0,
                34,
                69,
                120,
                105,
                102,
                0,
                0,
                73,
                73,
                42,
                0,
                8,
                0,
                0,
                0,
                1,
                0,
                18,
                1,
                3,
                0,
                1,
                0,
                0,
                0,
                6,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        file.writeBytes(jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size))
        return file
    }

    private fun wave(): File {
        val rate = 8_000
        val samples = rate / 4
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        data
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(rate)
            .putInt(rate * 2)
            .putShort(2)
            .putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) {
            data.putShort(
                (kotlin.math.sin(it * 2.0 * Math.PI * 440 / rate) * 10_000).toInt().toShort()
            )
        }
        return File(context.cacheDir, "fixture-${System.nanoTime()}.wav").also {
            it.writeBytes(data.array())
        }
    }

    private fun hash(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }

    private fun assertDominant(color: Int, channel: Int) {
        val values = listOf(Color.red(color), Color.green(color), Color.blue(color))
        assertTrue(
            "unexpected photo order/color: $values",
            values[channel] > 180 &&
                values.filterIndexed { index, _ -> index != channel }.all { it < 70 },
        )
    }

    private fun tracks(uri: Uri): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!
            }
        } finally {
            extractor.release()
        }
    }

    private fun assertPublished(uri: Uri) {
        context.contentResolver
            .query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null,
            )!!
            .use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("Movies/Lightforge/Memories/", it.getString(1))
            }
    }

    private fun publicationCount(): Int =
        context.contentResolver
            .query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf("Movies/Lightforge/Memories/"),
                null,
            )!!
            .use { it.count }

    private fun assertNoStaging() =
        assertTrue(
            context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("memory-video-") }
        )

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("expected failure", failed)
    }
}
