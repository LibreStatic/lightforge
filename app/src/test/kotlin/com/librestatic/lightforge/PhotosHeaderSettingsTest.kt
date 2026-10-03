package com.librestatic.lightforge

import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import com.librestatic.lightforge.feature.photos.PhotosFilter
import com.librestatic.lightforge.feature.photos.PhotosSort
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotosHeaderSettingsTest {
    @Test
    fun everyHeaderChoiceRoundTripsThroughSettings() {
        val base = LibrarySettings()
        PhotosFilter.entries.forEach { assertEquals(it, base.withPhotosFilter(it).photosFilter()) }
        PhotosSort.entries.forEach { assertEquals(it, base.withPhotosSort(it).photosSort()) }
    }

    @Test
    fun headerChoicesWriteTheExpectedQuery() {
        val base = LibrarySettings()
        assertEquals(LibraryFilter.Images, base.withPhotosFilter(PhotosFilter.Photos).filter)
        val oldest = base.withPhotosSort(PhotosSort.Oldest)
        assertEquals(LibrarySort.DateTaken, oldest.sort)
        assertEquals(true, oldest.ascending)
        val largest = base.withPhotosSort(PhotosSort.Largest)
        assertEquals(LibrarySort.Size, largest.sort)
        assertEquals(false, largest.ascending)
    }

    @Test
    fun ordersTheMenuDoesNotOfferShowTheirFieldDefault() {
        assertEquals(PhotosSort.Name, LibrarySettings(sort = LibrarySort.Name, ascending = false).photosSort())
        assertEquals(PhotosSort.RecentlyModified, LibrarySettings(sort = LibrarySort.DateModified, ascending = true).photosSort())
    }
}
