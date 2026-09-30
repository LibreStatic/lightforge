package com.librestatic.lightforge.feature.pdfstudio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Pure tick math for the expanded-mode canvas rulers (Phase F2 item A). No Compose/Android
 * dependency so it is covered by plain JVM tests: given the project's unit and how many screen
 * units correspond to one physical millimeter (i.e. density * zoom), it picks a minor/major tick
 * spacing that never crowds labels together, then lists every tick in a visible mm range.
 *
 * Values are always expressed in physical millimeters *from the page's top-left origin*
 * (`positionMm`); the caller (the ruler composable) is responsible for placing them on screen
 * using its own pan/zoom transform and for never mirroring that origin in RTL — the ruler drawing
 * is forced to [androidx.compose.ui.unit.LayoutDirection.Ltr] regardless of the app's locale.
 */
internal object PdfRulerMath {
    /** One tick along a ruler axis. [labelUnits] is the value in the ruler's own unit (mm/cm/in;
     * pixel projects still render in mm — see [displayUnit]), already rounded to avoid float noise
     * like 4.999999999. */
    data class Tick(val positionMm: Double, val major: Boolean, val labelUnits: Double)

    /** `px` (Phase spec: "px shows mm") has no natural ruler grid of its own, so the ruler always
     * falls back to millimeters for it; every other unit rules itself. */
    fun displayUnit(projectUnit: PdfUnit): PdfUnit =
        if (projectUnit == PdfUnit.Pixel) PdfUnit.Millimeter else projectUnit

    /** "Nice" step multipliers, tried at every power of ten: 1, 2, 5, 10, 20, 50, 100, ... and
     * 0.1, 0.2, 0.5 going the other way for very high zoom. */
    private val NICE_MULTIPLIERS = doubleArrayOf(1.0, 2.0, 5.0)

    /**
     * The minor tick spacing, in physical millimeters, such that consecutive minor ticks are at
     * least [minSpacingScreenUnits] apart on screen at [screenUnitsPerMm] (typically density *
     * zoom, so ticks track pan/zoom exactly). Always a "nice" number *in the ruler's own unit*
     * (1/2/5 mm, 1/2/5 cm, or 1/2/5 inch) so labels read naturally, never an arbitrary mm value.
     */
    fun minorStepMm(unit: PdfUnit, screenUnitsPerMm: Double, minSpacingScreenUnits: Double): Double {
        require(screenUnitsPerMm > 0 && minSpacingScreenUnits > 0)
        val unitMm = displayUnit(unit).millimeters
        for (exp in -4..8) {
            val scale = 10.0.pow(exp)
            for (m in NICE_MULTIPLIERS) {
                val candidateUnits = m * scale
                val candidateMm = candidateUnits * unitMm
                if (candidateMm * screenUnitsPerMm >= minSpacingScreenUnits) return candidateMm
            }
        }
        // Degenerate (near-zero zoom/density): fall back to the coarsest nice step tried above.
        return NICE_MULTIPLIERS.last() * 10.0.pow(8) * unitMm
    }

    /**
     * The major tick spacing: the smallest integer multiple of [minorStepMm] (2x, 5x or 10x it)
     * whose on-screen spacing reaches [minMajorSpacingScreenUnits] — major ticks are the ones that
     * carry a label, so they need more room than a bare minor tick mark.
     */
    fun majorStepMm(
        minorStepMm: Double,
        screenUnitsPerMm: Double,
        minMajorSpacingScreenUnits: Double,
    ): Double {
        require(minorStepMm > 0)
        for (multiple in intArrayOf(1, 2, 5, 10, 20, 50, 100)) {
            val candidate = minorStepMm * multiple
            if (candidate * screenUnitsPerMm >= minMajorSpacingScreenUnits) return candidate
        }
        return minorStepMm * 100
    }

    /**
     * Every tick in `[rangeStartMm, rangeEndMm]` (inclusive, with a small epsilon so a boundary
     * tick isn't dropped by float rounding), aligned to multiples of [minorStepMm] *from the page
     * origin (0 mm)* — not from `rangeStartMm` — so ticks never shift as the canvas pans; only
     * which ones are in view changes. A tick is [Tick.major] when its position is (within
     * epsilon) a multiple of [majorStepMm].
     */
    fun ticks(
        rangeStartMm: Double,
        rangeEndMm: Double,
        minorStepMm: Double,
        majorStepMm: Double,
        unit: PdfUnit,
        dpi: Int = 300,
    ): List<Tick> {
        require(minorStepMm > 0 && majorStepMm > 0 && rangeEndMm >= rangeStartMm)
        val effectiveUnit = displayUnit(unit)
        val unitFactor = effectiveUnit.factor(dpi)
        val epsilon = minorStepMm * 1e-6
        val firstIndex = floor((rangeStartMm - epsilon) / minorStepMm).toLong()
        val lastIndex = ceil((rangeEndMm + epsilon) / minorStepMm).toLong()
        val result = mutableListOf<Tick>()
        var index = firstIndex
        while (index <= lastIndex) {
            val positionMm = index * minorStepMm
            if (positionMm >= rangeStartMm - epsilon && positionMm <= rangeEndMm + epsilon) {
                val remainder = abs(positionMm % majorStepMm)
                val major = remainder < epsilon || abs(remainder - majorStepMm) < epsilon
                val labelUnits = positionMm / unitFactor
                result += Tick(positionMm, major, roundForDisplay(labelUnits))
            }
            index++
        }
        return result
    }

    /** Rounds away float noise (`4.9999999999` -> `5.0`) without hiding genuine fractional ticks
     * (a 0.5 in / 1 cm step still renders as such). */
    private fun roundForDisplay(value: Double): Double {
        val scaled = value * 1000.0
        val rounded = kotlin.math.round(scaled)
        return if (abs(scaled - rounded) < 1e-6) rounded / 1000.0 else value
    }
}
