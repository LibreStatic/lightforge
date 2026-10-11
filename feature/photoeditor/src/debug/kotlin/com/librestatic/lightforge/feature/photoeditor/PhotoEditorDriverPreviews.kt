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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.ml.ModelDownloadWait
import com.librestatic.lightforge.core.model.RawCameraCrop
import com.librestatic.lightforge.core.model.RawDevelopmentSettings
import com.librestatic.lightforge.core.model.RawMetadata
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

/** RAW editor whose file declares a 4.6x digital zoom: the camera framing / full sensor choice is shown. */
@Preview
@Composable
fun RawCameraFramingPreview() = DriverFrame {
    RawEditorPreview(RawCameraCrop(left = 1_564, top = 1_173, width = 873, height = 654))
}

/** RAW editor with only the 8 px margins plain DNG writers declare: no framing choice is offered. */
@Preview
@Composable
fun RawSmallMarginPreview() = DriverFrame {
    RawEditorPreview(RawCameraCrop(left = 8, top = 8, width = 3_984, height = 2_984))
}

/**
 * RAW editor whose file declares a camera frame (3600x2700 at 200,150 of a 4000x3000 sensor), with the
 * full-sensor crop source already published. Open the Crop tool (`click tag=photo-editor-tool-crop`) to see
 * the whole image with the camera frame as the crop rectangle.
 */
@Preview
@Composable
fun RawCropToolFullSensorPreview() = DriverFrame {
    val photo = remember { fakePhoto() }
    val crop = RawCameraCrop(left = 200, top = 150, width = 3_600, height = 2_700)
    val metadata = remember {
        RawMetadata(
            make = "OpenCineCam", model = "Sensor", width = crop.width, height = crop.height,
            bitsPerSample = 12, blackLevel = 64, whiteLevel = 4_095,
            fullWidth = 4_000, fullHeight = 3_000, cameraCrop = crop,
        )
    }
    PhotoEditorContent(
        state = PhotoEditorContentState(
            preview = photo, originalPreview = photo, cropSourcePreview = fakePhoto(), cropSourceFullSensor = true,
            isRaw = true, rawMetadata = metadata,
        ),
        onBack = {},
        onSaveCopy = {},
        onApply = {},
        onUndo = {},
        onRedo = {},
    )
}

@Composable
private fun RawEditorPreview(crop: RawCameraCrop) {
    val photo = remember { fakePhoto() }
    val metadata = remember {
        RawMetadata(
            make = "OpenCineCam", model = "Sensor", width = crop.width, height = crop.height,
            bitsPerSample = 12, blackLevel = 64, whiteLevel = 4_095,
            fullWidth = 4_000, fullHeight = 3_000, cameraCrop = crop,
        )
    }
    var settings by remember { mutableStateOf(RawDevelopmentSettings()) }
    PhotoEditorContent(
        state = PhotoEditorContentState(
            preview = photo, originalPreview = photo, isRaw = true, rawMetadata = metadata, rawSettings = settings,
        ),
        onBack = {},
        onSaveCopy = {},
        onApply = {},
        onUndo = {},
        onRedo = {},
        onRawSettingsChange = { settings = it },
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
