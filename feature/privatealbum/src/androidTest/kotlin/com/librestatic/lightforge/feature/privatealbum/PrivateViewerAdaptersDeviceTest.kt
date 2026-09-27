package com.librestatic.lightforge.feature.privatealbum

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public synthetic bytes only, kept in RAM; no media rows, files, accounts or global settings. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PrivateViewerAdaptersDeviceTest {
    @Test
    fun mediaExactUriRangesEofAndReopenLeaveLeaseOwnedByCaller() {
        val source = MemorySource(ByteArray(32) { it.toByte() })
        val adapter = PrivateMediaDataSource.Factory(source).createDataSource()
        try {
            listOf("https://example.invalid/video", "content://media/external/video/media/1",
                "file:///private.mp4", "lightforge-private://viewer/${source.metadata.mediaId + 1}",
                "${source.uri}?fallback=1", "${source.uri}/").forEach { uri ->
                expectIo { adapter.open(DataSpec(Uri.parse(uri))) }
            }
            assertEquals(0, source.readCalls)
            assertNull(adapter.uri)
            assertEquals(7L, adapter.open(spec(source, 5, 7)))
            assertEquals(source.uri, adapter.uri)
            val buffer = ByteArray(12) { 99 }
            assertEquals(0, adapter.read(buffer, 3, 0))
            assertEquals(7, adapter.read(buffer, 3, 9))
            assertArrayEquals(byteArrayOf(99, 99, 99, 5, 6, 7, 8, 9, 10, 11, 99, 99), buffer)
            assertEquals(C.RESULT_END_OF_INPUT, adapter.read(buffer, 0, 1))
            adapter.close()
            assertNull(adapter.uri)
            assertEquals(0, source.closeCalls)
            assertEquals(2L, adapter.open(spec(source, 30)))
            assertEquals(2, adapter.read(buffer, 0, buffer.size))
            assertEquals(30, buffer[0].toInt())
            assertEquals(31, buffer[1].toInt())
            assertEquals(C.RESULT_END_OF_INPUT, adapter.read(buffer, 0, 1))
            adapter.close()
            assertEquals(0L, adapter.open(spec(source, 32)))
            assertEquals(C.RESULT_END_OF_INPUT, adapter.read(buffer, 0, 1))
            adapter.close()
            expectIo { adapter.open(spec(source, 33)) }
            assertTrue(source.valid.value)
        } finally { adapter.close(); source.close() }
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun mediaRevocationAndPrematureEofNeverDeliverReadBuffer() {
        val source = MemorySource(ByteArray(16) { 42 })
        val adapter = PrivateMediaDataSource.Factory(source).createDataSource()
        try {
            adapter.open(spec(source))
            source.afterRead = { source.valid.value = false }
            val buffer = ByteArray(8) { 99 }
            expectIo { adapter.read(buffer, 2, 4) }
            assertArrayEquals(byteArrayOf(99, 99, 0, 0, 0, 0, 99, 99), buffer)
            val calls = source.readCalls
            expectIo { adapter.read(buffer, 0, 1) }
            assertEquals(calls, source.readCalls)
            adapter.close()
            expectIo { adapter.open(spec(source)) }
            assertEquals(0, source.closeCalls)
        } finally { adapter.close(); source.close() }

        val short = MemorySource(byteArrayOf(1, 2), declaredSize = 4)
        val shortAdapter = PrivateMediaDataSource.Factory(short).createDataSource()
        try {
            assertEquals(4L, shortAdapter.open(spec(short)))
            val buffer = ByteArray(4) { 99 }
            assertEquals(2, shortAdapter.read(buffer, 0, 4))
            expectIo { shortAdapter.read(buffer, 0, 2) }
            assertEquals(0, buffer[0].toInt())
            assertEquals(0, buffer[1].toInt())
        } finally { shortAdapter.close(); short.close() }
    }

    @Test
    fun jpegExifAllOrientationsDecodeRealPixelsAndSampledRegionsThenCloseWorker() {
        val jpeg = publicJpeg()
        for (orientation in 1..8) {
            val bytes = withOrientation(jpeg, orientation)
            assertEquals(orientation, ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            val source = MemorySource(bytes, mime = "image/jpeg")
            val tiles = PrivateImageTileSource(context, source)
            var worker: Thread? = null
            try {
                worker = ownWorker(source)
                assertEquals(if (orientation >= 5) HEIGHT else WIDTH, tiles.width)
                assertEquals(if (orientation >= 5) WIDTH else HEIGHT, tiles.height)
                val full = tiles.decodeRegion(Rect(0, 0, tiles.width, tiles.height), 1)
                try {
                    assertEquals(tiles.width, full.width)
                    assertEquals(tiles.height, full.height)
                    for (row in 0..1) for (column in 0..1) {
                        val x = tiles.width * (column * 2 + 1) / 4
                        val y = tiles.height * (row * 2 + 1) / 4
                        val expected = expectedColor(orientation, x, y)
                        assertColor(expected, full.getPixel(x, y), "EXIF $orientation full $column/$row")
                        val crop = tiles.decodeRegion(Rect(x - 4, y - 4, x + 4, y + 4), 2)
                        try {
                            assertEquals(4, crop.width)
                            assertEquals(4, crop.height)
                            assertColor(expected, crop.getPixel(2, 2), "EXIF $orientation crop $column/$row")
                        } finally { crop.recycle() }
                    }
                } finally { full.recycle() }
                assertTrue(source.readCalls > 0)
            } finally {
                try { tiles.close() } finally { source.close() }
            }
            assertFalse("Exact proxy worker must terminate", requireNotNull(worker).isAlive)
            tiles.close()
            assertEquals(1, source.closeCalls)
        }
    }

    @Test
    fun tileRevocationRejectsDecodeAndCleansOnlyItsWorkerWithoutClosingLease() {
        val source = MemorySource(withOrientation(publicJpeg(), 6), mime = "image/jpeg")
        val tiles = PrivateImageTileSource(context, source)
        var worker: Thread? = null
        try {
            worker = ownWorker(source)
            val bitmap = tiles.decodeRegion(Rect(0, 0, tiles.width, tiles.height), 2)
            bitmap.recycle()
            source.valid.value = false
            val calls = source.readCalls
            expectIo { tiles.decodeRegion(Rect(0, 0, 8, 8), 1) }
            assertEquals(calls, source.readCalls)
        } finally {
            try { tiles.close() } finally { source.close() }
        }
        assertFalse(requireNotNull(worker).isAlive)
        tiles.close()
        expectIo { tiles.decodeRegion(Rect(0, 0, 8, 8), 1) }
        assertEquals(1, source.closeCalls)
        val revoked = MemorySource(publicJpeg(), mime = "image/jpeg")
        try {
            revoked.valid.value = false
            expectIo { PrivateImageTileSource(context, revoked) }
            assertFalse(Thread.getAllStackTraces().keys.any { it.name == workerName(revoked) && it.isAlive })
        } finally { revoked.close() }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun spec(source: MemorySource, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(source.uri).setPosition(position).setLength(length).build()

    private fun workerName(source: MemorySource) = "private-image-${source.metadata.mediaId}"
    private fun ownWorker(source: MemorySource): Thread = Thread.getAllStackTraces().keys
        .single { it.name == workerName(source) && it.isAlive }

    private fun expectIo(action: () -> Unit) {
        try { action() } catch (_: IOException) { return }
        fail("Expected IOException")
    }

    private class MemorySource(
        private val bytes: ByteArray,
        mime: String = "video/mp4",
        declaredSize: Long = bytes.size.toLong(),
    ) : PrivateViewerSource {
        override val metadata = PrivateViewerMetadata(
            UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE, "public-fixture", mime,
            if (mime.startsWith("image/")) "image" else "video", WIDTH, HEIGHT, 0, declaredSize,
        )
        override val valid = MutableStateFlow(true)
        val uri: Uri get() = Uri.parse("lightforge-private://viewer/${metadata.mediaId}")
        @Volatile var readCalls = 0
        var closeCalls = 0
        var afterRead: (() -> Unit)? = null
        override fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int {
            if (!valid.value) throw IOException("Fixture revoked")
            readCalls++
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(target, offset, position.toInt(), position.toInt() + count)
            afterRead?.invoke()
            return count
        }
        override fun close() { closeCalls++; valid.value = false }
    }

    private fun publicJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            for (y in 0 until HEIGHT) for (x in 0 until WIDTH) bitmap.setPixel(x, y, rawColor(x, y))
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    /** Inserts a standard big-endian TIFF Orientation IFD into our encoder's JPEG, entirely in RAM. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        require(orientation in 1..8 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(byteArrayOf(69, 120, 105, 102, 0, 0))
                out.writeShort(0x4d4d); out.writeShort(42); out.writeInt(8)
                out.writeShort(1)
                out.writeShort(0x0112); out.writeShort(3); out.writeInt(1)
                out.writeShort(orientation); out.writeShort(0); out.writeInt(0)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(jpeg, 0, 2); out.writeShort(0xffe1); out.writeShort(payload.size + 2)
                out.write(payload); out.write(jpeg, 2, jpeg.size - 2)
            }
        }.toByteArray()
    }

    private fun expectedColor(orientation: Int, x: Int, y: Int): Int {
        val raw = when (orientation) {
            2 -> WIDTH - 1 - x to y
            3 -> WIDTH - 1 - x to HEIGHT - 1 - y
            4 -> x to HEIGHT - 1 - y
            5 -> y to x
            6 -> y to HEIGHT - 1 - x
            7 -> WIDTH - 1 - y to HEIGHT - 1 - x
            8 -> WIDTH - 1 - y to x
            else -> x to y
        }
        return rawColor(raw.first, raw.second)
    }

    private fun rawColor(x: Int, y: Int): Int = when {
        y < HEIGHT / 2 && x < WIDTH / 2 -> Color.RED
        y < HEIGHT / 2 -> Color.GREEN
        x < WIDTH / 2 -> Color.BLUE
        else -> Color.YELLOW
    }

    private fun assertColor(expected: Int, actual: Int, label: String) {
        for (shift in listOf(16, 8, 0)) assertTrue("$label RGB channel $shift",
            kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 25)
    }

    private companion object { const val WIDTH = 96; const val HEIGHT = 64 }
}
