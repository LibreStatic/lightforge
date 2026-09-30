package com.librestatic.lightforge.feature.places

import com.librestatic.lightforge.core.model.MediaKey
import kotlinx.coroutines.flow.Flow

/**
 * UTC year follows the gallery timeline; coordinates are authorized current-generation EXIF only.
 */
data class PlacePhoto(
    val key: MediaKey,
    val latitude: Double,
    val longitude: Double,
    val timelineSortMillis: Long,
    val generation: Long,
)

data class PlaceBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        require(listOf(west, south, east, north).all { it.isFinite() })
        require(
            west in -180.0..180.0 &&
                east in -180.0..180.0 &&
                south in -90.0..90.0 &&
                north in south..90.0
        )
    }

    fun contains(lat: Double, lon: Double) =
        lat in south..north && if (west <= east) lon in west..east else lon >= west || lon <= east

    companion object {
        val World = PlaceBounds(-180.0, -85.0511287, 180.0, 85.0511287)
    }
}

data class PlacesPhotoPage(val photos: List<PlacePhoto>, val totalMatching: Long) {
    init {
        require(photos.size <= 2000 && totalMatching >= photos.size)
    }
}

interface PlacesSource {
    val revision: Flow<Long>

    suspend fun years(): List<Int>

    suspend fun query(bounds: PlaceBounds, year: Int?, limit: Int = 2000): PlacesPhotoPage
}

data class PlacePhotoGroup(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val photos: List<PlacePhoto>,
)

/** Screen-grid sized groups. Longitude cells wrap across the antimeridian. */
fun groupPlacePhotos(photos: List<PlacePhoto>, zoom: Double): List<PlacePhotoGroup> {
    val cells = Math.pow(2.0, zoom.coerceIn(0.0, 20.0)).toLong().coerceAtLeast(1) * 4
    return photos
        .filter {
            it.latitude.isFinite() &&
                it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 &&
                it.longitude in -180.0..180.0
        }
        .groupBy {
            val x = (((it.longitude + 180.0) / 360.0 * cells).toLong() % cells)
            val y = ((it.latitude + 90.0) / 180.0 * cells).toLong()
            "${x}:${y}"
        }
        .map { (id, members) ->
            val first = members.first()
            PlacePhotoGroup(
                id,
                first.latitude,
                first.longitude,
                members.sortedWith(
                    compareByDescending<PlacePhoto> { it.timelineSortMillis }
                        .thenBy { it.key.toString() }
                ),
            )
        }
}
