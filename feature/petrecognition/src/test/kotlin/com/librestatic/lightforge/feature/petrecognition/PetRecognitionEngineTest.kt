package com.librestatic.lightforge.feature.petrecognition

import org.junit.Assert.*
import org.junit.Test

class PetRecognitionEngineTest {
    @Test fun pinnedDetectorAnchorsHaveExpectedShapeAndCenters() {
        val a = PetDetectionDecoder.anchors
        assertEquals(19206 * 4, a.size)
        assertEquals(.0125f, a[0], .000001f)
        assertEquals(.075f, a[2], .000001f)
        assertEquals(5f / 6f, a[a.size - 4], .000001f)
        assertTrue(a.all(Float::isFinite))
    }
    @Test fun nonFiniteAndLowConfidenceOutputNeverProducesAnAnimal() {
        val scores = FloatArray(19206 * 90)
        scores[16] = Float.NaN
        assertTrue(PetDetectionDecoder.decode(FloatArray(19206 * 4), scores).isEmpty())
    }
    @Test fun overlappingSpeciesCandidatesProduceOneBoxNotTwoIdentities() {
        val scores = FloatArray(19206 * 90)
        scores[16] = .8f; scores[17] = .7f
        scores[90 + 16] = .75f
        val found = PetDetectionDecoder.decode(FloatArray(19206 * 4), scores)
        assertEquals(1, found.size)
        assertEquals(PetSpecies.Cat, found.single().species)
    }
    @Test fun spatiallyDifferentAnimalsKeepIndependentBoxes() {
        val scores = FloatArray(19206 * 90)
        scores[16] = .8f
        scores[(40 * 9 * 25 + 9 * 25) * 90 + 17] = .85f
        assertEquals(2, PetDetectionDecoder.decode(FloatArray(19206 * 4), scores).size)
    }
    @Test fun boxesMustBeFiniteAndHavePositiveArea() {
        assertThrows(IllegalArgumentException::class.java) { PetBox(0f, 0f, Float.NaN, 1f) }
        assertThrows(IllegalArgumentException::class.java) { PetBox(.5f, 0f, .4f, 1f) }
    }
    @Test fun uncertainSpeciesCannotBecomeAnIdentityWithoutReview() {
        assertThrows(IllegalArgumentException::class.java) {
            PetIdentityCard("pet", PetSpecies.Uncertain, "Pet", 1, null)
        }
    }
    @Test fun identityEmbeddingMustBeRealSizedFiniteAndUnitNormalized() {
        assertThrows(IllegalArgumentException::class.java) {
            PetAnalyzedObservation("id", PetBox(0f, 0f, 1f, 1f), PetSpecies.Cat, .9f, FloatArray(512))
        }
    }
}
