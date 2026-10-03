package com.librestatic.lightforge.feature.picker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.MediaKey
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import kotlinx.coroutines.flow.flowOf

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// They feed fake pages into the real PickerMediaGrid; thumbnails are flat tones from a fake source.

/** A multiple-item request with two items already picked: every other tile shows an empty check. */
@Preview
@Composable
fun PickerGridPreview() = PickerFrame(multiple = true, initialSelection = listOf(2L, 5L))

/** A single-item request: tiles are plain buttons, no checks. */
@Preview
@Composable
fun PickerSingleGridPreview() = PickerFrame(multiple = false)

@Composable
private fun PickerFrame(multiple: Boolean, initialSelection: List<Long> = emptyList()) {
    var selection by remember { mutableStateOf(initialSelection.map(::key)) }
    val loader = remember { previewThumbnailLoader() }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val pages = remember { flowOf(PagingData.from(SampleMedia)) }.collectAsLazyPagingItems()
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            PickerMediaGrid(
                pages = pages,
                loader = loader,
                selection = selection,
                multiple = multiple,
                onToggle = { media -> selection = if (media.key in selection) selection - media.key else selection + media.key },
                onPick = {},
            )
        }
    }
}

private fun key(id: Long) = MediaKey("external_primary", id)

private val SampleMedia = List(40) { index ->
    val id = index + 1L
    val video = id % 6 == 0L
    PickerMedia(
        key = key(id),
        isVideo = video,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        displayName = if (video) "VID_$id.mp4" else "IMG_$id.jpg",
        sizeBytes = 2_400_000,
        durationMillis = if (video) 42_000 else 0,
        dateModifiedSeconds = 1_790_000_000L - id * 3_600,
        generationModified = 1,
        isFavorite = id % 9 == 0L,
    )
}

/** Fake photo content: one flat tone per item, standing in for decoded pixels. */
private val SampleTones = intArrayOf(
    0xFF8DA9C4.toInt(), 0xFFC9A66B.toInt(), 0xFF7FA77A.toInt(),
    0xFFB4878F.toInt(), 0xFF9C8FC0.toInt(), 0xFF6F9A9E.toInt(),
)

private fun previewThumbnailLoader() = ThumbnailLoader(
    source = { request, _ ->
        Bitmap.createBitmap(request.widthPx, request.heightPx, Bitmap.Config.ARGB_8888).apply {
            eraseColor(SampleTones[(request.mediaKey.mediaStoreId % SampleTones.size).toInt()])
        }
    },
    maxCacheBytes = 16L * 1024 * 1024,
    threadCount = 1,
)
