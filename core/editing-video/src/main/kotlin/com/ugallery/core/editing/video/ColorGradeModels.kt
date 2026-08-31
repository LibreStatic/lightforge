package com.ugallery.core.editing.video

import java.io.Serializable

/** Input transfer curve. This does not imply a vendor-gamut to Rec.709 matrix conversion. */
enum class LogInputProfile(val displayName: String) {
    Standard("Standard Rec.709"),
    AppleLog("Apple Log"),
    SonySLog2("Sony S-Log2"),
    SonySLog3("Sony S-Log3"),
    CanonLog2("Canon Log 2"),
    CanonLog3("Canon Log 3"),
    PanasonicVLog("Panasonic V-Log"),
    DjiDLog("DJI D-Log"),
    FujifilmFLog("Fujifilm F-Log"),
    FujifilmFLog2("Fujifilm F-Log2"),
    NikonNLog("Nikon N-Log"),
    BlackmagicFilmGen5("Blackmagic Film Gen 5"),
    ArriLogC3("ARRI LogC3"),
    ArriLogC4("ARRI LogC4"),
    RedLog3G10("RED Log3G10"),
}

enum class HueBand { Red, Orange, Yellow, Green, Cyan, Blue, Purple, Magenta }

data class HueBandAdjustment(
    val band: HueBand,
    val hueShiftDegrees: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) : Serializable {
    init {
        require(hueShiftDegrees in -45f..45f)
        require(saturation in -1f..1f && luminance in -1f..1f)
    }
}

data class LogWheel(
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val level: Float = 0f,
) : Serializable {
    init { require(listOf(red, green, blue, level).all { it in -1f..1f }) }
}

data class LogWheels(
    val shadows: LogWheel = LogWheel(),
    val midtones: LogWheel = LogWheel(),
    val highlights: LogWheel = LogWheel(),
) : Serializable

enum class BuiltInLook(val displayName: String) {
    None("No creative look"), Clean709("Clean Rec.709"), WarmFilm("Warm film"),
    CoolFilm("Cool film"), Bleach("Bleach bypass"), TealOrange("Teal and orange"),
    Monochrome("Monochrome"),
}

data class LutReference(
    val builtIn: BuiltInLook = BuiltInLook.None,
    val customId: Long? = null,
    val intensity: Float = 1f,
) : Serializable {
    init {
        require(intensity in 0f..1f)
        require(customId == null || builtIn == BuiltInLook.None)
    }
}

data class VideoColorGrade(
    val inputProfile: LogInputProfile = LogInputProfile.Standard,
    val profileWasAutoDetected: Boolean = false,
    val exposureEv: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 0f,
    val pivot: Float = 0.42f,
    val saturation: Float = 0f,
    val logWheels: LogWheels = LogWheels(),
    val hueBands: List<HueBandAdjustment> = HueBand.entries.map(::HueBandAdjustment),
    val lut: LutReference = LutReference(),
    val bypass: Boolean = false,
) : Serializable {
    init {
        require(exposureEv in -5f..5f)
        require(temperature in -1f..1f && tint in -1f..1f)
        require(contrast in -1f..1f && pivot in 0.05f..0.95f && saturation in -1f..1f)
        require(hueBands.map(HueBandAdjustment::band) == HueBand.entries) {
            "Hue adjustments must contain every band in canonical order"
        }
    }

    val hasChanges: Boolean get() = !bypass && copy(profileWasAutoDetected = false) != VideoColorGrade()
}

enum class VideoOutputQuality { HevcMain10, H264Compatible }

data class LogProfileDetection(
    val profile: LogInputProfile,
    val confidence: Float,
    val evidence: String,
) {
    init { require(confidence in 0f..1f) }
}

data class CustomLutOption(val id: Long, val displayName: String, val cubeSize: Int)
