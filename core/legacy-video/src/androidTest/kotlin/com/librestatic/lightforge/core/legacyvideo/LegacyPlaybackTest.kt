package com.librestatic.lightforge.core.legacyvideo

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Plays the Sorenson Spark sample end to end through ExoPlayer with the legacy renderer and extractor. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LegacyPlaybackTest {
    @Test
    fun playsSorensonFlvThroughExoPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "sorenson_160x120.flv")
        instrumentation.context.assets.open("sorenson_160x120.flv").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }

        val imageThread = HandlerThread("legacy-test-images").apply { start() }
        val reader = ImageReader.newInstance(160, 120, ImageFormat.YV12, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(imageThread.looper))

        val ended = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        val rendered = AtomicReference(0)
        val player = AtomicReference<ExoPlayer>()
        val counters = AtomicReference<DecoderCounters?>(null)
        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context, LegacyRenderersFactory(context))
                .setMediaSourceFactory(LegacyMediaSourceFactory.create(context))
                .setLooper(Looper.getMainLooper())
                .build()
            exo.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                    counters.set(decoderCounters)
                }
            })
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error.set(e)
                    ended.countDown()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        counters.get()?.let { it.ensureUpdated(); rendered.set(it.renderedOutputBufferCount) }
                        ended.countDown()
                    }
                }
            })
            exo.setVideoSurface(reader.surface)
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exo.prepare()
            exo.playWhenReady = true
            player.set(exo)
        }
        try {
            val finished = ended.await(30, TimeUnit.SECONDS)
            if (!finished) {
                val diag = AtomicReference("")
                instrumentation.runOnMainSync {
                    val exo = player.get()
                    counters.get()?.ensureUpdated()
                    diag.set("buf=${exo.bufferedPosition} loading=${exo.isLoading} playing=${exo.isPlaying} state=${exo.playbackState} pos=${exo.currentPosition} dur=${exo.duration} " +
                        "rendered=${counters.get()?.renderedOutputBufferCount} dropped=${counters.get()?.droppedBufferCount} " +
                        "skipped=${counters.get()?.skippedOutputBufferCount} inputs=${counters.get()?.queuedInputBufferCount}")
                }
                val stacks = Thread.getAllStackTraces().filter { it.key.name.contains("ExoPlayer") || it.key.name.contains("Decode") || it.key.name.contains("Legacy") }
                    .map { "${it.key.name}: ${it.value.take(8).joinToString(" < ")}" }
                assertTrue("playback did not finish: ${diag.get()} $stacks", false)
            }
            assertNull("player error: ${error.get()}", error.get())
            assertTrue("no frames rendered", rendered.get() > 0)
        } finally {
            instrumentation.runOnMainSync { player.get()?.release() }
            reader.close()
            imageThread.quitSafely()
            file.delete()
        }
    }
}
