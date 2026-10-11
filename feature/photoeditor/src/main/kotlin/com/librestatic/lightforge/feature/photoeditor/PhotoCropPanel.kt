package com.librestatic.lightforge.feature.photoeditor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSlider
import com.librestatic.lightforge.core.designsystem.EditorAdjustmentSliderRules
import com.librestatic.lightforge.core.designsystem.GalleryIcons
import com.librestatic.lightforge.core.designsystem.GallerySpacing
import com.librestatic.lightforge.core.model.EditOperation
import com.librestatic.lightforge.core.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The operations a crop draft commits, in history order: perspective (when not the identity), then
 * crop, then straighten. The history keeps its own slot order whatever order they arrive in.
 */
internal fun cropEditOperations(draft: PhotoCropDraft): List<EditOperation> = listOfNotNull(
    draft.perspective
        .takeIf { it != IdentityCorners && isConvexQuad(it, margin = 0f) }
        ?.let { EditOperation.Perspective(it) },
    EditOperation.Crop(
        (draft.left * 1_000).toInt().coerceIn(0, 999),
        (draft.top * 1_000).toInt().coerceIn(0, 999),
        (draft.right * 1_000).toInt().coerceIn(1, 1_000),
        (draft.bottom * 1_000).toInt().coerceIn(1, 1_000),
    ),
    EditOperation.Straighten(draft.straightenDegrees),
)

/** Crop panel: sub-tool carousel and the controls of the selected sub-tool; see [CropActions]. */
@Composable
internal fun CropControls(
    draft: PhotoCropDraft,
    cropTool: PhotoCropTool,
    onCropToolChange: (PhotoCropTool) -> Unit,
    sourceAspectRatio: Float,
    autoStraightenDegrees: Float?,
    onAutoStraighten: () -> Unit,
    onChange: (PhotoCropDraft) -> Unit,
    onRotate: (clockwise: Boolean) -> Unit,
    onFlip: (horizontal: Boolean) -> Unit,
    /** Puts the crop back on the camera frame; null unless the draft covers the full sensor area. */
    onCameraFraming: (() -> Unit)? = null,
) {
    val currentDraft by rememberUpdatedState(draft)
    val currentOnChange by rememberUpdatedState(onChange)
    // The detector's answer arrives later as state; only a result asked for here moves the slider.
    var awaitingAuto by remember { mutableStateOf(false) }
    LaunchedEffect(autoStraightenDegrees) {
        val degrees = autoStraightenDegrees
        if (awaitingAuto && degrees != null) {
            awaitingAuto = false
            currentOnChange(currentDraft.copy(straightenDegrees = degrees.coerceIn(-45f, 45f)))
        }
    }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(PhotoCropTool.entries, key = { it.name }) { tool ->
            PhotoToolTile(
                icon = tool.icon,
                label = stringResource(tool.labelRes),
                selected = tool == cropTool,
                onClick = { onCropToolChange(tool) },
                testTag = "photo-crop-${tool.token}",
                modified = when (tool) {
                    PhotoCropTool.Straighten -> draft.straightenDegrees != 0f
                    PhotoCropTool.Perspective -> draft.hasPerspective
                    else -> false
                },
            )
        }
    }
    when (cropTool) {
        PhotoCropTool.Crop -> {
            val presets = cropAspectPresets(sourceAspectRatio)
            val selectedIndex = presets.indexOfFirst { sameAspect(it.ratio, draft.aspectRatio) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
                items(presets.size, key = { presets[it].token }) { index ->
                    val preset = presets[index]
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { onChange(cropForAspect(draft, preset.ratio, sourceAspectRatio)) },
                        label = { Text(stringResource(preset.label)) },
                        modifier = Modifier.testTag("photo-crop-aspect-${preset.token}"),
                    )
                }
            }
            if (onCameraFraming != null) {
                OutlinedButton(
                    onClick = onCameraFraming,
                    modifier = Modifier.fillMaxWidth().testTag("photo-crop-camera-framing"),
                ) { Text(stringResource(R.string.photo_editor_raw_camera_framing), maxLines = 1) }
            }
        }
        PhotoCropTool.Straighten -> {
            EditorAdjustmentSlider(
                label = stringResource(R.string.photo_editor_straighten),
                value = draft.straightenDegrees,
                onValueChange = { onChange(draft.copy(straightenDegrees = (it * 10f).roundToInt() / 10f)) },
                valueRange = -45f..45f,
                displayValue = { "${EditorAdjustmentSliderRules.format(it, 0f, decimals = 1)}°" },
                leading = { Icon(GalleryIcons.Straighten, contentDescription = null) },
                testTag = "photo-crop-straighten-slider",
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { awaitingAuto = true; onAutoStraighten() },
                modifier = Modifier.testTag("photo-crop-auto"),
            ) {
                Icon(GalleryIcons.AutoAwesome, contentDescription = null)
                Text(stringResource(R.string.photo_editor_straighten_auto), maxLines = 1)
            }
        }
        PhotoCropTool.Rotate -> ActionRow(
            first = Triple(GalleryIcons.RotateLeft, R.string.photo_editor_rotate_left, "photo-crop-rotate-left") to { onRotate(false) },
            second = Triple(GalleryIcons.RotateRight, R.string.photo_editor_rotate_right, "photo-crop-rotate-right") to { onRotate(true) },
        )
        PhotoCropTool.Flip -> ActionRow(
            first = Triple(GalleryIcons.SwapHoriz, R.string.photo_editor_flip_horizontal, "photo-crop-flip-horizontal") to { onFlip(true) },
            second = Triple(GalleryIcons.SwapVert, R.string.photo_editor_flip_vertical, "photo-crop-flip-vertical") to { onFlip(false) },
        )
        PhotoCropTool.Perspective -> {
            EditorAdjustmentSlider(
                label = stringResource(R.string.photo_editor_perspective_vertical),
                value = verticalKeystone(draft.perspective) * 100f,
                onValueChange = { onChange(draft.withPerspective(withVerticalKeystone(draft.perspective, it / 100f))) },
                valueRange = -100f..100f,
                displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = 0) },
                leading = { Icon(GalleryIcons.SwapVert, contentDescription = null) },
                testTag = "photo-crop-keystone-vertical",
                modifier = Modifier.fillMaxWidth(),
            )
            EditorAdjustmentSlider(
                label = stringResource(R.string.photo_editor_perspective_horizontal),
                value = horizontalKeystone(draft.perspective) * 100f,
                onValueChange = { onChange(draft.withPerspective(withHorizontalKeystone(draft.perspective, it / 100f))) },
                valueRange = -100f..100f,
                displayValue = { EditorAdjustmentSliderRules.format(it, 0f, decimals = 0) },
                leading = { Icon(GalleryIcons.SwapHoriz, contentDescription = null) },
                testTag = "photo-crop-keystone-horizontal",
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.hasPerspective) {
                TextButton(
                    onClick = { onChange(draft.withPerspective(IdentityCorners)) },
                    modifier = Modifier.testTag("photo-crop-perspective-reset"),
                ) { Text(stringResource(R.string.photo_editor_perspective_reset), maxLines = 1) }
            }
        }
    }
}

/** Reset / Cancel / Apply, kept outside the scrolling panel so Apply never scrolls off screen. */
@Composable
internal fun CropActions(
    draft: PhotoCropDraft,
    onChange: (PhotoCropDraft) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Xs),
    ) {
        TextButton(
            // On a full-sensor draft this uncovers the whole sensor, i.e. removes the camera framing.
            onClick = { onChange(PhotoCropDraft(fullSensor = draft.fullSensor)) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.photo_editor_reset_crop), maxLines = 1) }
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_cancel_crop), maxLines = 1)
        }
        Button(onClick = onApply, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.photo_editor_apply_crop), maxLines = 1)
        }
    }
}

@Composable
private fun ActionRow(
    first: Pair<Triple<ImageVector, Int, String>, () -> Unit>,
    second: Pair<Triple<ImageVector, Int, String>, () -> Unit>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GallerySpacing.Sm)) {
        listOf(first, second).forEach { (spec, onClick) ->
            val (icon, label, tag) = spec
            OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f).testTag(tag)) {
                Icon(icon, contentDescription = null)
                Text(stringResource(label), maxLines = 2)
            }
        }
    }
}

internal val PhotoCropDraftSaver = listSaver<PhotoCropDraft?, Float>(
    save = { draft ->
        draft?.let {
            listOf(it.left, it.top, it.right, it.bottom, it.aspectRatio ?: 0f, it.straightenDegrees) +
                it.perspective.flatMap { corner -> listOf(corner.x, corner.y) } +
                (if (it.fullSensor) 1f else 0f)
        } ?: emptyList()
    },
    restore = { values ->
        if (values.size < 6) null else PhotoCropDraft(
            left = values[0],
            top = values[1],
            right = values[2],
            bottom = values[3],
            aspectRatio = values[4].takeIf { it > 0f },
            straightenDegrees = values[5],
            perspective = if (values.size >= 14) {
                List(4) { NormalizedPoint(values[6 + it * 2].coerceIn(0f, 1f), values[7 + it * 2].coerceIn(0f, 1f)) }
                    .takeIf { isConvexQuad(it, margin = 0f) } ?: IdentityCorners
            } else IdentityCorners,
            fullSensor = values.getOrNull(14) == 1f,
        )
    },
)

internal class CropAspectPreset(val token: String, val ratio: Float?, @StringRes val label: Int)

/** Aspect ratios offered by the crop tool; "Original" follows the photo's own [sourceAspectRatio]. */
internal fun cropAspectPresets(sourceAspectRatio: Float): List<CropAspectPreset> = listOf(
    CropAspectPreset("free", null, R.string.photo_editor_crop_free),
    CropAspectPreset("original", sourceAspectRatio, R.string.photo_editor_filter_original),
    CropAspectPreset("square", 1f, R.string.photo_editor_crop_square),
    CropAspectPreset("4-3", 4f / 3f, R.string.photo_editor_crop_four_three),
    CropAspectPreset("3-4", 3f / 4f, R.string.photo_editor_crop_three_four),
    CropAspectPreset("3-2", 3f / 2f, R.string.photo_editor_crop_three_two),
    CropAspectPreset("2-3", 2f / 3f, R.string.photo_editor_crop_two_three),
    CropAspectPreset("5-4", 5f / 4f, R.string.photo_editor_crop_five_four),
    CropAspectPreset("4-5", 4f / 5f, R.string.photo_editor_crop_four_five),
    CropAspectPreset("16-9", 16f / 9f, R.string.photo_editor_crop_sixteen_nine),
    CropAspectPreset("9-16", 9f / 16f, R.string.photo_editor_crop_nine_sixteen),
)

/** Fixed ratios a rotated draft snaps back to, so 16:9 turned a quarter becomes exactly 9:16. */
private val fixedAspectRatios = listOf(1f, 4f / 3f, 3f / 4f, 3f / 2f, 2f / 3f, 5f / 4f, 4f / 5f, 16f / 9f, 9f / 16f)

private fun sameAspect(preset: Float?, current: Float?): Boolean =
    if (preset == null || current == null) preset == current else abs(preset - current) < 0.0005f

/** The draft after a clockwise quarter turn, matching how the edit history rotates a Crop. */
internal fun PhotoCropDraft.rotatedClockwise(): PhotoCropDraft = copy(
    left = 1f - bottom,
    top = left,
    right = 1f - top,
    bottom = right,
    aspectRatio = aspectRatio?.let { ratio ->
        fixedAspectRatios.firstOrNull { abs(it * ratio - 1f) < 0.001f } ?: (1f / ratio)
    },
    perspective = perspective.rotatedClockwise(),
)

/** The draft after a horizontal flip; straightening turns the other way in the mirrored frame. */
internal fun PhotoCropDraft.flippedHorizontally(): PhotoCropDraft = copy(
    left = 1f - right,
    right = 1f - left,
    straightenDegrees = -straightenDegrees,
    perspective = perspective.flipped(horizontal = true),
)

/** The draft after a vertical flip. */
internal fun PhotoCropDraft.flippedVertically(): PhotoCropDraft = copy(
    top = 1f - bottom,
    bottom = 1f - top,
    straightenDegrees = -straightenDegrees,
    perspective = perspective.flipped(horizontal = false),
)

internal fun cropForAspect(draft: PhotoCropDraft, ratio: Float?, sourceAspectRatio: Float): PhotoCropDraft {
    if (ratio == null) return draft.copy(aspectRatio = null)
    val normalizedRatio = ratio / sourceAspectRatio
    val centerX = (draft.left + draft.right) / 2f
    val centerY = (draft.top + draft.bottom) / 2f
    var width = draft.width
    var height = width / normalizedRatio
    if (height > draft.height) {
        height = draft.height
        width = height * normalizedRatio
    }
    return draft.copy(
        left = (centerX - width / 2f).coerceAtLeast(0f),
        right = (centerX + width / 2f).coerceAtMost(1f),
        top = (centerY - height / 2f).coerceAtLeast(0f),
        bottom = (centerY + height / 2f).coerceAtMost(1f),
        aspectRatio = ratio,
    )
}

internal fun cropDraftFromState(state: PhotoEditorContentState): PhotoCropDraft {
    val crop = state.crop
    val perspective = state.perspective?.corners ?: IdentityCorners
    return if (crop == null) {
        PhotoCropDraft(straightenDegrees = state.straightenDegrees, perspective = perspective)
    } else PhotoCropDraft(
        left = crop.leftPermille / 1_000f,
        top = crop.topPermille / 1_000f,
        right = crop.rightPermille / 1_000f,
        bottom = crop.bottomPermille / 1_000f,
        straightenDegrees = state.straightenDegrees,
        perspective = perspective,
    )
}
