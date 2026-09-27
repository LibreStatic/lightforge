package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RifeFrameInterpolatorDeviceTest {
    @Test
    fun bundledModelInterpolatesLocally() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val started = System.nanoTime()
        RifeFrameInterpolator(context).use { interpolator ->
            val output = interpolator.interpolate(first, second, 0.5f)
            try {
                assertEquals(64, output.width)
                assertEquals(64, output.height)
                assertFalse(output.isRecycled)
                assertTrue(output.getPixel(32, 32) ushr 24 != 0)
                println(
                    "LIGHTFORGE_RIFE backend=${interpolator.capability.backend} " +
                        "coldLatencyMs=${(System.nanoTime() - started) / 1_000_000}",
                )
            } finally {
                output.recycle()
            }
            val warmStarted = System.nanoTime()
            interpolator.interpolate(first, second, 0.5f).recycle()
            println("LIGHTFORGE_RIFE warmLatencyMs=${(System.nanoTime() - warmStarted) / 1_000_000}")
        }
        first.recycle()
        second.recycle()
    }
}
