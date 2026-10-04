package com.librestatic.lightforge.core.editing.video

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
        // Runs for every cell of a preview or export cube, so it avoids per-call allocations
        // beyond the result.
        val r = coordinate(red, 0)
        val g = coordinate(green, 1)
        val b = coordinate(blue, 2)
        val r0 = floor(r).toInt().coerceIn(0, size - 1)
        val g0 = floor(g).toInt().coerceIn(0, size - 1)
        val b0 = floor(b).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val fr = r - r0
        val fg = g - g0
        val fb = b - b0
        val output = FloatArray(3)
        for (corner in 0 until 8) {
            val useR = corner and 1 != 0
            val useG = corner and 2 != 0
            val useB = corner and 4 != 0
            val weight = (if (useR) fr else 1f - fr) *
                (if (useG) fg else 1f - fg) *
                (if (useB) fb else 1f - fb)
            if (weight == 0f) continue
            val base = (((if (useB) b1 else b0) * size + (if (useG) g1 else g0)) * size + (if (useR) r1 else r0)) * 3
            output[0] += values[base] * weight
            output[1] += values[base + 1] * weight
            output[2] += values[base + 2] * weight
        }
        return output
    }

    private fun coordinate(value: Float, channel: Int): Float =
        ((value - domainMin[channel]) / (domainMax[channel] - domainMin[channel]))
            .coerceIn(0f, 1f) * (size - 1)
}

object CubeLutParser {
    fun parse(reader: Reader, fallbackTitle: String = "Custom LUT"): CubeLut {
        var title = fallbackTitle
        var size = 0
        var oneDimensional = false
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        // A 65-point cube has 274,625 rows: keep them in a primitive array and split lines by
        // hand. Boxed floats and a regex per line used tens of megabytes per parse.
        var values = FloatArray(0)
        var count = 0
        val parts = ArrayList<String>(4)
        reader.buffered().forEachLine { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            splitWhitespace(line, parts)
            when (parts[0].uppercase()) {
                "TITLE" -> title = line.substringAfter(' ').trim().trim('"').take(120)
                "LUT_3D_SIZE" -> {
                    require(size == 0) { "LUT size is declared more than once" }
                    size = parts.getOrNull(1)?.toIntOrNull() ?: error("Invalid LUT size")
                    require(size in 2..65) { "LUT size must be between 2 and 65" }
                    values = FloatArray(size * size * size * 3)
                }
                "LUT_1D_SIZE" -> {
                    require(size == 0) { "LUT size is declared more than once" }
                    size = parts.getOrNull(1)?.toIntOrNull() ?: error("Invalid LUT size")
                    require(size in 2..65) { "LUT size must be between 2 and 65" }
                    oneDimensional = true
                    values = FloatArray(size * 3)
                }
                "DOMAIN_MIN" -> domainMin = triplet(parts, "DOMAIN_MIN")
                "DOMAIN_MAX" -> domainMax = triplet(parts, "DOMAIN_MAX")
                else -> {
                    require(parts.size == 3) { "Invalid LUT data row" }
                    require(size != 0) { "LUT data appears before its size" }
                    require(count + 3 <= values.size) { "LUT has more data rows than its size declares" }
                    for (part in parts) values[count++] = part.toFloat()
                }
            }
        }
        require(size in 2..65) { "LUT size must be between 2 and 65" }
        require(count == values.size) { "LUT has fewer data rows than its size declares" }
        if (!oneDimensional) return CubeLut(title, size, values, domainMin, domainMax)
        val parsed = values
        val expanded = FloatArray(size * size * size * 3)
        for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
            val destination = ((blue * size + green) * size + red) * 3
            expanded[destination] = parsed[red * 3]
            expanded[destination + 1] = parsed[green * 3 + 1]
            expanded[destination + 2] = parsed[blue * 3 + 2]
        }
        return CubeLut(title, size, expanded, domainMin, domainMax)
    }

    private fun splitWhitespace(line: String, into: MutableList<String>) {
        into.clear()
        var start = -1
        for (index in line.indices) {
            if (line[index].isWhitespace()) {
                if (start >= 0) into += line.substring(start, index)
                start = -1
            } else if (start < 0) {
                start = index
            }
        }
        if (start >= 0) into += line.substring(start)
    }

    private fun triplet(parts: List<String>, name: String): FloatArray {
        require(parts.size == 4) { "$name requires three values" }
        return FloatArray(3) { index -> parts[index + 1].toFloat() }
    }
}
