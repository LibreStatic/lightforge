package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Brightness5
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.rounded.AlignHorizontalCenter
import androidx.compose.material.icons.automirrored.rounded.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.rounded.AlignHorizontalRight
import androidx.compose.material.icons.rounded.AlignVerticalTop
import androidx.compose.material.icons.rounded.AlignVerticalCenter
import androidx.compose.material.icons.rounded.AlignVerticalBottom
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.FlipToBack
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Icon definitions for Lightforge, mapped to Material Symbols.
 * Matches the icon system from docs/mock/styles/gallery-components.css.
 *
 * The mockup uses SVG stroke-based custom icons; we use Material Symbols
 * Filled variants which are bundled with material-icons-extended.
 */

val GalleryIconBack: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
val GalleryIconChevronForward: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight
val GalleryIconMore: ImageVector get() = Icons.Rounded.MoreVert
val GalleryIconGrid: ImageVector get() = Icons.Rounded.GridOn
val GalleryIconCollections: ImageVector get() = Icons.Rounded.Collections
val GalleryIconSearch: ImageVector get() = Icons.Rounded.Search
val GalleryIconPlus: ImageVector get() = Icons.Rounded.Add
val GalleryIconHeart: ImageVector get() = Icons.Rounded.Favorite
val GalleryIconVideo: ImageVector get() = Icons.Rounded.Videocam
val GalleryIconTrash: ImageVector get() = Icons.Rounded.Delete
val GalleryIconCamera: ImageVector get() = Icons.Rounded.PhotoCamera
val GalleryIconMic: ImageVector get() = Icons.Rounded.Mic
val GalleryIconClose: ImageVector get() = Icons.Rounded.Close
val GalleryIconCheck: ImageVector get() = Icons.Rounded.Check
val GalleryIconShare: ImageVector get() = Icons.Rounded.Share
val GalleryIconAlbum: ImageVector get() = Icons.Rounded.PhotoAlbum
val GalleryIconEdit: ImageVector get() = Icons.Rounded.Edit
val GalleryIconAnalyze: ImageVector get() = Icons.Rounded.ImageSearch
val GalleryIconPlace: ImageVector get() = Icons.Rounded.Place
val GalleryIconAutoAwesome: ImageVector get() = Icons.Rounded.AutoAwesome
val GalleryIconInfo: ImageVector get() = Icons.Rounded.Info
val GalleryIconCrop: ImageVector get() = Icons.Rounded.Crop
val GalleryIconTransform: ImageVector get() = Icons.Rounded.Transform
val GalleryIconTune: ImageVector get() = Icons.Rounded.Tune
val GalleryIconPalette: ImageVector get() = Icons.Rounded.Palette
val GalleryIconLock: ImageVector get() = Icons.Rounded.Lock
val GalleryIconUser: ImageVector get() = Icons.Rounded.Person
val GalleryIconSettings: ImageVector get() = Icons.Rounded.Settings
val GalleryIconCleanup: ImageVector get() = Icons.Rounded.CleaningServices
val GalleryIconPet: ImageVector get() = Icons.Rounded.Pets
val GalleryIconWarning: ImageVector get() = Icons.Rounded.Warning
val GalleryIconImage: ImageVector get() = Icons.Rounded.Image
val GalleryIconPlay: ImageVector get() = Icons.Rounded.PlayArrow
val GalleryIconPause: ImageVector get() = Icons.Rounded.Pause
val GalleryIconRepeat: ImageVector get() = Icons.Rounded.Repeat
val GalleryIconUndo: ImageVector get() = Icons.AutoMirrored.Rounded.Undo
val GalleryIconRedo: ImageVector get() = Icons.AutoMirrored.Rounded.Redo
val GalleryIconDownload: ImageVector get() = Icons.Rounded.Download
val GalleryIconSpeed: ImageVector get() = Icons.Rounded.Speed
val GalleryIconVolume: ImageVector get() = Icons.AutoMirrored.Rounded.VolumeUp
val GalleryIconVolumeOff: ImageVector get() = Icons.Rounded.VolumeOff
val GalleryIconMusic: ImageVector get() = Icons.Rounded.MusicNote
val GalleryIconArchive: ImageVector get() = Icons.Rounded.Archive
val GalleryIconUnarchive: ImageVector get() = Icons.Rounded.Unarchive
val GalleryIconRestoreFromTrash: ImageVector get() = Icons.Rounded.RestoreFromTrash
val GalleryIconAsk: ImageVector get() = Icons.Rounded.AutoAwesome
val GalleryIconFolder: ImageVector get() = Icons.Rounded.Folder
val GalleryIconSidePanel: ImageVector get() = Icons.AutoMirrored.Rounded.ViewSidebar
val GalleryIconNotifications: ImageVector get() = Icons.Rounded.Notifications
val GalleryIconLayers: ImageVector get() = Icons.Rounded.Layers
val GalleryIconFitScreen: ImageVector get() = Icons.Rounded.FitScreen
val GalleryIconResize: ImageVector get() = Icons.Rounded.OpenInFull
val GalleryIconOpenInNew: ImageVector get() = Icons.AutoMirrored.Rounded.OpenInNew
val GalleryIconCheckCircle: ImageVector get() = Icons.Rounded.CheckCircle
val GalleryIconError: ImageVector get() = Icons.Rounded.Error
val GalleryIconHistory: ImageVector get() = Icons.Rounded.History
val GalleryIconDragIndicator: ImageVector get() = Icons.Rounded.DragIndicator
val GalleryIconRotateRight: ImageVector get() = Icons.AutoMirrored.Rounded.RotateRight
val GalleryIconRotateLeft: ImageVector get() = Icons.AutoMirrored.Rounded.RotateLeft
val GalleryIconAlignHorizontalLeft: ImageVector get() = Icons.AutoMirrored.Rounded.AlignHorizontalLeft
val GalleryIconAlignHorizontalCenter: ImageVector get() = Icons.Rounded.AlignHorizontalCenter
val GalleryIconAlignHorizontalRight: ImageVector get() = Icons.AutoMirrored.Rounded.AlignHorizontalRight
val GalleryIconAlignVerticalTop: ImageVector get() = Icons.Rounded.AlignVerticalTop
val GalleryIconAlignVerticalCenter: ImageVector get() = Icons.Rounded.AlignVerticalCenter
val GalleryIconAlignVerticalBottom: ImageVector get() = Icons.Rounded.AlignVerticalBottom
val GalleryIconFlipToFront: ImageVector get() = Icons.Rounded.FlipToFront
val GalleryIconFlipToBack: ImageVector get() = Icons.Rounded.FlipToBack
val GalleryIconZoomIn: ImageVector get() = Icons.Rounded.ZoomIn
val GalleryIconLink: ImageVector get() = Icons.Rounded.Link
val GalleryIconLinkOff: ImageVector get() = Icons.Rounded.LinkOff
val GalleryIconSwapHoriz: ImageVector get() = Icons.Rounded.SwapHoriz
val GalleryIconSwapVert: ImageVector get() = Icons.Rounded.SwapVert
val GalleryIconWifi: ImageVector get() = Icons.Rounded.Wifi
val GalleryIconLightbulb: ImageVector get() = Icons.Rounded.Lightbulb

// Fix-round item 5: material-icons-core (the only icon set this project allows — no material-
// icons-extended) has no dedicated "distribute spacing" glyph, and SwapHoriz/SwapVert's crossed-
// arrows shape reads as "swap", not "distribute" — confusing next to the group Align menu's own
// arrow icon. Hand-built instead, in the same 24x24 viewport/rounded-corner spirit as the bundled
// Rounded set: three equal blocks with equal gaps between them (Material Symbols' own
// "horizontal_distribute"/"vertical_distribute" use the same three-equal-gaps motif), no new
// dependency.
private val GalleryIconDistributeHorizontalCustom: ImageVector by lazy {
    ImageVector.Builder(
            name = "DistributeHorizontal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(fill = SolidColor(Color.Black)) {
                // Three vertical bars, evenly spaced, equal width/gaps: "space these evenly".
                moveTo(3f, 4f)
                lineTo(6f, 4f)
                lineTo(6f, 20f)
                lineTo(3f, 20f)
                close()
                moveTo(10.5f, 4f)
                lineTo(13.5f, 4f)
                lineTo(13.5f, 20f)
                lineTo(10.5f, 20f)
                close()
                moveTo(18f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(18f, 20f)
                close()
            }
        }
        .build()
}

private val GalleryIconDistributeVerticalCustom: ImageVector by lazy {
    ImageVector.Builder(
            name = "DistributeVertical",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .apply {
            path(fill = SolidColor(Color.Black)) {
                // Same motif, rotated: three horizontal bars, evenly spaced.
                moveTo(4f, 3f)
                lineTo(20f, 3f)
                lineTo(20f, 6f)
                lineTo(4f, 6f)
                close()
                moveTo(4f, 10.5f)
                lineTo(20f, 10.5f)
                lineTo(20f, 13.5f)
                lineTo(4f, 13.5f)
                close()
                moveTo(4f, 18f)
                lineTo(20f, 18f)
                lineTo(20f, 21f)
                lineTo(4f, 21f)
                close()
            }
        }
        .build()
}
val GalleryIconDistributeHorizontal: ImageVector get() = GalleryIconDistributeHorizontalCustom
val GalleryIconDistributeVertical: ImageVector get() = GalleryIconDistributeVerticalCustom
val GalleryIconChecklist: ImageVector get() = Icons.Rounded.Checklist
val GalleryIconSelectAll: ImageVector get() = Icons.Rounded.SelectAll
val GalleryIconCenterFocusStrong: ImageVector get() = Icons.Rounded.CenterFocusStrong
val GalleryIconMinus: ImageVector get() = Icons.Rounded.Remove
val GalleryIconPictureAsPdf: ImageVector get() = Icons.Rounded.PictureAsPdf
val GalleryIconSort: ImageVector get() = Icons.Rounded.Sort
val GalleryIconExpandMore: ImageVector get() = Icons.Rounded.ExpandMore
val GalleryIconCalendarMonth: ImageVector get() = Icons.Rounded.CalendarMonth
val GalleryIconUnchecked: ImageVector get() = Icons.Rounded.RadioButtonUnchecked
val GalleryIconRename: ImageVector get() = Icons.Rounded.DriveFileRenameOutline
val GalleryIconContentCopy: ImageVector get() = Icons.Rounded.ContentCopy
val GalleryIconReceipt: ImageVector get() = Icons.Rounded.Receipt
val GalleryIconGridView: ImageVector get() = Icons.Rounded.GridView
val GalleryIconPhoto: ImageVector get() = Icons.Rounded.Photo
val GalleryIconKeyboard: ImageVector get() = Icons.Rounded.Keyboard
val GalleryIconStraighten: ImageVector get() = Icons.Rounded.Straighten
val GalleryIconPhotoLibrary: ImageVector get() = Icons.Rounded.PhotoLibrary
val GalleryIconAddPhotoAlternate: ImageVector get() = Icons.Rounded.AddPhotoAlternate
val GalleryIconTextFields: ImageVector get() = Icons.Rounded.TextFields
val GalleryIconFormatBold: ImageVector get() = Icons.Rounded.FormatBold
/** Physical left/right, deliberately NOT [Icons.AutoMirrored] — the PDF Studio text layer's
 * Start/Center/End alignment is never mirrored in RTL (the page is a physical print layout), so
 * its icons must not flip either. */
val GalleryIconFormatAlignLeft: ImageVector get() = Icons.Rounded.FormatAlignLeft
val GalleryIconFormatAlignCenter: ImageVector get() = Icons.Rounded.FormatAlignCenter
val GalleryIconFormatAlignRight: ImageVector get() = Icons.Rounded.FormatAlignRight

// Adjustment indicators for editor sliders (decorative; the slider label names the control).
val GalleryIconAdjustExposure: ImageVector get() = Icons.Rounded.Exposure
val GalleryIconAdjustContrast: ImageVector get() = Icons.Rounded.Contrast
val GalleryIconAdjustHighlights: ImageVector get() = Icons.Rounded.LightMode
val GalleryIconAdjustShadows: ImageVector get() = Icons.Rounded.DarkMode
val GalleryIconAdjustMidtones: ImageVector get() = Icons.Rounded.Tonality
val GalleryIconAdjustLevel: ImageVector get() = Icons.Rounded.Brightness6
val GalleryIconAdjustTemperature: ImageVector get() = Icons.Rounded.Thermostat
val GalleryIconAdjustTint: ImageVector get() = Icons.Rounded.Gradient
val GalleryIconAdjustSaturation: ImageVector get() = Icons.Rounded.InvertColors
val GalleryIconAdjustHue: ImageVector get() = Icons.Rounded.Colorize
val GalleryIconAdjustLuminance: ImageVector get() = Icons.Rounded.Brightness5
val GalleryIconAdjustPivot: ImageVector get() = Icons.Rounded.Adjust
val GalleryIconAdjustIntensity: ImageVector get() = Icons.Rounded.Opacity

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
        modifier = modifier.size(24.dp),
        tint = tint,
    )
}

/**
 * Convenience: gallery icons for common actions.
 * Reduces the chance of conflicting hard-coded icon choices.
 */
object GalleryIcons {
    val Back get() = GalleryIconBack
    val ChevronForward get() = GalleryIconChevronForward
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
    val Place get() = GalleryIconPlace
    val AutoAwesome get() = GalleryIconAutoAwesome
    val Info get() = GalleryIconInfo
    val Crop get() = GalleryIconCrop
    val Transform get() = GalleryIconTransform
    val Tune get() = GalleryIconTune
    val Palette get() = GalleryIconPalette
    val Lock get() = GalleryIconLock
    val User get() = GalleryIconUser
    val Settings get() = GalleryIconSettings
    val Cleanup get() = GalleryIconCleanup
    val Pet get() = GalleryIconPet
    val Warning get() = GalleryIconWarning
    val Image get() = GalleryIconImage
    val Play get() = GalleryIconPlay
    val Pause get() = GalleryIconPause
    val Repeat get() = GalleryIconRepeat
    val Undo get() = GalleryIconUndo
    val Redo get() = GalleryIconRedo
    val Download get() = GalleryIconDownload
    val Speed get() = GalleryIconSpeed
    val Volume get() = GalleryIconVolume
    val VolumeOff get() = GalleryIconVolumeOff
    val Music get() = GalleryIconMusic
    val Archive get() = GalleryIconArchive
    val Unarchive get() = GalleryIconUnarchive
    val RestoreFromTrash get() = GalleryIconRestoreFromTrash
    val Ask get() = GalleryIconAsk
    val Folder get() = GalleryIconFolder
    val SidePanel get() = GalleryIconSidePanel
    val Notifications get() = GalleryIconNotifications
    val Layers get() = GalleryIconLayers
    val FitScreen get() = GalleryIconFitScreen
    val Resize get() = GalleryIconResize
    val OpenInNew get() = GalleryIconOpenInNew
    val CheckCircle get() = GalleryIconCheckCircle
    val Error get() = GalleryIconError
    val History get() = GalleryIconHistory
    val DragIndicator get() = GalleryIconDragIndicator
    val RotateRight get() = GalleryIconRotateRight
    val RotateLeft get() = GalleryIconRotateLeft
    val AlignHorizontalLeft get() = GalleryIconAlignHorizontalLeft
    val AlignHorizontalCenter get() = GalleryIconAlignHorizontalCenter
    val AlignHorizontalRight get() = GalleryIconAlignHorizontalRight
    val AlignVerticalTop get() = GalleryIconAlignVerticalTop
    val AlignVerticalCenter get() = GalleryIconAlignVerticalCenter
    val AlignVerticalBottom get() = GalleryIconAlignVerticalBottom
    val FlipToFront get() = GalleryIconFlipToFront
    val FlipToBack get() = GalleryIconFlipToBack
    val ZoomIn get() = GalleryIconZoomIn
    val Link get() = GalleryIconLink
    val LinkOff get() = GalleryIconLinkOff
    val SwapHoriz get() = GalleryIconSwapHoriz
    val SwapVert get() = GalleryIconSwapVert
    val DistributeHorizontal get() = GalleryIconDistributeHorizontal
    val DistributeVertical get() = GalleryIconDistributeVertical
    val Checklist get() = GalleryIconChecklist
    val SelectAll get() = GalleryIconSelectAll
    val CenterFocusStrong get() = GalleryIconCenterFocusStrong
    val Minus get() = GalleryIconMinus
    val PictureAsPdf get() = GalleryIconPictureAsPdf
    val Sort get() = GalleryIconSort
    val Rename get() = GalleryIconRename
    val ContentCopy get() = GalleryIconContentCopy
    val Receipt get() = GalleryIconReceipt
    val GridView get() = GalleryIconGridView
    val Photo get() = GalleryIconPhoto
    val Keyboard get() = GalleryIconKeyboard
    val Straighten get() = GalleryIconStraighten
    val PhotoLibrary get() = GalleryIconPhotoLibrary
    val AddPhotoAlternate get() = GalleryIconAddPhotoAlternate
    val TextFields get() = GalleryIconTextFields
    val FormatBold get() = GalleryIconFormatBold
    val FormatAlignLeft get() = GalleryIconFormatAlignLeft
    val FormatAlignCenter get() = GalleryIconFormatAlignCenter
    val FormatAlignRight get() = GalleryIconFormatAlignRight
    val ExpandMore get() = GalleryIconExpandMore
    val CalendarMonth get() = GalleryIconCalendarMonth
    val Unchecked get() = GalleryIconUnchecked
    val AdjustExposure get() = GalleryIconAdjustExposure
    val AdjustContrast get() = GalleryIconAdjustContrast
    val AdjustHighlights get() = GalleryIconAdjustHighlights
    val AdjustShadows get() = GalleryIconAdjustShadows
    val AdjustMidtones get() = GalleryIconAdjustMidtones
    val AdjustLevel get() = GalleryIconAdjustLevel
    val AdjustTemperature get() = GalleryIconAdjustTemperature
    val AdjustTint get() = GalleryIconAdjustTint
    val AdjustSaturation get() = GalleryIconAdjustSaturation
    val AdjustHue get() = GalleryIconAdjustHue
    val AdjustLuminance get() = GalleryIconAdjustLuminance
    val AdjustPivot get() = GalleryIconAdjustPivot
    val AdjustIntensity get() = GalleryIconAdjustIntensity
}
