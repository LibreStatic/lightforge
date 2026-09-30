package com.librestatic.lightforge.feature.viewer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoPlaybackDeviceTest {
    @Test
    fun h264AndHevcPrepareWithOnePlayerAndBackgroundPauses() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        lateinit var controller: VideoViewerController
        instrumentation.runOnMainSync { controller = VideoViewerController(context) }
        val files = listOf("h264.mp4", "hevc.mp4").map { name ->
            File(context.cacheDir, "lightforge-m2-$name").also { file ->
                instrumentation.context.assets.open(name).use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
        }
        try {
            files.forEach { file ->
                val uri = Uri.fromFile(file)
                instrumentation.runOnMainSync { controller.select(uri) }
                val state = withTimeout(10_000) {
                    controller.state.first {
                        (it is VideoViewerState.Ready && it.uri == uri) ||
                            (it is VideoViewerState.Failure && it.uri == uri)
                    }
                }
                assertTrue("codec failed: $state", state is VideoViewerState.Ready)
            }

            instrumentation.runOnMainSync { controller.play() }
            val playing = withTimeout(5_000) {
                controller.state.first { it is VideoViewerState.Ready && it.isPlaying }
            } as VideoViewerState.Ready
            assertTrue(playing.isPlaying)
            instrumentation.runOnMainSync { controller.onBackground() }
            val paused = withTimeout(5_000) {
                controller.state.first { it is VideoViewerState.Ready && !it.isPlaying }
            } as VideoViewerState.Ready
            assertFalse(paused.isPlaying)
        } finally {
            instrumentation.runOnMainSync { controller.close() }
            assertTrue(controller.state.value is VideoViewerState.Released)
            files.forEach(File::delete)
        }
    }
}
