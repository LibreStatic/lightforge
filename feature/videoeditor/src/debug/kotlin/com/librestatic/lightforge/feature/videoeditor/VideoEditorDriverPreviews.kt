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
import com.librestatic.lightforge.core.editing.video.VideoAspectMode
import com.librestatic.lightforge.core.editing.video.VideoAspectOverride
import com.librestatic.lightforge.core.editing.video.VideoEncoderCapabilities
import com.librestatic.lightforge.core.editing.video.VideoEncoderSupport
import com.librestatic.lightforge.core.editing.video.VideoOutputCodec
import com.librestatic.lightforge.core.editing.video.VideoOutputSettings
import com.librestatic.lightforge.core.editing.video.VideoSourceInfo

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

/**
 * The HikVision CCTV clip from the bug report: a 16:9 scene squeezed into 960×1088 HEVC that
 * claims 25 fps but delivers about 15.
 */
private val CctvSource = VideoSourceInfo(
    width = 960,
    height = 1088,
    frameRate = 15f,
    nominalFrameRate = 25f,
    videoBitrate = 979_000,
    audioBitrate = 15_500,
    videoMimeType = "video/hevc",
    audioMimeType = "audio/mp4a-latm",
    durationMs = 73_000,
    hasAudio = true,
    sizeBytes = 8_700_000,
)

/** A typical phone: hardware H.264 and HEVC, AV1 only in software. */
private val PhoneEncoders = VideoEncoderCapabilities(
    h264 = VideoEncoderSupport(maxWidth = 4096, maxHeight = 2304),
    hevc = VideoEncoderSupport(maxWidth = 4096, maxHeight = 2304),
    av1 = VideoEncoderSupport(maxWidth = 2048, maxHeight = 2048, hardwareAccelerated = false),
    hevcMain10 = true,
)

private val CctvState = VideoEditorContentState(
    durationMillis = 73_000,
    trimEndMillis = 73_000,
    output = VideoOutputSettings(aspect = VideoAspectOverride.Forced(16, 9, VideoAspectMode.Stretch)),
    outputSource = CctvSource,
    outputEncoders = PhoneEncoders,
    supportedOutputCodecs = VideoOutputCodec.entries.filter(PhoneEncoders::supports).toSet(),
    isHevcMain10Available = true,
    isDirty = true,
)

/** Output tool on its own, CCTV source with Forced 16:9 Stretch. Driver: `VideoOutputSettingsPreview`. */
@Preview
@Composable
fun VideoOutputSettingsPreview() = DriverFrame {
    var state by remember { mutableStateOf(CctvState) }
    VideoOutputControls(state = state, onChange = { state = state.copy(output = it) })
}

/**
 * Whole editor for the CCTV clip with a forced 16:9 aspect: the preview frame is reshaped (open the
 * Output tool with `click tag=video-editor-tool-bar` + text selectors to change it live).
 * Driver: `VideoEditorForcedAspectPreview`.
 */
@Preview
@Composable
fun VideoEditorForcedAspectPreview() = DriverFrame {
    var state by remember { mutableStateOf(CctvState) }
    VideoEditorContent(
        sessionId = "driver-video-editor-forced-aspect",
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
        onOutputSettingsChange = { state = state.copy(output = it) },
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
