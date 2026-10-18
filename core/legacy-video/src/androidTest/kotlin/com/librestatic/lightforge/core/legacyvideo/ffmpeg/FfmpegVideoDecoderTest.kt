package com.librestatic.lightforge.core.legacyvideo.ffmpeg

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegVideoDecoderTest {
    private class Packet(val data: ByteArray, val timeUs: Long, val key: Boolean)

    /** Minimal FLV walker: 9-byte header, then [prevSize(4)][tag header(11)][payload]. */
    private fun readVideoPackets(): List<Packet> {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sorenson_160x120.flv").use { it.readBytes() }
        val out = ArrayList<Packet>()
        var pos = 9
        while (pos + 4 + 11 <= bytes.size) {
            pos += 4
            val type = bytes[pos].toInt() and 0xFF
            val size = u24(bytes, pos + 1)
            val ts = u24(bytes, pos + 4) or ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val payload = pos + 11
            if (type == 9 && size > 1) {
                val head = bytes[payload].toInt() and 0xFF
                assertEquals("codec id must be Sorenson", 2, head and 0x0F)
                out += Packet(bytes.copyOfRange(payload + 1, payload + size), ts * 1000L, (head shr 4) == 1)
            }
            pos = payload + size
        }
        return out
    }

    private fun u24(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    /** Feeds all packets, collecting one callback per decoded frame. */
    private fun decodeAll(d: FfmpegVideoDecoder, packets: List<Packet>, onFrame: () -> Unit): Int {
        var frames = 0
        for (p in packets) {
            val buf = ByteBuffer.wrap(p.data)
            var status = d.sendPacket(buf, p.data.size, p.timeUs, p.key)
            if (status == FfmpegVideoDecoder.STATUS_EAGAIN) {
                while (d.receiveFrame() == FfmpegVideoDecoder.STATUS_OK) { frames++; onFrame() }
                status = d.sendPacket(buf, p.data.size, p.timeUs, p.key)
            }
            assertEquals(FfmpegVideoDecoder.STATUS_OK, status)
            while (d.receiveFrame() == FfmpegVideoDecoder.STATUS_OK) { frames++; onFrame() }
        }
        d.sendEndOfStream()
        while (d.receiveFrame() == FfmpegVideoDecoder.STATUS_OK) { frames++; onFrame() }
        return frames
    }

    @Test
    fun libraryLoads() = assertTrue(FfmpegVideoDecoder.isAvailable)

    @Test
    fun decodesSorensonToBitmapWithTestsrcColors() {
        val packets = readVideoPackets()
        assertTrue(packets.size >= 10)
        FfmpegVideoDecoder(FfmpegVideoDecoder.Codec.FLV1, null).use { d ->
            val bmp = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            var checked = false
            val frames = decodeAll(d, packets) {
                assertEquals(160, d.width)
                assertEquals(120, d.height)
                if (!checked) {
                    assertTrue(d.copyToBitmap(bmp))
                    checked = true
                }
            }
            assertEquals(packets.size, frames)
            val px = IntArray(160 * 120).also { bmp.getPixels(it, 0, 160, 0, 0, 160, 120) }
            assertTrue("non-uniform", px.toSet().size > 8)
            fun has(f: (Int, Int, Int) -> Boolean) = px.any { f((it shr 16) and 255, (it shr 8) and 255, it and 255) }
            assertTrue("reddish pixel", has { r, g, b -> r > 150 && g < 100 && b < 100 })
            assertTrue("greenish pixel", has { r, g, b -> g > 150 && r < 100 && b < 100 })
            assertTrue("bluish pixel", has { r, g, b -> b > 150 && r < 100 && g < 100 })
            // Wrong-size bitmap is rejected, not crashed on.
            assertFalse(d.copyToBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test
    fun yuvCopyAndSurfaceRender() {
        val packets = readVideoPackets()
        val reader = ImageReader.newInstance(160, 120, ImageFormat.YV12, 3)
        try {
            FfmpegVideoDecoder(FfmpegVideoDecoder.Codec.FLV1, null).use { d ->
                var rendered = 0
                var viaStatic = 0
                decodeAll(d, packets.take(6)) {
                    val yStride = 192
                    val uvStride = 96
                    val ch = (d.height + 1) / 2
                    val dst = ByteBuffer.allocateDirect(yStride * d.height + 2 * uvStride * ch)
                    assertTrue(d.copyFrameToYuv(dst, yStride, uvStride))
                    // Too-small stride/buffer must fail cleanly.
                    assertFalse(d.copyFrameToYuv(dst, 10, uvStride))
                    assertFalse(d.copyFrameToYuv(ByteBuffer.allocateDirect(16), yStride, uvStride))
                    val y = dst.duplicate().apply { position(0); limit(yStride * d.height) }.slice()
                    val u = dst.duplicate().apply { position(yStride * d.height); limit(position() + uvStride * ch) }.slice()
                    val v = dst.duplicate().apply { position(yStride * d.height + uvStride * ch) }.slice()
                    if (FfmpegVideoDecoder.renderYuvToSurface(reader.surface, arrayOf(y, u, v), intArrayOf(yStride, uvStride, uvStride), d.width, d.height)) viaStatic++
                    reader.acquireLatestImage()?.close()
                    if (d.renderToSurface(reader.surface)) rendered++
                    reader.acquireLatestImage()?.close()
                }
                assertTrue("static render", viaStatic > 0)
                assertTrue("current-frame render", rendered > 0)
                FfmpegVideoDecoder.releaseSurfaceCache()
            }
        } finally {
            reader.close()
            FfmpegVideoDecoder.releaseSurfaceCache()
        }
    }

    @Test
    fun flushAllowsRestartAtKeyFrame() {
        val packets = readVideoPackets()
        FfmpegVideoDecoder(FfmpegVideoDecoder.Codec.FLV1, null, threads = 2).use { d ->
            assertTrue(decodeAll(d, packets.take(5)) {} > 0)
            d.flush()
            assertEquals(FfmpegVideoDecoder.STATUS_EAGAIN, d.receiveFrame())
            assertTrue(decodeAll(d, packets) {} > 0)
        }
    }

    @Test
    fun closeIsIdempotentAndUseAfterCloseFails() {
        val d = FfmpegVideoDecoder(FfmpegVideoDecoder.Codec.FLV1, null)
        d.close()
        d.close()
        assertEquals(FfmpegVideoDecoder.STATUS_ERROR, d.receiveFrame())
        assertEquals(FfmpegVideoDecoder.STATUS_ERROR, d.sendPacket(ByteBuffer.allocate(4), 4, 0, true))
        assertFalse(d.copyToBitmap(Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)))
        d.flush()
    }

    @Test
    fun vp6OpensAndRejectsGarbage() {
        for (codec in listOf(FfmpegVideoDecoder.Codec.VP6F, FfmpegVideoDecoder.Codec.VP6A)) {
            FfmpegVideoDecoder(codec, byteArrayOf(0)).use { d ->
                val garbage = ByteArray(64) { (it * 37 + 11).toByte() }
                val status = d.sendPacket(ByteBuffer.wrap(garbage), garbage.size, 0, true)
                if (status == FfmpegVideoDecoder.STATUS_OK) {
                    assertTrue(d.receiveFrame() != FfmpegVideoDecoder.STATUS_OK)
                } else {
                    assertEquals(FfmpegVideoDecoder.STATUS_ERROR, status)
                }
                // Still usable afterwards.
                d.flush()
                assertEquals(FfmpegVideoDecoder.STATUS_ERROR, d.sendPacket(ByteBuffer.allocate(2), 8, 0, true))
            }
        }
    }
}
