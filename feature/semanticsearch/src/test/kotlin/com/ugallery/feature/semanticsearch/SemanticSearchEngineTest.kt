package com.ugallery.feature.semanticsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticSearchEngineTest {

    private val engine = SemanticSearchEngine()

    @Test
    fun isSemanticAvailable_withoutModel_returnsFalse() {
        assertFalse(engine.isSemanticAvailable())
    }

    @Test
    fun searchMethod_clipEmbedding_isDefined() {
        assertEquals("CLIP_EMBEDDING", SemanticSearchEngine.SearchMethod.CLIP_EMBEDDING.name)
    }

    @Test
    fun searchMethod_keywordFallback_isDefined() {
        assertEquals("KEYWORD_FALLBACK", SemanticSearchEngine.SearchMethod.KEYWORD_FALLBACK.name)
    }

    @Test
    fun embeddingConfig_defaultsAreReasonable() {
        val config = SemanticSearchEngine.EmbeddingConfig()
        assertEquals("unbundled", config.modelVersion)
        assertEquals(512, config.embeddingDim)
        assertEquals(50, config.maxResults)
    }
}
