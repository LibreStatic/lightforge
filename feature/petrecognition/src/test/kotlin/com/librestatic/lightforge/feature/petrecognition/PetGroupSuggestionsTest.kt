package com.librestatic.lightforge.feature.petrecognition

import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PetGroupSuggestionsTest {
    /** Unit vector at [degrees] in the first two dimensions: cosine between two of them is cos(delta). */
    private fun vector(degrees: Double) = FloatArray(512).also {
        it[0] = cos(Math.toRadians(degrees)).toFloat()
        it[1] = sin(Math.toRadians(degrees)).toFloat()
    }
    private fun candidate(id: String, degrees: Double, species: PetSpecies = PetSpecies.Cat, photo: String = id) =
        PetGroupCandidate(id, photo, species, vector(degrees))

    @Test fun similarObservationsFormOneGroupAndDistantOnesStaySeparate() {
        val groups = PetGroupSuggestions.cluster(listOf(candidate("a", 0.0), candidate("b", 10.0), candidate("c", 90.0), candidate("d", 95.0), candidate("e", 5.0)))
        assertEquals(listOf(listOf("a", "b", "e"), listOf("c", "d")), groups.map { it.observationIds })
    }

    @Test fun singletonsAreNeverSuggested() {
        assertTrue(PetGroupSuggestions.cluster(listOf(candidate("a", 0.0), candidate("b", 90.0), candidate("c", 180.0))).isEmpty())
    }

    @Test fun thresholdIsConservative() {
        // cos(40 degrees) = .766 < .8: a plausible but unconfirmed likeness is not offered.
        assertTrue(PetGroupSuggestions.cluster(listOf(candidate("a", 0.0), candidate("b", 40.0))).isEmpty())
        assertEquals(1, PetGroupSuggestions.cluster(listOf(candidate("a", 0.0), candidate("b", 30.0))).size)
    }

    @Test fun speciesNeverMix() {
        val groups = PetGroupSuggestions.cluster(
            listOf(
                candidate("cat1", 0.0, PetSpecies.Cat), candidate("dog1", 0.0, PetSpecies.Dog),
                candidate("cat2", 1.0, PetSpecies.Cat), candidate("dog2", 1.0, PetSpecies.Dog),
            ),
        )
        assertEquals(mapOf(PetSpecies.Cat to listOf("cat1", "cat2"), PetSpecies.Dog to listOf("dog1", "dog2")), groups.associate { it.species to it.observationIds })
    }

    @Test fun samePhotoNeverJoinsOneGroupTwice() {
        // Two near-identical animals in one photo cannot be one pet: the second starts its own cluster.
        val groups = PetGroupSuggestions.cluster(listOf(candidate("a", 0.0, photo = "p1"), candidate("b", 1.0, photo = "p1"), candidate("c", -1.0, photo = "p2")))
        assertEquals(listOf(listOf("a", "c")), groups.map { it.observationIds })
    }

    @Test fun largestGroupsComeFirstAndInputIsBounded() {
        val many = List(PetGroupSuggestions.MaxObservationsPerSpecies + 500) { candidate("m$it", 0.0) }
        val groups = PetGroupSuggestions.cluster(many + listOf(candidate("s1", 90.0), candidate("s2", 91.0)))
        assertEquals(PetGroupSuggestions.MaxObservationsPerSpecies, groups.first().observationIds.size)
        assertTrue(groups.size == 1)
    }

    @Test fun uncertainSpeciesCandidatesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { PetGroupCandidate("u", "u", PetSpecies.Uncertain, vector(0.0)) }
        assertThrows(IllegalArgumentException::class.java) { PetGroupSuggestion(PetSpecies.Cat, listOf("only")) }
    }
}
