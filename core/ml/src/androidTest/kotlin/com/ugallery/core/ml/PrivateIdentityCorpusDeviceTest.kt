package com.ugallery.core.ml

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivateIdentityCorpusDeviceTest {
    @Test
    fun reportsSeparatedCalibrationAndHoldoutMetricsWithoutPersistingBiometrics() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val required = InstrumentationRegistry.getArguments().getString("m4IdentityCorpusRequired") == "true"
        val assets = instrumentation.context.assets
        val available = assets.list(AssetRoot).orEmpty().contains("metadata.csv")
        if (required) assertTrue("Run tools/corpora/prepare_private_identity_corpus.py first", available)
        else assumeTrue("Optional private identity corpus is not staged", available)

        val records = assets.open("$AssetRoot/metadata.csv").bufferedReader().useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).map { line ->
                val columns = line.split(',')
                Record(columns[0], columns[1], columns[2])
            }.toList()
        }
        val embedded = mutableListOf<Embedded>()
        val rejected = JSONArray()
        BundledMlKitFaceDetectionInference().use { detector ->
            SFaceLiteRtEmbeddingInference(instrumentation.targetContext).use { inference ->
                records.forEach { record ->
                    val bitmap = assets.open("$AssetRoot/${record.file}").use(BitmapFactory::decodeStream)
                    try {
                        val candidates = detector.infer(bitmap).map { it to FaceQualityFilter.evaluate(it, bitmap.width, bitmap.height) }
                        val selected = candidates.filter { it.second.accepted }.maxByOrNull { it.second.score }
                        if (selected == null) {
                            rejected.put(record.file)
                        } else {
                            val aligned = SFaceAligner.align(bitmap, selected.first, selected.second.crop)
                            try {
                                embedded += Embedded(record, CompactFaceEmbedding.quantize(inference.embed(aligned)))
                            } finally {
                                aligned.recycle()
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

        val calibration = embedded.filter { it.record.partition == Partition.Calibration }
        val validation = embedded.filter { it.record.partition == Partition.Validation }
        val holdout = embedded.filter { it.record.partition == Partition.Holdout }
        val calibrationPairs = pairs(calibration)
        val validationPairs = pairs(validation)
        val holdoutPairs = pairs(holdout)
        val developmentNegatives = (calibrationPairs + validationPairs).filterNot(PairScore::positive)
        val calibratedThreshold = (developmentNegatives.maxOf(PairScore::similarity) + SafetyMargin).coerceAtMost(0.99f)
        val threshold = PersonClusteringMlEngine.MinimumCosine
        val calibrationMetrics = metrics(calibrationPairs, threshold)
        val validationMetrics = metrics(validationPairs, threshold)
        val holdoutMetrics = metrics(holdoutPairs, threshold)
        val acceptedByIdentity = embedded.groupingBy { it.record.identity }.eachCount()

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("modelVersion", SFaceLiteRtEmbeddingInference.ModelVersion)
            .put("source", JSONObject(assets.open("$AssetRoot/source.json").bufferedReader().use { it.readText() }))
            .put("inputCount", records.size)
            .put("embeddedCount", embedded.size)
            .put("rejected", rejected)
            .put("acceptedByIdentity", JSONObject(acceptedByIdentity))
            .put("calibration", calibrationMetrics.toJson())
            .put("validation", validationMetrics.toJson())
            .put("holdout", holdoutMetrics.toJson())
            .put("threshold", threshold.toDouble())
            .put("calibratedThreshold", calibratedThreshold.toDouble())
            .put("safetyMargin", SafetyMargin.toDouble())
            .put("similarityDistributions", JSONObject()
                .put("calibrationPositive", distribution(calibrationPairs.filter(PairScore::positive).map(PairScore::similarity)))
                .put("calibrationNegative", distribution(calibrationPairs.filterNot(PairScore::positive).map(PairScore::similarity)))
                .put("validationPositive", distribution(validationPairs.filter(PairScore::positive).map(PairScore::similarity)))
                .put("validationNegative", distribution(validationPairs.filterNot(PairScore::positive).map(PairScore::similarity)))
                .put("holdoutPositive", distribution(holdoutPairs.filter(PairScore::positive).map(PairScore::similarity)))
                .put("holdoutNegative", distribution(holdoutPairs.filterNot(PairScore::positive).map(PairScore::similarity))))
        val reportFile = File(instrumentation.targetContext.getExternalFilesDir(null), "m4-private-identity-report.json")
        reportFile.writeText(report.toString(2))

        listOf("persona_01", "persona_02", "persona_03").forEach { identity ->
            assertTrue("Known identity $identity needs at least 8 accepted faces; report=$reportFile", acceptedByIdentity.getOrDefault(identity, 0) >= 8)
        }
        assertTrue("At least 10 distractors must survive the quality filter; report=$reportFile", acceptedByIdentity.getOrDefault("none", 0) >= 10)
        assertTrue("Calibration must have positive and negative pairs", calibrationMetrics.positivePairs > 0 && calibrationMetrics.negativePairs > 0)
        assertTrue("Validation must have positive and negative pairs", validationMetrics.positivePairs > 0 && validationMetrics.negativePairs > 0)
        assertTrue("Holdout must have positive and negative pairs", holdoutMetrics.positivePairs > 0 && holdoutMetrics.negativePairs > 0)
        assertTrue("Implementation threshold must not relax calibration", threshold >= calibratedThreshold)
        assertEquals("False merge gate failed; report=$reportFile", 0, holdoutMetrics.falseAccepts)
        assertEquals("Holdout true-accept gate failed; report=$reportFile", holdoutMetrics.positivePairs, holdoutMetrics.trueAccepts)
    }

    private fun pairs(values: List<Embedded>): List<PairScore> = buildList {
        values.indices.forEach { left ->
            for (right in left + 1 until values.size) {
                val a = values[left]
                val b = values[right]
                val positive = a.record.role == KnownIdentity && b.record.role == KnownIdentity &&
                    a.record.identity == b.record.identity
                add(PairScore(positive, CompactFaceEmbedding.cosine(a.vector, b.vector)))
            }
        }
    }

    private fun metrics(pairs: List<PairScore>, threshold: Float): Metrics {
        val positives = pairs.filter(PairScore::positive)
        val negatives = pairs.filterNot(PairScore::positive)
        return Metrics(
            positivePairs = positives.size,
            negativePairs = negatives.size,
            trueAccepts = positives.count { it.similarity >= threshold },
            falseAccepts = negatives.count { it.similarity >= threshold },
        )
    }

    private fun distribution(values: List<Float>): JSONObject {
        val sorted = values.sorted()
        return JSONObject()
            .put("count", sorted.size)
            .put("minimum", sorted.first().toDouble())
            .put("p05", percentile(sorted, 0.05).toDouble())
            .put("p50", percentile(sorted, 0.50).toDouble())
            .put("p95", percentile(sorted, 0.95).toDouble())
            .put("maximum", sorted.last().toDouble())
    }

    private fun percentile(values: List<Float>, fraction: Double): Float =
        values[((values.lastIndex * fraction).toInt()).coerceIn(values.indices)]

    private data class Record(val file: String, val role: String, val identity: String) {
        val partition: Partition
            get() {
                val number = file.substringAfterLast('/').substringBefore('.').filter(Char::isDigit).toInt()
                return when (number % 3) {
                    1 -> Partition.Calibration
                    2 -> Partition.Validation
                    else -> Partition.Holdout
                }
            }
    }
    private data class Embedded(val record: Record, val vector: ByteArray)
    private data class PairScore(val positive: Boolean, val similarity: Float)
    private data class Metrics(
        val positivePairs: Int,
        val negativePairs: Int,
        val trueAccepts: Int,
        val falseAccepts: Int,
    ) {
        fun toJson() = JSONObject()
            .put("positivePairs", positivePairs)
            .put("negativePairs", negativePairs)
            .put("trueAccepts", trueAccepts)
            .put("falseAccepts", falseAccepts)
            .put("trueAcceptRate", trueAccepts.toDouble() / positivePairs)
            .put("falseAcceptRate", falseAccepts.toDouble() / negativePairs)
    }
    private enum class Partition { Calibration, Validation, Holdout }

    private companion object {
        const val AssetRoot = "private_identity"
        const val KnownIdentity = "known_identity"
        const val SafetyMargin = 0.02f
    }
}
