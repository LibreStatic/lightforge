package com.ugallery.feature.pdfstudio

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PdfRulerMathTest {
    @Test
    fun minorStepGrowsAsZoomShrinksSoLabelsNeverCrowd() {
        // Screen units per mm shrinks as zoom drops; the chosen step must grow to compensate, so
        // consecutive minor ticks stay at least minSpacing apart on screen at every zoom.
        val zoomedIn = PdfRulerMath.minorStepMm(PdfUnit.Millimeter, screenUnitsPerMm = 8.0, minSpacingScreenUnits = 24.0)
        val zoomedOut = PdfRulerMath.minorStepMm(PdfUnit.Millimeter, screenUnitsPerMm = 1.0, minSpacingScreenUnits = 24.0)
        assertTrue(zoomedOut >= zoomedIn)
        assertTrue(zoomedIn * 8.0 >= 24.0 - 1e-9)
        assertTrue(zoomedOut * 1.0 >= 24.0 - 1e-9)
    }

    @Test
    fun minorStepIsNiceInTheRulersOwnUnit() {
        val niceMultipliers = setOf(1.0, 2.0, 5.0)
        for (unit in listOf(PdfUnit.Millimeter, PdfUnit.Centimeter, PdfUnit.Inch)) {
            val step = PdfRulerMath.minorStepMm(unit, screenUnitsPerMm = 3.7, minSpacingScreenUnits = 30.0)
            val unitValue = step / unit.millimeters
            // unitValue must equal m * 10^k for some nice multiplier m and integer k.
            val matches =
                niceMultipliers.any { m ->
                    val k = Math.round(Math.log10(unitValue / m).toFloat()).toDouble()
                    abs(unitValue - m * Math.pow(10.0, k)) < 1e-6
                }
            assertTrue("unit=$unit step=$step unitValue=$unitValue not nice", matches)
        }
    }

    @Test
    fun pixelUnitFallsBackToMillimeterRuler() {
        assertEquals(PdfUnit.Millimeter, PdfRulerMath.displayUnit(PdfUnit.Pixel))
        assertEquals(PdfUnit.Millimeter, PdfRulerMath.displayUnit(PdfUnit.Millimeter))
        assertEquals(PdfUnit.Centimeter, PdfRulerMath.displayUnit(PdfUnit.Centimeter))
        assertEquals(PdfUnit.Inch, PdfRulerMath.displayUnit(PdfUnit.Inch))
    }

    @Test
    fun majorStepIsAnIntegerMultipleOfMinorStepAndWideEnough() {
        val minor = PdfRulerMath.minorStepMm(PdfUnit.Millimeter, screenUnitsPerMm = 4.0, minSpacingScreenUnits = 20.0)
        val major = PdfRulerMath.majorStepMm(minor, screenUnitsPerMm = 4.0, minMajorSpacingScreenUnits = 60.0)
        assertTrue(major >= minor)
        val ratio = major / minor
        assertEquals(ratio, Math.round(ratio).toDouble(), 1e-9)
        assertTrue(major * 4.0 >= 60.0 - 1e-9)
    }

    @Test
    fun ticksAreAlignedToThePageOriginNotTheVisibleRangeStart() {
        // A pan that starts mid-step (here 23mm into a 10mm grid) must not shift the grid: ticks
        // still land on multiples of the step measured from 0, only the visible subset changes.
        val ticks = PdfRulerMath.ticks(rangeStartMm = 23.0, rangeEndMm = 83.0, minorStepMm = 10.0, majorStepMm = 50.0, unit = PdfUnit.Millimeter)
        val positions = ticks.map { it.positionMm }
        assertEquals(listOf(30.0, 40.0, 50.0, 60.0, 70.0, 80.0), positions)
    }

    @Test
    fun ticksIncludeRangeBoundariesWhenExactlyOnStep() {
        val ticks = PdfRulerMath.ticks(rangeStartMm = 0.0, rangeEndMm = 50.0, minorStepMm = 10.0, majorStepMm = 50.0, unit = PdfUnit.Millimeter)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), ticks.map { it.positionMm })
    }

    @Test
    fun majorFlagMarksExactMultiplesOfMajorStep() {
        val ticks = PdfRulerMath.ticks(rangeStartMm = 0.0, rangeEndMm = 100.0, minorStepMm = 10.0, majorStepMm = 50.0, unit = PdfUnit.Millimeter)
        val majors = ticks.filter { it.major }.map { it.positionMm }
        assertEquals(listOf(0.0, 50.0, 100.0), majors)
    }

    @Test
    fun labelUnitsConvertFromPhysicalMillimeters() {
        val cm = PdfRulerMath.ticks(rangeStartMm = 0.0, rangeEndMm = 30.0, minorStepMm = 10.0, majorStepMm = 10.0, unit = PdfUnit.Centimeter)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), cm.map { it.labelUnits })
        val inch = PdfRulerMath.ticks(rangeStartMm = 0.0, rangeEndMm = 25.4 * 2, minorStepMm = 25.4, majorStepMm = 25.4, unit = PdfUnit.Inch)
        assertEquals(listOf(0.0, 1.0, 2.0), inch.map { it.labelUnits })
    }

    @Test
    fun pixelUnitTicksAreLabeledInMillimeters() {
        val ticks = PdfRulerMath.ticks(rangeStartMm = 0.0, rangeEndMm = 20.0, minorStepMm = 10.0, majorStepMm = 10.0, unit = PdfUnit.Pixel, dpi = 300)
        assertEquals(listOf(0.0, 10.0, 20.0), ticks.map { it.labelUnits })
    }

    @Test
    fun ticksNeverCrowdOnScreenAcrossAWideZoomSweep() {
        for (screenUnitsPerMm in listOf(0.3, 1.0, 2.0, 4.0, 8.0, 16.0)) {
            for (unit in PdfUnit.values()) {
                val minor = PdfRulerMath.minorStepMm(unit, screenUnitsPerMm, minSpacingScreenUnits = 8.0)
                val ticks = PdfRulerMath.ticks(0.0, 500.0, minor, PdfRulerMath.majorStepMm(minor, screenUnitsPerMm, 40.0), unit)
                val positions = ticks.map { it.positionMm }
                for (i in 1 until positions.size) {
                    val gapScreen = (positions[i] - positions[i - 1]) * screenUnitsPerMm
                    assertTrue("unit=$unit zoomFactor=$screenUnitsPerMm gap=$gapScreen", gapScreen >= 8.0 - 1e-6)
                }
            }
        }
    }
}
