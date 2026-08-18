package com.ugallery.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.Environment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SFaceLiteRtEmbeddingDeviceTest {
    @Test
    fun bundledModelMatchesGoldenAndReportsAvailableAccelerators() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = goldenBitmap()
        val environment = Environment.create(context)
        val available: Set<Accelerator> = try {
            environment.getAvailableAccelerators()
        } finally {
            environment.close()
        }
        val results = JSONArray()
        var cpuVector: ByteArray? = null
        for (accelerator in listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)) {
            val result = JSONObject().put("accelerator", accelerator.name)
            if (accelerator !in available) {
                results.put(result.put("status", "unavailable"))
                continue
            }
            runCatching {
                SFaceLiteRtEmbeddingInference(context, accelerator).use { inference ->
                    val latencies = mutableListOf<Long>()
                    val vectors = List(Iterations) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        val vector = CompactFaceEmbedding.quantize(inference.embed(bitmap))
                        latencies += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                        vector
                    }
                    vectors.drop(1).forEach { assertArrayEquals(vectors.first(), it) }
                    if (accelerator == Accelerator.CPU) cpuVector = vectors.first()
                    result.put("status", "passed")
                        .put("p50Millis", percentile(latencies.sorted(), 0.50))
                        .put("p95Millis", percentile(latencies.sorted(), 0.95))
                        .put("quantizedSha256", sha256(vectors.first()))
                }
            }.onFailure { failure ->
                result.put("status", "compile_or_run_failed")
                    .put("error", failure.javaClass.simpleName)
                    .put("message", failure.message.orEmpty().take(300))
            }
            results.put(result)
        }
        bitmap.recycle()

        val cpu = requireNotNull(cpuVector) { "LiteRT CPU is mandatory" }
        assertEquals(GoldenQuantizedSha256, sha256(cpu))
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("modelVersion", SFaceLiteRtEmbeddingInference.ModelVersion)
            .put("availableAccelerators", JSONArray(available.map(Accelerator::name)))
            .put("results", results)
        val reportFile = File(context.getExternalFilesDir(null), "m4-sface-litert-benchmark.json")
        reportFile.writeText(report.toString(2))
        assertTrue(reportFile.length() > 0)
    }

    private fun goldenBitmap(): Bitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888).also { bitmap ->
        val pixels = IntArray(112 * 112)
        repeat(112) { y ->
            repeat(112) { x ->
                pixels[y * 112 + x] = Color.rgb((x * 2 + y) % 256, (x + y * 3) % 256, (x * 5 + y * 7) % 256)
            }
        }
        bitmap.setPixels(pixels, 0, 112, 0, 0, 112, 112)
    }

    private fun percentile(values: List<Long>, fraction: Double): Long =
        values[((values.lastIndex * fraction).toInt()).coerceIn(values.indices)]

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val Iterations = 8
        const val GoldenQuantizedSha256 = "6711c947fb26dfc5ce267beb8854fa9085d769f5a61208f0c2709414a90b54b5"
    }
}
