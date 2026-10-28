package com.librestatic.lightforge.feature.viewer.shared

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feeds a four-colour test pattern into the renderer and reads it back from two ImageReader
 * outputs, one upright and one turned 180 degrees.
 */
@RunWith(AndroidJUnit4::class)
class MirroredVideoRendererDeviceTest {
    private val readerThread = HandlerThread("mirror-test-reader")
    private lateinit var readerHandler: Handler
    private var renderer: MirroredVideoRenderer? = null

    @Before
    fun setUp() {
        readerThread.start()
        readerHandler = Handler(readerThread.looper)
    }

    @After
    fun tearDown() {
        renderer?.release()
        readerThread.quitSafely()
    }

    private class Capture(width: Int, height: Int, handler: Handler) {
        val reader: ImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val gotFrame = CountDownLatch(1)
        @Volatile var lastPixels: IntArray? = null

        init {
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val pixels = IntArray(width * height)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            val r = buffer.get(offset).toInt() and 0xFF
                            val g = buffer.get(offset + 1).toInt() and 0xFF
                            val b = buffer.get(offset + 2).toInt() and 0xFF
                            pixels[y * width + x] = Color.rgb(r, g, b)
                        }
                    }
                    lastPixels = pixels
                }
                gotFrame.countDown()
            }, handler)
        }
    }

    private fun drawPattern(renderer: MirroredVideoRenderer, size: Int) {
        val canvas: Canvas = renderer.inputSurface.lockCanvas(null)
        val half = size / 2f
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, half, half, paint)
        paint.color = Color.GREEN
        canvas.drawRect(half, 0f, size.toFloat(), half, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, half, half, size.toFloat(), paint)
        paint.color = Color.YELLOW
        canvas.drawRect(half, half, size.toFloat(), size.toFloat(), paint)
        renderer.inputSurface.unlockCanvasAndPost(canvas)
    }

    private fun assertNear(expected: Int, actual: Int, label: String) {
        val tolerance = 40
        assertTrue(
            "$label: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}",
            Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    @Test
    fun oneDecodeFeedsBothOutputsAndTheRotatedOneIsTurned() {
        val size = 64
        val mirror = MirroredVideoRenderer().also { renderer = it }
        mirror.setInputBufferSize(size, size)
        mirror.setVideoSize(size.toFloat(), size.toFloat())
        val upright = Capture(size, size, readerHandler)
        val turned = Capture(size, size, readerHandler)
        mirror.setOutput("upright", upright.reader.surface, size, size, 0)
        mirror.setOutput("turned", turned.reader.surface, size, size, 180)

        drawPattern(mirror, size)

        assertTrue("upright output got no frame", upright.gotFrame.await(5, TimeUnit.SECONDS))
        assertTrue("rotated output got no frame", turned.gotFrame.await(5, TimeUnit.SECONDS))
        val a = upright.lastPixels!!
        val b = turned.lastPixels!!
        val q = size / 4
        val far = size - q
        assertNear(Color.RED, a[q * size + q], "upright top-left")
        assertNear(Color.GREEN, a[q * size + far], "upright top-right")
        assertNear(Color.BLUE, a[far * size + q], "upright bottom-left")
        assertNear(Color.YELLOW, a[far * size + far], "upright bottom-right")
        assertNear(Color.YELLOW, b[q * size + q], "turned top-left")
        assertNear(Color.BLUE, b[q * size + far], "turned top-right")
        assertNear(Color.GREEN, b[far * size + q], "turned bottom-left")
        assertNear(Color.RED, b[far * size + far], "turned bottom-right")
        upright.reader.close()
        turned.reader.close()
    }

    @Test
    fun outputAddedAfterTheFrameShowsTheLastFrameAndRemovalIsClean() {
        val size = 64
        val mirror = MirroredVideoRenderer().also { renderer = it }
        mirror.setInputBufferSize(size, size)
        mirror.setVideoSize(size.toFloat(), size.toFloat())
        drawPattern(mirror, size)
        // Let the frame be latched with no output attached.
        Thread.sleep(300)

        val late = Capture(size, size, readerHandler)
        mirror.setOutput("late", late.reader.surface, size, size, 0)
        assertTrue("late output got no frame", late.gotFrame.await(5, TimeUnit.SECONDS))
        assertNear(Color.RED, late.lastPixels!![size / 4 * size + size / 4], "late top-left")

        mirror.removeOutput("late")
        late.reader.close()
        mirror.release()
        mirror.release()
    }
}
