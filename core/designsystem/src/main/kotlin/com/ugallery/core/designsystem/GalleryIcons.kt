package com.ugallery.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Icon definitions for UGallery, mapped to Material Symbols.
 * Matches the icon system from docs/mock/styles/gallery-components.css.
 *
 * The mockup uses SVG stroke-based custom icons; we use Material Symbols
 * Filled variants which are bundled with material-icons-extended.
 */

val GalleryIconBack: ImageVector get() = Icons.AutoMirrored.Filled.ArrowBack
val GalleryIconMore: ImageVector get() = Icons.Filled.MoreVert
val GalleryIconGrid: ImageVector get() = Icons.Filled.GridOn
val GalleryIconCollections: ImageVector get() = Icons.Filled.Collections
val GalleryIconSearch: ImageVector get() = Icons.Filled.Search
val GalleryIconPlus: ImageVector get() = Icons.Filled.Add
val GalleryIconHeart: ImageVector get() = Icons.Filled.Favorite
val GalleryIconVideo: ImageVector get() = Icons.Filled.Videocam
val GalleryIconTrash: ImageVector get() = Icons.Filled.Delete
val GalleryIconCamera: ImageVector get() = Icons.Filled.PhotoCamera  // placeholder, use Camera below
val GalleryIconMic: ImageVector get() = Icons.Filled.Mic
val GalleryIconClose: ImageVector get() = Icons.Filled.Close
val GalleryIconCheck: ImageVector get() = Icons.Filled.Check
val GalleryIconShare: ImageVector get() = Icons.Filled.Share
val GalleryIconAlbum: ImageVector get() = Icons.Filled.Collections  // closest to album
val GalleryIconEdit: ImageVector get() = Icons.Filled.Edit
val GalleryIconAnalyze: ImageVector get() = Icons.Filled.Crop  // closest to analyze/scan
val GalleryIconInfo: ImageVector get() = Icons.Filled.Info
val GalleryIconCrop: ImageVector get() = Icons.Filled.Crop
val GalleryIconTune: ImageVector get() = Icons.Filled.Tune
val GalleryIconPalette: ImageVector get() = Icons.Filled.Palette
val GalleryIconFolder: ImageVector get() = Icons.Filled.Folder  // placeholder, use Folder below
val GalleryIconLock: ImageVector get() = Icons.Filled.Lock
val GalleryIconUser: ImageVector get() = Icons.Filled.Person
val GalleryIconSettings: ImageVector get() = Icons.Filled.Settings
val GalleryIconPet: ImageVector get() = Icons.Filled.Pets
val GalleryIconWarning: ImageVector get() = Icons.Filled.Warning
val GalleryIconImage: ImageVector get() = Icons.Filled.Image
val GalleryIconPlay: ImageVector get() = Icons.Filled.PlayArrow
val GalleryIconPause: ImageVector get() = Icons.Filled.Pause
val GalleryIconUndo: ImageVector get() = Icons.AutoMirrored.Filled.Undo
val GalleryIconRedo: ImageVector get() = Icons.AutoMirrored.Filled.Redo  // placeholder
val GalleryIconDownload: ImageVector get() = Icons.Filled.Download
val GalleryIconSpeed: ImageVector get() = Icons.Filled.Speed
val GalleryIconVolume: ImageVector get() = Icons.Filled.VolumeUp
val GalleryIconMusic: ImageVector get() = Icons.Filled.MusicNote

/**
 * Standard gallery icon composable with Material 3 defaults:
 * 22dp size (matches CSS svg width/height: 22px), 1.9 stroke-width equivalent.
 * Color follows [LocalContentColor] (typically onSurface / onSurfaceVariant).
 *
 * @param imageVector The [ImageVector] to render (use GalleryIcon* vals)
 * @param contentDescription Accessibility description; null if purely decorative
 * @param modifier Optional modifier
 * @param tint Color override; defaults to LocalContentColor.current
 */
@Composable
fun GalleryIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(22.dp),
        tint = tint,
    )
}

/**
 * Convenience: gallery icons for common actions.
 * Reduces the chance of conflicting hard-coded icon choices.
 */
object GalleryIcons {
    val Back get() = GalleryIconBack
    val More get() = GalleryIconMore
    val Grid get() = GalleryIconGrid
    val Collections get() = GalleryIconCollections
    val Search get() = GalleryIconSearch
    val Plus get() = GalleryIconPlus
    val Heart get() = GalleryIconHeart
    val Video get() = GalleryIconVideo
    val Trash get() = GalleryIconTrash
    val Mic get() = GalleryIconMic
    val Close get() = GalleryIconClose
    val Check get() = GalleryIconCheck
    val Share get() = GalleryIconShare
    val Album get() = GalleryIconAlbum
    val Edit get() = GalleryIconEdit
    val Analyze get() = GalleryIconAnalyze
    val Info get() = GalleryIconInfo
    val Crop get() = GalleryIconCrop
    val Tune get() = GalleryIconTune
    val Palette get() = GalleryIconPalette
    val Lock get() = GalleryIconLock
    val User get() = GalleryIconUser
    val Settings get() = GalleryIconSettings
    val Pet get() = GalleryIconPet
    val Warning get() = GalleryIconWarning
    val Image get() = GalleryIconImage
    val Play get() = GalleryIconPlay
    val Pause get() = GalleryIconPause
    val Undo get() = GalleryIconUndo
    val Redo get() = GalleryIconRedo
    val Download get() = GalleryIconDownload
    val Speed get() = GalleryIconSpeed
    val Volume get() = GalleryIconVolume
    val Music get() = GalleryIconMusic
}
