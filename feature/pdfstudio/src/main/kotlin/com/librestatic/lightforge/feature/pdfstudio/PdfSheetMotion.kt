package com.librestatic.lightforge.feature.pdfstudio

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Spring rest threshold, in px, below which a sheet offset animation is considered settled. */
private const val SheetOffsetThresholdPx = 1f

/**
 * Wraps a [androidx.compose.material3.ModalBottomSheet] so its slide animation stops ticking once
 * it is visually settled, without touching the app-wide motion scheme.
 *
 * M3 1.5 reads `MaterialTheme.motionScheme` when the sheet is composed: show and drag settle use
 * `defaultSpatialSpec()`, hide uses `fastEffectsSpec()`. The expressive spatial spring has no
 * visibility threshold, so for Float offsets it keeps redrawing the sheet for ~0.8 s after it is
 * already sub-pixel still. Here the spatial specs keep their stiffness and damping ratio (same
 * feel) but get a 1 px threshold. Only the sheet reads the override; the content is composed with the
 * outer scheme again, because spatial specs are also requested for non-Float types (Dp, Offset...)
 * and a Float threshold would be wrong for those.
 *
 * Call it around the `ModalBottomSheet` call and wrap the sheet's content in the `restoreMotion`
 * lambda it hands to [sheet].
 */
@Composable
internal fun SettledSheetMotion(sheet: @Composable (restoreMotion: @Composable (@Composable () -> Unit) -> Unit) -> Unit) {
    val outer = MaterialTheme.motionScheme
    val settled = remember(outer) { SettledOffsetMotionScheme(outer) }
    MaterialTheme(motionScheme = settled) {
        sheet { inner -> MaterialTheme(motionScheme = outer) { inner() } }
    }
}

private class SettledOffsetMotionScheme(private val base: MotionScheme) : MotionScheme by base {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = base.defaultSpatialSpec<T>().settled()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = base.fastSpatialSpec<T>().settled()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = base.slowSpatialSpec<T>().settled()

    private fun <T> FiniteAnimationSpec<T>.settled(): FiniteAnimationSpec<T> =
        if (this is SpringSpec<T>) FloatSettledSpring(this) else this
}

/**
 * [base] with a [SheetOffsetThresholdPx] visibility threshold, applied only when the spec is
 * vectorized for a Float (the sheet offset). Any other type keeps [base] untouched, so a spatial
 * spec requested for Dp/Offset never receives a Float threshold.
 */
private data class FloatSettledSpring<T>(private val base: SpringSpec<T>) : FiniteAnimationSpec<T> {
    @Suppress("UNCHECKED_CAST")
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        if (converter === Float.VectorConverter) {
            SpringSpec(base.dampingRatio, base.stiffness, SheetOffsetThresholdPx as T).vectorize(converter)
        } else {
            base.vectorize(converter)
        }
}
