package com.librestatic.lightforge.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AlignHorizontalCenter
import androidx.compose.material.icons.outlined.AlignVerticalBottom
import androidx.compose.material.icons.outlined.AlignVerticalCenter
import androidx.compose.material.icons.outlined.AlignVerticalTop
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Outlined icons for menus. Menus read lighter with thin outlined glyphs than with the filled
 * [GalleryIcons] used by top bars, chips and badges, so [GalleryMenuItem] swaps every
 * [GalleryIcons] glyph for its outlined twin through [outlined]. Glyphs without a twin (and
 * icons that are already outlined) are drawn as given.
 */
object GalleryMenuIcons {
    val Check: ImageVector get() = Icons.Outlined.Check

    val Print: ImageVector get() = Icons.Outlined.Print
    val SetAsWallpaper: ImageVector get() = Icons.Outlined.Wallpaper
    val MoveUp: ImageVector get() = Icons.Outlined.ArrowUpward
    val MoveDown: ImageVector get() = Icons.Outlined.ArrowDownward
    val EditDate: ImageVector get() = Icons.Outlined.EditCalendar

    // Photos sort options.
    val SortNewest: ImageVector get() = Icons.Outlined.ArrowDownward
    val SortOldest: ImageVector get() = Icons.Outlined.ArrowUpward
    val SortRecentlyModified: ImageVector get() = Icons.Outlined.EditCalendar
    val SortName: ImageVector get() = Icons.Outlined.SortByAlpha
    val SortLargest: ImageVector get() = Icons.Outlined.PhotoSizeSelectLarge

    // Photos grouping entry and options.
    val GroupBy: ImageVector get() = Icons.Outlined.CalendarMonth
    val GroupDay: ImageVector get() = Icons.Outlined.Today
    val GroupMonth: ImageVector get() = Icons.Outlined.CalendarViewMonth
    val GroupYear: ImageVector get() = Icons.Outlined.DateRange
    val GroupNone: ImageVector get() = Icons.Outlined.GridView

    /** The outlined twin of a [GalleryIcons] glyph, or [icon] itself when it has none. */
    fun outlined(icon: ImageVector): ImageVector = outlinedByFilled[icon] ?: icon

    // ImageVector has identity equality and every GalleryIcons getter returns a cached singleton.
    private val outlinedByFilled: Map<ImageVector, ImageVector> by lazy {
        mapOf(
            GalleryIconBack to Icons.AutoMirrored.Outlined.ArrowBack,
            GalleryIconChevronForward to Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            GalleryIconMore to Icons.Outlined.MoreVert,
            GalleryIconGrid to Icons.Outlined.GridOn,
            GalleryIconCollections to Icons.Outlined.Collections,
            GalleryIconSearch to Icons.Outlined.Search,
            GalleryIconPlus to Icons.Outlined.Add,
            GalleryIconHeart to Icons.Outlined.Favorite,
            GalleryIconVideo to Icons.Outlined.Videocam,
            GalleryIconTrash to Icons.Outlined.Delete,
            GalleryIconCamera to Icons.Outlined.PhotoCamera,
            GalleryIconMic to Icons.Outlined.Mic,
            GalleryIconClose to Icons.Outlined.Close,
            GalleryIconCheck to Icons.Outlined.Check,
            GalleryIconShare to Icons.Outlined.Share,
            GalleryIconAlbum to Icons.Outlined.PhotoAlbum,
            GalleryIconEdit to Icons.Outlined.Edit,
            GalleryIconAnalyze to Icons.Outlined.ImageSearch,
            GalleryIconPlace to Icons.Outlined.Place,
            GalleryIconAutoAwesome to Icons.Outlined.AutoAwesome,
            GalleryIconInfo to Icons.Outlined.Info,
            GalleryIconCrop to Icons.Outlined.Crop,
            GalleryIconTransform to Icons.Outlined.Transform,
            GalleryIconTune to Icons.Outlined.Tune,
            GalleryIconPalette to Icons.Outlined.Palette,
            GalleryIconLock to Icons.Outlined.Lock,
            GalleryIconUser to Icons.Outlined.Person,
            GalleryIconSettings to Icons.Outlined.Settings,
            GalleryIconCleanup to Icons.Outlined.CleaningServices,
            GalleryIconPet to Icons.Outlined.Pets,
            GalleryIconWarning to Icons.Outlined.Warning,
            GalleryIconImage to Icons.Outlined.Image,
            GalleryIconPlay to Icons.Outlined.PlayArrow,
            GalleryIconPause to Icons.Outlined.Pause,
            GalleryIconRepeat to Icons.Outlined.Repeat,
            GalleryIconUndo to Icons.AutoMirrored.Outlined.Undo,
            GalleryIconRedo to Icons.AutoMirrored.Outlined.Redo,
            GalleryIconDownload to Icons.Outlined.Download,
            GalleryIconSlowMotion to Icons.Outlined.SlowMotionVideo,
            GalleryIconFastForward to Icons.Outlined.FastForward,
            GalleryIconMarkIn to Icons.AutoMirrored.Outlined.Login,
            GalleryIconMarkOut to Icons.AutoMirrored.Outlined.Logout,
            GalleryIconSpeed to Icons.Outlined.Speed,
            GalleryIconVolume to Icons.AutoMirrored.Outlined.VolumeUp,
            GalleryIconVolumeOff to Icons.Outlined.VolumeOff,
            GalleryIconMusic to Icons.Outlined.MusicNote,
            GalleryIconArchive to Icons.Outlined.Archive,
            GalleryIconUnarchive to Icons.Outlined.Unarchive,
            GalleryIconRestoreFromTrash to Icons.Outlined.RestoreFromTrash,
            GalleryIconAsk to Icons.Outlined.AutoAwesome,
            GalleryIconFolder to Icons.Outlined.Folder,
            GalleryIconCreateNewFolder to Icons.Outlined.CreateNewFolder,
            GalleryIconVisibility to Icons.Outlined.Visibility,
            GalleryIconVisibilityOff to Icons.Outlined.VisibilityOff,
            GalleryIconSidePanel to Icons.AutoMirrored.Outlined.ViewSidebar,
            GalleryIconNotifications to Icons.Outlined.Notifications,
            GalleryIconLayers to Icons.Outlined.Layers,
            GalleryIconFitScreen to Icons.Outlined.FitScreen,
            GalleryIconResize to Icons.Outlined.OpenInFull,
            GalleryIconOpenInNew to Icons.AutoMirrored.Outlined.OpenInNew,
            GalleryIconCheckCircle to Icons.Outlined.CheckCircle,
            GalleryIconError to Icons.Outlined.Error,
            GalleryIconHistory to Icons.Outlined.History,
            GalleryIconRefresh to Icons.Outlined.Refresh,
            GalleryIconDragIndicator to Icons.Outlined.DragIndicator,
            GalleryIconRotateRight to Icons.AutoMirrored.Outlined.RotateRight,
            GalleryIconRotateLeft to Icons.AutoMirrored.Outlined.RotateLeft,
            GalleryIconAlignHorizontalLeft to Icons.AutoMirrored.Outlined.AlignHorizontalLeft,
            GalleryIconAlignHorizontalCenter to Icons.Outlined.AlignHorizontalCenter,
            GalleryIconAlignHorizontalRight to Icons.AutoMirrored.Outlined.AlignHorizontalRight,
            GalleryIconAlignVerticalTop to Icons.Outlined.AlignVerticalTop,
            GalleryIconAlignVerticalCenter to Icons.Outlined.AlignVerticalCenter,
            GalleryIconAlignVerticalBottom to Icons.Outlined.AlignVerticalBottom,
            GalleryIconFlipToFront to Icons.Outlined.FlipToFront,
            GalleryIconFlipToBack to Icons.Outlined.FlipToBack,
            GalleryIconZoomIn to Icons.Outlined.ZoomIn,
            GalleryIconLink to Icons.Outlined.Link,
            GalleryIconLinkOff to Icons.Outlined.LinkOff,
            GalleryIconSwapHoriz to Icons.Outlined.SwapHoriz,
            GalleryIconSwapVert to Icons.Outlined.SwapVert,
            GalleryIconWifi to Icons.Outlined.Wifi,
            GalleryIconLightbulb to Icons.Outlined.Lightbulb,
            GalleryIconChecklist to Icons.Outlined.Checklist,
            GalleryIconSelectAll to Icons.Outlined.SelectAll,
            GalleryIconCenterFocusStrong to Icons.Outlined.CenterFocusStrong,
            GalleryIconMinus to Icons.Outlined.Remove,
            GalleryIconPictureAsPdf to Icons.Outlined.PictureAsPdf,
            GalleryIconSort to Icons.Outlined.Sort,
            GalleryIconExpandMore to Icons.Outlined.ExpandMore,
            GalleryIconCalendarMonth to Icons.Outlined.CalendarMonth,
            GalleryIconUnchecked to Icons.Outlined.RadioButtonUnchecked,
            GalleryIconPin to Icons.Outlined.PushPin,
            GalleryIconBlock to Icons.Outlined.Block,
            GalleryIconViewList to Icons.AutoMirrored.Outlined.ViewList,
            GalleryIconRename to Icons.Outlined.DriveFileRenameOutline,
            GalleryIconDriveFileMove to Icons.Outlined.DriveFileMove,
            GalleryIconContentCopy to Icons.Outlined.ContentCopy,
            GalleryIconDescription to Icons.Outlined.Description,
            GalleryIconReceipt to Icons.Outlined.Receipt,
            GalleryIconGridView to Icons.Outlined.GridView,
            GalleryIconPhoto to Icons.Outlined.Photo,
            GalleryIconKeyboard to Icons.Outlined.Keyboard,
            GalleryIconStraighten to Icons.Outlined.Straighten,
            GalleryIconPhotoLibrary to Icons.Outlined.PhotoLibrary,
            GalleryIconAddPhotoAlternate to Icons.Outlined.AddPhotoAlternate,
            GalleryIconTextFields to Icons.Outlined.TextFields,
        )
    }
}
