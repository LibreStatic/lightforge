package com.librestatic.lightforge.core.ml

import android.os.PowerManager
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class MlThermalPaceTest {
    private val unknown = Float.NaN

    @Test
    fun `cool devices run at full speed`() {
        assertEquals(ThermalPace.Full, thermalPace(PowerManager.THERMAL_STATUS_NONE, unknown, heldForHeat = false))
        assertEquals(ThermalPace.Full, thermalPace(PowerManager.THERMAL_STATUS_LIGHT, 0.6f, heldForHeat = false))
    }

    @Test
    fun `moderate slows the work instead of stopping it`() {
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, unknown, heldForHeat = false))
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, 0.7f, heldForHeat = false))
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_LIGHT, SlowHeadroom, heldForHeat = false))
    }

    @Test
    fun `severe or a forecast about to reach it holds the work`() {
        assertEquals(ThermalPace.Hold, thermalPace(PowerManager.THERMAL_STATUS_SEVERE, unknown, heldForHeat = false))
        assertEquals(ThermalPace.Hold, thermalPace(PowerManager.THERMAL_STATUS_SHUTDOWN, 0.5f, heldForHeat = false))
        assertEquals(ThermalPace.Hold, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, HoldHeadroom, heldForHeat = false))
    }

    @Test
    fun `a held task resumes only well below the hold point`() {
        val between = (HoldHeadroom + ResumeHeadroom) / 2
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, between, heldForHeat = false))
        assertEquals(ThermalPace.Hold, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, between, heldForHeat = true))
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, ResumeHeadroom, heldForHeat = true))
        assertEquals(ThermalPace.Full, thermalPace(PowerManager.THERMAL_STATUS_NONE, 0.5f, heldForHeat = true))
    }

    @Test
    fun `without a forecast a held task resumes once the status drops below severe`() {
        assertEquals(ThermalPace.Slow, thermalPace(PowerManager.THERMAL_STATUS_MODERATE, unknown, heldForHeat = true))
    }

    @Test
    fun `the rest matches the work within bounds`() {
        assertEquals(Duration.ofSeconds(20), thermalRest(Duration.ofSeconds(20)))
        assertEquals(Duration.ofSeconds(1), thermalRest(Duration.ofMillis(30)))
        assertEquals(Duration.ofMinutes(1), thermalRest(Duration.ofMinutes(5)))
    }
}
