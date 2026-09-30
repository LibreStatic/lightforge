package com.librestatic.lightforge.feature.semanticsearch

import kotlin.math.roundToInt
import kotlin.math.sqrt

object CompactSemanticEmbedding {
    const val Dimensions = 512

    fun quantize(raw: FloatArray): ByteArray {
        require(raw.size == Dimensions)
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        require(norm.isFinite() && norm > 0f)
        return ByteArray(Dimensions) { index ->
            ((raw[index] / norm) * 127f).roundToInt().coerceIn(-127, 127).toByte()
        }
    }

    fun cosine(a: ByteArray, b: ByteArray): Float {
        require(a.size == Dimensions && b.size == Dimensions)
        var dot = 0L
        var aNorm = 0L
        var bNorm = 0L
        repeat(Dimensions) { index ->
            val av = a[index].toInt()
            val bv = b[index].toInt()
            dot += av * bv
            aNorm += av * av
            bNorm += bv * bv
        }
        if (aNorm == 0L || bNorm == 0L) return 0f
        return (dot / sqrt(aNorm.toDouble() * bNorm.toDouble())).toFloat().coerceIn(-1f, 1f)
    }
}

object SemanticLsh {
    const val BandCount = 8
    const val BitsPerBand = 16

    fun bands(vector: ByteArray, modelVersion: String): IntArray {
        require(vector.size == CompactSemanticEmbedding.Dimensions)
        return IntArray(BandCount) { band ->
            var bucket = 0
            repeat(BitsPerBand) { bit ->
                var sum = 0L
                vector.forEachIndexed { dimension, value ->
                    sum += value.toInt() * sign(modelVersion, band, bit, dimension)
                }
                if (sum >= 0L) bucket = bucket or (1 shl bit)
            }
            bucket
        }
    }

    fun probes(bucket: Int, radius: Int): List<Int> {
        require(radius in 0..2)
        val values = linkedSetOf(bucket)
        if (radius >= 1) repeat(BitsPerBand) { first -> values += bucket xor (1 shl first) }
        if (radius >= 2) repeat(BitsPerBand) { first ->
            for (second in first + 1 until BitsPerBand) values += bucket xor (1 shl first) xor (1 shl second)
        }
        return values.toList()
    }

    private fun sign(version: String, band: Int, bit: Int, dimension: Int): Int {
        var value = version.hashCode().toLong() xor
            (band.toLong() shl 48) xor (bit.toLong() shl 32) xor dimension.toLong()
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return if ((value xor (value ushr 33)) and 1L == 0L) -1 else 1
    }
}

data class RankedSemanticKey(val key: String, val score: Double)

object ReciprocalRankFusion {
    private const val K = 60.0

    fun fuse(keyword: List<RankedSemanticKey>, semantic: List<RankedSemanticKey>): List<RankedSemanticKey> {
        val scores = linkedMapOf<String, Double>()
        keyword.forEachIndexed { index, item -> scores[item.key] = scores.getOrDefault(item.key, 0.0) + 1.0 / (K + index + 1) }
        semantic.forEachIndexed { index, item -> scores[item.key] = scores.getOrDefault(item.key, 0.0) + 1.0 / (K + index + 1) }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { RankedSemanticKey(it.key, it.value) }
    }
}

