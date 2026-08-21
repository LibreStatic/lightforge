package com.ugallery.core.editing.video

import android.net.Uri
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

object VideoEditRecipeCodec {
    fun encode(recipe: VideoEditRecipe): String {
        val grade = recipe.colorGrade
        return Properties().apply {
            setProperty("version", "1")
            setProperty("start", recipe.startMillis.toString())
            recipe.endMillis?.let { setProperty("end", it.toString()) }
            setProperty("speed", recipe.speed.toString())
            setProperty("audio", recipe.originalAudioVolume.toString())
            recipe.musicUri?.let { setProperty("music", it.toString()) }
            setProperty("musicVolume", recipe.musicVolume.toString())
            setProperty("quality", recipe.outputQuality.name)
            setProperty("profile", grade.inputProfile.name)
            setProperty("profileAuto", grade.profileWasAutoDetected.toString())
            setProperty("exposure", grade.exposureEv.toString())
            setProperty("temperature", grade.temperature.toString())
            setProperty("tint", grade.tint.toString())
            setProperty("contrast", grade.contrast.toString())
            setProperty("pivot", grade.pivot.toString())
            setProperty("saturation", grade.saturation.toString())
            setProperty("bypass", grade.bypass.toString())
            setProperty("look", grade.lut.builtIn.name)
            grade.lut.customId?.let { setProperty("customLut", it.toString()) }
            setProperty("lutIntensity", grade.lut.intensity.toString())
            setProperty("wheels", listOf(
                grade.logWheels.shadows, grade.logWheels.midtones, grade.logWheels.highlights,
            ).flatMap { listOf(it.red, it.green, it.blue, it.level) }.joinToString(","))
            setProperty("bands", grade.hueBands.joinToString(";") {
                "${it.band.name},${it.hueShiftDegrees},${it.saturation},${it.luminance}"
            })
        }.let { properties ->
            StringWriter().also { properties.store(it, null) }.toString()
        }
    }

    fun decode(encoded: String): VideoEditRecipe {
        val properties = Properties().apply { load(StringReader(encoded)) }
        require(properties.getProperty("version") == "1") { "Unsupported video recipe version" }
        val wheels = properties.getProperty("wheels", "").split(',').mapNotNull(String::toFloatOrNull)
        fun wheel(offset: Int) = if (wheels.size >= offset + 4) {
            LogWheel(wheels[offset], wheels[offset + 1], wheels[offset + 2], wheels[offset + 3])
        } else LogWheel()
        val bands = properties.getProperty("bands", "").split(';').mapNotNull { encodedBand ->
            val values = encodedBand.split(',')
            runCatching {
                HueBandAdjustment(
                    HueBand.valueOf(values[0]), values[1].toFloat(), values[2].toFloat(), values[3].toFloat(),
                )
            }.getOrNull()
        }.ifEmpty { HueBand.entries.map(::HueBandAdjustment) }
        val grade = VideoColorGrade(
            inputProfile = enumValue(properties, "profile", LogInputProfile.Standard),
            profileWasAutoDetected = properties.getProperty("profileAuto").toBoolean(),
            exposureEv = floatValue(properties, "exposure", 0f),
            temperature = floatValue(properties, "temperature", 0f),
            tint = floatValue(properties, "tint", 0f),
            contrast = floatValue(properties, "contrast", 0f),
            pivot = floatValue(properties, "pivot", 0.42f),
            saturation = floatValue(properties, "saturation", 0f),
            logWheels = LogWheels(wheel(0), wheel(4), wheel(8)),
            hueBands = bands,
            lut = LutReference(
                builtIn = enumValue(properties, "look", BuiltInLook.None),
                customId = properties.getProperty("customLut")?.toLongOrNull(),
                intensity = floatValue(properties, "lutIntensity", 1f),
            ),
            bypass = properties.getProperty("bypass").toBoolean(),
        )
        return VideoEditRecipe(
            startMillis = properties.getProperty("start", "0").toLong(),
            endMillis = properties.getProperty("end")?.toLongOrNull(),
            speed = floatValue(properties, "speed", 1f),
            originalAudioVolume = floatValue(properties, "audio", 1f),
            musicUri = properties.getProperty("music")?.let(Uri::parse),
            musicVolume = floatValue(properties, "musicVolume", 0.6f),
            colorGrade = grade,
            outputQuality = enumValue(properties, "quality", VideoOutputQuality.H264Compatible),
        )
    }

    private fun floatValue(properties: Properties, key: String, default: Float) =
        properties.getProperty(key)?.toFloatOrNull() ?: default

    private inline fun <reified T : Enum<T>> enumValue(properties: Properties, key: String, default: T): T =
        properties.getProperty(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
