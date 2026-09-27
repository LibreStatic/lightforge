package com.librestatic.lightforge.core.editing.video

import android.net.Uri
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

object VideoEditRecipeCodec {
    fun encode(recipe: VideoEditRecipe): String {
        val grade = recipe.colorGrade
        return Properties().apply {
            setProperty("version", "5")
            setProperty("start", recipe.startMillis.toString())
            recipe.endMillis?.let { setProperty("end", it.toString()) }
            setProperty("speed", recipe.speed.toString())
            setProperty("audio", recipe.originalAudioVolume.toString())
            recipe.musicUri?.let { setProperty("music", it.toString()) }
            setProperty("musicVolume", recipe.musicVolume.toString())
            setProperty("quality", recipe.outputQuality.name)
            setProperty("dynamicRange", recipe.dynamicRange.name)
            setProperty("geometry", recipe.geometry.let {
                listOf(it.left, it.top, it.right, it.bottom, it.rotationDegrees, it.flipHorizontal)
                    .joinToString(",")
            })
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
            setProperty("slowSegments", recipe.slowMotionSegments.joinToString(";") { segment ->
                listOf(
                    segment.id,
                    segment.startMillis,
                    segment.endMillis,
                    segment.speed,
                    segment.audioMode.name,
                ).joinToString(",")
            })
            if (recipe.annotations.isNotEmpty()) {
                setProperty("annotations", VideoAnnotationCodec.encode(recipe.annotations))
            }
        }.let { properties ->
            StringWriter().also { properties.store(it, null) }.toString()
        }
    }

    fun decode(encoded: String): VideoEditRecipe {
        val properties = Properties().apply { load(StringReader(encoded)) }
        val version = requireNotNull(properties.getProperty("version")?.toIntOrNull()) {
            "Unsupported video recipe version"
        }
        require(version in 1..5) { "Unsupported video recipe version" }
        val wheels = properties.getProperty("wheels", "").split(',').mapNotNull(String::toFloatOrNull)
        fun wheel(offset: Int) = if (wheels.size >= offset + 4) {
            LogWheel(wheels[offset], wheels[offset + 1], wheels[offset + 2], wheels[offset + 3])
        } else LogWheel()
        val decodedBands = properties.getProperty("bands", "").split(';').mapNotNull { encodedBand ->
            val values = encodedBand.split(',')
            runCatching {
                HueBandAdjustment(
                    HueBand.valueOf(values[0]), values[1].toFloat(), values[2].toFloat(), values[3].toFloat(),
                )
            }.getOrNull()
        }.associateBy(HueBandAdjustment::band)
        // Persisted recipes can be truncated or come from an older writer. Downstream color
        // controls address every band, so always restore a complete, deterministic set.
        val bands = HueBand.entries.map { band -> decodedBands[band] ?: HueBandAdjustment(band) }
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
        val slowSegments = if (version >= 2) {
            properties.getProperty("slowSegments", "").split(';').mapNotNull { encodedSegment ->
                val values = encodedSegment.split(',')
                if (values.size != 5) return@mapNotNull null
                runCatching {
                    SlowMotionSegment(
                        id = values[0],
                        startMillis = values[1].toLong(),
                        endMillis = values[2].toLong(),
                        speed = values[3].toFloat(),
                        audioMode = enumValueOf(values[4]),
                    )
                }.getOrNull()
            }.sortedBy(SlowMotionSegment::startMillis)
        } else emptyList()
        val geometry = properties.getProperty("geometry", "").split(',').let { values ->
            if (version >= 3 && values.size == 6) runCatching {
                VideoGeometry(
                    left = values[0].toFloat(),
                    top = values[1].toFloat(),
                    right = values[2].toFloat(),
                    bottom = values[3].toFloat(),
                    rotationDegrees = values[4].toFloat(),
                    flipHorizontal = values[5].toBoolean(),
                )
            }.getOrDefault(VideoGeometry()) else VideoGeometry()
        }
        val annotations = if (version >= 4) {
            properties.getProperty("annotations")?.let { encodedAnnotations ->
                runCatching { VideoAnnotationCodec.decode(encodedAnnotations) }.getOrDefault(emptyList())
            }.orEmpty()
        } else emptyList()
        return VideoEditRecipe(
            startMillis = properties.getProperty("start", "0").toLong(),
            endMillis = properties.getProperty("end")?.toLongOrNull(),
            speed = floatValue(properties, "speed", 1f),
            originalAudioVolume = floatValue(properties, "audio", 1f),
            musicUri = properties.getProperty("music")?.let(Uri::parse),
            musicVolume = floatValue(properties, "musicVolume", 0.6f),
            geometry = geometry,
            colorGrade = grade,
            outputQuality = enumValue(properties, "quality", VideoOutputQuality.H264Compatible),
            dynamicRange = if (version >= 5) {
                enumValue(properties, "dynamicRange", VideoDynamicRange.SdrRec709)
            } else VideoDynamicRange.SdrRec709,
            slowMotionSegments = slowSegments,
            annotations = annotations,
        )
    }

    private fun floatValue(properties: Properties, key: String, default: Float) =
        properties.getProperty(key)?.toFloatOrNull() ?: default

    private inline fun <reified T : Enum<T>> enumValue(properties: Properties, key: String, default: T): T =
        properties.getProperty(key)?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
