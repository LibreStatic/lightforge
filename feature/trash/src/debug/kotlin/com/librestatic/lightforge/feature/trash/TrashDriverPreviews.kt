package com.librestatic.lightforge.feature.trash

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.model.MediaKind
import com.librestatic.lightforge.core.model.TimelineMedia
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource
import kotlinx.coroutines.flow.flowOf

// Debug-only, zero-argument entry points for tools/compose-driver. Each one feeds fake trashed
// items into the real TrashContent; thumbnails are flat tinted bitmaps.

/** A typical trash: a dozen photos and videos expiring over the next month. */
@Composable
fun TrashPreview() = TrashFrame(List(12) { trashed(it) })

/** One item only: the sparse layout with larger tiles. */
@Composable
fun TrashSinglePreview() = TrashFrame(listOf(trashed(0)))

/** Nothing in the trash. */
@Composable
fun TrashEmptyPreview() = TrashFrame(emptyList())

@Composable
private fun TrashFrame(media: List<TimelineMedia>) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            val items = remember(media) { flowOf(PagingData.from(media)) }.collectAsLazyPagingItems()
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            TrashContent(
                items = items,
                totalCount = media.size.toLong(),
                thumbnailLoader = loader,
                selectionMode = false,
                isSelected = { false },
                onOpen = {},
                onSelectionModeChange = {},
                onSelectionChange = { _, _ -> },
            )
        }
    }
}

private const val DayMillis = 24L * 60 * 60 * 1000

private fun trashed(index: Int): TimelineMedia {
    val now = System.currentTimeMillis()
    return TimelineMedia(
        key = MediaKey("external_primary", 100L + index),
        kind = if (index % 4 == 1) MediaKind.Video else MediaKind.Image,
        generationModified = 1,
        timelineSortMillis = now - index * DayMillis,
        width = 4000,
        height = 3000,
        durationMillis = if (index % 4 == 1) 42_000L else 0L,
        // Expiries from "today" to almost a month away.
        dateExpiresMillis = now + (index * 2.5 * DayMillis).toLong() + DayMillis / 3,
        isFavorite = index == 2,
        isTrashed = true,
        displayName = "IMG_20260${index % 9 + 1}12_1043$index.jpg",
    )
}

// Fake photo content (not UI color): a different tint per item so tiles are distinguishable.
private val FakeThumbnails = ThumbnailSource { request, _ ->
    val hues = intArrayOf(0xFF7A8F6B.toInt(), 0xFF8C6E5A.toInt(), 0xFF5D7A99.toInt(), 0xFFA38B5C.toInt(), 0xFF6F5F8C.toInt())
    Bitmap.createBitmap(request.widthPx.coerceAtMost(256), request.heightPx.coerceAtMost(256), Bitmap.Config.ARGB_8888)
        .apply { eraseColor(hues[(request.mediaKey.mediaStoreId % hues.size).toInt()]) }
}
