package com.librestatic.lightforge.core.frameinterpolation

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compares [VideoFrameReader] frames of HLG clips with the platform's tone-mapped
 * `getScaledFrameAtTime`. Clips are read from `/data/local/tmp/hdr` (any .mp4) (push them with adb);
 * the tests are skipped when none are there. Numbers go to logcat with the `LIGHTFORGE_HDR` tag.
 */
@RunWith(AndroidJUnit4::class)
class HdrFrameReaderDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Copies the pushed clips into the app cache: the media server cannot open `/data/local/tmp`. */
    private val clips: List<File> by lazy {
        File("/data/local/tmp/hdr").listFiles { f -> f.extension == "mp4" }?.sortedBy { it.name }.orEmpty().map { source ->
            File(context.cacheDir, source.name).also { target -> if (!target.exists()) source.copyTo(target) }
        }
    }

    @Test
    fun hdrDecoderOutputIsReported() {
        assumeTrue(clips.isNotEmpty())
        clips.forEach { clip ->
            for (request in listOf(false, true)) {
                val extractor = MediaExtractor().apply { setDataSource(clip.path) }
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                if (request) format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val info = MediaCodec.BufferInfo()
                    var done = false
                    var report = "no output"
                    var guard = 0
                    while (!done && guard++ < 2000) {
                        val input = codec.dequeueInputBuffer(10_000)
                        if (input >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                            if (size < 0) codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            else { codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        }
                        val out = codec.dequeueOutputBuffer(info, 10_000)
                        if (out >= 0) {
                            val image = codec.getOutputImage(out)!!
                            val p = image.planes
                            val of = codec.outputFormat
                            fun key(k: String) = if (of.containsKey(k)) of.getInteger(k) else null
                            report = "imageFormat=${image.format} yPixel=${p[0].pixelStride} yRow=${p[0].rowStride} " +
                                "uPixel=${p[1].pixelStride} uRow=${p[1].rowStride} yBuf=${p[0].buffer.remaining()} " +
                                "outTransfer=${key(MediaFormat.KEY_COLOR_TRANSFER)} outStd=${key(MediaFormat.KEY_COLOR_STANDARD)} " +
                                "outRange=${key(MediaFormat.KEY_COLOR_RANGE)} outColorFormat=${key(MediaFormat.KEY_COLOR_FORMAT)} " +
                                "codec=${codec.name}"
                            codec.releaseOutputBuffer(out, false)
                            done = true
                        }
                    }
                    Log.i(Tag, "probe ${clip.name} request=$request $report")
                } catch (e: Exception) {
                    Log.i(Tag, "probe ${clip.name} request=$request failed: $e")
                } finally {
                    runCatching { codec.release() }
                    extractor.release()
                }
            }
        }
    }

    @Test
    fun decoderToneMappedFramesMatchPlatform() = compare(decoderToneMapping = true, maxDiff = 20.0, maxShadowDiff = 14.0, maxMeanDiff = 20.0)

    @Test
    fun softwareToneMappedFramesMatchPlatform() = compare(decoderToneMapping = false, maxDiff = 4.0, maxShadowDiff = 3.0, maxMeanDiff = 4.0)

    private fun compare(decoderToneMapping: Boolean, maxDiff: Double, maxShadowDiff: Double, maxMeanDiff: Double) = runBlocking {
        assumeTrue(clips.isNotEmpty())
        val failures = mutableListOf<String>()
        clips.forEach { clip ->
            val retriever = MediaMetadataRetriever().apply { setDataSource(context, Uri.fromFile(clip)) }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("no duration for ${clip.name}")
            VideoFrameReader(context, Uri.fromFile(clip), 1280, decoderToneMapping = decoderToneMapping).use { reader ->
                for (fraction in listOf(0.2, 0.5, 0.8)) {
                    val time = (duration * fraction).toLong()
                    reader.frameAt(time) // warm up the decoder so the timing below is steady state
                    val started = System.nanoTime()
                    val ours = reader.frameAt(time + 100) ?: error("reader frame null at $time")
                    val ms = (System.nanoTime() - started) / 1_000_000
                    val ref = retriever.getScaledFrameAtTime(time * 1000 + 100_000, MediaMetadataRetriever.OPTION_CLOSEST, ours.width, ours.height)
                        ?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
                        ?: error("platform frame null at $time")
                    val a = LumaStats.of(ours)
                    val b = LumaStats.of(ref)
                    val diff = LumaStats.meanAbsLumaDiff(ours, ref)
                    Log.i(
                        Tag,
                        "compare decoderToneMapping=$decoderToneMapping ${clip.name} t=${time + 100} " +
                            "ours(mean=${a.mean.toInt()} p1=${a.p1} p5=${a.p5} p50=${a.p50} p95=${a.p95}) " +
                            "platform(mean=${b.mean.toInt()} p1=${b.p1} p5=${b.p5} p50=${b.p50} p95=${b.p95}) " +
                            "meanAbsLumaDiff=${diff.toInt()} frameAtMs=$ms convertMs=${reader.lastConvertNanos / 1_000_000.0}",
                    )
                    if (kotlin.math.abs(a.mean - b.mean) > maxMeanDiff || kotlin.math.abs(a.p5 - b.p5) > maxShadowDiff || diff > maxDiff) {
                        failures += "${clip.name}@$time mean ${a.mean} vs ${b.mean}, p5 ${a.p5} vs ${b.p5}, diff $diff"
                    }
                    ours.recycle(); ref.recycle()
                }
            }
            retriever.release()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private class LumaStats(val mean: Double, val p1: Int, val p5: Int, val p50: Int, val p95: Int) {
        companion object {
            fun lumas(bitmap: Bitmap): IntArray {
                val px = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return IntArray(px.size) { i ->
                    val c = px[i]
                    ((((c shr 16) and 255) * 2126 + ((c shr 8) and 255) * 7152 + (c and 255) * 722) / 10000)
                }
            }

            fun of(bitmap: Bitmap): LumaStats {
                val l = lumas(bitmap)
                val hist = IntArray(256).also { h -> l.forEach { h[it]++ } }
                fun pct(p: Double): Int {
                    var acc = 0
                    for (i in 0..255) { acc += hist[i]; if (acc >= p * l.size) return i }
                    return 255
                }
                return LumaStats(l.average(), pct(0.01), pct(0.05), pct(0.5), pct(0.95))
            }

            fun meanAbsLumaDiff(a: Bitmap, b: Bitmap): Double {
                val x = lumas(a)
                val y = lumas(b)
                return x.indices.sumOf { kotlin.math.abs(x[it] - y[it]).toLong() }.toDouble() / x.size
            }
        }
    }

    private companion object {
        const val Tag = "LIGHTFORGE_HDR"
    }
}
