package com.librestatic.lightforge.feature.album

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.database.AlbumMediaFilter
import com.librestatic.lightforge.core.database.AlbumSort
import com.librestatic.lightforge.core.designsystem.GalleryTopAppBar
import com.librestatic.lightforge.core.designsystem.GalleryWindowClass
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.AlbumAvailability
import com.librestatic.lightforge.core.model.AlbumKey
import com.librestatic.lightforge.core.model.AlbumSummary
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.selection.SelectionSpec
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource
import kotlinx.coroutines.flow.flowOf

// Debug-only, zero-argument entry points for tools/compose-driver. They feed fake albums into the
// real AlbumWithSidePanel and AlbumContent; the top app bar stands in for the one the shell draws.
// Thumbnails are flat tinted bitmaps.

/** A user album with 18 items, albums panel closed. */
@Composable
fun AlbumPreview() = AlbumFrame(albums[0], panelOpen = false)

/** A user album with the albums panel open: only user albums are listed. */
@Composable
fun AlbumPanelPreview() = AlbumFrame(albums[0], panelOpen = true, windowClass = GalleryWindowClass.Expanded)

/** A device folder with the panel open: only folders are listed, and there is no overflow menu. */
@Composable
fun AlbumFolderPreview() = AlbumFrame(folders[0], panelOpen = true, windowClass = GalleryWindowClass.Expanded)

/** An empty user album. */
@Composable
fun AlbumEmptyPreview() = AlbumFrame(albums[2], panelOpen = false, itemCount = 0)

@Composable
private fun AlbumFrame(
    album: AlbumSummary,
    panelOpen: Boolean,
    windowClass: GalleryWindowClass = GalleryWindowClass.Compact,
    itemCount: Int = album.itemCount.toInt(),
) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            val media = remember(album, itemCount) { List(itemCount) { media(it) } }
            val items = remember(media) { flowOf(PagingData.from(media)) }.collectAsLazyPagingItems()
            val virtual = remember { flowOf(PagingData.from(albums)) }.collectAsLazyPagingItems()
            val physical = remember { flowOf(PagingData.from(folders)) }.collectAsLazyPagingItems()
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            var open by remember { mutableStateOf(panelOpen) }
            var filter by remember { mutableStateOf(AlbumMediaFilter.All) }
            var sort by remember { mutableStateOf(AlbumSort.NewestFirst) }
            Column(Modifier.fillMaxSize()) {
                GalleryTopAppBar(
                    title = album.name.orEmpty(),
                    subtitle = pluralStringResource(R.plurals.album_item_count, itemCount, itemCount),
                    onBack = {},
                    navigationContentDescription = "Back",
                )
                AlbumWithSidePanel(
                    selectedKey = album.key,
                    virtualAlbums = virtual,
                    physicalAlbums = physical,
                    thumbnails = loader,
                    windowClass = windowClass,
                    open = open,
                    onOpenChange = { open = it },
                    onAlbumSelect = {},
                    modifier = Modifier.weight(1f),
                ) { contentModifier ->
                    AlbumContent(
                        album = album,
                        items = items,
                        thumbnails = loader,
                        filter = filter,
                        sort = sort,
                        selection = SelectionSpec.explicit(),
                        selectionQueryCount = itemCount.toLong(),
                        onFilterChange = { filter = it },
                        onSortChange = { sort = it },
                        onMediaClick = {},
                        onMediaSelectionChange = { _, _ -> },
                        showHeader = false,
                        modifier = contentModifier,
                        onRenameAlbum = {},
                        onDeleteAlbum = {},
                        deleteAlbumLabel = "Delete album",
                        onSetCover = {},
                    )
                }
            }
        }
    }
}

private val albums = listOf(
    AlbumSummary(AlbumKey.Virtual(1), "Patagonia", 18, null, MediaKey("external_primary", 100), AlbumAvailability.Available),
    AlbumSummary(AlbumKey.Virtual(2), "Birthday", 42, null, MediaKey("external_primary", 101), AlbumAvailability.Available),
    AlbumSummary(AlbumKey.Virtual(3), "Recipes", 0, null, null, AlbumAvailability.Available),
)

private val folders = listOf(
    AlbumSummary(AlbumKey.Physical("external_primary", 10), "Camera", 1240, null, MediaKey("external_primary", 102), AlbumAvailability.Available),
    AlbumSummary(AlbumKey.Physical("external_primary", 11), "Screenshots", 96, null, MediaKey("external_primary", 103), AlbumAvailability.Available),
    AlbumSummary(AlbumKey.Physical("external_primary", 12), "WhatsApp Images", 530, null, MediaKey("external_primary", 104), AlbumAvailability.Available),
)

private fun media(index: Int) = TimelineMedia(
    key = MediaKey("external_primary", 200L + index),
    kind = if (index % 5 == 2) MediaKind.Video else MediaKind.Image,
    generationModified = 1,
    timelineSortMillis = 1_780_000_000_000L - index * 3_600_000L,
    width = 4000,
    height = 3000,
    durationMillis = if (index % 5 == 2) 75_000L else 0L,
    isFavorite = index == 4,
    displayName = "IMG_$index.jpg",
)

// Fake photo content (not UI color): a different tint per item so tiles are distinguishable.
private val FakeThumbnails = ThumbnailSource { request, _ ->
    val hues = intArrayOf(0xFF7A8F6B.toInt(), 0xFF8C6E5A.toInt(), 0xFF5D7A99.toInt(), 0xFFA38B5C.toInt(), 0xFF6F5F8C.toInt())
    Bitmap.createBitmap(request.widthPx.coerceAtMost(256), request.heightPx.coerceAtMost(256), Bitmap.Config.ARGB_8888)
        .apply { eraseColor(hues[(request.mediaKey.mediaStoreId % hues.size).toInt()]) }
}
