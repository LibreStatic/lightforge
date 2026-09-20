package com.ugallery.feature.pdfstudio

/** A single policy for actual parent constraints, including embedded panes and large text. */
internal data class PdfStudioLayoutPolicy(val expanded: Boolean, val compactChrome: Boolean) {
    companion object {
        fun forSize(widthDp: Float, heightDp: Float, fontScale: Float): PdfStudioLayoutPolicy {
            val scale = fontScale.coerceIn(1f, 2f)
            return PdfStudioLayoutPolicy(
                expanded = widthDp >= 840f * scale,
                compactChrome = heightDp < 600f || scale >= 1.5f || widthDp < 360f,
            )
        }
    }
}
