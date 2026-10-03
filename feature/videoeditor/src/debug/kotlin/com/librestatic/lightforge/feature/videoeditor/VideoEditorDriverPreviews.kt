package com.librestatic.lightforge.feature.videoeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
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
import com.librestatic.lightforge.core.editing.video.SlowMotionSegment

// Debug-only, zero-argument entry points for tools/compose-driver and Android Studio previews.
// Each one feeds fake state into the real production composable. There is no player in the
// headless renderer, so the preview pane shows its "unavailable" state and the filmstrip shows
// its placeholders; layout, tools, timeline and panels are the real ones.

/** Video editor with an edit history and a slow-motion range. Driver: `VideoEditorPreview`. */
@Preview
@Composable
fun VideoEditorPreview() = DriverFrame {
    var state by remember {
        mutableStateOf(
            VideoEditorContentState(
                durationMillis = 18_000,
                trimStartMillis = 1_200,
                trimEndMillis = 15_600,
                currentMillis = 4_000,
                canUndo = true,
                isDirty = true,
                slowMotionSegments = listOf(SlowMotionSegment(id = "s1", startMillis = 6_000, endMillis = 8_500, speed = 0.25f)),
            ),
        )
    }
    VideoEditorContent(
        sessionId = "driver-video-editor",
        state = state,
        controller = null,
        onBack = {},
        onSaveCopy = {},
        onSpeedChange = { state = state.copy(speed = it) },
        onOriginalVolumeChange = { state = state.copy(originalAudioVolume = it) },
        onChooseMusic = {},
        onRemoveMusic = {},
        onSeek = {},
        onTrimChange = { start, end -> state = state.copy(trimStartMillis = start, trimEndMillis = end) },
        onColorGradeChange = { state = state.copy(colorGrade = it) },
    )
}

/** The filmstrip half loaded, coarse to fine: frames where decoded, placeholders elsewhere. */
@Preview(heightDp = 200)
@Composable
fun VideoFilmstripLoadingPreview() = DriverFrame {
    val loaded = remember { VideoFilmstripLoader.progressiveOrder(FilmstripFrameCount).take(4).toSet() }
    val frames = remember { List(FilmstripFrameCount) { index -> if (index in loaded) fakeFrame(index) else null } }
    var position by remember { mutableIntStateOf(4_000) }
    VideoFilmstripTimeline(
        frames = frames,
        durationMillis = 18_000,
        trimStartMillis = 1_200,
        trimEndMillis = 15_600,
        positionMillis = position.toLong(),
        onSeek = { position = it.toInt() },
        onTrimChange = { _, _ -> },
        slowMotionSegments = emptyList(),
    )
}

@Composable
private fun DriverFrame(content: @Composable () -> Unit) {
    LightforgeTheme { Surface(Modifier.fillMaxSize(), content = content) }
}

private fun fakeFrame(index: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(160, 96, Bitmap.Config.ARGB_8888)
    val hue = index * 40f
    val start = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
    val end = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.7f, 0.45f))
    Canvas(bitmap).drawRect(
        0f, 0f, 160f, 96f,
        Paint().apply { shader = LinearGradient(0f, 0f, 160f, 96f, start, end, Shader.TileMode.CLAMP) },
    )
    return bitmap
}
