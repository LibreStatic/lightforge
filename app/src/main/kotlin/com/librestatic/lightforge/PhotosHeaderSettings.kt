package com.librestatic.lightforge

import com.librestatic.lightforge.core.preferences.LibraryFilter
import com.librestatic.lightforge.core.preferences.LibrarySettings
import com.librestatic.lightforge.core.preferences.LibrarySort
import com.librestatic.lightforge.feature.photos.PhotosFilter
import com.librestatic.lightforge.feature.photos.PhotosSort

// The Photos header shows the library query settings as filter chips and a sort menu; these map
// both ways so the header and Settings always agree.

internal fun LibrarySettings.photosFilter(): PhotosFilter = when (filter) {
    LibraryFilter.All -> PhotosFilter.All
    LibraryFilter.Images -> PhotosFilter.Photos
    LibraryFilter.Videos -> PhotosFilter.Videos
    LibraryFilter.Animated -> PhotosFilter.Animated
    LibraryFilter.Raw -> PhotosFilter.Raw
}

internal fun LibrarySettings.withPhotosFilter(value: PhotosFilter): LibrarySettings = copy(
    filter = when (value) {
        PhotosFilter.All -> LibraryFilter.All
        PhotosFilter.Photos -> LibraryFilter.Images
        PhotosFilter.Videos -> LibraryFilter.Videos
        PhotosFilter.Animated -> LibraryFilter.Animated
        PhotosFilter.Raw -> LibraryFilter.Raw
    },
)

/** Settings combinations the menu does not offer (oldest-modified, Z to A…) show as their field's default order. */
internal fun LibrarySettings.photosSort(): PhotosSort = when (sort) {
    LibrarySort.DateTaken -> if (ascending) PhotosSort.Oldest else PhotosSort.Newest
    LibrarySort.DateModified -> PhotosSort.RecentlyModified
    LibrarySort.Name -> PhotosSort.Name
    LibrarySort.Size -> PhotosSort.Largest
}

internal fun LibrarySettings.withPhotosSort(value: PhotosSort): LibrarySettings = when (value) {
    PhotosSort.Newest -> copy(sort = LibrarySort.DateTaken, ascending = false)
    PhotosSort.Oldest -> copy(sort = LibrarySort.DateTaken, ascending = true)
    PhotosSort.RecentlyModified -> copy(sort = LibrarySort.DateModified, ascending = false)
    PhotosSort.Name -> copy(sort = LibrarySort.Name, ascending = true)
    PhotosSort.Largest -> copy(sort = LibrarySort.Size, ascending = false)
}
