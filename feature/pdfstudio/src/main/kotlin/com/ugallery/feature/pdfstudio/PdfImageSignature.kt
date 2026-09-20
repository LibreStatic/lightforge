package com.ugallery.feature.pdfstudio

/**
 * Container detection from the source bytes themselves.
 *
 * `BitmapFactory.Options.outMimeType` is documented as filled in only "if known": the bounds probe
 * can report a perfectly good width/height and still leave the type unnamed. Gating intake on that
 * string alone rejected ordinary gallery JPEGs that every other decoder in the app reads. The
 * accepted set is unchanged - exactly the three raster containers the export pipeline renders - but
 * the decision now comes from the file, with the decoder's opinion kept only as a fallback.
 */
internal object PdfImageSignature {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val PDF = "application/pdf"

    /** Bytes required to classify every container below (RIFF needs 12). */
    const val LENGTH = 16

    val SUPPORTED_IMAGES = listOf(JPEG, PNG, WEBP)

    /** The container this header describes, or null when it is none the studio handles. */
    fun of(header: ByteArray, read: Int = header.size): String? {
        fun match(offset: Int, vararg expected: Int): Boolean =
            read >= offset + expected.size &&
                expected.withIndex().all { (n, value) ->
                    (header[offset + n].toInt() and 0xFF) == value
                }
        return when {
            match(0, 0x25, 0x50, 0x44, 0x46, 0x2D) -> PDF
            match(0, 0xFF, 0xD8, 0xFF) -> JPEG
            match(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
            match(0, 0x52, 0x49, 0x46, 0x46) && match(8, 0x57, 0x45, 0x42, 0x50) -> WEBP
            else -> null
        }
    }

    /** Same as [of], but never claims a PDF is an image. */
    fun image(header: ByteArray, read: Int = header.size): String? =
        of(header, read)?.takeIf { it != PDF }

    /**
     * The MIME to store for a decoded source, or null when the studio must refuse it. Bounds still
     * have to decode: a file whose signature is right but whose pixels are unreadable would only
     * fail later, during export.
     */
    fun accept(
        header: ByteArray,
        read: Int,
        decoderMime: String?,
        width: Int,
        height: Int,
    ): String? {
        if (width <= 0 || height <= 0) return null
        val mime = image(header, read) ?: decoderMime
        return mime?.takeIf { it in SUPPORTED_IMAGES }
    }
}
