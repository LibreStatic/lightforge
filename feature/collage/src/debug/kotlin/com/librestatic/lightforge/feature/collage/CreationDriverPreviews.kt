package com.librestatic.lightforge.feature.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.librestatic.lightforge.core.designsystem.LightforgeTheme

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// They feed fake state into the real stateless collage and GIF editor layouts.

/** Collage editor with three photos and the second slot selected. Driver: `CollageEditorPreview`. */
@Preview
@Composable
fun CollageEditorPreview() = DriverFrame {
    val templates = remember { CreationCollageTemplate.forCount(3) }
    var state by remember {
        mutableStateOf(
            CreationCollageUiState(
                layout = CreationCollageLayout(templates.first(), listOf(0, 1, 2)),
                selectedSlot = 1,
                preview = fakeImage(640, 640, 20f),
                publication = CreationCollagePublicationUi.None,
                sourcesAvailable = true,
            ),
        )
    }
    CreationCollageEditorLayout(
        state = state,
        templates = templates,
        supported = true,
        editingEnabled = true,
        callbackError = false,
        contentScroll = rememberScrollState(),
        actions = CreationCollageEditorActions(
            onTemplate = { template -> state = state.copy(layout = CreationCollageLayout(template, listOf(0, 1, 2))) },
            onSelect = { slot -> state = state.copy(selectedSlot = slot) },
            onExport = { state = state.copy(result = "content://driver/collage.png") },
        ),
    )
}

/** GIF editor on frame 3 of 6, paused. Driver: `GifEditorPreview`. */
@Preview
@Composable
fun GifEditorPreview() = DriverFrame {
    val frames = remember { List(6) { fakeImage(480, 360, it * 50f) } }
    var index by remember { mutableIntStateOf(2) }
    var seconds by remember { mutableIntStateOf(GifFrameTiming.Default) }
    var playing by remember { mutableStateOf(false) }
    CreationGifEditorLayout(
        ui = CreationGifEditorUi(
            title = androidx.compose.ui.res.stringResource(R.string.creation_gif_title),
            preview = frames[index],
            index = index,
            count = frames.size,
            playing = playing,
            seconds = seconds,
        ),
        actions = CreationGifEditorActions(
            onTogglePlay = { playing = !playing },
            onPrevious = { index = (index - 1 + frames.size) % frames.size },
            onNext = { index = (index + 1) % frames.size },
            onSeconds = { seconds = it },
        ),
        contentScroll = rememberScrollState(),
    )
}

@Composable
private fun DriverFrame(content: @Composable () -> Unit) {
    LightforgeTheme { Surface(Modifier.fillMaxSize(), content = content) }
}

private fun fakeImage(width: Int, height: Int, hue: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val start = android.graphics.Color.HSVToColor(floatArrayOf(hue % 360f, 0.5f, 0.9f))
    val end = android.graphics.Color.HSVToColor(floatArrayOf((hue + 90f) % 360f, 0.7f, 0.4f))
    Canvas(bitmap).drawRect(
        0f, 0f, width.toFloat(), height.toFloat(),
        Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), start, end, Shader.TileMode.CLAMP) },
    )
    return bitmap
}
