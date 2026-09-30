package com.librestatic.lightforge.core.model

import java.io.Serializable

enum class RawSensorLayout { Bayer, XTrans, Layered, Unknown }

data class RawMetadata(
    val make: String = "",
    val model: String = "",
    val lens: String = "",
    val width: Int,
    val height: Int,
    val bitsPerSample: Int,
    val blackLevel: Int,
    val whiteLevel: Int,
    val iso: Int? = null,
    val shutterSeconds: Double? = null,
    val aperture: Double? = null,
    val focalLengthMm: Double? = null,
    val cameraTemperatureKelvin: Int? = null,
    val cameraTint: Float = 0f,
    val sensorLayout: RawSensorLayout = RawSensorLayout.Unknown,
    val colorDescription: String = "",
) : Serializable {
    init {
        require(width > 0 && height > 0)
        require(bitsPerSample in 1..32)
        require(blackLevel >= 0 && whiteLevel > blackLevel)
    }
}

data class RawDevelopmentSettings(
    val exposureEv: Float = 0f,
    val temperatureKelvin: Int = 6_500,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val highlightRecovery: Float = 0f,
    val luminanceNoiseReduction: Float = 0f,
    val chromaNoiseReduction: Float = 0f,
    val sharpening: Float = 0.25f,
    val chromaticAberrationCorrection: Boolean = true,
    val lensCorrection: Boolean = true,
) : Serializable {
    init {
        require(exposureEv in -5f..5f)
        require(temperatureKelvin in 2_000..50_000)
        require(tint in -150f..150f)
        require(highlights in -1f..1f && shadows in -1f..1f)
        require(whites in -1f..1f && blacks in -1f..1f)
        require(contrast in -1f..1f && saturation in -1f..1f && vibrance in -1f..1f)
        require(highlightRecovery in 0f..1f)
        require(luminanceNoiseReduction in 0f..1f && chromaNoiseReduction in 0f..1f)
        require(sharpening in 0f..1f)
    }

    val isIdentity: Boolean get() = this == RawDevelopmentSettings()
}

enum class RawOutputFormat { JpegSrgb, Tiff16Srgb }
