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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.designsystem.LightforgeTheme
import com.librestatic.lightforge.core.ml.ModelDownloadWait
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
