package com.ugallery.core.editing.video

import android.graphics.Color
import androidx.media3.common.Effect
import androidx.media3.effect.SingleColorLut
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

object VideoColorGradeEffects {
    const val DefaultCubeSize = 33

    fun create(
        grade: VideoColorGrade,
        customLut: CubeLut? = null,
        cubeSize: Int = DefaultCubeSize,
    ): List<Effect> {
        if (grade.bypass || (!grade.hasChanges && customLut == null)) return emptyList()
        return listOf(SingleColorLut.createFromCube(buildCube(grade, customLut, cubeSize)))
    }

    fun buildCube(
        grade: VideoColorGrade,
        customLut: CubeLut? = null,
        size: Int = DefaultCubeSize,
    ): Array<Array<IntArray>> {
        require(size in 2..65)
        return Array(size) { redIndex ->
            Array(size) { greenIndex ->
                IntArray(size) { blueIndex ->
                    val denominator = (size - 1).toFloat()
                    val source = floatArrayOf(
                        redIndex / denominator,
                        greenIndex / denominator,
                        blueIndex / denominator,
                    )
                    val graded = grade(source, grade, customLut)
                    Color.rgb(
                        (graded[0] * 255f).toInt().coerceIn(0, 255),
                        (graded[1] * 255f).toInt().coerceIn(0, 255),
                        (graded[2] * 255f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    fun buildPreviewCube(
        grade: VideoColorGrade,
        customLut: CubeLut? = null,
        size: Int,
    ): Array<Array<IntArray>> =
        if (grade.bypass || (!grade.hasChanges && customLut == null)) {
            buildIdentityCube(size)
        } else {
            buildCube(grade, customLut, size)
        }

    private fun buildIdentityCube(size: Int): Array<Array<IntArray>> {
        require(size in 2..65)
        val denominator = (size - 1).toFloat()
        return Array(size) { red ->
            Array(size) { green ->
                IntArray(size) { blue ->
                    Color.rgb(
                        (red / denominator * 255f).toInt().coerceIn(0, 255),
                        (green / denominator * 255f).toInt().coerceIn(0, 255),
                        (blue / denominator * 255f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    internal fun grade(input: FloatArray, settings: VideoColorGrade, customLut: CubeLut?): FloatArray {
        var rgb = FloatArray(3) { channel -> decodeLog(input[channel], settings.inputProfile) }
        val exposure = 2f.pow(settings.exposureEv)
        rgb = FloatArray(3) { rgb[it] * exposure }
        val warmth = settings.temperature * 0.12f
        rgb[0] *= 1f + warmth
        rgb[2] *= 1f - warmth
        rgb[1] *= 1f + settings.tint * 0.06f
        val contrastScale = 2f.pow(settings.contrast * 1.5f)
        rgb = FloatArray(3) { (rgb[it] - settings.pivot) * contrastScale + settings.pivot }
        applyLogWheels(rgb, settings.logWheels)
        rgb = adjustSaturation(rgb, 1f + settings.saturation)
        rgb = applyHueBands(rgb, settings.hueBands)
        val beforeLook = rgb
        val builtInResult = applyBuiltInLook(beforeLook, settings.lut.builtIn)
        rgb = FloatArray(3) { channel ->
            mix(beforeLook[channel], builtInResult[channel], settings.lut.intensity)
        }
        if (customLut != null) {
            val mapped = customLut.sample(rgb[0], rgb[1], rgb[2])
            rgb = FloatArray(3) { channel -> mix(rgb[channel], mapped[channel], settings.lut.intensity) }
        }
        return FloatArray(3) { channel -> encode709(rgb[channel].coerceIn(0f, 1f)) }
    }

    private fun decodeLog(value: Float, profile: LogInputProfile): Float = when (profile) {
        LogInputProfile.Standard -> value.coerceAtLeast(0f).pow(2.4f)
        LogInputProfile.AppleLog -> logDecode(value, 0.0564f, 0.0869f, 0.5291f, 0.0929f)
        LogInputProfile.SonySLog2 -> ((10f.pow((value - 0.616596f) / 0.432699f) - 0.037584f) * 0.9f).coerceAtLeast(0f)
        LogInputProfile.SonySLog3 -> ((10f.pow((value - 0.410557f) / 0.25562f) - 0.052632f) / 5.555556f).coerceAtLeast(0f)
        LogInputProfile.CanonLog2 -> logDecode(value, 0.035f, 0.092f, 0.241f, 0.125f)
        LogInputProfile.CanonLog3 -> logDecode(value, 0.040f, 0.097f, 0.367f, 0.127f)
        LogInputProfile.PanasonicVLog -> if (value < 0.181f) (value - 0.125f) / 5.6f else 10f.pow((value - 0.598206f) / 0.241514f) - 0.00873f
        LogInputProfile.DjiDLog -> logDecode(value, 0.035f, 0.092f, 0.256f, 0.120f)
        LogInputProfile.FujifilmFLog -> logDecode(value, 0.045f, 0.092f, 0.344f, 0.100f)
        LogInputProfile.FujifilmFLog2 -> logDecode(value, 0.040f, 0.092f, 0.384f, 0.100f)
        LogInputProfile.NikonNLog -> logDecode(value, 0.040f, 0.095f, 0.310f, 0.115f)
        LogInputProfile.BlackmagicFilmGen5 -> logDecode(value, 0.038f, 0.086f, 0.312f, 0.108f)
        LogInputProfile.ArriLogC3 -> logDecode(value, 0.052f, 0.092f, 0.247f, 0.111f)
        LogInputProfile.ArriLogC4 -> logDecode(value, 0.050f, 0.092f, 0.278f, 0.110f)
        LogInputProfile.RedLog3G10 -> logDecode(value, 0.035f, 0.091f, 0.270f, 0.105f)
    }.coerceIn(0f, 16f)

    private fun logDecode(value: Float, cut: Float, offset: Float, slope: Float, black: Float): Float =
        if (value <= black) ((value - black) / 5f).coerceAtLeast(0f)
        else ((10f.pow((value - offset) / slope) - 1f) * cut).coerceAtLeast(0f)

    private fun encode709(value: Float): Float = if (value < 0.018f) value * 4.5f else 1.099f * value.pow(0.45f) - 0.099f

    private fun applyLogWheels(rgb: FloatArray, wheels: LogWheels) {
        val luminance = luma(rgb).coerceIn(0f, 1f)
        val shadowWeight = smooth(0.55f, 0.05f, 1f - luminance)
        val highlightWeight = smooth(0.45f, 0.95f, luminance)
        val middleWeight = (1f - shadowWeight - highlightWeight).coerceIn(0f, 1f)
        applyWheel(rgb, wheels.shadows, shadowWeight)
        applyWheel(rgb, wheels.midtones, middleWeight)
        applyWheel(rgb, wheels.highlights, highlightWeight)
    }

    private fun applyWheel(rgb: FloatArray, wheel: LogWheel, weight: Float) {
        rgb[0] += (wheel.red * 0.18f + wheel.level * 0.25f) * weight
        rgb[1] += (wheel.green * 0.18f + wheel.level * 0.25f) * weight
        rgb[2] += (wheel.blue * 0.18f + wheel.level * 0.25f) * weight
    }

    private fun applyHueBands(rgb: FloatArray, bands: List<HueBandAdjustment>): FloatArray {
        val hsv = FloatArray(3)
        Color.RGBToHSV(
            (rgb[0].coerceIn(0f, 1f) * 255).toInt(),
            (rgb[1].coerceIn(0f, 1f) * 255).toInt(),
            (rgb[2].coerceIn(0f, 1f) * 255).toInt(), hsv,
        )
        bands.forEach { adjustment ->
            val center = adjustment.band.ordinal * 45f
            val distance = min(abs(hsv[0] - center), 360f - abs(hsv[0] - center))
            val weight = (1f - distance / 45f).coerceIn(0f, 1f)
            hsv[0] = (hsv[0] + adjustment.hueShiftDegrees * weight + 360f) % 360f
            hsv[1] = (hsv[1] * (1f + adjustment.saturation * weight)).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] + adjustment.luminance * 0.25f * weight).coerceIn(0f, 1f)
        }
        val color = Color.HSVToColor(hsv)
        return floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
    }

    private fun applyBuiltInLook(rgb: FloatArray, look: BuiltInLook): FloatArray = when (look) {
        BuiltInLook.None, BuiltInLook.Clean709 -> rgb
        BuiltInLook.WarmFilm -> floatArrayOf(rgb[0] * 1.08f + 0.015f, rgb[1] * 1.01f, rgb[2] * 0.90f)
        BuiltInLook.CoolFilm -> floatArrayOf(rgb[0] * 0.92f, rgb[1] * 1.01f, rgb[2] * 1.08f + 0.01f)
        BuiltInLook.Bleach -> adjustSaturation(FloatArray(3) { (rgb[it] - 0.5f) * 1.25f + 0.5f }, 0.38f)
        BuiltInLook.TealOrange -> floatArrayOf(rgb[0] * 1.06f, rgb[1] * 0.99f + rgb[2] * 0.015f, rgb[2] * 1.04f + rgb[1] * 0.02f)
        BuiltInLook.Monochrome -> FloatArray(3) { luma(rgb) }
    }

    private fun adjustSaturation(rgb: FloatArray, amount: Float): FloatArray {
        val luminance = luma(rgb)
        return FloatArray(3) { channel -> luminance + (rgb[channel] - luminance) * amount }
    }

    private fun luma(rgb: FloatArray) = rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f
    private fun mix(start: Float, end: Float, amount: Float) = start + (end - start) * amount
    private fun smooth(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
