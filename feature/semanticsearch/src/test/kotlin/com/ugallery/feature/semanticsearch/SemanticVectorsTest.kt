package com.ugallery.feature.semanticsearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVectorsTest {
    @Test fun quantizedCosinePreservesDirection() {
        val vector = FloatArray(512) { if (it % 3 == 0) 1f else -0.5f }
        val opposite = FloatArray(512) { -vector[it] }
        val encoded = CompactSemanticEmbedding.quantize(vector)
        assertTrue(CompactSemanticEmbedding.cosine(encoded, encoded) > 0.999f)
        assertTrue(CompactSemanticEmbedding.cosine(encoded, CompactSemanticEmbedding.quantize(opposite)) < -0.999f)
    }

    @Test fun lshIsStableAndProducesEightBands() {
        val vector = CompactSemanticEmbedding.quantize(FloatArray(512) { it.toFloat() - 256f })
        assertEquals(SemanticLsh.bands(vector, "model-v1").toList(), SemanticLsh.bands(vector, "model-v1").toList())
        assertEquals(8, SemanticLsh.bands(vector, "model-v1").size)
        assertEquals(137, SemanticLsh.probes(0, 2).size)
    }

    @Test fun reciprocalRankFusionRewardsAgreement() {
        val result = ReciprocalRankFusion.fuse(
            keyword = listOf(RankedSemanticKey("a", 1.0), RankedSemanticKey("b", 0.9)),
            semantic = listOf(RankedSemanticKey("b", 0.8), RankedSemanticKey("c", 0.7)),
        )
        assertEquals("b", result.first().key)
    }
}

