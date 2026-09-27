package com.librestatic.lightforge.feature.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineGazetteerTest {

    private val gazetteer = OfflineGazetteer(BundledGazetteer.load())

    @Test
    fun reverseGeocode_buenosAires_returnsBuenosAires() {
        val result = gazetteer.reverseGeocode(-34.6037, -58.3816)
        assertNotNull(result)
        assertEquals("Buenos Aires", result!!.city.name)
        assertTrue(result.distanceKm < 1.0)
    }

    @Test
    fun reverseGeocode_nearCordoba_returnsCordoba() {
        val result = gazetteer.reverseGeocode(-31.42, -64.19)
        assertNotNull(result)
        assertEquals("Cordoba", result!!.city.name)
        assertTrue(result.distanceKm < 5.0)
    }

    @Test
    fun reverseGeocode_newYork_returnsNewYork() {
        val result = gazetteer.reverseGeocode(40.71, -74.01)
        assertNotNull(result)
        assertEquals("New York", result!!.city.name)
    }

    @Test
    fun reverseGeocode_tokyo_returnsTokyo() {
        val result = gazetteer.reverseGeocode(35.68, 139.65)
        assertNotNull(result)
        assertEquals("Tokyo", result!!.city.name)
    }

    @Test
    fun reverseGeocode_emptyDataset_returnsNull() {
        val empty = OfflineGazetteer(emptyList())
        assertNull(empty.reverseGeocode(0.0, 0.0))
    }

    @Test
    fun findNearby_buenosAiresArea_returnsMultipleCities() {
        val results = gazetteer.findNearby(-34.6, -58.38, 500.0)
        assertTrue(results.isNotEmpty())
        assertEquals("Buenos Aires", results[0].city.name)
        // La Plata should be nearby
        assertTrue(results.any { it.city.name == "La Plata" })
        assertTrue(results.any { it.city.name == "Montevideo" })
    }

    @Test
    fun findNearby_remoteLocation_returnsEmptyOrDistant() {
        val results = gazetteer.findNearby(-85.0, -150.0, 100.0)
        // Antarctica / South Pacific — no cities nearby
        assertTrue(results.isEmpty())
    }

    @Test
    fun equirectangularDistance_samePoint_isZero() {
        val dist = OfflineGazetteer.equirectangularDistanceKm(0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun equirectangularDistance_knownDistance() {
        // Buenos Aires to Montevideo is approximately 230 km
        val dist = OfflineGazetteer.equirectangularDistanceKm(
            -34.6037, -58.3816, -34.9011, -56.1645
        )
        assertTrue("Distance should be around 200-260km, got $dist", dist in 200.0..260.0)
    }

    @Test
    fun haversineDistance_samePoint_isZero() {
        val dist = OfflineGazetteer.haversineDistanceKm(0.0, 0.0, 0.0, 0.0)
        assertEquals(0.0, dist, 0.001)
    }

    @Test
    fun bundledGazetteer_hasAllContinents() {
        val cities = BundledGazetteer.load()
        assertTrue(cities.any { it.countryCode == "AR" }) // Argentina
        assertTrue(cities.any { it.countryCode == "US" }) // USA
        assertTrue(cities.any { it.countryCode == "JP" }) // Japan
        assertTrue(cities.any { it.countryCode == "EG" }) // Egypt
        assertTrue(cities.any { it.countryCode == "AU" }) // Australia
        assertTrue(cities.any { it.countryCode == "BR" }) // Brazil
    }

    @Test
    fun bundledGazetteer_hasReasonableSize() {
        val cities = BundledGazetteer.load()
        assertTrue("Should have at least 50 cities, got ${cities.size}", cities.size >= 50)
        assertTrue("Should have fewer than 1000 cities for bounded storage, got ${cities.size}", cities.size < 1000)
    }
}
