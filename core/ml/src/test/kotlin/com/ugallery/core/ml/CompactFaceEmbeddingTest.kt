package com.ugallery.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactFaceEmbeddingTest {
    @Test
    fun normalizedVectorUsesExactlyOneBytePerDimension() {
        val raw = FloatArray(CompactFaceEmbedding.Dimensions) { index -> index - 64f }
        val compact = CompactFaceEmbedding.quantize(raw)
        assertEquals(128, compact.size)
        assertEquals(1f, CompactFaceEmbedding.cosine(compact, compact), 0.0001f)
    }

    @Test
    fun cosinePreservesDirectionAndRejectsOppositeDirection() {
        val a = FloatArray(128) { index -> if (index % 2 == 0) 1f else -0.5f }
        val b = FloatArray(128) { index -> a[index] * 3f }
        val opposite = FloatArray(128) { index -> -a[index] }
        assertTrue(CompactFaceEmbedding.cosine(CompactFaceEmbedding.quantize(a), CompactFaceEmbedding.quantize(b)) > 0.999f)
        assertTrue(CompactFaceEmbedding.cosine(CompactFaceEmbedding.quantize(a), CompactFaceEmbedding.quantize(opposite)) < -0.999f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroVectorIsRejected() {
        CompactFaceEmbedding.quantize(FloatArray(128))
    }
}
