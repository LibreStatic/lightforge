package com.librestatic.lightforge.core.ml

import android.os.PowerManager
import java.time.Duration

/** How hard background analysis may work at the current device temperature. */
enum class ThermalPace {
    /** Full speed. */
    Full,

    /** Keep going, resting as long as the last stretch of work took (about half the duty cycle). */
    Slow,

    /** Stop and wait for the device to cool. */
    Hold,
}

/**
 * [status] is [PowerManager.getCurrentThermalStatus]; [headroom] is the 10 s forecast of
 * [PowerManager.getThermalHeadroom], where 1.0 is the severe-throttling point, or NaN when the
 * device does not report it.
 *
 * MODERATE only means light throttling that users barely notice, and a phone held in the hand can
 * stay there for long periods, so it slows the work instead of stopping it. The work stops at
 * SEVERE or when the forecast is about to reach it. After a hold, the forecast must fall clearly
 * below that point before the work resumes, so it does not toggle on and off at the threshold.
 */
fun thermalPace(status: Int, headroom: Float, heldForHeat: Boolean): ThermalPace {
    val known = !headroom.isNaN()
    return when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalPace.Hold
        known && headroom >= HoldHeadroom -> ThermalPace.Hold
        heldForHeat && known && headroom > ResumeHeadroom -> ThermalPace.Hold
        status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalPace.Slow
        known && headroom >= SlowHeadroom -> ThermalPace.Slow
        else -> ThermalPace.Full
    }
}

/** The rest after [worked] of analysis at [ThermalPace.Slow]; never a busy loop, never a long stall. */
fun thermalRest(worked: Duration): Duration = worked.coerceIn(MinThermalRest, MaxThermalRest)

/** WorkManager rechecks a held task after this long. */
val ThermalHoldWait: Duration = Duration.ofMinutes(2)

internal const val HoldHeadroom = 0.95f
internal const val ResumeHeadroom = 0.85f
internal const val SlowHeadroom = 0.8f
private val MinThermalRest = Duration.ofSeconds(1)
private val MaxThermalRest = Duration.ofMinutes(1)
