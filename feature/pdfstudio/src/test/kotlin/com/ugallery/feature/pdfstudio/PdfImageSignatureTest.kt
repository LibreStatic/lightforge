package com.ugallery.feature.pdfstudio

import org.junit.Assert.*
import org.junit.Test

class PdfImageSignatureTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** First bytes of an ordinary gallery photo: Pillow, baseline JPEG, JFIF APP0, no EXIF. */
    private val galleryJpeg =
        bytes(
            0xFF,
            0xD8,
            0xFF,
            0xE0,
            0x00,
            0x10,
            0x4A,
            0x46,
            0x49,
            0x46,
            0x00,
            0x01,
            0x01,
            0x00,
            0x00,
            0x01,
        )

    private val png =
        bytes(
            0x89,
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
            0x00,
            0x00,
            0x00,
            0x0D,
            0x49,
            0x48,
            0x44,
            0x52,
        )

    private val webp =
        bytes(
            0x52,
            0x49,
            0x46,
            0x46,
            0x24,
            0x10,
            0x00,
            0x00,
            0x57,
            0x45,
            0x42,
            0x50,
            0x56,
            0x50,
            0x38,
            0x20,
        )

    private val pdf =
        bytes(
            0x25,
            0x50,
            0x44,
            0x46,
            0x2D,
            0x31,
            0x2E,
            0x37,
            0x0A,
            0x25,
            0xE2,
            0xE3,
            0xCF,
            0xD3,
            0x0A,
            0x0A,
        )

    @Test
    fun containersAreRecognisedFromTheirOwnBytes() {
        assertEquals(PdfImageSignature.JPEG, PdfImageSignature.of(galleryJpeg))
        assertEquals(PdfImageSignature.PNG, PdfImageSignature.of(png))
        assertEquals(PdfImageSignature.WEBP, PdfImageSignature.of(webp))
        assertEquals(PdfImageSignature.PDF, PdfImageSignature.of(pdf))
        assertNull(PdfImageSignature.image(pdf))
    }

    /** The reported bug: bounds decode, but the platform probe leaves the type unnamed. */
    @Test
    fun galleryJpegWithoutDecoderMimeIsAccepted() {
        assertEquals(
            PdfImageSignature.JPEG,
            PdfImageSignature.accept(galleryJpeg, galleryJpeg.size, null, 1080, 1440),
        )
        assertEquals(
            PdfImageSignature.JPEG,
            PdfImageSignature.accept(galleryJpeg, galleryJpeg.size, "", 1440, 1080),
        )
        assertEquals(
            PdfImageSignature.JPEG,
            PdfImageSignature.accept(
                galleryJpeg,
                galleryJpeg.size,
                "application/octet-stream",
                1080,
                1440,
            ),
        )
    }

    /** The gate that shipped: the decoder's optional naming was the whole decision. */
    @Test
    fun theDecoderMimeOnlyGateRejectedThisPhoto() {
        val decoderMime: String? = null
        assertFalse(1080 > 0 && 1440 > 0 && decoderMime in PdfImageSignature.SUPPORTED_IMAGES)
        assertNotNull(
            PdfImageSignature.accept(galleryJpeg, galleryJpeg.size, decoderMime, 1080, 1440)
        )
    }

    @Test
    fun signatureOutranksAGenericDecoderNaming() {
        assertEquals(
            PdfImageSignature.PNG,
            PdfImageSignature.accept(png, png.size, "application/octet-stream", 32, 48),
        )
        assertEquals(
            PdfImageSignature.WEBP,
            PdfImageSignature.accept(webp, webp.size, null, 32, 48),
        )
    }

    @Test
    fun decoderNamingStillCarriesAnUnsignedContainer() {
        val heic = ByteArray(PdfImageSignature.LENGTH)
        assertEquals(
            PdfImageSignature.JPEG,
            PdfImageSignature.accept(heic, heic.size, "image/jpeg", 100, 100),
        )
    }

    @Test
    fun whatThePipelineCannotRenderIsStillRefused() {
        val gif = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x10, 0x00)
        val text = "not an image".toByteArray()
        assertNull(PdfImageSignature.accept(gif, gif.size, "image/gif", 16, 16))
        assertNull(PdfImageSignature.accept(text, text.size, null, 0, 0))
        // A PDF is a source, never an image asset.
        assertNull(PdfImageSignature.accept(pdf, pdf.size, null, 100, 100))
        // Right signature, unreadable pixels: rejecting now is honest, failing at export is not.
        assertNull(PdfImageSignature.accept(galleryJpeg, galleryJpeg.size, "image/jpeg", -1, -1))
    }

    @Test
    fun shortHeadersNeverMisclassify() {
        assertNull(PdfImageSignature.of(galleryJpeg, 2))
        assertEquals(PdfImageSignature.JPEG, PdfImageSignature.of(galleryJpeg, 3))
        // RIFF alone is not WebP.
        assertNull(PdfImageSignature.of(webp, 8))
        assertEquals(PdfImageSignature.WEBP, PdfImageSignature.of(webp, 12))
        assertNull(PdfImageSignature.of(ByteArray(0), 0))
    }
}
