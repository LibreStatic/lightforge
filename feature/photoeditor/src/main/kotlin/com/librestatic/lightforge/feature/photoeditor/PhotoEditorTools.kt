package com.librestatic.lightforge.feature.photoeditor

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.model.AdjustKey
import java.text.Normalizer
import java.util.Locale

/** What the inspector shows. Eraser and cut-out are sub-tools of the Actions tab. */
internal enum class PhotoEditorTool { Automatic, Crop, Adjust, Filters, ObjectEraser, SubjectClip, More, Raw, Export }

/** The pill tabs. */
internal enum class PhotoEditorTab { Automatic, Crop, Adjust, Actions, Filters, More, Raw, Export }

internal fun PhotoEditorTool.tab(): PhotoEditorTab = when (this) {
    PhotoEditorTool.Automatic -> PhotoEditorTab.Automatic
    PhotoEditorTool.Crop -> PhotoEditorTab.Crop
    PhotoEditorTool.Adjust -> PhotoEditorTab.Adjust
    PhotoEditorTool.Filters -> PhotoEditorTab.Filters
    PhotoEditorTool.ObjectEraser, PhotoEditorTool.SubjectClip -> PhotoEditorTab.Actions
    PhotoEditorTool.More -> PhotoEditorTab.More
    PhotoEditorTool.Raw -> PhotoEditorTab.Raw
    PhotoEditorTool.Export -> PhotoEditorTab.Export
}

internal fun PhotoEditorTab.firstTool(): PhotoEditorTool = when (this) {
    PhotoEditorTab.Automatic -> PhotoEditorTool.Automatic
    PhotoEditorTab.Crop -> PhotoEditorTool.Crop
    PhotoEditorTab.Adjust -> PhotoEditorTool.Adjust
    PhotoEditorTab.Actions -> PhotoEditorTool.ObjectEraser
    PhotoEditorTab.Filters -> PhotoEditorTool.Filters
    PhotoEditorTab.More -> PhotoEditorTool.More
    PhotoEditorTab.Raw -> PhotoEditorTool.Raw
    PhotoEditorTab.Export -> PhotoEditorTool.Export
}

/** Tabs for the photo kind. More only exists while the host can open another editor (no empty tabs). */
internal fun photoEditorTabs(isRaw: Boolean, canOpenWith: Boolean): List<PhotoEditorTab> =
    if (isRaw) {
        listOf(PhotoEditorTab.Raw, PhotoEditorTab.Crop, PhotoEditorTab.Export)
    } else {
        listOfNotNull(
            PhotoEditorTab.Automatic,
            PhotoEditorTab.Crop,
            PhotoEditorTab.Adjust,
            PhotoEditorTab.Actions,
            PhotoEditorTab.Filters,
            PhotoEditorTab.More.takeIf { canOpenWith },
        )
    }

/** One tile of the Adjust carousel; [ColorMix] has no [AdjustKey] and opens the colour swatches. */
internal enum class PhotoAdjustTile(val key: AdjustKey?, val token: String) {
    Brightness(AdjustKey.Brightness, "brightness"),
    Contrast(AdjustKey.Contrast, "contrast"),
    WhitePoint(AdjustKey.WhitePoint, "whitepoint"),
    Highlights(AdjustKey.Highlights, "highlights"),
    Shadows(AdjustKey.Shadows, "shadows"),
    BlackPoint(AdjustKey.BlackPoint, "blackpoint"),
    Tone(AdjustKey.Tone, "tone"),
    Saturation(AdjustKey.Saturation, "saturation"),
    Warmth(AdjustKey.Warmth, "warmth"),
    Tint(AdjustKey.Tint, "tint"),
    SkinTone(AdjustKey.SkinTone, "skintone"),
    BlueTone(AdjustKey.BlueTone, "bluetone"),
    Vignette(AdjustKey.Vignette, "vignette"),
    ColorMix(null, "colormix"),
}

@get:StringRes
internal val PhotoAdjustTile.labelRes: Int
    get() = when (this) {
        PhotoAdjustTile.Brightness -> R.string.photo_editor_brightness
        PhotoAdjustTile.Contrast -> R.string.photo_editor_contrast
        PhotoAdjustTile.WhitePoint -> R.string.photo_editor_adjust_white_point
        PhotoAdjustTile.Highlights -> R.string.photo_editor_adjust_highlights
        PhotoAdjustTile.Shadows -> R.string.photo_editor_shadows
        PhotoAdjustTile.BlackPoint -> R.string.photo_editor_adjust_black_point
        PhotoAdjustTile.Tone -> R.string.photo_editor_adjust_tone
        PhotoAdjustTile.Saturation -> R.string.photo_editor_saturation
        PhotoAdjustTile.Warmth -> R.string.photo_editor_adjust_warmth
        PhotoAdjustTile.Tint -> R.string.photo_editor_tint
        PhotoAdjustTile.SkinTone -> R.string.photo_editor_adjust_skin_tone
        PhotoAdjustTile.BlueTone -> R.string.photo_editor_adjust_blue_tone
        PhotoAdjustTile.Vignette -> R.string.photo_editor_adjust_vignette
        PhotoAdjustTile.ColorMix -> R.string.photo_editor_adjust_color_mix
    }

internal val PhotoAdjustTile.icon: ImageVector
    get() = when (this) {
        PhotoAdjustTile.Brightness -> GalleryIcons.AdjustLevel
        PhotoAdjustTile.Contrast -> GalleryIcons.AdjustContrast
        PhotoAdjustTile.WhitePoint -> GalleryIcons.AdjustExposure
        PhotoAdjustTile.Highlights -> GalleryIcons.AdjustHighlights
        PhotoAdjustTile.Shadows -> GalleryIcons.AdjustShadows
        PhotoAdjustTile.BlackPoint -> GalleryIcons.AdjustLuminance
        PhotoAdjustTile.Tone -> GalleryIcons.AdjustMidtones
        PhotoAdjustTile.Saturation -> GalleryIcons.AdjustSaturation
        PhotoAdjustTile.Warmth -> GalleryIcons.AdjustTemperature
        PhotoAdjustTile.Tint -> GalleryIcons.AdjustTint
        PhotoAdjustTile.SkinTone -> GalleryIcons.User
        PhotoAdjustTile.BlueTone -> GalleryIcons.AdjustHue
        PhotoAdjustTile.Vignette -> GalleryIcons.AdjustPivot
        PhotoAdjustTile.ColorMix -> GalleryIcons.Palette
    }

/** Sub-tools of the crop tab, in carousel order. */
internal enum class PhotoCropTool(val token: String) {
    Crop("crop"), Straighten("straighten"), Rotate("rotate"), Perspective("perspective"), Flip("flip"),
}

@get:StringRes
internal val PhotoCropTool.labelRes: Int
    get() = when (this) {
        PhotoCropTool.Crop -> R.string.photo_editor_crop
        PhotoCropTool.Straighten -> R.string.photo_editor_straighten
        PhotoCropTool.Rotate -> R.string.photo_editor_rotate
        PhotoCropTool.Perspective -> R.string.photo_editor_perspective
        PhotoCropTool.Flip -> R.string.photo_editor_flip
    }

internal val PhotoCropTool.icon: ImageVector
    get() = when (this) {
        PhotoCropTool.Crop -> GalleryIcons.Crop
        PhotoCropTool.Straighten -> GalleryIcons.Straighten
        PhotoCropTool.Rotate -> GalleryIcons.RotateRight
        PhotoCropTool.Perspective -> GalleryIcons.Transform
        PhotoCropTool.Flip -> GalleryIcons.SwapHoriz
    }

internal enum class PhotoLooksTab { Filters, Mine }

/** Where a tapped search result (or a restored session) lands. Null sub-selections keep the current one. */
internal data class PhotoToolDestination(
    val tool: PhotoEditorTool,
    val adjustTile: PhotoAdjustTile? = null,
    val cropTool: PhotoCropTool? = null,
    val looksTab: PhotoLooksTab? = null,
)

/** A searchable tool; its localized name is [labelRes]. */
internal data class PhotoToolEntry(
    @StringRes val labelRes: Int,
    val destination: PhotoToolDestination,
    val icon: ImageVector,
)

/** Every tool the editor offers for this kind of photo, in tab order. */
internal fun photoToolEntries(isRaw: Boolean, canOpenWith: Boolean): List<PhotoToolEntry> = buildList {
    if (isRaw) add(PhotoToolEntry(R.string.photo_editor_raw_title, PhotoToolDestination(PhotoEditorTool.Raw), GalleryIcons.Tune))
    else add(PhotoToolEntry(R.string.photo_editor_automatic, PhotoToolDestination(PhotoEditorTool.Automatic), GalleryIcons.AutoAwesome))
    PhotoCropTool.entries.forEach {
        add(PhotoToolEntry(it.labelRes, PhotoToolDestination(PhotoEditorTool.Crop, cropTool = it), it.icon))
    }
    if (isRaw) {
        add(PhotoToolEntry(R.string.photo_editor_export_tab, PhotoToolDestination(PhotoEditorTool.Export), GalleryIcons.Download))
        return@buildList
    }
    PhotoAdjustTile.entries.forEach {
        add(PhotoToolEntry(it.labelRes, PhotoToolDestination(PhotoEditorTool.Adjust, adjustTile = it), it.icon))
    }
    add(PhotoToolEntry(R.string.photo_editor_object_eraser, PhotoToolDestination(PhotoEditorTool.ObjectEraser), GalleryIcons.Cleanup))
    add(PhotoToolEntry(R.string.photo_editor_subject_clip, PhotoToolDestination(PhotoEditorTool.SubjectClip), GalleryIcons.CenterFocusStrong))
    add(PhotoToolEntry(R.string.photo_editor_filters, PhotoToolDestination(PhotoEditorTool.Filters, looksTab = PhotoLooksTab.Filters), GalleryIcons.Palette))
    add(PhotoToolEntry(R.string.photo_editor_looks_mine, PhotoToolDestination(PhotoEditorTool.Filters, looksTab = PhotoLooksTab.Mine), GalleryIcons.Heart))
    if (canOpenWith) {
        add(PhotoToolEntry(R.string.photo_editor_open_with, PhotoToolDestination(PhotoEditorTool.More), GalleryIcons.OpenInNew))
    }
}

/** Lower-cased, accent-free form used to compare a query with a tool name. */
internal fun normalizeSearchText(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

/**
 * The entries whose localized name ([labelOf]) matches [query]: every query word must start a word
 * of the name, or (from 3 characters) the name must contain the whole query. A blank query keeps everything in order.
 * Accents and case are ignored, so "saturacion" finds "Saturación".
 */
internal fun filterToolEntries(
    entries: List<PhotoToolEntry>,
    query: String,
    labelOf: (PhotoToolEntry) -> String,
): List<PhotoToolEntry> {
    val needle = normalizeSearchText(query)
    if (needle.isEmpty()) return entries
    val queryWords = needle.split(Regex("\\s+"))
    return entries.filter { entry ->
        val name = normalizeSearchText(labelOf(entry))
        val nameWords = name.split(Regex("[^\\p{L}\\p{N}:]+")).filter { it.isNotEmpty() }
        (needle.length >= 3 && name.contains(needle)) || queryWords.all { word -> nameWords.any { it.startsWith(word) } }
    }
}
