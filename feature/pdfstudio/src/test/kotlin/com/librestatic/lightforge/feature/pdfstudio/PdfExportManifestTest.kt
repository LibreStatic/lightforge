package com.librestatic.lightforge.feature.pdfstudio

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class PdfExportManifestTest {
    @Test
    fun exactLimitRoundTripsButOneExtraByteIsRejectedBeforeParsing() {
        val project = PdfProject(name = "Local é 日本語")
        val raw = PdfExportManifest.encode(project)
        val padded = raw + ByteArray(PdfExportManifest.LIMIT - raw.size) { 32 }
        assertEquals(project, PdfExportManifest.read(ByteArrayInputStream(padded)))
        val input = ByteArrayInputStream(padded + ByteArray(90))
        val error = runCatching { PdfExportManifest.read(input) }.exceptionOrNull()!!
        assertEquals(PdfFailure.LimitExceeded, PdfFailure.from(error))
        assertEquals(89, input.available())
    }

    @Test
    fun unknownLengthStreamIsBoundedWithoutDependingOnAvailable() {
        val input =
            object : java.io.InputStream() {
                var reads = 0

                override fun available(): Int = 0

                override fun read(): Int {
                    reads++
                    return 32
                }
            }
        val error = runCatching { PdfExportManifest.read(input) }.exceptionOrNull()!!
        assertEquals(PdfFailure.LimitExceeded, PdfFailure.from(error))
        assertEquals(PdfExportManifest.LIMIT + 1, input.reads)
    }

    @Test
    fun malformedUtf8IsRejectedInsteadOfSilentlyChangingProjectName() {
        val raw = PdfExportManifest.encode(PdfProject(name = "X"))
        val name = raw.toString(Charsets.UTF_8).indexOf("\"name\":\"X\"") + 8
        assertTrue(name >= 8)
        raw[name] = 0xff.toByte()
        assertEquals(
            PdfFailure.InvalidInput,
            PdfFailure.from(
                runCatching { PdfExportManifest.read(ByteArrayInputStream(raw)) }
                    .exceptionOrNull()!!
            ),
        )
    }

    @Test
    fun encodedByteLimitIncludesMultibyteText() {
        val excessive = PdfProject(name = "é".repeat(PdfExportManifest.LIMIT / 2))
        assertEquals(
            PdfFailure.LimitExceeded,
            PdfFailure.from(runCatching { PdfExportManifest.encode(excessive) }.exceptionOrNull()!!),
        )
    }
}
