package com.librestatic.lightforge.core.mediastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedImageSnifferTest {
    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
        }
        return out.toByteArray()
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun webp(flags: Int) = bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8X", 10, 0, 0, 0, flags, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun png(vararg chunks: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(bytes(0x89, "PNG", 0x0D, 0x0A, 0x1A, 0x0A))
        for (type in chunks) { out.write(be32(4)); out.write(type.toByteArray()); out.write(ByteArray(4)); out.write(be32(0)) }
        return out.toByteArray()
    }

    private fun isoFile(major: String, vararg compat: String): ByteArray {
        val body = bytes(major, be32(0), *compat)
        return bytes(be32(8 + body.size), "ftyp", body)
    }

    @Test fun gifIsAnimatedByMimeAlone() {
        assertTrue(AnimatedImageSniffer.isAnimated("image/gif", ByteArray(0)))
        assertFalse(AnimatedImageSniffer.needsHeader("image/gif"))
    }

    @Test fun staticWebpIsNotAnimatedButAnimatedWebpIs() {
        assertFalse(AnimatedImageSniffer.isAnimated("image/webp", webp(flags = 0x10)))
        assertTrue(AnimatedImageSniffer.isAnimated("image/webp", webp(flags = 0x12)))
        // A simple (non-extended) WebP can never be animated.
        assertFalse(AnimatedImageSniffer.isAnimated("image/webp", bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8 ", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun apngIsAnimatedWhenAcTlComesBeforeIdat() {
        assertTrue(AnimatedImageSniffer.isAnimated("image/png", png("IHDR", "acTL", "fcTL", "IDAT")))
        assertFalse(AnimatedImageSniffer.isAnimated("image/png", png("IHDR", "sRGB", "IDAT", "IEND")))
        // An acTL after the first IDAT is not an animation control chunk.
        assertFalse(AnimatedImageSniffer.isAnimated("image/png", png("IHDR", "IDAT", "acTL")))
    }

    @Test fun avifAndHeifSequencesAreAnimatedStillsAreNot() {
        assertTrue(AnimatedImageSniffer.isAnimated("image/avif", isoFile("avis", "avif", "msf1", "miaf")))
        assertTrue(AnimatedImageSniffer.isAnimated("image/avif", isoFile("avif", "mif1", "avis")))
        assertTrue(AnimatedImageSniffer.isAnimated("image/heic", isoFile("hevc", "mif1")))
        assertFalse(AnimatedImageSniffer.isAnimated("image/avif", isoFile("avif", "mif1", "miaf")))
        assertFalse(AnimatedImageSniffer.isAnimated("image/heic", isoFile("heic", "mif1", "heic")))
    }

    @Test fun truncatedOrUnknownHeadersAreNotAnimated() {
        assertFalse(AnimatedImageSniffer.isAnimated("image/webp", bytes("RIFF")))
        assertFalse(AnimatedImageSniffer.isAnimated("image/png", bytes(0x89, "PNG")))
        assertFalse(AnimatedImageSniffer.isAnimated("image/jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertFalse(AnimatedImageSniffer.needsHeader("image/jpeg"))
        assertTrue(AnimatedImageSniffer.needsHeader("IMAGE/WEBP"))
    }
}
