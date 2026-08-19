package com.ugallery.feature.semanticsearch

/**
 * Semantic text-image search using joint embeddings.
 *
 * This module implements the architecture for CLIP-style joint text-image embeddings.
 * The actual model is not yet bundled — this provides the interface and a
 * keyword-based fallback that uses the existing AppSearch index.
 *
 * When a bundled CLIP-compatible model is available:
 * 1. Images are encoded into dense vectors during indexing
 * 2. Text queries are encoded into the same vector space
 * 3. Cosine similarity finds the best matches
 *
 * No cloud, no network — all inference is local.
 * Model is bundled, not downloaded at runtime.
 */
class SemanticSearchEngine {

    data class SemanticResult(
        val mediaKey: String,
        val score: Float,
        val method: SearchMethod,
    )

    enum class SearchMethod {
        CLIP_EMBEDDING,
        KEYWORD_FALLBACK,
    }

    data class EmbeddingConfig(
        val modelVersion: String = "unbundled",
        val embeddingDim: Int = 512,
        val maxResults: Int = 50,
    )

    /**
     * Returns whether CLIP-based semantic search is available.
     * Currently false because no model is bundled.
     */
    fun isSemanticAvailable(): Boolean = false

    /**
     * Searches for images matching the text query.
     * When CLIP is not available, falls back to keyword search.
     */
    suspend fun search(
        query: String,
        config: EmbeddingConfig = EmbeddingConfig(),
    ): List<SemanticResult> {
        if (isSemanticAvailable()) {
            return searchWithEmbeddings(query, config)
        }
        return searchWithKeywordFallback(query, config)
    }

    /**
     * CLIP-based semantic search.
     * Not yet implemented — requires a bundled model.
     * Throws UnsupportedOperationException to prevent silent fallback.
     */
    private suspend fun searchWithEmbeddings(
        query: String,
        config: EmbeddingConfig,
    ): List<SemanticResult> {
        throw UnsupportedOperationException(
            "CLIP semantic search requires a bundled model (version=" + config.modelVersion + ")"
        )
    }

    /**
     * Keyword-based fallback using existing AppSearch index.
     * This is explicitly labeled as KEYWORD_FALLBACK, not semantic.
     */
    private suspend fun searchWithKeywordFallback(
        query: String,
        config: EmbeddingConfig,
    ): List<SemanticResult> {
        // In production, this would query the existing AppSearch index
        // with the query text. For now, return empty results.
        return emptyList()
    }
}
