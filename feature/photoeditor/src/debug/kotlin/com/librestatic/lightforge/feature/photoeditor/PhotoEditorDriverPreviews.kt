package com.librestatic.lightforge.feature.photoeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.ml.ModelDownloadWait
import com.librestatic.lightforge.core.model.AdjustKey
import com.librestatic.lightforge.core.model.ColorMixBand
import com.librestatic.lightforge.core.model.ColorMixHue
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.feature.objecteraser.InpaintModelStatus

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds fake state into the real production composable; callbacks are no-ops.

/** Editor with a loaded photo and undo history. Driver: `PhotoEditorDriverPreviewsKt.PhotoEditorPreview`. */
@Preview
@Composable
fun PhotoEditorPreview() = DriverFrame {
    val photo = remember { fakePhoto() }
    PhotoEditorContent(
        state = PhotoEditorContentState(preview = photo, originalPreview = photo, isDirty = true, canUndo = true),
        onBack = {},
        onSaveCopy = {},
        onApply = {},
        onUndo = {},
        onRedo = {},
    )
}

/** Editor while the first preview is still decoding. */
@Preview
@Composable
fun PhotoEditorLoadingPreview() = DriverFrame {
    PhotoEditorContent(
        state = PhotoEditorContentState(isRendering = true),
        onBack = {},
        onSaveCopy = {},
        onApply = {},
        onUndo = {},
        onRedo = {},
    )
}

/**
 * Every eraser model download state, stacked: offer, failed, queued, paused on Wi-Fi, downloading
 * and installed. Its buttons talk to WorkManager, which the headless driver does not initialize.
 */
@Preview(heightDp = 1400)
@Composable
fun EraserModelPanelStatesPreview() = DriverFrame {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(GallerySpacing.Md),
        verticalArrangement = Arrangement.spacedBy(GallerySpacing.Md),
    ) {
        listOf(
            InpaintModelStatus.NotInstalled,
            InpaintModelStatus.Failed,
            InpaintModelStatus.Queued(wait = null),
            InpaintModelStatus.Queued(wait = ModelDownloadWait.WiFi),
            InpaintModelStatus.Downloading(bytes = 9_400_000, total = 27_800_000),
            InpaintModelStatus.Installed,
        ).forEach { EraserModelPanel(it) }
    }
}

private val previewLooks = listOf(
    "builtin:vivid", "builtin:natural", "builtin:warm", "builtin:cool", "builtin:golden", "builtin:film",
    "builtin:fade", "builtin:teal_orange", "builtin:pastel", "builtin:dramatic", "builtin:mono", "builtin:noir",
)
private const val CustomA = "custom:123e4567-e89b-12d3-a456-426614174000"
private const val CustomB = "custom:123e4567-e89b-12d3-a456-426614174001"

/** The fake photo tinted differently per look, standing in for graded 256 px thumbnails. */
private fun fakeLookThumbnails(photo: Bitmap, ids: List<String>): Map<String, Bitmap> = ids.withIndex().associate { (i, id) ->
    val hue = i * 30f
    val thumbnail = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
            setRotate(0, hue); postConcat(android.graphics.ColorMatrix().apply { setRotate(1, hue / 2f) })
        })
    }
    Canvas(thumbnail).drawBitmap(Bitmap.createScaledBitmap(photo, 256, 192, true), 0f, 0f, paint)
    id to thumbnail
}

@Composable
private fun EditorAt(
    initial: PhotoEditorInitialUi,
    state: (Bitmap) -> PhotoEditorContentState,
) = DriverFrame {
    val photo = remember { fakePhoto() }
    CompositionLocalProvider(LocalPhotoEditorInitialUi provides initial) {
        PhotoEditorContent(
            state = state(photo),
            onBack = {},
            onSaveCopy = {},
            onApply = {},
            onUndo = {},
            onRedo = {},
            onOpenWith = {},
        )
    }
}

/** Adjust tab with Highlights selected and two edited tiles. */
@Preview
@Composable
fun PhotoAdjustPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Adjust, adjustTile = PhotoAdjustTile.Highlights)) { photo ->
    PhotoEditorContentState(
        preview = photo, originalPreview = photo, isDirty = true, canUndo = true,
        adjust = EditOperation.Adjust(mapOf(AdjustKey.Highlights to 0.35f, AdjustKey.Contrast to -0.2f)),
    )
}

/** Adjust tab, Color mix: eight swatches and the Hue / Saturation / Luminance sliders. */
@Preview
@Composable
fun PhotoColorMixPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Adjust, adjustTile = PhotoAdjustTile.ColorMix)) { photo ->
    PhotoEditorContentState(
        preview = photo, originalPreview = photo, isDirty = true, canUndo = true,
        colorMix = EditOperation.ColorMix.Neutral.withBand(ColorMixHue.Blue, ColorMixBand(hue = 0.2f, saturation = 0.45f)),
    )
}

/** Filters tab with graded thumbnails and a selected look at 60 % intensity. */
@Preview
@Composable
fun PhotoFiltersPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Filters)) { photo ->
    PhotoEditorContentState(
        preview = photo, originalPreview = photo, isDirty = true, canUndo = true,
        look = EditOperation.Look("builtin:warm", 0.6f),
        lookThumbnails = fakeLookThumbnails(photo, previewLooks),
    )
}

/** My filters: the create card and two saved looks, one selected. */
@Preview
@Composable
fun PhotoMyFiltersPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Filters, looksTab = PhotoLooksTab.Mine)) { photo ->
    PhotoEditorContentState(
        preview = photo, originalPreview = photo, isDirty = true, canUndo = true,
        look = EditOperation.Look(CustomA, 0.8f),
        customLooks = listOf(PhotoCustomLook(CustomA, "Sunset"), PhotoCustomLook(CustomB, "Moody forest walk")),
        lookThumbnails = fakeLookThumbnails(photo, listOf(CustomA, CustomB)),
    )
}

/** Crop tool in Perspective mode: draggable corners over the photo plus the keystone sliders. */
@Preview
@Composable
fun PhotoCropPerspectivePreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Crop, cropTool = PhotoCropTool.Perspective)) { photo ->
    PhotoEditorContentState(
        preview = photo, originalPreview = photo, cropSourcePreview = photo,
        perspective = EditOperation.Perspective(withHorizontalKeystone(withVerticalKeystone(IdentityCorners, 0.5f), -0.2f)),
    )
}

/** Crop tool with the aspect ratio chips. */
@Preview
@Composable
fun PhotoCropPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Crop)) { photo ->
    PhotoEditorContentState(preview = photo, originalPreview = photo, cropSourcePreview = photo)
}

/** The tool search sheet open over the Adjust tab. */
@Preview
@Composable
fun PhotoToolSearchPreview() = EditorAt(PhotoEditorInitialUi(tool = PhotoEditorTool.Adjust, searchOpen = true)) { photo ->
    PhotoEditorContentState(preview = photo, originalPreview = photo)
}

@Composable
private fun DriverFrame(content: @Composable () -> Unit) {
    LightforgeTheme { Surface(Modifier.fillMaxSize(), content = content) }
}

/** A 4:3 dusk gradient standing in for a decoded photo. */
private fun fakePhoto(): Bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).also { bitmap ->
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 900f, intArrayOf(0xFF1E3A5F.toInt(), 0xFFE07A5F.toInt(), 0xFFF2CC8F.toInt()), null, Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, 1200f, 900f, paint)
}
