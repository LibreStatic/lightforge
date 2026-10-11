package com.librestatic.lightforge.core.model

import kotlin.math.roundToInt

/**
 * The camera framing (DNG DefaultCrop) as a crop of the full-sensor image after the LibRaw
 * orientation [RawMetadata.flip] and every Rotate/Flip in [geometry] (other operations are
 * ignored). Null unless the framing is meaningful. Sizes and the crop rect are in sensor
 * orientation, so the rect is normalized before the orientation is applied.
 */
fun RawMetadata.cameraFrame(geometry: List<EditOperation>): EditOperation.Crop? {
    val crop = cameraCrop?.takeIf { hasMeaningfulCameraCrop } ?: return null
    val left = (crop.left * 1_000.0 / fullWidth).roundToInt().coerceIn(0, 999)
    val top = (crop.top * 1_000.0 / fullHeight).roundToInt().coerceIn(0, 999)
    val right = ((crop.left + crop.width) * 1_000.0 / fullWidth).roundToInt().coerceIn(left + 1, 1_000)
    val bottom = ((crop.top + crop.height) * 1_000.0 / fullHeight).roundToInt().coerceIn(top + 1, 1_000)
    var frame = EditOperation.Crop(left, top, right, bottom)
    when (flip) {
        6 -> frame = frame.afterGeometry(EditOperation.Rotate(90))
        5 -> frame = frame.afterGeometry(EditOperation.Rotate(270))
        3 -> frame = frame.afterGeometry(EditOperation.Rotate(180))
    }
    geometry.forEach { if (it is EditOperation.Rotate || it is EditOperation.Flip) frame = frame.afterGeometry(it) }
    return frame
}

/** This crop, expressed relative to [frame], placed in the outer image that contains [frame]. */
fun EditOperation.Crop.within(frame: EditOperation.Crop): EditOperation.Crop {
    val width = frame.rightPermille - frame.leftPermille
    val height = frame.bottomPermille - frame.topPermille
    val left = (frame.leftPermille + leftPermille * width / 1_000.0).roundToInt().coerceIn(0, 999)
    val top = (frame.topPermille + topPermille * height / 1_000.0).roundToInt().coerceIn(0, 999)
    val right = (frame.leftPermille + rightPermille * width / 1_000.0).roundToInt().coerceIn(left + 1, 1_000)
    val bottom = (frame.topPermille + bottomPermille * height / 1_000.0).roundToInt().coerceIn(top + 1, 1_000)
    return EditOperation.Crop(left, top, right, bottom)
}

/**
 * The inverse of [within]: this crop (in the outer image) intersected with [frame] and expressed
 * relative to it. Null when that is the whole frame or the crop misses the frame.
 */
fun EditOperation.Crop.relativeTo(frame: EditOperation.Crop): EditOperation.Crop? {
    val left = maxOf(leftPermille, frame.leftPermille)
    val top = maxOf(topPermille, frame.topPermille)
    val right = minOf(rightPermille, frame.rightPermille)
    val bottom = minOf(bottomPermille, frame.bottomPermille)
    if (right <= left || bottom <= top) return null
    val width = (frame.rightPermille - frame.leftPermille).toDouble()
    val height = (frame.bottomPermille - frame.topPermille).toDouble()
    val relativeLeft = ((left - frame.leftPermille) * 1_000 / width).roundToInt().coerceIn(0, 999)
    val relativeTop = ((top - frame.topPermille) * 1_000 / height).roundToInt().coerceIn(0, 999)
    val relativeRight = ((right - frame.leftPermille) * 1_000 / width).roundToInt().coerceIn(relativeLeft + 1, 1_000)
    val relativeBottom = ((bottom - frame.topPermille) * 1_000 / height).roundToInt().coerceIn(relativeTop + 1, 1_000)
    return EditOperation.Crop(relativeLeft, relativeTop, relativeRight, relativeBottom).takeUnless { it.isFullFrame }
}
