package com.ugallery.feature.pdfstudio

import kotlin.math.sqrt

internal object PdfPreviewPolicy {
    const val DISK_BYTES = 32L * 1024 * 1024
    const val DISK_ENTRIES = 64
    const val ENTRY_BYTES = 6L * 1024 * 1024
    const val FREE_BYTES = 8L * 1024 * 1024
    const val MEMORY_BYTES = 16 * 1024 * 1024
    const val CANVAS_BYTES = 24L * 1024 * 1024
    const val THUMBNAIL_BYTES = 1024L * 1024

    // A rotated square can occupy twice the input area, at four bytes per sRGB pixel.
    fun rotatedBytes(side: Int): Long {
        val extent = kotlin.math.ceil(side * sqrt(2.0)).toLong()
        return 4L * extent * extent
    }

    fun side(images: Int, thumbnail: Boolean): Int {
        require(images in 1..24)
        val budget = if (thumbnail) THUMBNAIL_BYTES else CANVAS_BYTES
        var side =
            sqrt(budget.toDouble() / (8 * images))
                .toInt()
                .coerceAtMost(if (thumbnail) 192 else 1024)
                .coerceAtLeast(1)
        while (rotatedBytes(side) * images > budget && side > 1) side--
        return side
    }
}
