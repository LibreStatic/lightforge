package com.librestatic.lightforge.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/** Pure rules for [EditorAdjustmentSlider], kept apart so they can be unit tested. */
object EditorAdjustmentSliderRules {
    /** Share of the range around the neutral value that snaps to it while dragging. */
    const val DetentFraction = 0.02f

    /** Snaps [raw] to [neutral] when it is inside the detent; otherwise returns it clamped. */
    fun snap(raw: Float, range: ClosedFloatingPointRange<Float>, neutral: Float): Float {
        val clamped = raw.coerceIn(range.start, range.endInclusive)
        val detent = (range.endInclusive - range.start) * DetentFraction
        return if (abs(clamped - neutral) <= detent) neutral else clamped
    }

    /** True when a drag lands on the neutral value from elsewhere: that is when the tick plays. */
    fun entersDetent(previous: Float, next: Float, neutral: Float): Boolean = next == neutral && previous != neutral

    /** Signed text: "+0.25", "−0.40", "0". [decimals] 0 prints whole numbers (e.g. tint, Kelvin offsets). */
    fun format(value: Float, neutral: Float, decimals: Int = 2): String {
        val delta = value - neutral
        if (abs(delta) < 0.5f * Math.pow(10.0, -decimals.toDouble()).toFloat()) return "0"
        val magnitude = String.format(Locale.ROOT, "%.${decimals}f", abs(delta))
        return (if (delta > 0) "+" else "−") + magnitude
    }

    /** Where the active fill starts and ends, as fractions of the track, for [value] around [neutral]. */
    fun fill(value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float): Pair<Float, Float> {
        val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: return 0f to 0f
        val centre = ((neutral - range.start) / span).coerceIn(0f, 1f)
        val position = ((value - range.start) / span).coerceIn(0f, 1f)
        return minOf(centre, position) to maxOf(centre, position)
    }
}

/**
 * Slider for bidirectional adjustments (exposure, contrast, tint…).
 *
 * The fill grows from the neutral value toward the thumb, the neutral value is a detent with a
 * haptic tick, a double tap on the track resets it, and accessibility services read the signed
 * value and get a "Reset" action.
 *
 * @param neutral the value that means "no change"; it need not be the middle of [valueRange].
 * @param displayValue the value shown and announced, relative to [neutral] (defaults to a signed delta).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorAdjustmentSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    neutral: Float = 0f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    displayValue: (Float) -> String = { EditorAdjustmentSliderRules.format(it, neutral) },
    testTag: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val shown = displayValue(value)
    val resetLabel = stringResource(R.string.editor_slider_reset, label)
    fun reset() {
        if (currentValue != neutral) {
            currentOnChange(neutral)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        currentOnFinished?.invoke()
    }
    val colors = SliderDefaults.colors()
    val scheme = MaterialTheme.colorScheme
    // Disabled tracks use the Material disabled treatment (onSurface at 38 % / 12 %).
    val activeColor = if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.38f)
    val inactiveColor = if (enabled) scheme.secondaryContainer else scheme.onSurface.copy(alpha = 0.12f)
    val tickColor = if (enabled) scheme.onSecondaryContainer else scheme.onSurface.copy(alpha = 0.38f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GallerySpacing.Xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(shown, style = GalleryMonoTypography, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                val next = EditorAdjustmentSliderRules.snap(raw, valueRange, neutral)
                if (EditorAdjustmentSliderRules.entersDetent(currentValue, next, neutral)) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                if (next != currentValue) currentOnChange(next)
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = colors,
            track = { state ->
                val (from, to) = EditorAdjustmentSliderRules.fill(state.value, state.valueRange, neutral)
                val centre = EditorAdjustmentSliderRules.fill(neutral, state.valueRange, neutral).first
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    val trackHeight = size.height
                    val radius = CornerRadius(trackHeight / 2f)
                    drawRoundRect(inactiveColor, size = Size(size.width, trackHeight), cornerRadius = radius)
                    if (to > from) {
                        drawRoundRect(
                            activeColor,
                            topLeft = Offset(size.width * from, 0f),
                            size = Size(size.width * (to - from), trackHeight),
                            cornerRadius = radius,
                        )
                    }
                    // The neutral mark stays visible on top of the fill so people can aim for it.
                    val tickWidth = 2.dp.toPx()
                    drawRect(
                        tickColor,
                        topLeft = Offset(size.width * centre - tickWidth / 2f, trackHeight * 0.2f),
                        size = Size(tickWidth, trackHeight * 0.6f),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
                .pointerInput(enabled, doubleTapTimeout) {
                    if (!enabled) return@pointerInput
                    // Observe taps in the Initial pass without consuming them, so the slider keeps
                    // its own drag and tap handling and a quick second tap still resets.
                    var lastTapUpTime = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var moved = false
                        var upTime = -1L
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > touchSlop) moved = true
                            if (!change.pressed) {
                                upTime = change.uptimeMillis
                                break
                            }
                        }
                        if (moved || upTime < 0) {
                            lastTapUpTime = 0L
                        } else if (lastTapUpTime != 0L && down.uptimeMillis - lastTapUpTime <= doubleTapTimeout) {
                            lastTapUpTime = 0L
                            reset()
                        } else {
                            lastTapUpTime = upTime
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    stateDescription = shown
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
                },
        )
    }
}
