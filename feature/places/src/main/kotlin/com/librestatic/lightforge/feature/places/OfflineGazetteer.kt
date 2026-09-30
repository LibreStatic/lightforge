package com.librestatic.lightforge.feature.places

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Offline reverse geocoder using a bundled gazetteer dataset.
 * No online geocoder, no network — all lookups are local.
 * Location privacy: GPS coordinates are never sent off-device.
 *
 * Uses a simple nearest-neighbor search on a bundled city dataset.
 * For production scale (100k+ cities), an R-tree or geohash index would be used,
 * but for a bundled dataset of ~1k major cities, linear scan is fast enough.
 */
class OfflineGazetteer(private val cities: List<CityEntry>) {

    data class CityEntry(
        val name: String,
        val country: String,
        val countryCode: String,
        val admin1: String,
        val lat: Double,
        val lon: Double,
        val population: Long = 0,
    )

    data class ReverseGeocodeResult(
        val city: CityEntry,
        val distanceKm: Double,
    )

    /**
     * Finds the nearest city to the given coordinates.
     * Returns null if the dataset is empty.
     * Uses equirectangular approximation for speed (good for <100km distances).
     */
    fun reverseGeocode(lat: Double, lon: Double): ReverseGeocodeResult? {
        if (cities.isEmpty()) return null

        var bestCity = cities[0]
        var bestDistance = Double.MAX_VALUE

        for (city in cities) {
            val dist = equirectangularDistanceKm(lat, lon, city.lat, city.lon)
            if (dist < bestDistance) {
                bestDistance = dist
                bestCity = city
            }
        }

        return ReverseGeocodeResult(bestCity, bestDistance)
    }

    /**
     * Finds all cities within a radius (km) of the given coordinates.
     * Useful for showing nearby places.
     */
    fun findNearby(lat: Double, lon: Double, radiusKm: Double): List<ReverseGeocodeResult> {
        return cities
            .map { ReverseGeocodeResult(it, equirectangularDistanceKm(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceKm <= radiusKm }
            .sortedBy { it.distanceKm }
    }

    companion object {
        private const val EARTH_RADIUS_KM = 6371.0

        /**
         * Equirectangular approximation distance.
         * Fast and accurate enough for distances < 100km.
         * For longer distances, haversine would be more accurate.
         */
        fun equirectangularDistanceKm(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double,
        ): Double {
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val lon1Rad = Math.toRadians(lon1)
            val lon2Rad = Math.toRadians(lon2)

            val x = (lon2Rad - lon1Rad) * Math.cos((lat1Rad + lat2Rad) / 2)
            val y = lat2Rad - lat1Rad
            return EARTH_RADIUS_KM * Math.sqrt(x * x + y * y)
        }

        /**
         * Haversine distance for more accurate long-distance calculations.
         */
        fun haversineDistanceKm(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double,
        ): Double {
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return EARTH_RADIUS_KM * c
        }
    }
}
