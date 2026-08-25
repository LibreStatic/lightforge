package com.ugallery.feature.semanticsearch

import android.content.Context
import android.provider.MediaStore
import com.ugallery.core.database.GalleryDatabase
import com.ugallery.core.database.SemanticEmbeddingCandidate
import com.ugallery.core.model.MediaKey
import com.ugallery.core.model.MediaKind
import com.ugallery.core.search.MediaSearchHit
import com.ugallery.core.search.SearchRankingDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

class SemanticSearchEngine(
    private val context: Context,
    private val database: GalleryDatabase,
    private val manager: SemanticModelManager,
) : Closeable {
    private var inferenceModelId: String? = null
    private var inference: SemanticEmbeddingInference? = null

    suspend fun search(query: String, limit: Int = 500): List<MediaSearchHit> = withContext(Dispatchers.IO) {
        require(limit in 1..500)
        val state = manager.state.value
        if (!state.enabled || state.activeModelId == null) return@withContext emptyList()
        val installed = manager.activeModel() ?: return@withContext emptyList()
        val indexId = manager.activeIndexId() ?: return@withContext emptyList()
        val index = database.semanticDao().index(indexId)?.takeIf { it.status == "active" } ?: return@withContext emptyList()
        val encodedQuery = CompactSemanticEmbedding.quantize(activeInference(installed).embedText(query))
        val candidates = candidates(indexId, encodedQuery, installed.descriptor.version)
        val ranked = candidates.asSequence()
            .filterNot { candidate -> candidate.quantizedVector.all { it == 0.toByte() } }
            .map { it to CompactSemanticEmbedding.cosine(encodedQuery, it.quantizedVector) }
            .filter { (_, score) -> score >= MinimumCosine }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
        if (ranked.isEmpty()) return@withContext emptyList()
        fun key(volume: String, id: Long) = "$volume:$id"
        val mediaByKey = database.semanticDao().mediaForEncodedKeys(
            ranked.map { (candidate, _) -> key(candidate.volumeName, candidate.mediaStoreId) },
        ).associateBy { media -> key(media.volumeName, media.mediaStoreId) }
        ranked.mapNotNull { (candidate, score) ->
            mediaByKey[key(candidate.volumeName, candidate.mediaStoreId)]?.let { media ->
                MediaSearchHit(
                    key = MediaKey(media.volumeName, media.mediaStoreId),
                    kind = if (media.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaKind.Video else MediaKind.Image,
                    displayName = media.displayName,
                    timelineSortMillis = media.timelineSortMillis,
                    generationModified = media.generationModified,
                    favorite = media.isFavorite,
                    debug = SearchRankingDebug(query, "semantic:${index.modelId}", score.toDouble(), listOf("semanticEmbedding")),
                    durationMillis = media.durationMillis,
                )
            }
        }
    }

    private suspend fun candidates(indexId: String, query: ByteArray, modelVersion: String): List<SemanticEmbeddingCandidate> {
        val dao = database.semanticDao()
        val bands = SemanticLsh.bands(query, modelVersion)
        val unique = linkedMapOf<String, SemanticEmbeddingCandidate>()
        suspend fun collect(radius: Int) {
            bands.forEachIndexed { band, bucket ->
                val probes = SemanticLsh.probes(bucket, radius)
                val page = when (band) {
                    0 -> dao.band0(indexId, probes, CandidatesPerBand)
                    1 -> dao.band1(indexId, probes, CandidatesPerBand)
                    2 -> dao.band2(indexId, probes, CandidatesPerBand)
                    3 -> dao.band3(indexId, probes, CandidatesPerBand)
                    4 -> dao.band4(indexId, probes, CandidatesPerBand)
                    5 -> dao.band5(indexId, probes, CandidatesPerBand)
                    6 -> dao.band6(indexId, probes, CandidatesPerBand)
                    else -> dao.band7(indexId, probes, CandidatesPerBand)
                }
                page.forEach { candidate ->
                    if (unique.size < MaximumCandidates) unique["${candidate.volumeName}:${candidate.mediaStoreId}"] = candidate
                }
            }
        }
        collect(1)
        if (unique.size < MinimumCandidates) collect(2)
        if (unique.size < MinimumCandidates) {
            var offset = 0
            while (unique.size < MaximumCandidates) {
                val page = dao.embeddingPage(indexId, offset, ScanPageSize)
                if (page.isEmpty()) break
                page.forEach { unique["${it.volumeName}:${it.mediaStoreId}"] = it }
                offset += page.size
            }
        }
        return unique.values.toList()
    }

    @Synchronized
    private fun activeInference(model: InstalledSemanticModel): SemanticEmbeddingInference {
        if (inferenceModelId != model.descriptor.id) {
            inference?.close()
            inference = LiteRtSemanticEmbeddingInference(context, model)
            inferenceModelId = model.descriptor.id
        }
        return requireNotNull(inference)
    }

    override fun close() {
        inference?.close()
        inference = null
        inferenceModelId = null
    }

    private companion object {
        const val MinimumCandidates = 200
        const val MaximumCandidates = 20_000
        const val CandidatesPerBand = 2_500
        const val ScanPageSize = 2_000
        const val MinimumCosine = 0.16f
    }
}
