package com.librestatic.lightforge.feature.photos

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Detent feedback for the timeline scrubber. The API 34 segment and gesture constants are silently
 * dropped by vibrators without composition primitives (most tablets), so those devices get the
 * classic constants, which every haptic engine maps.
 */
internal class ScrubberHaptics(private val view: View) {
    private val detents: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        view.context.getSystemService(Vibrator::class.java)?.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ) == true

    fun begin() = perform(if (detents) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.KEYBOARD_TAP)

    fun month() = perform(if (detents) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun year() = perform(if (detents) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.KEYBOARD_TAP)

    fun end() = perform(if (detents) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.KEYBOARD_RELEASE)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}
