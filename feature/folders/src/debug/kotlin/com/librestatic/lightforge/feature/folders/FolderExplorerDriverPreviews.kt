package com.librestatic.lightforge.feature.folders

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.model.folders.FolderNode
import com.librestatic.lightforge.core.model.folders.FolderStats
import com.librestatic.lightforge.core.model.folders.FolderTree
import com.librestatic.lightforge.core.model.folders.NaturalNameOrder
import com.librestatic.lightforge.core.model.folders.buildFolderForest
import com.librestatic.lightforge.core.thumbnail.ThumbnailLoader
import com.librestatic.lightforge.core.thumbnail.ThumbnailSource

// Debug-only, zero-argument entry points for tools/compose-driver. They run the real
// buildFolderExplorerUiState over a fake folder tree and feed it to the real FolderExplorerContent;
// navigation works (tap cards and crumbs). Thumbnails are flat tinted bitmaps.

/** Two volumes: the start level lists them with free space. */
@Composable
fun FolderExplorerVolumesPreview() = Frame(twoVolumes, FolderLocation.Start)

/** A folder with its own files plus sub-folders, one of them a compacted chain. */
@Composable
fun FolderExplorerFolderPreview() = Frame(twoVolumes, FolderLocation("external_primary", "DCIM/"))

/** Single volume: starts inside it, with compacted names. */
@Composable
fun FolderExplorerSingleVolumePreview() = Frame(singleVolume, FolderLocation.Start)

/** A deep path, to check the breadcrumb scrolls to the current folder. */
@Composable
fun FolderExplorerDeepPreview() =
    Frame(singleVolume, FolderLocation("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/"))

/** No folders at all. */
@Composable
fun FolderExplorerEmptyPreview() = Frame(FolderTree.Empty, FolderLocation.Start)

@Composable
private fun Frame(tree: FolderTree, start: FolderLocation) {
    LightforgeTheme {
        Surface(Modifier.fillMaxSize()) {
            var location by remember { mutableStateOf(start) }
            val loader = remember { ThumbnailLoader(FakeThumbnails, maxCacheBytes = 16L * 1024 * 1024, threadCount = 1) }
            FolderExplorerContent(
                state = buildFolderExplorerUiState(tree, location, spaces),
                thumbnails = loader,
                onEnter = { location = it },
                onOpenFiles = {},
            )
        }
    }
}

private val spaces = mapOf(
    "external_primary" to VolumeSpace(null, 41_000_000_000L, 128_000_000_000L),
    "1234-ABCD" to VolumeSpace("SD card", 20_000_000_000L, 64_000_000_000L),
)

private class Row(val volume: String, val path: String, val bucket: Long, val count: Long, val newest: Long)

private fun tree(vararg rows: Row): FolderTree {
    fun FolderNode<Row>.stats(): FolderNode<FolderStats> = FolderNode(
        volumeName, relativePath, name,
        direct?.let { FolderStats(it.bucket, it.count, it.count, 0, it.count * 3_500_000L, 1, it.newest, 100 + it.bucket, true) },
        children.map { it.stats() },
    )
    return FolderTree.from(
        buildFolderForest(
            items = rows.toList(),
            volumeOf = Row::volume,
            relativePathOf = Row::path,
            fallbackKeyOf = { it.bucket.toString() },
            fallbackNameOf = { it.bucket.toString() },
            nameOrder = NaturalNameOrder,
        ).map { it.stats() },
    )
}

private val primaryRows = arrayOf(
    Row("external_primary", "DCIM/", 1, 12, 900),
    Row("external_primary", "DCIM/Camera/", 2, 1240, 1000),
    Row("external_primary", "DCIM/Screenshots/", 3, 96, 800),
    Row("external_primary", "DCIM/Exports/2026/Trips/", 4, 31, 700),
    Row("external_primary", "Pictures/Instagram/", 5, 54, 600),
    Row("external_primary", "Android/media/com.whatsapp/WhatsApp/Media/", 6, 530, 500),
)

private val singleVolume = tree(*primaryRows)
private val twoVolumes = tree(*primaryRows, Row("1234-ABCD", "DCIM/Camera/", 7, 310, 400))

private val FakeThumbnails = ThumbnailSource { request, _ ->
    val hues = intArrayOf(0xFF7A8F6B.toInt(), 0xFF8C6E5A.toInt(), 0xFF5D7A99.toInt(), 0xFFA38B5C.toInt(), 0xFF6F5F8C.toInt())
    Bitmap.createBitmap(request.widthPx.coerceAtMost(256), request.heightPx.coerceAtMost(256), Bitmap.Config.ARGB_8888)
        .apply { eraseColor(hues[(request.mediaKey.mediaStoreId % hues.size).toInt()]) }
}
