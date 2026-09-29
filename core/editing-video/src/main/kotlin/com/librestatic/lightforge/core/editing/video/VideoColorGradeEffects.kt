@file:Suppress("UnsafeOptInUsageError")

package com.librestatic.lightforge.core.editing.video

import androidx.media3.common.Effect
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import kotlin.math.abs
import kotlin.math.max
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
        require(grade.lut.customId == null || customLut != null) {
            "Custom LUT ${grade.lut.customId} is unavailable or corrupt"
        }
        return listOf(SingleColorLut.createFromCube(buildCube(grade, customLut, cubeSize)))
    }

    fun geometryEffects(geometry: VideoGeometry): List<Effect> = buildList {
        if (geometry.left > 0f || geometry.top > 0f || geometry.right < 1f || geometry.bottom < 1f) {
            add(Crop(
                -1f + geometry.left * 2f,
                -1f + geometry.right * 2f,
                1f - geometry.bottom * 2f,
                1f - geometry.top * 2f,
            ))
        }
        if (geometry.rotationDegrees != 0f || geometry.flipHorizontal) {
            add(ScaleAndRotateTransformation.Builder()
                .setScale(if (geometry.flipHorizontal) -1f else 1f, 1f)
                .setRotationDegrees(geometry.rotationDegrees)
                .build())
        }
    }

    fun buildCube(
        grade: VideoColorGrade,
        customLut: CubeLut? = null,
        size: Int = DefaultCubeSize,
    ): Array<Array<IntArray>> {
        require(size in 2..65)
        require(grade.lut.customId == null || customLut != null) {
            "Custom LUT ${grade.lut.customId} is unavailable or corrupt"
        }
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
                    packedRgb(
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
                    packedRgb(
                        (red / denominator * 255f).toInt().coerceIn(0, 255),
                        (green / denominator * 255f).toInt().coerceIn(0, 255),
                        (blue / denominator * 255f).toInt().coerceIn(0, 255),
                    )
                }
            }
        }
    }

    /**
     * Applies [settings] to packed ARGB pixels, for small previews such as look thumbnails. It runs
     * the same per-pixel math as the export, so a thumbnail matches what the look will produce; it
     * is not meant for full frames.
     */
    fun gradePixels(argb: IntArray, settings: VideoColorGrade, customLut: CubeLut? = null): IntArray {
        if (!settings.hasChanges) return argb.copyOf()
        val input = FloatArray(3)
        return IntArray(argb.size) { index ->
            val pixel = argb[index]
            input[0] = ((pixel shr 16) and 0xFF) / 255f
            input[1] = ((pixel shr 8) and 0xFF) / 255f
            input[2] = (pixel and 0xFF) / 255f
            val graded = grade(input, settings, customLut)
            (pixel and 0xFF000000.toInt()) or
                ((graded[0] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((graded[1] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (graded[2] * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    internal fun grade(input: FloatArray, settings: VideoColorGrade, customLut: CubeLut?): FloatArray {
        var rgb = FloatArray(3) { channel -> decodeToLinear(input[channel], settings.inputProfile) }
        val exposure = 2f.pow(settings.exposureEv)
        rgb = FloatArray(3) { rgb[it] * exposure }
        val warmth = settings.temperature * 0.12f
        rgb[0] *= 1f + warmth
        rgb[2] *= 1f - warmth
        rgb[1] *= 1f + settings.tint * 0.06f
        val contrastScale = 2f.pow(settings.contrast * 1.5f)
        rgb = FloatArray(3) { (rgb[it] - settings.pivot) * contrastScale + settings.pivot }
        if (settings.shadows != 0f || settings.highlights != 0f) {
            val gain = tonalRangeGain(luma(rgb), settings.shadows, settings.highlights)
            rgb = FloatArray(3) { rgb[it] * gain }
        }
        applyLogWheels(rgb, settings.logWheels)
        rgb = adjustSaturation(rgb, 1f + settings.saturation)
        if (settings.vibrance != 0f) rgb = applyVibrance(rgb, settings.vibrance)
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

    internal fun decodeToLinear(value: Float, profile: LogInputProfile): Float = when (profile) {
        LogInputProfile.Standard -> decode709(value)
        LogInputProfile.AppleLog -> when {
            value >= 0.2085553f ->
                2f.pow((value - 0.69336945f) / 0.08550479f) - 0.00964052f
            value >= 0f -> kotlin.math.sqrt(value / 47.28711236f) - 0.05641088f
            else -> -0.05641088f
        }
        LogInputProfile.SonySLog2 -> decodeSLog2(value)
        LogInputProfile.SonySLog3 -> if (value >= 171.2102946929f / 1023f) {
            10f.pow((value * 1023f - 420f) / 261.5f) * 0.19f - 0.01f
        } else {
            (value * 1023f - 95f) * 0.01125f / (171.2102946929f - 95f)
        }
        LogInputProfile.CanonLog2 -> if (value < 0.092864125f) {
            -(10f.pow((0.092864125f - value) / 0.24136077f) - 1f) / 87.09937546f * 0.9f
        } else {
            (10f.pow((value - 0.092864125f) / 0.24136077f) - 1f) / 87.09937546f * 0.9f
        }
        LogInputProfile.CanonLog3 -> when {
            value < 0.097465473f ->
                -(10f.pow((0.12783901f - value) / 0.36726845f) - 1f) / 14.98325f * 0.9f
            value <= 0.15277891f -> (value - 0.12512219f) / 1.9754798f * 0.9f
            else -> (10f.pow((value - 0.12240537f) / 0.36726845f) - 1f) / 14.98325f * 0.9f
        }
        LogInputProfile.PanasonicVLog -> if (value < 0.181f) {
            (value - 0.125f) / 5.6f
        } else {
            10f.pow((value - 0.598206f) / 0.241514f) - 0.00873f
        }
        LogInputProfile.DjiDLog -> if (value <= 0.14f) {
            (value - 0.0929f) / 6.025f
        } else {
            (10f.pow(3.89616f * value - 2.27752f) - 0.0108f) / 0.9892f
        }
        LogInputProfile.FujifilmFLog -> decodeFLog(
            value = value,
            encodedCut = 0.100537775223865f,
            a = 0.555556f,
            b = 0.009468f,
            c = 0.344676f,
            d = 0.790453f,
            e = 8.735631f,
            f = 0.092864f,
        )
        LogInputProfile.FujifilmFLog2 -> decodeFLog(
            value = value,
            encodedCut = 0.100686685370811f,
            a = 5.555556f,
            b = 0.064829f,
            c = 0.245281f,
            d = 0.384316f,
            e = 8.799461f,
            f = 0.092864f,
        )
        LogInputProfile.NikonNLog -> if (value < 452f / 1023f) {
            (value / (650f / 1023f)).pow(3) - 0.0075f
        } else {
            kotlin.math.exp(((value - 619f / 1023f) / (150f / 1023f)).toDouble()).toFloat()
        }
        LogInputProfile.BlackmagicFilmGen5 -> if (value < BlackmagicLogCut) {
            (value - 0.09246575342465753f) / 8.283605932402494f
        } else {
            kotlin.math.exp(((value - 0.5300133392291939f) / 0.08692876065491224f).toDouble()).toFloat() -
                0.005494072432257808f
        }
        LogInputProfile.ArriLogC3 -> if (value > 0.149658f) {
            (10f.pow((value - 0.385537f) / 0.247190f) - 0.052272f) / 5.555556f
        } else {
            (value - 0.092809f) / 5.367655f
        }
        LogInputProfile.ArriLogC4 -> decodeArriLogC4(value)
        LogInputProfile.RedLog3G10 -> if (value < 0f) {
            value / 15.1927f - 0.01f
        } else {
            (10f.pow(value / 0.224282f) - 1f) / 155.975327f - 0.01f
        }
    }.coerceIn(0f, 16f)

    private fun decodeSLog2(value: Float): Float {
        val fullRangeSignal = (value * 1023f - 64f) / 876f
        val cameraLinear = if (fullRangeSignal >= 0.030001222851889303f) {
            10f.pow((fullRangeSignal - 0.646596f) / 0.432699f) - 0.037584f
        } else {
            (fullRangeSignal - 0.030001222851889303f) / 5f
        }
        return cameraLinear * 0.9f * 219f / 155f
    }

    private fun decodeFLog(
        value: Float,
        encodedCut: Float,
        a: Float,
        b: Float,
        c: Float,
        d: Float,
        e: Float,
        f: Float,
    ): Float = if (value < encodedCut) {
        (value - f) / e
    } else {
        (10f.pow((value - d) / c) - b) / a
    }

    private fun decodeArriLogC4(value: Float): Float {
        val a = (2f.pow(18) - 16f) / 117.45f
        val b = (1023f - 95f) / 1023f
        val c = 95f / 1023f
        val s = (7f * kotlin.math.ln(2f) * 2f.pow(7f - 14f * c / b)) / (a * b)
        val t = (2f.pow(14f * (-c / b) + 6f) - 64f) / a
        return if (value >= 0f) {
            (2f.pow(14f * ((value - c) / b) + 6f) - 64f) / a
        } else {
            value * s + t
        }
    }

    private val BlackmagicLogCut =
        8.283605932402494f * 0.005f + 0.09246575342465753f

    private fun decode709(value: Float): Float = when {
        value <= 0f -> 0f
        value < 0.081f -> value / 4.5f
        else -> ((value + 0.099f) / 1.099f).pow(1f / 0.45f)
    }

    private fun encode709(value: Float): Float = if (value < 0.018f) value * 4.5f else 1.099f * value.pow(0.45f) - 0.099f

    private fun applyLogWheels(rgb: FloatArray, wheels: LogWheels) {
        val luminance = luma(rgb).coerceIn(0f, 1f)
        val (shadowWeight, middleWeight, highlightWeight) = logWheelWeights(luminance)
        applyWheel(rgb, wheels.shadows, shadowWeight)
        applyWheel(rgb, wheels.midtones, middleWeight)
        applyWheel(rgb, wheels.highlights, highlightWeight)
    }

    internal fun logWheelWeights(luminance: Float): FloatArray {
        val normalized = luminance.coerceIn(0f, 1f)
        val shadowWeight = 1f - smooth(0.05f, 0.55f, normalized)
        val highlightWeight = smooth(0.45f, 0.95f, normalized)
        val middleWeight = (1f - shadowWeight - highlightWeight).coerceIn(0f, 1f)
        return floatArrayOf(shadowWeight, middleWeight, highlightWeight)
    }

    private fun applyWheel(rgb: FloatArray, wheel: LogWheel, weight: Float) {
        rgb[0] += (wheel.red * 0.18f + wheel.level * 0.25f) * weight
        rgb[1] += (wheel.green * 0.18f + wheel.level * 0.25f) * weight
        rgb[2] += (wheel.blue * 0.18f + wheel.level * 0.25f) * weight
    }

    internal fun applyHueBands(rgb: FloatArray, bands: List<HueBandAdjustment>): FloatArray {
        if (bands.all { it.hueShiftDegrees == 0f && it.saturation == 0f && it.luminance == 0f }) {
            return rgb
        }
        val hsv = rgbToHsv(rgb)
        val originalHue = hsv[0]
        var hueDelta = 0f
        var saturationDelta = 0f
        var luminanceDelta = 0f
        bands.forEach { adjustment ->
            val center = adjustment.band.ordinal * 45f
            val distance = min(abs(originalHue - center), 360f - abs(originalHue - center))
            val weight = (1f - distance / 45f).coerceIn(0f, 1f)
            hueDelta += adjustment.hueShiftDegrees * weight
            saturationDelta += adjustment.saturation * weight
            luminanceDelta += adjustment.luminance * weight
        }
        hsv[0] = (originalHue + hueDelta + 360f) % 360f
        hsv[1] = (hsv[1] * (1f + saturationDelta)).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] + luminanceDelta * 0.25f).coerceIn(0f, 1f)
        return hsvToRgb(hsv)
    }

    private fun rgbToHsv(rgb: FloatArray): FloatArray {
        val red = rgb[0].coerceIn(0f, 1f)
        val green = rgb[1].coerceIn(0f, 1f)
        val blue = rgb[2].coerceIn(0f, 1f)
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val range = maximum - minimum
        val hue = when {
            range == 0f -> 0f
            maximum == red -> 60f * ((green - blue) / range % 6f)
            maximum == green -> 60f * ((blue - red) / range + 2f)
            else -> 60f * ((red - green) / range + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (maximum == 0f) 0f else range / maximum
        return floatArrayOf(hue, saturation, maximum)
    }

    private fun hsvToRgb(hsv: FloatArray): FloatArray {
        val hue = (hsv[0] % 360f + 360f) % 360f
        val saturation = hsv[1].coerceIn(0f, 1f)
        val value = hsv[2].coerceIn(0f, 1f)
        val chroma = value * saturation
        val secondary = chroma * (1f - abs((hue / 60f % 2f) - 1f))
        val match = value - chroma
        val (red, green, blue) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(chroma, secondary, 0f)
            1 -> Triple(secondary, chroma, 0f)
            2 -> Triple(0f, chroma, secondary)
            3 -> Triple(0f, secondary, chroma)
            4 -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        return floatArrayOf(red + match, green + match, blue + match)
    }

    private fun applyBuiltInLook(rgb: FloatArray, look: BuiltInLook): FloatArray = when (look) {
        BuiltInLook.None, BuiltInLook.Clean709 -> rgb
        BuiltInLook.WarmFilm -> floatArrayOf(rgb[0] * 1.08f + 0.015f, rgb[1] * 1.01f, rgb[2] * 0.90f)
        BuiltInLook.CoolFilm -> floatArrayOf(rgb[0] * 0.92f, rgb[1] * 1.01f, rgb[2] * 1.08f + 0.01f)
        BuiltInLook.Bleach -> adjustSaturation(FloatArray(3) { (rgb[it] - 0.5f) * 1.25f + 0.5f }, 0.38f)
        BuiltInLook.TealOrange -> floatArrayOf(rgb[0] * 1.06f, rgb[1] * 0.99f + rgb[2] * 0.015f, rgb[2] * 1.04f + rgb[1] * 0.02f)
        BuiltInLook.Monochrome -> FloatArray(3) { luma(rgb) }
    }

    /**
     * Multiplicative gain for the shadows/highlights sliders. The same weights and 2^x scaling are
     * implemented in `video_hdr_grade_fragment.glsl`; keep both in sync.
     */
    internal fun tonalRangeGain(luminance: Float, shadows: Float, highlights: Float): Float {
        val y = luminance.coerceIn(0f, 1f)
        val shadowWeight = 1f - smooth(0f, 0.35f, y)
        val highlightWeight = smooth(0.25f, 0.9f, y)
        return 2f.pow(shadows * shadowWeight + highlights * highlightWeight)
    }

    /** Boosts (or mutes) saturation more for dull colors than for already vivid ones. */
    internal fun applyVibrance(rgb: FloatArray, vibrance: Float): FloatArray {
        val red = rgb[0].coerceIn(0f, 1f)
        val green = rgb[1].coerceIn(0f, 1f)
        val blue = rgb[2].coerceIn(0f, 1f)
        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))
        val chroma = if (highest > 0.0001f) (highest - lowest) / highest else 0f
        return adjustSaturation(rgb, 1f + vibrance * (1f - chroma))
    }

    private fun adjustSaturation(rgb: FloatArray, amount: Float): FloatArray {
        val luminance = luma(rgb)
        return FloatArray(3) { channel -> luminance + (rgb[channel] - luminance) * amount }
    }

    private fun luma(rgb: FloatArray) = rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f
    private fun mix(start: Float, end: Float, amount: Float) = start + (end - start) * amount
    private fun packedRgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun smooth(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
