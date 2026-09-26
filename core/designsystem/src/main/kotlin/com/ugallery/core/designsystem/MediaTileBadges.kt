package com.ugallery.core.designsystem

import android.content.ContentUris
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.provider.MediaStore
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Settings' thumbnail badges and grid animation, provided once at the app root. */
data class ThumbnailTileSettings(
    val markFavorites: Boolean = true,
    val showFileType: Boolean = true,
    val animateMedia: Boolean = false,
)

val LocalThumbnailTileSettings = staticCompositionLocalOf { ThumbnailTileSettings() }

private val RawExtensions = setOf(
    "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "srw", "raf", "orf", "rw2", "pef", "raw", "3fr", "iiq", "x3f",
)

/**
 * Short file type label for a grid tile, or null for the everyday formats (JPEG photos, MP4
 * videos) so only formats worth noticing carry a badge.
 */
fun mediaFileTypeLabel(displayName: String?, isVideo: Boolean): String? {
    val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    if (extension.isEmpty()) return null
    if (isVideo) return when (extension) {
        "mp4", "m4v" -> null
        "mov", "webm", "mkv", "3gp", "avi" -> extension.uppercase()
        else -> null
    }
    return when (extension) {
        "jpg", "jpeg", "jpe" -> null
        "gif", "png", "webp", "avif", "bmp" -> extension.uppercase()
        "heic", "heif" -> "HEIC"
        "tif", "tiff" -> "TIFF"
        in RawExtensions -> "RAW"
        else -> null
    }
}

/** True for names whose content may be animated (GIF, WebP) and worth decoding as such. */
fun isAnimatableMediaName(displayName: String?): Boolean {
    val extension = displayName?.substringAfterLast('.', "")?.lowercase() ?: return false
    return extension == "gif" || extension == "webp"
}

fun mediaStoreImageUri(volumeName: String, mediaStoreId: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volumeName), mediaStoreId)

/**
 * Favorite heart, file type and "Archived" badges for the top-start corner of a grid tile. The
 * archived badge is not a display preference: surfaces that mix archived items in always show it.
 */
@Composable
fun MediaTileBadges(
    isFavorite: Boolean,
    displayName: String?,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    isArchived: Boolean = false,
) {
    val settings = LocalThumbnailTileSettings.current
    val showFavorite = settings.markFavorites && isFavorite
    val typeLabel = if (settings.showFileType) mediaFileTypeLabel(displayName, isVideo) else null
    if (!showFavorite && typeLabel == null && !isArchived) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isArchived) {
            val label = stringResource(R.string.media_tile_archived)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            ) {
                Text(label, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showFavorite) {
            val description = stringResource(R.string.media_tile_favorite_description)
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            ) {
                Icon(GalleryIconHeart, contentDescription = null, modifier = Modifier.padding(3.dp).size(14.dp))
            }
        }
        if (typeLabel != null) {
            val description = stringResource(R.string.media_tile_file_type_description, typeLabel)
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            ) {
                Text(typeLabel, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** At most this many grid tiles decode and play an animation at once. */
private const val MaxAnimatedTiles = 4
private val animatedTilePermits = Semaphore(MaxAnimatedTiles)

/**
 * Plays a GIF/WebP over its static thumbnail when "Animate GIF and WebP" is on. Only tiles that
 * are laid out on screen take one of [MaxAnimatedTiles] permits, and frames decode at tile size.
 */
@Composable
fun AnimatedMediaTile(
    uri: Uri,
    displayName: String?,
    sizePx: Int,
    modifier: Modifier = Modifier,
) {
    if (!LocalThumbnailTileSettings.current.animateMedia || !isAnimatableMediaName(displayName)) return
    val resolver = LocalContext.current.contentResolver
    var visible by remember(uri) { mutableStateOf(false) }
    var drawable by remember(uri) { mutableStateOf<AnimatedImageDrawable?>(null) }
    LaunchedEffect(uri, sizePx, visible) {
        if (!visible) return@LaunchedEffect
        animatedTilePermits.withPermit {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        val shortSide = min(info.size.width, info.size.height).coerceAtLeast(1)
                        val scale = sizePx.toFloat() / shortSide
                        if (scale < 1f) {
                            decoder.setTargetSize(
                                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                                (info.size.height * scale).roundToInt().coerceAtLeast(1),
                            )
                        }
                    }
                }.getOrNull()
            } as? AnimatedImageDrawable ?: return@withPermit
            drawable = decoded
            try {
                awaitCancellation()
            } finally {
                decoded.stop()
                drawable = null
            }
        }
    }
    Box(
        modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            visible = coordinates.isAttached && !coordinates.boundsInWindow().isEmpty
        },
    ) {
        drawable?.let { animated ->
            AndroidView(
                factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                update = { view ->
                    if (view.drawable !== animated) view.setImageDrawable(animated)
                    animated.start()
                },
                onRelease = { view -> view.setImageDrawable(null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
