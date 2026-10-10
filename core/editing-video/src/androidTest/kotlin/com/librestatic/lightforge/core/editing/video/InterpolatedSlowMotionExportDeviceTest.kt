package com.librestatic.lightforge.core.editing.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exports a 1 s synthetic 30 fps clip with and without synthesized in-between frames. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class InterpolatedSlowMotionExportDeviceTest {
    private lateinit var context: Context
    private lateinit var source: File
    private val outputs = mutableListOf<File>()

    @Before
    fun createSource() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        source = File(context.cacheDir, "interp-source-${System.nanoTime()}.mp4")
        val width = 1280
        val height = 720
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        IntermediateVideoWriter(source, width, height, SourceFps.toFloat()).use { writer ->
            for (frame in 0 until SourceFrames) {
                paint.shader = LinearGradient(
                    frame * 8f, 0f, frame * 8f + width, height.toFloat(),
                    Color.rgb(20, 40, 160), Color.rgb(230, 200, 40), Shader.TileMode.MIRROR,
                )
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.shader = null
                paint.color = Color.WHITE
                canvas.drawRect(40f + frame * 30f, 300f, 200f + frame * 30f, 420f, paint)
                writer.write(bitmap)
            }
            writer.finish()
        }
        bitmap.recycle()
    }

    @After
    fun cleanUp() {
        source.delete()
        outputs.forEach(File::delete)
    }

    @Test
    fun globalHalfSpeedWithInterpolationDoublesTheFrames() = runBlocking {
        val result = export(VideoEditRecipe(speed = 0.5f), "global-interp")
        assertEquals(2_000.0, result.durationMs, 120.0)
        assertEquals(2.0 * SourceFrames, result.frames.toDouble(), 3.0)
    }

    @Test
    fun globalHalfSpeedWithoutInterpolationRepeatsFrames() = runBlocking {
        val result = export(VideoEditRecipe(speed = 0.5f, interpolateSlowMotion = false), "global-plain")
        assertEquals(2_000.0, result.durationMs, 120.0)
        assertTrue("expected about the source frame count, got ${result.frames}", result.frames <= SourceFrames + 3)
    }

    @Test
    fun segmentWithInterpolationAddsFramesOnlyInsideTheSegment() = runBlocking {
        val segment = SlowMotionSegment(startMillis = 250, endMillis = 750, speed = 0.5f)
        val result = export(VideoEditRecipe(slowMotionSegments = listOf(segment)), "segment-interp")
        // 0.5 s at 1x + 0.5 s at 0.5x.
        assertEquals(1_500.0, result.durationMs, 120.0)
        assertTrue("frames ${result.frames}", result.frames in (SourceFrames + SourceFps / 2 - 3)..(SourceFrames + SourceFps / 2 + 3))
    }

    private class Measured(val durationMs: Double, val frames: Int)

    private suspend fun export(recipe: VideoEditRecipe, label: String): Measured {
        val output = File(context.cacheDir, "interp-$label-${System.nanoTime()}.mp4").also(outputs::add)
        val startedAt = System.nanoTime()
        Media3VideoExporter(context).export(
            VideoExportRequest(input = Uri.fromFile(source), output = output, recipe = recipe),
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
        val measured = measure(output)
        Log.i(Tag, "$label: export ${"%.0f".format(elapsedMs)} ms for 1 s of source " +
            "(${"%.1f".format(elapsedMs / 1_000.0)} s per source second), output " +
            "${"%.0f".format(measured.durationMs)} ms, ${measured.frames} frames")
        return measured
    }

    private fun measure(file: File): Measured {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            extractor.selectTrack(track)
            val durationUs = extractor.getTrackFormat(track).getLong(MediaFormat.KEY_DURATION)
            var frames = 0
            while (extractor.sampleTime >= 0) {
                frames += 1
                if (!extractor.advance()) break
            }
            return Measured(durationUs / 1_000.0, frames)
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val Tag = "InterpolatedExport"
        const val SourceFps = 30
        const val SourceFrames = 30
    }
}
