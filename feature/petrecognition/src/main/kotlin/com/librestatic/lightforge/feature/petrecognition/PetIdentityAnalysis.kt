package com.librestatic.lightforge.feature.petrecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max

/** Bounded source pages and one decoded photo at a time; no photo bytes enter a network client. */
class PetIdentityAnalysis(context: Context, private val repository: PetIdentityRepository, private val models: PetModelStore) {
    private val resolver = context.applicationContext.contentResolver
    suspend fun run(
        signal: CancellationSignal,
        progress: (analyzed: Long, detected: Long, skipped: Long) -> Unit = { _, _, _ -> },
    ) = withContext(Dispatchers.IO) {
        var analyzed = 0L
        var detected = 0L
        var skipped = 0L
        var after: com.librestatic.lightforge.core.model.MediaKey? = null
        models.openEngine(signal).use { engine ->
            do {
                signal.throwIfCanceled()
                val summary = repository.currentSummary()
                check(summary.enabled && summary.modelFingerprint == PetModelCatalog.Fingerprint)
                val page = repository.eligibleSources(after, 64)
                require(page.items.size <= 64)
                for (source in page.items) {
                    signal.throwIfCanceled()
                    if (!repository.isCurrent(source) || repository.isAnalyzed(source, PetModelCatalog.Fingerprint)) continue
                    val bitmap = try {
                        decode(source)
                    } catch (_: IOException) {
                        // Missing or undecodable bytes (DecodeException) are permanent for this
                        // generation. Commit an empty analysis so retries move past this photo;
                        // a later edit bumps its generation and re-admits it.
                        if (repository.isCurrent(source) && repository.commitAnalysis(source, PetModelCatalog.Fingerprint, emptyList())) {
                            skipped++; progress(analyzed, detected, skipped)
                        }
                        continue
                    }
                    val observations = try { engine.analyze(bitmap, signal) } finally { bitmap.recycle() }
                    signal.throwIfCanceled()
                    if (repository.isCurrent(source) && repository.commitAnalysis(source, PetModelCatalog.Fingerprint, observations)) {
                        analyzed++; detected += observations.size; progress(analyzed, detected, skipped)
                    }
                }
                check(page.nextKey == null || page.nextKey != after) { "Pet source cursor did not advance" }
                after = page.nextKey
            } while (after != null)
        }
    }
    private fun decode(source: PetMediaSource): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, source.uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        // Orientation is applied by ImageDecoder; bound resident pixels, not original file size.
        val side = max(info.size.width, info.size.height)
        if (side > 1600) decoder.setTargetSize(max(1, info.size.width * 1600 / side), max(1, info.size.height * 1600 / side))
    }
}

data class PetSimilaritySuggestion(val identityId: String, val cosineSimilarity: Float)

/** Ranking only: no calibrated acceptance threshold or automatic identity claim. */
object PetSimilarityRanking {
    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == 512 && right.size == 512 && left.all(Float::isFinite) && right.all(Float::isFinite))
        return left.indices.sumOf { (left[it] * right[it]).toDouble() }.toFloat().coerceIn(-1f, 1f)
    }
    suspend fun suggest(repository: PetIdentityRepository, observation: PetObservationCard, embedding: FloatArray, limit: Int = 10): List<PetSimilaritySuggestion> {
        require(limit in 1..20 && observation.species != PetSpecies.Uncertain && !observation.excluded)
        val best = mutableMapOf<String, Float>()
        var after: String? = null
        do {
            val page = repository.referenceEmbeddingsPage(observation.species, observation.modelFingerprint, after, 64)
            require(page.items.size <= 64)
            page.items.forEach { reference ->
                if (reference.observationId != observation.id) {
                    val score = cosine(embedding, reference.embedding)
                    best[reference.identityId] = maxOf(best[reference.identityId] ?: -1f, score)
                }
            }
            // Keep memory bounded without losing the best score of any surviving candidate.
            if (best.size > limit) best.entries.sortedByDescending { it.value }.drop(limit).forEach { best.remove(it.key) }
            check(page.nextId == null || page.nextId != after)
            after = page.nextId
        } while (after != null)
        return best.map { PetSimilaritySuggestion(it.key, it.value) }.sortedByDescending { it.cosineSimilarity }
    }
}
