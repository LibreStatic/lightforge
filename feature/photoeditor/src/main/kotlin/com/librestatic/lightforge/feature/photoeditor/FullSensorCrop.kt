package com.librestatic.lightforge.feature.photoeditor

import com.librestatic.lightforge.core.model.EditOperation
import kotlin.math.abs
import kotlin.math.roundToInt

/** A draft rectangle that may extend past the camera frame is accepted within this many permille. */
private const val FrameTolerancePermille = 2

/** What applying a crop draft does: the operations to record, and whether they need the full sensor. */
internal data class CropOutcome(
    val crop: EditOperation.Crop,
    val straightenDegrees: Float,
    /** The crop leaves the camera frame, so the RAW must be developed without camera framing. */
    val needsFullSensor: Boolean,
    val perspective: EditOperation.Perspective? = null,
) {
    /** In [cropEditOperations] order, so an outcome compares equal to the operations already applied. */
    val operations: List<EditOperation>
        get() = listOfNotNull(perspective, crop, EditOperation.Straighten(straightenDegrees))
}

private fun PhotoCropDraft.toRoundedCrop(): EditOperation.Crop {
    val left = (left * 1_000).roundToInt().coerceIn(0, 998)
    val top = (top * 1_000).roundToInt().coerceIn(0, 998)
    return EditOperation.Crop(
        left,
        top,
        (right * 1_000).roundToInt().coerceIn(left + 1, 1_000),
        (bottom * 1_000).roundToInt().coerceIn(top + 1, 1_000),
    )
}

internal fun EditOperation.Crop.toDraft(
    straightenDegrees: Float = 0f,
    fullSensor: Boolean = false,
): PhotoCropDraft = PhotoCropDraft(
    left = leftPermille / 1_000f,
    top = topPermille / 1_000f,
    right = rightPermille / 1_000f,
    bottom = bottomPermille / 1_000f,
    straightenDegrees = straightenDegrees,
    fullSensor = fullSensor,
)

/** The draft, drawn on the framed image, re-expressed on the full-sensor image that contains [frame]. */
internal fun PhotoCropDraft.toFullSensor(frame: EditOperation.Crop): PhotoCropDraft {
    val frameLeft = frame.leftPermille / 1_000f
    val frameTop = frame.topPermille / 1_000f
    val frameWidth = (frame.rightPermille - frame.leftPermille) / 1_000f
    val frameHeight = (frame.bottomPermille - frame.topPermille) / 1_000f
    return copy(
        left = frameLeft + left * frameWidth,
        top = frameTop + top * frameHeight,
        right = frameLeft + right * frameWidth,
        bottom = frameTop + bottom * frameHeight,
        fullSensor = true,
    )
}

/** The inverse of [toFullSensor]; the parts outside [frame] are clamped away. A null frame keeps the rectangle. */
internal fun PhotoCropDraft.toFramed(frame: EditOperation.Crop?): PhotoCropDraft {
    if (frame == null) return copy(fullSensor = false)
    val frameLeft = frame.leftPermille / 1_000f
    val frameTop = frame.topPermille / 1_000f
    val frameWidth = (frame.rightPermille - frame.leftPermille) / 1_000f
    val frameHeight = (frame.bottomPermille - frame.topPermille) / 1_000f
    val newLeft = ((left - frameLeft) / frameWidth).coerceIn(0f, 0.99f)
    val newTop = ((top - frameTop) / frameHeight).coerceIn(0f, 0.99f)
    return copy(
        left = newLeft,
        top = newTop,
        right = ((right - frameLeft) / frameWidth).coerceIn(newLeft + 0.01f, 1f),
        bottom = ((bottom - frameTop) / frameHeight).coerceIn(newTop + 0.01f, 1f),
        fullSensor = false,
    )
}

/** Moves a full-sensor draft back onto the camera frame, keeping its straighten angle. */
internal fun PhotoCropDraft.resetToFrame(frame: EditOperation.Crop): PhotoCropDraft =
    frame.toDraft(straightenDegrees, fullSensor = true)

/**
 * Resolves [draft] to the edit it stands for. A full-sensor draft inside [cameraFrame] becomes a
 * normal crop of the framed image (framing stays on); [baseline], the crop recorded before the
 * draft was opened, absorbs the rounding drift of an untouched draft. A draft that leaves the
 * frame needs the full sensor area, and so does a perspective drawn on the full sensor: its corners
 * are positions in that image, not in the framed one.
 */
internal fun cropOutcome(
    draft: PhotoCropDraft,
    cameraFrame: EditOperation.Crop?,
    baseline: EditOperation.Crop?,
): CropOutcome {
    if (!draft.fullSensor || cameraFrame == null) {
        val operations = cropEditOperations(draft)
        return CropOutcome(
            operations.filterIsInstance<EditOperation.Crop>().single(),
            draft.straightenDegrees,
            needsFullSensor = false,
            perspective = operations.filterIsInstance<EditOperation.Perspective>().singleOrNull(),
        )
    }
    val absolute = draft.toRoundedCrop()
    val perspective = cropEditOperations(draft).filterIsInstance<EditOperation.Perspective>().singleOrNull()
    if (perspective != null) {
        return CropOutcome(absolute, draft.straightenDegrees, needsFullSensor = true, perspective = perspective)
    }
    val tolerance = FrameTolerancePermille
    val inside = absolute.leftPermille >= cameraFrame.leftPermille - tolerance &&
        absolute.topPermille >= cameraFrame.topPermille - tolerance &&
        absolute.rightPermille <= cameraFrame.rightPermille + tolerance &&
        absolute.bottomPermille <= cameraFrame.bottomPermille + tolerance
    if (!inside) return CropOutcome(absolute, draft.straightenDegrees, needsFullSensor = true)
    // Convert in float: rounding the absolute rect first would be magnified by 1000 / frame size,
    // several permille on a tight frame.
    val full = EditOperation.Crop(0, 0, 1_000, 1_000)
    val relative = draft.toFramed(cameraFrame).toRoundedCrop()
    val previous = baseline ?: full
    val unchanged = abs(relative.leftPermille - previous.leftPermille) <= tolerance &&
        abs(relative.topPermille - previous.topPermille) <= tolerance &&
        abs(relative.rightPermille - previous.rightPermille) <= tolerance &&
        abs(relative.bottomPermille - previous.bottomPermille) <= tolerance
    return CropOutcome(if (unchanged) previous else relative, draft.straightenDegrees, needsFullSensor = false)
}
