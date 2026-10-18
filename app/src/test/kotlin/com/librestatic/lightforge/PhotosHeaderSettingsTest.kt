package com.librestatic.lightforge

import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import com.librestatic.lightforge.feature.photos.PhotosFilter
import com.librestatic.lightforge.feature.photos.PhotosGrouping
import com.librestatic.lightforge.feature.photos.PhotosSort
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotosHeaderSettingsTest {
    @Test
    fun everyHeaderChoiceRoundTripsThroughSettings() {
        val base = LibrarySettings()
        PhotosFilter.entries.filter { it != PhotosFilter.All }.forEach {
            assertEquals(setOf(it), base.withPhotosFilterToggled(it).photosFilters())
        }
        PhotosGrouping.entries.forEach { assertEquals(it, base.withPhotosGrouping(it).photosGrouping()) }
        PhotosSort.entries.forEach { assertEquals(it, base.withPhotosSort(it).photosSort()) }
    }

    @Test
    fun headerChoicesWriteTheExpectedQuery() {
        val base = LibrarySettings()
        assertEquals(LibraryFilter.Images, base.withPhotosFilterToggled(PhotosFilter.Photos).filter)
        val oldest = base.withPhotosSort(PhotosSort.Oldest)
        assertEquals(LibrarySort.DateTaken, oldest.sort)
        assertEquals(true, oldest.ascending)
        val largest = base.withPhotosSort(PhotosSort.Largest)
        assertEquals(LibrarySort.Size, largest.sort)
        assertEquals(false, largest.ascending)
    }

    @Test
    fun typeChipsAreMultiSelectAndAllClearsThem() {
        val both = LibrarySettings()
            .withPhotosFilterToggled(PhotosFilter.Photos)
            .withPhotosFilterToggled(PhotosFilter.Videos)
        assertEquals(setOf(PhotosFilter.Photos, PhotosFilter.Videos), both.photosFilters())
        val onlyVideos = both.withPhotosFilterToggled(PhotosFilter.Photos)
        assertEquals(setOf(PhotosFilter.Videos), onlyVideos.photosFilters())
        assertEquals(emptySet<PhotosFilter>(), onlyVideos.withPhotosFilterToggled(PhotosFilter.Videos).photosFilters())
        assertEquals(emptySet<PhotosFilter>(), both.withPhotosFilterToggled(PhotosFilter.All).photosFilters())
    }

    @Test
    fun ordersTheMenuDoesNotOfferShowTheirFieldDefault() {
        assertEquals(PhotosSort.Name, LibrarySettings(sort = LibrarySort.Name, ascending = false).photosSort())
        assertEquals(PhotosSort.RecentlyModified, LibrarySettings(sort = LibrarySort.DateModified, ascending = true).photosSort())
    }
}
