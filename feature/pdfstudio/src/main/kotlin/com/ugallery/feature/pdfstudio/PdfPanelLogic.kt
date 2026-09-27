package com.ugallery.feature.pdfstudio

import kotlin.math.roundToInt

/** Pure clamping/step math behind [PdfStepperField]; kept separate so it has plain JVM coverage. */
internal object PdfStepperMath {
    /** [value] moved by one [step] in the direction of [delta] (usually -1 or 1), clamped. */
    fun stepped(value: Double, step: Double, delta: Int, min: Double, max: Double): Double =
        (value + step * delta).coerceIn(min, max)
}

/** A visual paper preset offered by the Layout panel's paper cards, in millimeters. */
internal data class PdfPaperPreset(val id: String, val label: String, val widthMm: Double, val heightMm: Double)

internal object PdfPaperPresets {
    const val A4 = "a4"
    const val LETTER = "letter"
    const val PRINT_10X15 = "10x15"
    const val SQUARE = "square"
    const val CUSTOM = "custom"

    /** Portrait dimensions; callers swap width/height themselves for landscape. */
    val presets =
        listOf(
            PdfPaperPreset(A4, "A4", 210.0, 297.0),
            PdfPaperPreset(LETTER, "Letter", 215.9, 279.4),
            PdfPaperPreset(PRINT_10X15, "10 × 15 cm", 100.0, 150.0),
            PdfPaperPreset(SQUARE, "Square", 148.0, 148.0),
        )

    /** Which preset (if any) a page's current, orientation-normalized size matches. */
    fun matching(widthMm: Double, heightMm: Double): String {
        val w = maxOf(widthMm, heightMm)
        val h = minOf(widthMm, heightMm)
        presets.forEach { preset ->
            val pw = maxOf(preset.widthMm, preset.heightMm)
            val ph = minOf(preset.widthMm, preset.heightMm)
            if (kotlin.math.abs(pw - w) < 0.5 && kotlin.math.abs(ph - h) < 0.5) return preset.id
        }
        return CUSTOM
    }
}

/**
 * Maps a photos-per-page template tile to a column count for [PdfGeometry.grid]. 1/4/9 have an
 * obvious square layout; 2 and 6 depend on the page's orientation so the grid reads naturally
 * (2 photos stack in portrait, sit side-by-side in landscape; 6 is 2×3 in portrait, 3×2 in
 * landscape).
 */
internal object PdfLayoutTemplates {
    val TEMPLATES = listOf(1, 2, 4, 6, 9)

    fun columnsFor(template: Int, landscape: Boolean): Int =
        when (template) {
            1 -> 1
            2 -> if (landscape) 2 else 1
            4 -> 2
            6 -> if (landscape) 3 else 2
            9 -> 3
            else -> throw IllegalArgumentException("Unsupported template: $template")
        }
}

/** Inline validation for the custom page-size sheet: min 20 mm, max 2000 mm (matches
 * [PdfProject.validate]'s page bounds). */
internal object PdfCustomSize {
    const val MIN_MM = 20.0
    const val MAX_MM = 2000.0

    enum class Problem {
        Invalid,
        TooSmall,
        TooLarge,
    }

    data class Field(val problem: Problem?) {
        val isValid: Boolean
            get() = problem == null
    }

    data class Result(val width: Field, val height: Field) {
        val isValid: Boolean
            get() = width.isValid && height.isValid
    }

    private fun check(mm: Double?): Field =
        Field(
            when {
                mm == null || !mm.isFinite() -> Problem.Invalid
                mm < MIN_MM -> Problem.TooSmall
                mm > MAX_MM -> Problem.TooLarge
                else -> null
            }
        )

    fun validate(widthMm: Double?, heightMm: Double?): Result = Result(check(widthMm), check(heightMm))
}

/** Pure math for the Adjust panel's 2D crop-focus viewport: dragging/keyboard nudging moves the
 * focus point within 0..1 on each axis; formatting is left to the caller (localized string). */
internal object PdfCropFocus {
    const val NUDGE = 0.05

    fun move(current: Double, delta: Double): Double = (current + delta).coerceIn(0.0, 1.0)

    fun percent(focus: Double): Int = (focus * 100).roundToInt().coerceIn(0, 100)
}
