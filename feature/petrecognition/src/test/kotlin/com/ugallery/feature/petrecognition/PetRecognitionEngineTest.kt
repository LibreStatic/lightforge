package com.ugallery.feature.petrecognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetRecognitionEngineTest {

    private val engine = PetRecognitionEngine()

    @Test
    fun isRecognitionAvailable_withoutModel_returnsFalse() {
        assertFalse(engine.isRecognitionAvailable())
    }

    @Test
    fun recognitionMethod_petEmbedding_isDefined() {
        assertEquals("PET_EMBEDDING", PetRecognitionEngine.RecognitionMethod.PET_EMBEDDING.name)
    }

    @Test
    fun recognitionMethod_labelOnlyFallback_isDefined() {
        assertEquals("LABEL_ONLY_FALLBACK", PetRecognitionEngine.RecognitionMethod.LABEL_ONLY_FALLBACK.name)
    }

    @Test
    fun recognitionConfig_defaultsAreReasonable() {
        val config = PetRecognitionEngine.RecognitionConfig()
        assertEquals("unbundled", config.modelVersion)
        assertEquals(128, config.embeddingDim)
        assertEquals(0.5f, config.confidenceThreshold, 0.001f)
    }

    @Test
    fun verifyNoCrossover_returnsTrue() {
        assertTrue(engine.verifyNoCrossoverWithPersonData())
    }
}
