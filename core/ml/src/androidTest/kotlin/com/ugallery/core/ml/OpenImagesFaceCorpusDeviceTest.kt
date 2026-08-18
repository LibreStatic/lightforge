package com.ugallery.core.ml

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OpenImagesFaceCorpusDeviceTest {
    @Test
    fun bundledDetectorMeetsLicensedPhotographicRecallGate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val required = InstrumentationRegistry.getArguments().getString("m4FaceCorpusRequired") == "true"
        val assets = instrumentation.context.assets
        val imageFiles = assets.list("images").orEmpty().filter { it.endsWith(".jpg") }
        if (required) {
            assertTrue("Run tools/corpora/prepare_open_images_miap_face_corpus.py first", imageFiles.size == ExpectedCount)
        } else {
            assumeTrue("Optional M4 photographic corpus is not prepared", imageFiles.size == ExpectedCount)
        }

        val manifest = JSONObject(assets.open("manifest.json").bufferedReader().use { it.readText() })
        val entries = manifest.getJSONArray("entries")
        val latencies = mutableListOf<Long>()
        val buckets = linkedMapOf<String, Bucket>()
        val results = org.json.JSONArray()
        BundledMlKitFaceDetectionInference().use { detector ->
            repeat(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val bitmap = assets.open("images/${entry.getString("file")}").use { BitmapFactory.decodeStream(it) }
                val started = SystemClock.elapsedRealtimeNanos()
                val detections = detector.infer(bitmap)
                latencies += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
                val expected = entry.getJSONArray("box").let {
                    RectF(
                        it.getDouble(0).toFloat() * bitmap.width,
                        it.getDouble(1).toFloat() * bitmap.height,
                        it.getDouble(2).toFloat() * bitmap.width,
                        it.getDouble(3).toFloat() * bitmap.height,
                    )
                }
                val bestIou = detections.maxOfOrNull { iou(expected, RectF(it.boundingBox)) } ?: 0f
                val matched = bestIou >= MinimumIou
                val bucketName = if (entry.getString("condition") == "clean") {
                    "${entry.getString("genderPresentation")}/${entry.getString("agePresentation")}" 
                } else {
                    entry.getString("condition")
                }
                buckets.getOrPut(bucketName, ::Bucket).record(matched)
                results.put(
                    JSONObject()
                        .put("imageId", entry.getString("imageId"))
                        .put("bucket", bucketName)
                        .put("matched", matched)
                        .put("bestIou", bestIou.toDouble())
                        .put("detections", detections.size),
                )
                bitmap.recycle()
            }
        }

        val total = buckets.values.sumOf { it.total }
        val matches = buckets.values.sumOf { it.matches }
        val sortedLatency = latencies.sorted()
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("modelVersion", FaceDetectionMlEngine.ModelVersion)
            .put("corpus", manifest.getString("dataset"))
            .put("imageCount", total)
            .put("matched", matches)
            .put("recall", matches.toDouble() / total)
            .put("minimumIou", MinimumIou.toDouble())
            .put("p50Millis", percentile(sortedLatency, 0.50))
            .put("p95Millis", percentile(sortedLatency, 0.95))
            .put("buckets", JSONObject().also { output ->
                buckets.forEach { (name, bucket) ->
                    output.put(name, JSONObject().put("total", bucket.total).put("matched", bucket.matches).put("recall", bucket.recall))
                }
            })
            .put("results", results)
        val reportFile = File(instrumentation.targetContext.getExternalFilesDir(null), "m4-open-images-face-corpus-report.json")
        reportFile.writeText(report.toString(2))

        assertTrue("Overall recall ${matches.toDouble() / total}; report=$reportFile", matches.toDouble() / total >= MinimumOverallRecall)
        buckets.filterKeys { it != "occluded" && it != "truncated" }.forEach { (name, bucket) ->
            assertTrue("Clean stratum $name recall ${bucket.recall}; report=$reportFile", bucket.recall >= MinimumCleanStratumRecall)
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF(a)
        if (!intersection.intersect(b)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        val union = a.width() * a.height() + b.width() * b.height() - intersectionArea
        return if (union <= 0f) 0f else intersectionArea / union
    }

    private fun percentile(values: List<Long>, fraction: Double): Long =
        values[((values.lastIndex * fraction).toInt()).coerceIn(values.indices)]

    private class Bucket(var total: Int = 0, var matches: Int = 0) {
        val recall: Double get() = if (total == 0) 0.0 else matches.toDouble() / total
        fun record(matched: Boolean) { total += 1; if (matched) matches += 1 }
    }

    private companion object {
        const val ExpectedCount = 60
        const val MinimumIou = 0.30f
        const val MinimumOverallRecall = 0.80
        const val MinimumCleanStratumRecall = 0.625
    }
}
