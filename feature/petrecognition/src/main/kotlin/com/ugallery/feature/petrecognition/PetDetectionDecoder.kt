package com.ugallery.feature.petrecognition

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

internal data class PetDetection(val box: PetBox, val catScore: Float, val dogScore: Float) {
    val score get() = maxOf(catScore, dogScore)
    val species get() = if (catScore >= dogScore) PetSpecies.Cat else PetSpecies.Dog
}

/** Pinned EfficientDet-Lite0 metadata: 19206 fixed SSD anchors, YXHW, exponential size, scales 1. */
internal object PetDetectionDecoder {
    const val Anchors = 19206
    const val Classes = 90
    // Generated from the model's fixed-anchor grids, checked against all embedded anchor values.
    val anchors: FloatArray by lazy {
        val result = ArrayList<Float>(Anchors * 4)
        for (grid in listOf(40, 20, 10, 5, 3)) for (y in 0 until grid) for (x in 0 until grid)
            for (scale in 0..2) for (aspect in listOf(1.0, 2.0, 0.5)) {
                val size = 3.0 / grid * 2.0.pow(scale / 3.0)
                result += ((x + 0.5) / grid).toFloat()
                result += ((y + 0.5) / grid).toFloat()
                result += (size * sqrt(aspect)).toFloat()
                result += (size / sqrt(aspect)).toFloat()
            }
        result.toFloatArray().also { check(it.size == Anchors * 4) }
    }

    fun decode(boxes: FloatArray, scores: FloatArray, threshold: Float = 0.35f): List<PetDetection> {
        require(boxes.size == Anchors * 4 && scores.size == Anchors * Classes)
        val candidates = ArrayList<PetDetection>()
        for (index in 0 until Anchors) {
            val cat = scores[index * Classes + 16]
            val dog = scores[index * Classes + 17]
            if (!cat.isFinite() || !dog.isFinite() || maxOf(cat, dog) < threshold) continue
            val offset = index * 4
            val y = boxes[offset]; val x = boxes[offset + 1]
            val h = boxes[offset + 2]; val w = boxes[offset + 3]
            if (!listOf(x, y, w, h).all(Float::isFinite) || w !in -10f..10f || h !in -10f..10f) continue
            val cx = x * anchors[offset + 2] + anchors[offset]
            val cy = y * anchors[offset + 3] + anchors[offset + 1]
            val width = exp(w) * anchors[offset + 2]
            val height = exp(h) * anchors[offset + 3]
            val l = (cx - width / 2).coerceIn(0f, 1f); val r = (cx + width / 2).coerceIn(0f, 1f)
            val t = (cy - height / 2).coerceIn(0f, 1f); val b = (cy + height / 2).coerceIn(0f, 1f)
            if (r - l < 0.005f || b - t < 0.005f) continue
            candidates += PetDetection(PetBox(l, t, r, b), cat.coerceIn(0f, 1f), dog.coerceIn(0f, 1f))
        }
        val kept = ArrayList<PetDetection>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (kept.none { overlap(it.box, candidate.box) > 0.5f }) kept += candidate
        }
        return kept
    }

    fun overlap(a: PetBox, b: PetBox): Float {
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection
        return if (union > 0f) intersection / union else 0f
    }
}
