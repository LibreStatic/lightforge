package com.librestatic.lightforge.core.model

import java.io.Serializable

enum class RawSensorLayout { Bayer, XTrans, Layered, Unknown }

/** Pixel rectangle inside the full visible sensor area (before orientation). */
data class RawCameraCrop(val left: Int, val top: Int, val width: Int, val height: Int) : Serializable

/** Size of a developed RAW frame in pixels, before orientation. */
data class RawFrameSize(val width: Int, val height: Int) : Serializable

/**
 * [width] and [height] are the camera-framed size (the DNG DefaultCrop when present, otherwise the
 * whole visible area); [fullWidth] and [fullHeight] are the full visible sensor area.
 */
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
    val fullWidth: Int = width,
    val fullHeight: Int = height,
    /** The camera framing inside the full area, or null when the file declares none. */
    val cameraCrop: RawCameraCrop? = null,
    /** LibRaw orientation code (0, 3, 5 or 6) the developed image is rotated by. */
    val flip: Int = 0,
) : Serializable {
    init {
        require(width > 0 && height > 0)
        require(fullWidth > 0 && fullHeight > 0)
        require(bitsPerSample in 1..32)
        require(blackLevel >= 0 && whiteLevel > blackLevel)
    }

    /** Size of the developed frame with camera framing on ([width] x [height]) or off (full area). */
    fun dimensions(cameraFraming: Boolean): RawFrameSize =
        if (cameraFraming) RawFrameSize(width, height) else RawFrameSize(fullWidth, fullHeight)

    /**
     * True when the camera framing removes more than 1% of the full width or height. The few pixels
     * of margin plain DNG writers declare are not worth a user-facing choice.
     */
    val hasMeaningfulCameraCrop: Boolean
        get() = cameraCrop != null &&
            ((fullWidth - width).toLong() * 100 > fullWidth || (fullHeight - height).toLong() * 100 > fullHeight)
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
    /** Develop the camera framing (DNG DefaultCrop) instead of the full visible sensor area. */
    val cameraFraming: Boolean = true,
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
