package com.ugallery.core.editing.video

import java.io.Reader
import kotlin.math.floor

data class CubeLut(
    val title: String,
    val size: Int,
    val values: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        require(size in 2..65)
        require(values.size == size * size * size * 3)
        require(domainMin.size == 3 && domainMax.size == 3)
        require((0..2).all { domainMax[it] > domainMin[it] })
        require(values.all(Float::isFinite))
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val coordinates = floatArrayOf(red, green, blue).mapIndexed { index, value ->
            ((value - domainMin[index]) / (domainMax[index] - domainMin[index]))
                .coerceIn(0f, 1f) * (size - 1)
        }
        val low = coordinates.map { floor(it).toInt().coerceIn(0, size - 1) }
        val high = low.map { (it + 1).coerceAtMost(size - 1) }
        val fraction = coordinates.mapIndexed { index, value -> value - low[index] }
        val output = FloatArray(3)
        for (rCorner in 0..1) for (gCorner in 0..1) for (bCorner in 0..1) {
            val r = if (rCorner == 0) low[0] else high[0]
            val g = if (gCorner == 0) low[1] else high[1]
            val b = if (bCorner == 0) low[2] else high[2]
            val weight = (if (rCorner == 0) 1f - fraction[0] else fraction[0]) *
                (if (gCorner == 0) 1f - fraction[1] else fraction[1]) *
                (if (bCorner == 0) 1f - fraction[2] else fraction[2])
            val base = ((b * size + g) * size + r) * 3
            repeat(3) { channel -> output[channel] += values[base + channel] * weight }
        }
        return output
    }
}

object CubeLutParser {
    fun parse(reader: Reader, fallbackTitle: String = "Custom LUT"): CubeLut {
        var title = fallbackTitle
        var size = 0
        var oneDimensional = false
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<Float>()
        reader.forEachLine { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> title = line.substringAfter(' ').trim().trim('"').take(120)
                "LUT_3D_SIZE" -> {
                    require(size == 0) { "LUT size is declared more than once" }
                    size = parts.getOrNull(1)?.toIntOrNull() ?: error("Invalid LUT size")
                }
                "LUT_1D_SIZE" -> {
                    require(size == 0) { "LUT size is declared more than once" }
                    size = parts.getOrNull(1)?.toIntOrNull() ?: error("Invalid LUT size")
                    oneDimensional = true
                }
                "DOMAIN_MIN" -> domainMin = triplet(parts, "DOMAIN_MIN")
                "DOMAIN_MAX" -> domainMax = triplet(parts, "DOMAIN_MAX")
                else -> {
                    require(parts.size == 3) { "Invalid LUT data row" }
                    parts.forEach { values += it.toFloat() }
                }
            }
        }
        require(size in 2..65) { "LUT size must be between 2 and 65" }
        val parsed = values.toFloatArray()
        if (!oneDimensional) return CubeLut(title, size, parsed, domainMin, domainMax)
        require(parsed.size == size * 3) { "1D LUT must contain exactly $size data rows" }
        val expanded = FloatArray(size * size * size * 3)
        for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
            val destination = ((blue * size + green) * size + red) * 3
            expanded[destination] = parsed[red * 3]
            expanded[destination + 1] = parsed[green * 3 + 1]
            expanded[destination + 2] = parsed[blue * 3 + 2]
        }
        return CubeLut(title, size, expanded, domainMin, domainMax)
    }

    private fun triplet(parts: List<String>, name: String): FloatArray {
        require(parts.size == 4) { "$name requires three values" }
        return FloatArray(3) { index -> parts[index + 1].toFloat() }
    }
}
